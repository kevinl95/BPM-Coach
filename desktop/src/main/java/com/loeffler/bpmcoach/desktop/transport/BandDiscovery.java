package com.loeffler.bpmcoach.desktop.transport;

import com.loeffler.bpmcoach.transport.BleTransport;
import com.loeffler.bpmcoach.transport.DiscoveredDevice;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
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
  private final SubmissionPublisher<DiscoveredDevice> updates = new SubmissionPublisher<>();
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
      cycle++;
      int cycleNumber = cycle;
      CountDownLatch cycleDone = new CountDownLatch(1);
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
