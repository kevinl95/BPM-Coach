package com.loeffler.bpmcoach.desktop.transport;

import com.loeffler.bpmcoach.transport.BleTransport;
import com.loeffler.bpmcoach.transport.DiscoveredDevice;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs {@link BleTransport#scan()} back-to-back in a loop for as long as the app is running,
 * keeping a live map of every band seen so far - which is what makes "recognize a previously paired
 * band on the next launch" possible: {@link com.loeffler.bpmcoach.desktop.MainApp}'s poll loop can
 * only connect to an address once it has actually been (re)discovered this session. The pairing
 * view subscribes to the same stream to show what's currently in range.
 *
 * <p>{@link #known} is a {@link LinkedHashMap} (not a {@code ConcurrentHashMap}) specifically so
 * discovery order is stable across repeated scan cycles - a re-discovered band updates its entry in
 * place rather than jumping to the end, which is what {@link PairingView}'s list relies on to not
 * reshuffle under the user while they're picking a device to name.
 */
public final class BandDiscovery {

  private static final System.Logger LOG = System.getLogger(BandDiscovery.class.getName());

  private static final Duration SCAN_CYCLE_TIMEOUT = Duration.ofSeconds(15);
  private static final Duration PAUSE_BETWEEN_CYCLES = Duration.ofSeconds(1);

  private final BleTransport transport;
  private final Object knownLock = new Object();
  private final Map<String, DiscoveredDevice> known = new LinkedHashMap<>();
  // Monotonic timestamp of each address's most recent advertisement sighting - backs the
  // com.loeffler.bpmcoach.transport.Sightings gate BandPoller uses before any connect attempt.
  private final Map<String, Long> seenNanos = new ConcurrentHashMap<>();
  private final SubmissionPublisher<DiscoveredDevice> updates = new SubmissionPublisher<>();
  private final Object pauseLock = new Object();
  private boolean paused;
  private volatile CountDownLatch cycleInFlight = new CountDownLatch(0);
  private volatile boolean running;
  private Thread loopThread;

  public BandDiscovery(BleTransport transport) {
    this.transport = transport;
  }

  public void start() {
    running = true;
    loopThread = Thread.startVirtualThread(this::loop);
  }

  public void stop() {
    running = false;
    if (loopThread != null) {
      loopThread.interrupt();
    }
    updates.close();
  }

  /**
   * Blocks until any in-flight scan cycle finishes, then keeps further cycles from starting until
   * {@link #resume()}. Called by the poll loop before every batch of band connections: scanning
   * concurrently with a live connection is what crashed the JVM twice (see SimpleBleTransport's
   * close() comment - the native binding's event dispatch races connection-state transitions when
   * scan sessions restart underneath them, and it dies with a SIGSEGV, not an exception), and on a
   * cheap adapter it also starves the connection of radio time, which matches whole poll rounds
   * where not even the band's documented immediate command-ack arrived.
   */
  public void pause() {
    synchronized (pauseLock) {
      paused = true;
    }
    try {
      cycleInFlight.await(SCAN_CYCLE_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** Lets the scan loop run again after {@link #pause()}. */
  public void resume() {
    synchronized (pauseLock) {
      paused = false;
      pauseLock.notifyAll();
    }
  }

  /**
   * True if {@code address} was sighted advertising within the last {@code window}. Matches the
   * {@link com.loeffler.bpmcoach.transport.Sightings} functional shape; {@code MainApp} passes
   * {@code discovery::seenWithin} to {@code BandPoller} as its connect gate.
   */
  public boolean seenWithin(String address, java.time.Duration window) {
    Long seen = seenNanos.get(address);
    return seen != null && System.nanoTime() - seen <= window.toNanos();
  }

  /** In first-discovered order, stable across repeated scan cycles. */
  public Map<String, DiscoveredDevice> knownDevices() {
    synchronized (knownLock) {
      return new LinkedHashMap<>(known);
    }
  }

  public Flow.Publisher<DiscoveredDevice> updates() {
    return updates;
  }

  private void loop() {
    LOG.log(Level.INFO, "Starting continuous background scan");
    int cycle = 0;
    while (running) {
      try {
        synchronized (pauseLock) {
          while (paused) {
            pauseLock.wait();
          }
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
      if (!running) {
        return;
      }
      cycle++;
      int cycleNumber = cycle;
      CountDownLatch cycleDone = new CountDownLatch(1);
      cycleInFlight = cycleDone;
      AtomicInteger seenThisCycle = new AtomicInteger();
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
                  seenThisCycle.incrementAndGet();
                  seenNanos.put(device.address(), System.nanoTime());
                  synchronized (knownLock) {
                    known.put(device.address(), device);
                  }
                  // A scan cycle runs on its own thread and can have a discovery in flight
                  // right as stop() closes `updates` (e.g. the window closing mid-scan) - that's
                  // a normal shutdown race, not a bug, so it shouldn't surface as an unhandled
                  // exception. The `running` check narrows the window; the catch is the actual
                  // guarantee, since close() can still land between the check and the submit.
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
                  LOG.log(Level.WARNING, "Scan cycle " + cycleNumber + " failed", throwable);
                  cycleDone.countDown();
                }

                @Override
                public void onComplete() {
                  cycleDone.countDown();
                }
              });
      try {
        boolean finished = cycleDone.await(SCAN_CYCLE_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        int totalKnown;
        synchronized (knownLock) {
          totalKnown = known.size();
        }
        LOG.log(
            Level.INFO,
            "Scan cycle {0} {1}: {2} device(s) this cycle, {3} known total",
            cycle,
            finished ? "complete" : "timed out",
            seenThisCycle.get(),
            totalKnown);
        if (running) {
          Thread.sleep(PAUSE_BETWEEN_CYCLES.toMillis());
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }
}
