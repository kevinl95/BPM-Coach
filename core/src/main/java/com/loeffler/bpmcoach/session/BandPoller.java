package com.loeffler.bpmcoach.session;

import com.loeffler.bpmcoach.protocol.Frame;
import com.loeffler.bpmcoach.protocol.FrameParser;
import com.loeffler.bpmcoach.protocol.LaxasfitProtocol;
import com.loeffler.bpmcoach.transport.BandConnection;
import com.loeffler.bpmcoach.transport.BleTransport;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Polls a batch of bands concurrently, one virtual thread per band, fanned out with {@link
 * StructuredTaskScope} (JEP 525, {@code open(Joiner, config)}). BLE adapters cap concurrent
 * connections at roughly 7-10, so the roster is split into {@link #BATCH_SIZE}-sized groups and
 * rotated. A dropped or slow band is a soft failure: the band's own display still shows the
 * reading, so one bad connection must never cancel or fail the rest of the batch — which is why the
 * joiner runs every subtask to completion instead of cancelling on first failure.
 */
public final class BandPoller {

  private static final System.Logger LOG = System.getLogger(BandPoller.class.getName());

  public static final int BATCH_SIZE = 6;
  // SimpleBleTransport.connect() includes not just the GATT link but a wait for BlueZ to
  // resolve the service table (up to ~6s on its own - see SimpleBleTransport), so this needs
  // margin beyond just the physical connection time.
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
  private static final Duration WRITE_TIMEOUT = Duration.ofSeconds(5);
  // The real band's own measurement cycle is documented at ~8-10s; this leaves real margin
  // beyond that, since a slow/laggy BLE connection interval can delay notification delivery
  // even after the band has actually finished measuring.
  private static final Duration MEASUREMENT_WAIT = Duration.ofSeconds(15);
  // Confirmed against real hardware: the band's cheap radio negotiates an aggressive low-power
  // connection interval (no requestConnectionPriority equivalent exists in this BLE binding to
  // fix that, unlike the Android reference client), so a notification can be genuinely dropped
  // rather than merely late - the band's own screen has shown a reading that never arrived over
  // BLE. Waiting longer for that same notification wouldn't help; re-sending CMD_HR_START on the
  // SAME still-open connection (cheap - no reconnect) gives it another real shot without paying
  // full reconnect overhead again.
  private static final int MAX_MEASUREMENT_ATTEMPTS = 2;
  // Must exceed CONNECT_TIMEOUT + MAX_MEASUREMENT_ATTEMPTS * (WRITE_TIMEOUT + MEASUREMENT_WAIT)
  // with real margin, since that's the worst-case serial duration of a single pollOne() call.
  private static final Duration BATCH_TIMEOUT = Duration.ofSeconds(70);

  private final BleTransport transport;
  private final ClassSession session;

  public BandPoller(BleTransport transport, ClassSession session) {
    this.transport = transport;
    this.session = session;
  }

  /** Splits the roster into {@link #BATCH_SIZE}-sized groups, preserving order. */
  public static List<List<BandAssignment>> batch(List<BandAssignment> assignments) {
    List<List<BandAssignment>> batches = new ArrayList<>();
    for (int i = 0; i < assignments.size(); i += BATCH_SIZE) {
      batches.add(
          List.copyOf(assignments.subList(i, Math.min(i + BATCH_SIZE, assignments.size()))));
    }
    return List.copyOf(batches);
  }

  /** Polls one batch of bands concurrently; blocks until every band answers or times out. */
  public void pollBatch(List<BandAssignment> assignments) throws InterruptedException {
    try (var scope =
        StructuredTaskScope.open(
            StructuredTaskScope.Joiner.<Void>allUntil(_ -> false),
            cfg -> cfg.withTimeout(BATCH_TIMEOUT))) {
      for (BandAssignment assignment : assignments) {
        scope.fork(
            () -> {
              pollOne(assignment);
              return null;
            });
      }
      scope.join();
    }
  }

  private void pollOne(BandAssignment assignment) {
    String address = assignment.device().address();
    LOG.log(Level.INFO, "Connecting to {0} ({1})", address, assignment.studentId());
    BandConnection connection;
    try {
      connection =
          transport.connect(assignment.device()).get(CONNECT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return;
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Could not connect to " + address, e);
      return;
    }

    try (connection) {
      // One subscription for the whole connection: a late frame from an earlier attempt still
      // counts down whichever latch is currently active, so a delayed-not-dropped notification
      // is still recognized as success instead of silently discarded between attempts.
      AtomicReference<CountDownLatch> currentLatch = new AtomicReference<>(new CountDownLatch(1));
      connection
          .notifications()
          .subscribe(
              new FrameSubscriber(
                  raw -> {
                    Frame frame = FrameParser.parse(raw);
                    if (frame instanceof Frame.HeartRate hr) {
                      LOG.log(Level.INFO, "HR frame from {0}: {1}", address, hr.bpm());
                      session.recordReading(assignment.studentId(), hr.bpm());
                      currentLatch.get().countDown();
                    } else if (frame instanceof Frame.Ack) {
                      LOG.log(Level.DEBUG, "ACK from {0}", address);
                    }
                  }));

      for (int attempt = 1; attempt <= MAX_MEASUREMENT_ATTEMPTS; attempt++) {
        CountDownLatch latch = new CountDownLatch(1);
        currentLatch.set(latch);
        try {
          connection
              .writeCommand(LaxasfitProtocol.withCrc(LaxasfitProtocol.CMD_HR_START))
              .get(WRITE_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        } catch (Exception e) {
          LOG.log(Level.WARNING, "Could not write HR-start command to " + address, e);
          return;
        }

        if (latch.await(MEASUREMENT_WAIT.toSeconds(), TimeUnit.SECONDS)) {
          return;
        }
        LOG.log(
            Level.WARNING,
            "No HR frame from {0} within {1}s (attempt {2}/{3})",
            address,
            MEASUREMENT_WAIT.toSeconds(),
            attempt,
            MAX_MEASUREMENT_ATTEMPTS);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** Minimal unbounded-request subscriber: forwards every item to a callback. */
  private record FrameSubscriber(Consumer<byte[]> onFrame) implements Flow.Subscriber<byte[]> {
    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(byte[] item) {
      onFrame.accept(item);
    }

    @Override
    public void onError(Throwable throwable) {}

    @Override
    public void onComplete() {}
  }
}
