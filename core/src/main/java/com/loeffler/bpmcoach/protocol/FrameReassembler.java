package com.loeffler.bpmcoach.protocol;

import java.io.ByteArrayOutputStream;
import java.lang.System.Logger.Level;
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
 *
 * <p>Resyncs on a bad buffer head instead of trusting it blindly: if the connection ever loses a
 * fragment (or a subscriber attaches after notifications were already enabled - see
 * SimpleBleTransport - so the very first bytes it ever sees are mid-frame), the byte at the head of
 * the buffer might not actually be a frame start. Reading a length prefix from arbitrary bytes can
 * produce an implausible value the buffer will then wait forever to fill, silencing the connection
 * for the rest of the session instead of costing just the one bad frame. Only {@link
 * LaxasfitProtocol#MSG_DATA} and {@link LaxasfitProtocol#MSG_ACK} are legal frame-start bytes, and
 * {@link #MAX_FRAME_LENGTH} caps how large a real frame can plausibly be; anything else means the
 * buffer is misaligned, so one byte is dropped and resync is retried from the next position.
 */
public final class FrameReassembler {

  private static final System.Logger LOG = System.getLogger(FrameReassembler.class.getName());

  // The largest documented frame in this protocol family is a ~203-byte steps dump; this leaves
  // real margin above that while still being far below what a genuinely desynced length prefix
  // (an arbitrary 16-bit value from two misaligned bytes) would typically produce.
  private static final int MAX_FRAME_LENGTH = 512;

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
      int marker = pending[offset] & 0xFF;
      if (marker != (LaxasfitProtocol.MSG_DATA & 0xFF)
          && marker != (LaxasfitProtocol.MSG_ACK & 0xFF)) {
        LOG.log(
            Level.WARNING,
            "Resyncing frame buffer: byte 0x{0} at offset {1} isn''t a valid frame start",
            Integer.toHexString(marker),
            offset);
        offset++;
        continue;
      }
      int len = ((pending[offset + 1] & 0xFF) << 8) | (pending[offset + 2] & 0xFF);
      int totalLength = len + 4;
      if (totalLength > MAX_FRAME_LENGTH) {
        LOG.log(
            Level.WARNING,
            "Resyncing frame buffer: implausible frame length {0} at offset {1}",
            totalLength,
            offset);
        offset++;
        continue;
      }
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
