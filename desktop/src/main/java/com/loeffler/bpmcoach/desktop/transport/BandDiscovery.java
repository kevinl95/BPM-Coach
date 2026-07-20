package com.loeffler.bpmcoach.desktop.transport;

import com.loeffler.bpmcoach.transport.BleTransport;
import com.loeffler.bpmcoach.transport.DiscoveredDevice;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;

/**
 * Keeps {@link BleTransport#scan()} running continuously for as long as the app is running (one
 * persistent subscription, not a repeating burst loop - see below), maintaining a live map of every
 * band seen so far. That's what makes "recognize a previously paired band on the next launch"
 * possible: {@link com.loeffler.bpmcoach.desktop.MainApp}'s poll loop can only connect to an
 * address once it has actually been (re)discovered this session. The pairing view subscribes to the
 * same stream to show what's currently in range.
 *
 * <p>This used to restart a fresh scan burst (via a self-completing {@code scan()}) every few
 * seconds in a loop. Confirmed against real hardware: that repeated native scan start/stop churn
 * reliably crashed the JVM with a SIGSEGV in a JNI callback on the adapter's own event-dispatch
 * thread after roughly 8-11 restarts - a stable, persistent {@code Adapter.EventListener} wasn't
 * enough on its own, since the churn was in the scan session lifecycle itself, not listener object
 * churn. {@code scan()}'s contract is now a real continuous stream that only ends when {@link
 * BleTransport#stopScan()} is called, so during normal operation the underlying native scan starts
 * exactly once and just keeps running; the only time it's actually stopped and restarted is around
 * {@link #pause()}/{@link #resume()}, which {@code BandPoller} uses to get exclusive radio access
 * for a live connection - far less frequent than the old fixed cycle.
 *
 * <p>{@link #known} is a {@link LinkedHashMap} (not a {@code ConcurrentHashMap}) specifically so
 * discovery order is stable - a re-discovered band updates its entry in place rather than jumping
 * to the end, which is what {@link PairingView}'s list relies on to not reshuffle under the user
 * while they're picking a device to name.
 */
public final class BandDiscovery {

  private static final System.Logger LOG = System.getLogger(BandDiscovery.class.getName());

  // Purely cosmetic: how often a snapshot of known.size() is logged, decoupled from any native
  // scan lifecycle event (there isn't a recurring one to hang this off of anymore).
  private static final Duration STATUS_LOG_INTERVAL = Duration.ofSeconds(6);
  // Grace period after stopScan() before treating the radio as free for a connection attempt -
  // the native call returns quickly, but isn't necessarily instantaneous at the radio level.
  private static final Duration STOP_SCAN_SETTLE = Duration.ofMillis(300);

  private final BleTransport transport;
  private final Object knownLock = new Object();
  private final Map<String, DiscoveredDevice> known = new LinkedHashMap<>();
  // Monotonic timestamp of each address's most recent advertisement sighting - backs the
  // com.loeffler.bpmcoach.transport.Sightings gate BandPoller uses before any connect attempt.
  private final Map<String, Long> seenNanos = new ConcurrentHashMap<>();
  private final SubmissionPublisher<DiscoveredDevice> updates = new SubmissionPublisher<>();
  private final Object scanLock = new Object();
  private volatile boolean running;
  private Thread statusLogThread;

  public BandDiscovery(BleTransport transport) {
    this.transport = transport;
  }

  public void start() {
    running = true;
    subscribeNow();
    statusLogThread = Thread.startVirtualThread(this::statusLogLoop);
  }

  public void stop() {
    running = false;
    synchronized (scanLock) {
      transport.stopScan();
    }
    if (statusLogThread != null) {
      statusLogThread.interrupt();
    }
    updates.close();
  }

  /**
   * Stops the current scan so a caller (namely {@code BandPoller}, before a batch of connection
   * attempts) gets exclusive radio access. See the class javadoc for why this - not a fixed timer -
   * is now the only thing that ever restarts the underlying native scan session.
   */
  public void pause() {
    synchronized (scanLock) {
      transport.stopScan();
    }
    sleepQuietly(STOP_SCAN_SETTLE);
  }

  /** Restarts scanning after {@link #pause()}. */
  public void resume() {
    if (!running) {
      return;
    }
    synchronized (scanLock) {
      subscribeNow();
    }
  }

  /**
   * True if {@code address} was sighted advertising within the last {@code window}. Matches the
   * {@link com.loeffler.bpmcoach.transport.Sightings} functional shape; {@code MainApp} passes
   * {@code discovery::seenWithin} to {@code BandPoller} as its connect gate.
   */
  public boolean seenWithin(String address, Duration window) {
    Long seen = seenNanos.get(address);
    return seen != null && System.nanoTime() - seen <= window.toNanos();
  }

  /** In first-discovered order, stable across rediscovery. */
  public Map<String, DiscoveredDevice> knownDevices() {
    synchronized (knownLock) {
      return new LinkedHashMap<>(known);
    }
  }

  public Flow.Publisher<DiscoveredDevice> updates() {
    return updates;
  }

  private void subscribeNow() {
    LOG.log(Level.INFO, "Starting background scan");
    transport
        .scan()
        .subscribe(
            new Flow.Subscriber<DiscoveredDevice>() {
              @Override
              public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
              }

              @Override
              public void onNext(DiscoveredDevice device) {
                seenNanos.put(device.address(), System.nanoTime());
                synchronized (knownLock) {
                  known.put(device.address(), device);
                }
                // A discovery can be in flight right as stop() closes `updates` (e.g. the window
                // closing mid-scan) - that's a normal shutdown race, not a bug, so it shouldn't
                // surface as an unhandled exception. The `running` check narrows the window; the
                // catch is the actual guarantee, since close() can still land between the check
                // and the submit.
                if (running) {
                  try {
                    updates.submit(device);
                  } catch (IllegalStateException e) {
                    // closed concurrently; nothing to do
                  }
                }
              }

              @Override
              public void onError(Throwable throwable) {
                LOG.log(Level.WARNING, "Scan failed", throwable);
              }

              @Override
              public void onComplete() {
                // Expected on every pause()/stop() - stopScan() closes the publisher, which
                // completes this subscription. resume() (if any) starts a fresh one.
              }
            });
  }

  private void statusLogLoop() {
    while (running) {
      if (!sleepQuietly(STATUS_LOG_INTERVAL)) {
        return;
      }
      if (!running) {
        return;
      }
      int totalKnown;
      synchronized (knownLock) {
        totalKnown = known.size();
      }
      LOG.log(Level.INFO, "Discovery status: {0} known device(s)", totalKnown);
    }
  }

  /** Returns false if interrupted (caller should stop), true if the sleep completed normally. */
  private static boolean sleepQuietly(Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }
}
