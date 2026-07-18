package com.loeffler.bpmcoach.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class FrameParserTest {

  @Test
  void parsesReferenceHeartRateFrame() {
    byte[] hrRef =
        LaxasfitProtocol.hex("df 00 11 a1 05 01 04 00 0c 34 28 00 01 00 00 ed fb 00 00 00 56");
    Frame frame = FrameParser.parse(hrRef);
    Frame.HeartRate hr = assertInstanceOf(Frame.HeartRate.class, frame);
    assertEquals(OptionalInt.of(86), hr.bpm());
  }

  @Test
  void truncatedFrameGivenAloneHasEmptyReading() {
    // Not a genuine finger-off capture - see LaxasfitProtocolTest and FrameReassemblerTest - but
    // handed exactly these (incomplete) bytes on their own, this is still the correct answer:
    // FrameParser/LaxasfitProtocol have no notion of frame boundaries, only "read what's here."
    byte[] truncated =
        LaxasfitProtocol.hex("df 00 11 69 05 01 04 00 0c 28 23 00 01 00 00 7a 4b 00 00 00");
    Frame frame = FrameParser.parse(truncated);
    Frame.HeartRate hr = assertInstanceOf(Frame.HeartRate.class, frame);
    assertTrue(hr.bpm().isEmpty());
  }

  @Test
  void genuineNoReadingCompleteFrameHasEmptyReading() {
    // A complete, correctly-sized (21-byte), CRC-valid frame with bpm=0: the actual shape of a
    // real "no valid reading this cycle" result, as opposed to the truncated vector above.
    byte[] noReading =
        LaxasfitProtocol.hex("df 00 11 4b 05 01 04 00 0c 34 28 00 01 00 00 ed fb 00 00 00 00");
    assertTrue(LaxasfitProtocol.crcValid(noReading));
    Frame frame = FrameParser.parse(noReading);
    Frame.HeartRate hr = assertInstanceOf(Frame.HeartRate.class, frame);
    assertTrue(hr.bpm().isEmpty());
  }

  @Test
  void parsesReferenceBloodPressureFrame() {
    byte[] bpRes =
        LaxasfitProtocol.hex("df 00 11 cc 05 01 05 00 0c 34 28 00 01 00 00 f4 bf 00 00 70 45");
    Frame frame = FrameParser.parse(bpRes);
    Frame.BloodPressure bp = assertInstanceOf(Frame.BloodPressure.class, frame);
    assertEquals(112, bp.systolic());
    assertEquals(69, bp.diastolic());
  }

  @Test
  void ackFrameParsesToAck() {
    byte[] ack = LaxasfitProtocol.hex("fd 00 03 00 02 10 0d");
    assertInstanceOf(Frame.Ack.class, FrameParser.parse(ack));
  }

  @Test
  void garbageParsesToUnknown() {
    byte[] garbage = LaxasfitProtocol.hex("00 01 02");
    assertInstanceOf(Frame.Unknown.class, FrameParser.parse(garbage));
  }
}
