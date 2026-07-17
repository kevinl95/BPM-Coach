package com.loeffler.bpmcoach.transport;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

/**
 * The seam between the domain layer and a concrete BLE stack. The desktop module provides a real
 * implementation (SimpleJavaBLE-backed) and a mock implementation (simulated bands) that both flow
 * through the exact same {@code core} pipeline.
 */
public interface BleTransport {

  /** Starts scanning; discovered devices are pushed until the subscription is cancelled. */
  Flow.Publisher<DiscoveredDevice> scan();

  /** Connects to a discovered device, requesting a high-priority/tight connection interval. */
  CompletableFuture<BandConnection> connect(DiscoveredDevice device);
}
