package com.loeffler.bpmcoach.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/**
 * Proves the real-hardware finding: a frame delivered as two BLE notifications (a 20-byte MTU-
 * capped chunk followed by a 1-byte orphaned tail) reassembles into one complete, CRC-valid frame
 * instead of the first chunk being misread as a complete "no reading" frame on its own.
 */
class FrameReassemblerTest {

  // The exact vector from the original test suite's "finger-not-seated" case, split exactly as
  // it would be at a 20-byte ATT MTU payload ceiling, with the reconciled trailing byte (0x52 =
  // 82 bpm) arriving as its own notification.
  private static final byte[] FIRST_CHUNK =
      LaxasfitProtocol.hex("df 00 11 69 05 01 04 00 0c 28 23 00 01 00 00 7a 4b 00 00 00");
  private static final byte[] ORPHANED_TAIL = LaxasfitProtocol.hex("52");

  @Test
  void reassemblesAFrameSplitAcrossTwoNotifications() {
    FrameReassembler reassembler = new FrameReassembler();

    assertTrue(
        reassembler.accept(FIRST_CHUNK).isEmpty(), "incomplete frame must not be emitted yet");

    List<byte[]> completed = reassembler.accept(ORPHANED_TAIL);
    assertEquals(1, completed.size());

    byte[] frame = completed.get(0);
    assertEquals(21, frame.length);
    assertTrue(LaxasfitProtocol.crcValid(frame), "reassembled frame's CRC should validate");
    assertEquals(82, LaxasfitProtocol.parseHeartRate(frame));

    Frame parsed = FrameParser.parse(frame);
    Frame.HeartRate hr = (Frame.HeartRate) parsed;
    assertEquals(OptionalInt.of(82), hr.bpm());
  }

  @Test
  void aFrameDeliveredWholeInOneNotificationPassesThroughUnchanged() {
    FrameReassembler reassembler = new FrameReassembler();
    byte[] wholeFrame =
        LaxasfitProtocol.hex("df 00 11 a1 05 01 04 00 0c 34 28 00 01 00 00 ed fb 00 00 00 56");

    List<byte[]> completed = reassembler.accept(wholeFrame);

    assertEquals(1, completed.size());
    assertArrayEquals(wholeFrame, completed.get(0));
  }

  @Test
  void multipleFramesArrivingInOneNotificationAreBothEmitted() {
    FrameReassembler reassembler = new FrameReassembler();
    byte[] ack = LaxasfitProtocol.hex("fd 00 03 00 02 10 0d");
    byte[] bpCmd = LaxasfitProtocol.withCrc(LaxasfitProtocol.hex("df 00 06 00 02 10 0e 00 01 01"));
    byte[] combined = concat(ack, bpCmd);

    List<byte[]> completed = reassembler.accept(combined);

    assertEquals(2, completed.size());
    assertArrayEquals(ack, completed.get(0));
    assertArrayEquals(bpCmd, completed.get(1));
  }

  @Test
  void resyncsPastAnOrphanByteThatIsNotAFrameStart() {
    // Simulates exactly the failure mode found on real hardware: a subscriber attaching after
    // peripheral.notify() already enabled notifications (or a genuinely dropped fragment) can
    // leave a leftover byte at the head of the buffer that isn't a real frame start. Without
    // resync, that byte's neighbors get misread as a length prefix and the buffer waits forever
    // for a frame that will never complete - silencing the whole connection instead of costing
    // one frame.
    FrameReassembler reassembler = new FrameReassembler();
    byte[] orphan = LaxasfitProtocol.hex("56"); // an arbitrary trailing byte, not 0xDF or 0xFD
    byte[] realFrame =
        LaxasfitProtocol.hex("df 00 11 a1 05 01 04 00 0c 34 28 00 01 00 00 ed fb 00 00 00 56");

    List<byte[]> completed = reassembler.accept(concat(orphan, realFrame));

    assertEquals(1, completed.size(), "the orphan byte must be skipped, not block the real frame");
    assertArrayEquals(realFrame, completed.get(0));
  }

  @Test
  void resyncsWhenAPlausibleMarkerHasAnImplausibleLength() {
    // Covers the case the marker check alone can't catch: a byte that happens to equal a real
    // marker (0xDF) but isn't actually a frame start, so its neighbors produce a length far
    // beyond any real frame in this protocol.
    FrameReassembler reassembler = new FrameReassembler();
    byte[] bogusHeader =
        LaxasfitProtocol.hex("df ff ff"); // marker byte, then an implausible length
    byte[] realFrame = LaxasfitProtocol.hex("fd 00 03 00 02 10 0d");

    List<byte[]> completed = reassembler.accept(concat(bogusHeader, realFrame));

    assertEquals(1, completed.size());
    assertArrayEquals(realFrame, completed.get(0));
  }

  @Test
  void resyncIsFedAcrossMultipleAcceptCallsNotJustOneChunk() {
    // The desync doesn't have to resolve within a single accept() call - a genuinely poisoned
    // buffer (several bad bytes in a row) must still recover once real frame bytes eventually
    // arrive, however many separate notifications that takes.
    FrameReassembler reassembler = new FrameReassembler();
    byte[] garbage = LaxasfitProtocol.hex("11 22 33 44 55");
    byte[] realFrame = LaxasfitProtocol.hex("fd 00 03 00 02 10 0d");

    assertTrue(reassembler.accept(garbage).isEmpty());
    List<byte[]> completed = reassembler.accept(realFrame);

    assertEquals(1, completed.size());
    assertArrayEquals(realFrame, completed.get(0));
  }

  private static byte[] concat(byte[] a, byte[] b) {
    byte[] out = new byte[a.length + b.length];
    System.arraycopy(a, 0, out, 0, a.length);
    System.arraycopy(b, 0, out, a.length, b.length);
    return out;
  }
}
