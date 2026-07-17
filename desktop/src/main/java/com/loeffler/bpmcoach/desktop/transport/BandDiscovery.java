package com.loeffler.bpmcoach.desktop.transport;

import com.loeffler.bpmcoach.transport.BleTransport;
import com.loeffler.bpmcoach.transport.DiscoveredDevice;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;

/**
 * Runs {@link BleTransport#scan()} back-to-back in a loop for as long as the app is running,
 * keeping a live map of every band seen so far - which is what makes "recognize a previously paired
 * band on the next launch" possible: {@link com.loeffler.bpmcoach.desktop.MainApp}'s poll loop can
 * only connect to an address once it has actually been (re)discovered this session. The pairing
 * view subscribes to the same stream to show what's currently in range.
 */
public final class BandDiscovery {

  private static final Duration SCAN_CYCLE_TIMEOUT = Duration.ofSeconds(15);
  private static final Duration PAUSE_BETWEEN_CYCLES = Duration.ofSeconds(1);

  private final BleTransport transport;
  private final Map<String, DiscoveredDevice> known = new ConcurrentHashMap<>();
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

  public Map<String, DiscoveredDevice> knownDevices() {
    return Map.copyOf(known);
  }

  public Flow.Publisher<DiscoveredDevice> updates() {
    return updates;
  }

  private void loop() {
    while (running) {
      CountDownLatch cycleDone = new CountDownLatch(1);
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
                  known.put(device.address(), device);
                  updates.submit(device);
                }

                @Override
                public void onError(Throwable throwable) {
                  cycleDone.countDown();
                }

                @Override
                public void onComplete() {
                  cycleDone.countDown();
                }
              });
      try {
        cycleDone.await(SCAN_CYCLE_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
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
