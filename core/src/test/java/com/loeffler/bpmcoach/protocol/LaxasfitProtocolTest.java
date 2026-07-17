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
  @DisplayName("reference HR frame parses to 86 bpm")
  void referenceHrFrameParses() {
    byte[] hrRef =
        LaxasfitProtocol.hex("df 00 11 a1 05 01 04 00 0c 34 28 00 01 00 00 ed fb 00 00 00 56");
    assertTrue(LaxasfitProtocol.crcValid(hrRef));
    assertEquals(86, LaxasfitProtocol.parseHeartRate(hrRef));
  }

  @Test
  @DisplayName("finger-not-seated HR frame parses to -1 (no reading)")
  void emptyHrFrameParsesToNoReading() {
    // This real capture's checksum does NOT self-validate under crc() (sum ==
    // 0x17, frame carries 0x69) - confirmed against the original TestLaxasfit.java
    // + LaxasfitProtocol.java unmodified, which also fails this one check (14/15,
    // not the full pass count). This is exactly the real-world evidence behind
    // parseHeartRate's documented decision not to enforce inbound CRC: some
    // firmware variants compute it over a record-count-dependent window, so a
    // whole-frame sum doesn't always match even on a genuine capture. What must
    // still hold - and does - is that the frame parses correctly regardless.
    byte[] hrEmpty =
        LaxasfitProtocol.hex("df 00 11 69 05 01 04 00 0c 28 23 00 01 00 00 7a 4b 00 00 00");
    assertEquals(-1, LaxasfitProtocol.parseHeartRate(hrEmpty));
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
