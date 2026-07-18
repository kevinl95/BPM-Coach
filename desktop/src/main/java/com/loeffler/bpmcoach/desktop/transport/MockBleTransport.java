package com.loeffler.bpmcoach.desktop.transport;

import com.loeffler.bpmcoach.protocol.LaxasfitProtocol;
import com.loeffler.bpmcoach.session.BandPoller;
import com.loeffler.bpmcoach.transport.BandConnection;
import com.loeffler.bpmcoach.transport.BleTransport;
import com.loeffler.bpmcoach.transport.DiscoveredDevice;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;

/**
 * Simulates {@code bandCount} bands so the app is demoable with only 2 real bands on hand. Runs
 * through the exact same {@code core} pipeline as {@link SimpleBleTransport}: same {@link
 * BleTransport}/{@link BandConnection} contract, same frame bytes, same {@code FrameParser}/{@code
 * BandPoller} - only the byte source differs.
 */
public final class MockBleTransport implements BleTransport {

  private static final int CMD_HR_START_ID = LaxasfitProtocol.cmdId(LaxasfitProtocol.CMD_HR_START);
  private static final double FINGER_OFF_PROBABILITY = 0.05;

  private final List<DiscoveredDevice> simulatedDevices;
  private final Map<String, MockBandState> states = new ConcurrentHashMap<>();

  public MockBleTransport(int bandCount) {
    this.simulatedDevices =
        IntStream.range(0, bandCount)
            .mapToObj(
                i ->
                    new DiscoveredDevice(
                        "MOCK-%02d".formatted(i),
                        "Mock Band %d".formatted(i + 1),
                        -40 - ThreadLocalRandom.current().nextInt(30)))
            .toList();
    for (DiscoveredDevice device : simulatedDevices) {
      states.put(device.address(), new MockBandState());
    }
  }

  public List<DiscoveredDevice> knownDevices() {
    return simulatedDevices;
  }

  @Override
  public Flow.Publisher<DiscoveredDevice> scan() {
    SubmissionPublisher<DiscoveredDevice> publisher = new SubmissionPublisher<>();
    // SubmissionPublisher doesn't replay: emission must not start until the
    // subscriber is actually registered, or early items are silently dropped.
    // subscribe() registers synchronously before returning (only onSubscribe's
    // delivery is async), so starting the emitter here is race-free.
    return subscriber -> {
      publisher.subscribe(subscriber);
      Thread.startVirtualThread(
          () -> {
            for (DiscoveredDevice device : simulatedDevices) {
              publisher.submit(device);
              sleep(150);
            }
            publisher.close();
          });
    };
  }

  @Override
  public CompletableFuture<BandConnection> connect(DiscoveredDevice device) {
    MockBandState state = states.get(device.address());
    if (state == null) {
      return CompletableFuture.failedFuture(
          new IllegalArgumentException("Unknown mock device " + device.address()));
    }
    return CompletableFuture.completedFuture(new MockBandConnection(state));
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }

  /** Persists each simulated band's current BPM across poll cycles (a random walk). */
  private static final class MockBandState {
    volatile int bpm = 70 + ThreadLocalRandom.current().nextInt(60);
  }

  private static final class MockBandConnection implements BandConnection {
    private final MockBandState state;
    private final SubmissionPublisher<byte[]> notifications = new SubmissionPublisher<>();

    MockBandConnection(MockBandState state) {
      this.state = state;
    }

    @Override
    public CompletableFuture<Void> writeCommand(byte[] frame) {
      if (LaxasfitProtocol.cmdId(frame) == CMD_HR_START_ID) {
        scheduleReading();
      }
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public Flow.Publisher<byte[]> notifications() {
      return notifications;
    }

    @Override
    public void close() {
      notifications.close();
    }

    private void scheduleReading() {
      // Must stay above BandPoller.MIN_LIVE_READING_DELAY, or a legitimate mock reading would
      // get rejected by the same "too fast to be real" guard that's there to catch a
      // stored-record push on actual hardware - mock and real must clear the same bar.
      int lowerBoundMillis = (int) BandPoller.MIN_LIVE_READING_DELAY.toMillis() + 1000;
      Thread.startVirtualThread(
          () -> {
            sleep(ThreadLocalRandom.current().nextInt(lowerBoundMillis, lowerBoundMillis + 3000));
            int bpm;
            if (ThreadLocalRandom.current().nextDouble() < FINGER_OFF_PROBABILITY) {
              bpm = 0; // "no reading this cycle", same sentinel the real band sends
            } else {
              int walk = ThreadLocalRandom.current().nextInt(-6, 7);
              state.bpm = clamp(state.bpm + walk, 55, 195);
              bpm = state.bpm;
            }
            notifications.submit(heartRateFrame(bpm));
          });
    }

    private static byte[] heartRateFrame(int bpm) {
      // The length prefix (bytes 1-2, big-endian) DOES matter now: FrameReassembler needs it to
      // know where this frame ends. CRC/date-time padding bytes still don't matter to parsing
      // (see LaxasfitProtocol.parseHeartRate) so those stay zeroed.
      byte[] frame = new byte[21];
      frame[0] = LaxasfitProtocol.MSG_DATA;
      int len = frame.length - 4;
      frame[1] = (byte) ((len >> 8) & 0xFF);
      frame[2] = (byte) (len & 0xFF);
      frame[4] = (byte) ((LaxasfitProtocol.DATA_HR >> 16) & 0xFF);
      frame[5] = (byte) ((LaxasfitProtocol.DATA_HR >> 8) & 0xFF);
      frame[6] = (byte) (LaxasfitProtocol.DATA_HR & 0xFF);
      frame[20] = (byte) bpm;
      return frame;
    }
  }
}
