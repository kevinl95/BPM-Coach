package com.loeffler.bpmcoach.session;

import com.loeffler.bpmcoach.protocol.Frame;
import com.loeffler.bpmcoach.protocol.FrameParser;
import com.loeffler.bpmcoach.protocol.LaxasfitProtocol;
import com.loeffler.bpmcoach.transport.BandConnection;
import com.loeffler.bpmcoach.transport.BleTransport;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.TimeUnit;
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

  public static final int BATCH_SIZE = 6;
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(8);
  private static final Duration MEASUREMENT_WAIT = Duration.ofSeconds(12);
  private static final Duration BATCH_TIMEOUT = Duration.ofSeconds(20);

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
    try (BandConnection connection =
        transport.connect(assignment.device()).get(CONNECT_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
      CountDownLatch readingReceived = new CountDownLatch(1);
      connection
          .notifications()
          .subscribe(
              new FrameSubscriber(
                  raw -> {
                    if (FrameParser.parse(raw) instanceof Frame.HeartRate hr) {
                      session.recordReading(assignment.studentId(), hr.bpm());
                      readingReceived.countDown();
                    }
                  }));
      connection
          .writeCommand(LaxasfitProtocol.withCrc(LaxasfitProtocol.CMD_HR_START))
          .get(CONNECT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
      readingReceived.await(MEASUREMENT_WAIT.toSeconds(), TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      // Soft failure: the band's own display already shows the reading;
      // a dropped link here just means this cycle has no logged sample.
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
