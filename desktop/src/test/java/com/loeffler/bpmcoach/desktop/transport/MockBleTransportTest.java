package com.loeffler.bpmcoach.desktop.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loeffler.bpmcoach.protocol.Frame;
import com.loeffler.bpmcoach.protocol.FrameParser;
import com.loeffler.bpmcoach.protocol.LaxasfitProtocol;
import com.loeffler.bpmcoach.transport.BandConnection;
import com.loeffler.bpmcoach.transport.DiscoveredDevice;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class MockBleTransportTest {

  @Test
  void knownDevicesMatchesRequestedBandCount() {
    MockBleTransport transport = new MockBleTransport(4);
    assertEquals(4, transport.knownDevices().size());
  }

  @Test
  void hrStartCommandEventuallyProducesAParsableHeartRateFrame() throws Exception {
    MockBleTransport transport = new MockBleTransport(1);
    DiscoveredDevice device = transport.knownDevices().get(0);
    BandConnection connection = transport.connect(device).get(2, TimeUnit.SECONDS);

    CountDownLatch received = new CountDownLatch(1);
    AtomicReference<Frame> lastFrame = new AtomicReference<>();
    connection
        .notifications()
        .subscribe(
            new Flow.Subscriber<byte[]>() {
              @Override
              public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
              }

              @Override
              public void onNext(byte[] item) {
                lastFrame.set(FrameParser.parse(item));
                received.countDown();
              }

              @Override
              public void onError(Throwable throwable) {}

              @Override
              public void onComplete() {}
            });

    connection
        .writeCommand(LaxasfitProtocol.withCrc(LaxasfitProtocol.CMD_HR_START))
        .get(1, TimeUnit.SECONDS);

    // MockBleTransport's simulated delay is calibrated to stay above
    // BandPoller.MIN_LIVE_READING_DELAY (currently up to ~9s); leave real margin above that.
    assertTrue(received.await(15, TimeUnit.SECONDS), "expected a heart-rate frame within 15s");
    assertTrue(lastFrame.get() instanceof Frame.HeartRate);
    connection.close();
  }

  @Test
  void nonHrCommandDoesNotScheduleAReading() throws Exception {
    MockBleTransport transport = new MockBleTransport(1);
    DiscoveredDevice device = transport.knownDevices().get(0);
    BandConnection connection = transport.connect(device).get(2, TimeUnit.SECONDS);

    CountDownLatch received = new CountDownLatch(1);
    connection
        .notifications()
        .subscribe(
            new Flow.Subscriber<byte[]>() {
              @Override
              public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
              }

              @Override
              public void onNext(byte[] item) {
                received.countDown();
              }

              @Override
              public void onError(Throwable throwable) {}

              @Override
              public void onComplete() {}
            });

    connection
        .writeCommand(LaxasfitProtocol.withCrc(LaxasfitProtocol.CMD_SPO2_START))
        .get(1, TimeUnit.SECONDS);

    assertFalse(
        received.await(1, TimeUnit.SECONDS), "SpO2-start must not trigger a mock HR reading");
    connection.close();
  }

  @Test
  void scanEmitsExactlyTheKnownDevices() throws Exception {
    MockBleTransport transport = new MockBleTransport(3);
    List<DiscoveredDevice> found =
        java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    CountDownLatch done = new CountDownLatch(1);
    transport
        .scan()
        .subscribe(
            new Flow.Subscriber<DiscoveredDevice>() {
              @Override
              public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
              }

              @Override
              public void onNext(DiscoveredDevice item) {
                found.add(item);
              }

              @Override
              public void onError(Throwable throwable) {
                done.countDown();
              }

              @Override
              public void onComplete() {
                done.countDown();
              }
            });

    assertTrue(done.await(5, TimeUnit.SECONDS));
    assertEquals(3, found.size());
  }
}
