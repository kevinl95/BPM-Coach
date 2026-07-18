package com.loeffler.bpmcoach.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Direct JUnit 5 port of {@code TestLaxasfit.java}'s manual 9-check harness, against the exact same
 * hex frame vectors (spec reference frames plus real captures, including the finger-off/empty-frame
 * case). {@link LaxasfitProtocol} itself is untouched ground truth; only the harness moved to
 * JUnit.
 */
class LaxasfitProtocolTest {

  @Test
  @DisplayName("CRC of BP-start frame == 0x07")
  void crcOfBpStartFrame() {
    byte[] bp = LaxasfitProtocol.hex("df 00 06 00 02 10 0e 00 01 01");
    assertEquals(0x07, LaxasfitProtocol.crc(bp) & 0xFF);
  }

  @Test
  @DisplayName("withCrc sets byte[3]=0x07 and is self-consistent")
  void withCrcFillsSlotCorrectly() {
    byte[] bp = LaxasfitProtocol.hex("df 00 06 00 02 10 0e 00 01 01");
    byte[] filled = LaxasfitProtocol.withCrc(bp);
    assertEquals(0x07, filled[3] & 0xFF);
    assertTrue(LaxasfitProtocol.crcValid(filled));
  }

  @Test
  @DisplayName("CRC of HR-start frame == 0x06, and CMD_HR_START matches once CRC'd")
  void hrStartCommandCrc() {
    byte[] hrCmd = LaxasfitProtocol.hex("df 00 06 00 02 10 0d 00 01 01");
    assertEquals(0x06, LaxasfitProtocol.crc(hrCmd) & 0xFF);
    assertArrayEquals(
        LaxasfitProtocol.hex("df 00 06 06 02 10 0d 00 01 01"),
        LaxasfitProtocol.withCrc(LaxasfitProtocol.CMD_HR_START));
  }

  @Test
  @DisplayName("CMD_HR_DATA_ACK matches the documented host-side data-ack frame once CRC'd")
  void hrDataAckCommandCrc() {
    // Gadgetbridge issue #5640's documented exchange: after DATA_HR, the host acks with this
    // exact frame (fd 00 05 0c 05 04 00 00 01).
    assertArrayEquals(
        LaxasfitProtocol.hex("fd 00 05 0c 05 04 00 00 01"),
        LaxasfitProtocol.withCrc(LaxasfitProtocol.CMD_HR_DATA_ACK));
  }

  @Test
  @DisplayName("reference HR frame parses to 86 bpm")
  void referenceHrFrameParses() {
    byte[] hrRef =
        LaxasfitProtocol.hex("df 00 11 a1 05 01 04 00 0c 34 28 00 01 00 00 ed fb 00 00 00 56");
    assertTrue(LaxasfitProtocol.crcValid(hrRef));
    assertEquals(86, LaxasfitProtocol.parseHeartRate(hrRef));
  }

  @Test
  @DisplayName("truncated HR frame (last byte missing) parses to -1 given only what arrived")
  void truncatedHrFrameParsesToNoReadingGivenOnlyThePartialBytes() {
    // This is NOT actually a finger-off/no-reading capture, despite how the original harness
    // labeled it - it's a genuine 82 bpm reading, one byte short. The length prefix (bytes 1-2 =
    // 0x0011 = 17) says the frame should be 17+4 = 21 bytes, same as every other DATA_HR capture,
    // but this vector is only 20. Its checksum doesn't self-validate under crc() (sum == 0x17,
    // frame carries 0x69) for exactly that reason: 0x69 - 0x17 = 0x52 = 82 (decimal), the missing
    // trailing byte - append it and the CRC validates. See FrameReassemblerTest, which reconstructs
    // this exact frame from a 20-byte chunk + the 1-byte tail and confirms it parses to 82 bpm.
    //
    // What's tested here is still correct and worth keeping: LaxasfitProtocol.parseHeartRate has
    // no notion of frame boundaries, only "read the last byte of whatever you're given" - so
    // handed exactly these 20 (incomplete) bytes on their own, -1 is the right answer. Reassembly
    // is FrameReassembler's job, upstream of this call, not this ground-truth file's.
    byte[] truncated =
        LaxasfitProtocol.hex("df 00 11 69 05 01 04 00 0c 28 23 00 01 00 00 7a 4b 00 00 00");
    assertEquals(-1, LaxasfitProtocol.parseHeartRate(truncated));
  }

  @Test
  @DisplayName("reference SpO2 frame parses to 95%")
  void referenceSpo2FrameParses() {
    byte[] spo2 =
        LaxasfitProtocol.hex("df 00 11 69 05 01 0e 00 0c 34 29 00 01 00 00 a7 f5 00 00 00 5f");
    assertTrue(LaxasfitProtocol.crcValid(spo2));
    assertEquals(95, LaxasfitProtocol.parseSpo2(spo2));
  }

  @Test
  @DisplayName("reference BP frame parses to 112/69")
  void referenceBpFrameParses() {
    byte[] bpRes =
        LaxasfitProtocol.hex("df 00 11 cc 05 01 05 00 0c 34 28 00 01 00 00 f4 bf 00 00 70 45");
    assertTrue(LaxasfitProtocol.crcValid(bpRes));
    int[] bpv = LaxasfitProtocol.parseBloodPressure(bpRes);
    assertEquals(112, bpv[0]);
    assertEquals(69, bpv[1]);
  }

  @Test
  @DisplayName("cmdId extraction distinguishes HR and SpO2 frames")
  void cmdIdDistinguishesFrameTypes() {
    byte[] hrRef =
        LaxasfitProtocol.hex("df 00 11 a1 05 01 04 00 0c 34 28 00 01 00 00 ed fb 00 00 00 56");
    byte[] spo2 =
        LaxasfitProtocol.hex("df 00 11 69 05 01 0e 00 0c 34 29 00 01 00 00 a7 f5 00 00 00 5f");
    assertEquals(LaxasfitProtocol.DATA_HR, LaxasfitProtocol.cmdId(hrRef));
    assertEquals(LaxasfitProtocol.DATA_SPO2, LaxasfitProtocol.cmdId(spo2));
  }

  @Test
  @DisplayName("non-data frame is not misclassified as data")
  void ackFrameIsNotData() {
    byte[] ack = LaxasfitProtocol.hex("fd 00 03 00 02 10 0d");
    assertFalse(LaxasfitProtocol.isData(ack));
    assertTrue(LaxasfitProtocol.isAck(ack));
  }
}
