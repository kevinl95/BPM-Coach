package com.loeffler.bpmcoach.desktop.transport;

import com.loeffler.bpmcoach.transport.BandConnection;
import com.loeffler.bpmcoach.transport.BleException;
import com.loeffler.bpmcoach.transport.BleTransport;
import com.loeffler.bpmcoach.transport.DiscoveredDevice;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
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

  private static final BluetoothUUID SERVICE =
      new BluetoothUUID("6e400001-b5a3-f393-e0a9-e50e24dcca9f");
  private static final BluetoothUUID RX_CHARACTERISTIC =
      new BluetoothUUID("6e400002-b5a3-f393-e0a9-e50e24dcca9f");
  private static final BluetoothUUID TX_CHARACTERISTIC =
      new BluetoothUUID("6e400003-b5a3-f393-e0a9-e50e24dcca9f");
  private static final int SCAN_WINDOW_MILLIS = 5000;

  private final Adapter adapter;
  private final Map<String, Peripheral> discovered = new ConcurrentHashMap<>();

  public SimpleBleTransport() {
    List<Adapter> adapters = Adapter.getAdapters();
    if (adapters.isEmpty()) {
      throw new BleException("No Bluetooth adapter found on this machine");
    }
    this.adapter = adapters.get(0);
  }

  @Override
  public Flow.Publisher<DiscoveredDevice> scan() {
    SubmissionPublisher<DiscoveredDevice> publisher = new SubmissionPublisher<>();
    adapter.setEventListener(
        new Adapter.EventListener() {
          @Override
          public void onScanStop() {
            publisher.close();
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
            publisher.submit(
                new DiscoveredDevice(address, peripheral.getIdentifier(), peripheral.getRssi()));
          }
        });
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
              publisher.closeExceptionally(new BleException("Scan failed", e));
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
          return new SimplePeripheralConnection(peripheral);
        });
  }

  private static final class SimplePeripheralConnection implements BandConnection {
    private final Peripheral peripheral;
    private final SubmissionPublisher<byte[]> notifications = new SubmissionPublisher<>();

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
