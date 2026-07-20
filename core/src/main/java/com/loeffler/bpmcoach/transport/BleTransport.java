package com.loeffler.bpmcoach.transport;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

/**
 * The seam between the domain layer and a concrete BLE stack. The desktop module provides a real
 * implementation (SimpleJavaBLE-backed) and a mock implementation (simulated bands) that both flow
 * through the exact same {@code core} pipeline.
 */
public interface BleTransport {

  /**
   * Starts scanning; discovered devices are pushed indefinitely and the publisher does not
   * self-complete - call {@link #stopScan()} to end it (e.g. to give a live connection exclusive
   * radio access). Calling {@code scan()} again after {@link #stopScan()} starts a fresh scan.
   */
  Flow.Publisher<DiscoveredDevice> scan();

  /**
   * Stops whatever scan is currently running, if any; safe to call with no scan active. No-op by
   * default; real transports with an underlying native scan session should override this.
   */
  default void stopScan() {}

  /** Connects to a discovered device, requesting a high-priority/tight connection interval. */
  CompletableFuture<BandConnection> connect(DiscoveredDevice device);

  /**
   * Best-effort cleanup for any connections still open when the app is shutting down abruptly (e.g.
   * Ctrl+C/SIGINT, which bypasses the normal per-connection {@code try}-with-resources close).
   * No-op by default; real transports with OS-level connection state should override this to avoid
   * leaving a BLE link stuck open after the process is gone.
   */
  default void shutdown() {}
}
