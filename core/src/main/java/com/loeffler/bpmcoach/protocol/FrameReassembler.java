package com.loeffler.bpmcoach.protocol;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * BLE notifications are capped by the negotiated ATT MTU (20 bytes of payload at the common default
 * MTU of 23), but a LaxasfitProtocol frame can be longer than that - the band splits one logical
 * frame across multiple GATT notifications rather than sending an oversized one. This buffers raw
 * notification bytes across calls and emits only complete frames, using the frame's own length
 * prefix (bytes 1-2, big-endian; total frame length is that value + 4) to know when enough has
 * arrived.
 *
 * <p>Confirmed against a real captured vector that was checked into the original test suite as
 * "finger-not-seated": it's actually a genuine 82 bpm reading truncated to 20 bytes, with the
 * trailing byte lost as an orphaned single-byte notification that didn't parse as anything on its
 * own and was silently dropped. Its CRC "failure" reconciles exactly once the missing byte is
 * accounted for - it was never a firmware CRC-window quirk, it was unassembled input.
 */
public final class FrameReassembler {

  private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

  /**
   * Feeds in one notification's raw bytes; returns any complete frames now available (0, 1, or
   * more).
   */
  public synchronized List<byte[]> accept(byte[] chunk) {
    buffer.writeBytes(chunk);
    byte[] pending = buffer.toByteArray();

    List<byte[]> complete = new ArrayList<>();
    int offset = 0;
    while (pending.length - offset >= 3) {
      int len = ((pending[offset + 1] & 0xFF) << 8) | (pending[offset + 2] & 0xFF);
      int totalLength = len + 4;
      if (pending.length - offset < totalLength) {
        break; // rest of this frame hasn't arrived yet
      }
      complete.add(Arrays.copyOfRange(pending, offset, offset + totalLength));
      offset += totalLength;
    }

    buffer.reset();
    if (offset < pending.length) {
      buffer.writeBytes(Arrays.copyOfRange(pending, offset, pending.length));
    }
    return complete;
  }
}
