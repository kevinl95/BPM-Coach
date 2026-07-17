package com.loeffler.bpmcoach.desktop.transport;

import com.loeffler.bpmcoach.transport.BandConnection;
import com.loeffler.bpmcoach.transport.BleException;
import com.loeffler.bpmcoach.transport.BleTransport;
import com.loeffler.bpmcoach.transport.DiscoveredDevice;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.simplejavable.Adapter;
import org.simplejavable.BluetoothUUID;
import org.simplejavable.Peripheral;

/**
 * {@link BleTransport} backed by SimpleJavaBLE, which wraps WinRT (Windows), CoreBluetooth (macOS)
 * and BlueZ (Linux) behind one API - see the module README for the license note (BUSL-1.1, free for
 * this non-commercial use) and per-OS support matrix.
 *
 * <p>Unlike the Android reference client, this API has no {@code requestConnectionPriority}
 * equivalent; each backend's OS-level BLE stack owns that negotiation.
 */
public final class SimpleBleTransport implements BleTransport {

  private static final System.Logger LOG = System.getLogger(SimpleBleTransport.class.getName());

  private static final String SERVICE_UUID = "6e400001-b5a3-f393-e0a9-e50e24dcca9f";
  private static final BluetoothUUID SERVICE = new BluetoothUUID(SERVICE_UUID);
  private static final BluetoothUUID RX_CHARACTERISTIC =
      new BluetoothUUID("6e400002-b5a3-f393-e0a9-e50e24dcca9f");
  private static final BluetoothUUID TX_CHARACTERISTIC =
      new BluetoothUUID("6e400003-b5a3-f393-e0a9-e50e24dcca9f");
  private static final int SCAN_WINDOW_MILLIS = 5000;
  // peripheral.connect() returns once the physical link is up, which is NOT the same moment
  // BlueZ finishes resolving the peripheral's GATT service table (a separate, asynchronous
  // step). Calling notify()/writeRequest() before that finishes fails with "Service ... not
  // found" even though the band is genuinely connected - confirmed against real hardware.
  private static final Duration SERVICE_DISCOVERY_TIMEOUT = Duration.ofSeconds(6);
  private static final Duration SERVICE_DISCOVERY_POLL_INTERVAL = Duration.ofMillis(200);

  private final Adapter adapter;
  private final Map<String, Peripheral> discovered = new ConcurrentHashMap<>();
  private final AtomicReference<SubmissionPublisher<DiscoveredDevice>> activeScan =
      new AtomicReference<>();
  // Confirmed against real hardware: killing the app abruptly (Ctrl+C/SIGINT, or any path that
  // bypasses the normal try-with-resources close on a BandConnection) leaves the peripheral
  // connected at the OS/BlueZ level with no process left to disconnect it - the band then stops
  // advertising, so the *next* launch's scan finds nothing at all and connect() fails with
  // "Unknown device". Tracking open connections here lets shutdown() clean them up regardless of
  // how the process is ending.
  private final Set<SimplePeripheralConnection> openConnections = ConcurrentHashMap.newKeySet();

  public SimpleBleTransport() {
    List<Adapter> adapters = Adapter.getAdapters();
    if (adapters.isEmpty()) {
      throw new BleException("No Bluetooth adapter found on this machine");
    }
    this.adapter = adapters.get(0);
    // Registered exactly once, for the adapter's whole lifetime. Registering a fresh
    // Adapter.EventListener on every scan() call (as BandDiscovery's continuous loop
    // would otherwise require) reproducibly crashed the JVM with a native SIGSEGV after
    // ~8 scan cycles: the crash was inside a JNI callback on a background thread the
    // native BlueZ backend owns, consistent with it holding a reference to a
    // already-replaced (and GC-eligible) listener object. One stable listener avoids
    // that churn entirely.
    this.adapter.setEventListener(
        new Adapter.EventListener() {
          @Override
          public void onScanStop() {
            SubmissionPublisher<DiscoveredDevice> publisher = activeScan.getAndSet(null);
            if (publisher != null) {
              publisher.close();
            }
          }

          @Override
          public void onScanFound(Peripheral peripheral) {
            publish(peripheral);
          }

          @Override
          public void onScanUpdated(Peripheral peripheral) {
            publish(peripheral);
          }

          private void publish(Peripheral peripheral) {
            String address = peripheral.getAddress().toString();
            discovered.put(address, peripheral);
            SubmissionPublisher<DiscoveredDevice> publisher = activeScan.get();
            if (publisher != null) {
              publisher.submit(
                  new DiscoveredDevice(address, peripheral.getIdentifier(), peripheral.getRssi()));
            }
          }
        });
  }

  @Override
  public Flow.Publisher<DiscoveredDevice> scan() {
    SubmissionPublisher<DiscoveredDevice> publisher = new SubmissionPublisher<>();
    if (!activeScan.compareAndSet(null, publisher)) {
      throw new BleException("A scan is already in progress");
    }
    // As in MockBleTransport: don't start the scan until the subscriber is
    // actually registered, or early discoveries are silently dropped
    // (SubmissionPublisher never replays to late subscribers).
    return subscriber -> {
      publisher.subscribe(subscriber);
      Thread.startVirtualThread(
          () -> {
            try {
              adapter.scanFor(SCAN_WINDOW_MILLIS);
            } catch (Exception e) {
              SubmissionPublisher<DiscoveredDevice> active = activeScan.getAndSet(null);
              if (active != null) {
                active.closeExceptionally(new BleException("Scan failed", e));
              }
            }
          });
    };
  }

  @Override
  public CompletableFuture<BandConnection> connect(DiscoveredDevice device) {
    return CompletableFuture.supplyAsync(
        () -> {
          Peripheral peripheral = discovered.get(device.address());
          if (peripheral == null) {
            throw new BleException(
                "Unknown device " + device.address() + " - scan() before connect()");
          }
          peripheral.connect();
          if (!peripheral.isConnected()) {
            throw new BleException("Failed to connect to " + device.address());
          }
          awaitUartService(peripheral, device.address());
          SimplePeripheralConnection connection = new SimplePeripheralConnection(peripheral);
          openConnections.add(connection);
          return connection;
        });
  }

  /** Disconnects any connections still open - see {@link #openConnections}'s javadoc. */
  @Override
  public void shutdown() {
    for (SimplePeripheralConnection connection : Set.copyOf(openConnections)) {
      connection.close();
    }
  }

  private static void awaitUartService(Peripheral peripheral, String address) {
    long deadline = System.nanoTime() + SERVICE_DISCOVERY_TIMEOUT.toNanos();
    while (System.nanoTime() < deadline) {
      boolean found =
          peripheral.services().stream()
              .anyMatch(service -> SERVICE_UUID.equalsIgnoreCase(service.uuid()));
      if (found) {
        return;
      }
      try {
        Thread.sleep(SERVICE_DISCOVERY_POLL_INTERVAL.toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
    LOG.log(
        Level.WARNING,
        "UART service not resolved for {0} within {1}s; proceeding anyway",
        address,
        SERVICE_DISCOVERY_TIMEOUT.toSeconds());
  }

  private final class SimplePeripheralConnection implements BandConnection {
    private final Peripheral peripheral;
    private final SubmissionPublisher<byte[]> notifications = new SubmissionPublisher<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    SimplePeripheralConnection(Peripheral peripheral) {
      this.peripheral = peripheral;
      peripheral.notify(SERVICE, TX_CHARACTERISTIC, notifications::submit);
    }

    @Override
    public CompletableFuture<Void> writeCommand(byte[] frame) {
      // writeRequest (ATT write-with-response) to mirror the Android reference
      // client's WRITE_TYPE_DEFAULT, which the real band is confirmed to ACK.
      return CompletableFuture.runAsync(
          () -> peripheral.writeRequest(SERVICE, RX_CHARACTERISTIC, frame));
    }

    @Override
    public Flow.Publisher<byte[]> notifications() {
      return notifications;
    }

    @Override
    public void close() {
      // Idempotent: both the normal try-with-resources path and a shutdown() sweep can reach
      // this for the same connection (e.g. shutdown racing a poll already in its own cleanup).
      if (!closed.compareAndSet(false, true)) {
        return;
      }
      openConnections.remove(this);
      try {
        peripheral.unsubscribe(SERVICE, TX_CHARACTERISTIC);
      } catch (RuntimeException ignored) {
        // best-effort: the peripheral may already be gone
      }
      peripheral.disconnect();
      notifications.close();
    }
  }
}
