package com.loeffler.bpmcoach.transport;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

/**
 * An open connection to one band's Nordic UART service. Mirrors the connect-subscribe-write flow
 * from the Android reference client, but transport-agnostic: no {@code android.bluetooth} or
 * SimpleBLE types here.
 */
public interface BandConnection extends AutoCloseable {

  /** Writes a command frame (e.g. {@code withCrc(CMD_HR_START)}) to the RX characteristic. */
  CompletableFuture<Void> writeCommand(byte[] frame);

  /** Notification frames pushed by the band on the TX characteristic (ACKs and data frames). */
  Flow.Publisher<byte[]> notifications();

  @Override
  void close();
}
