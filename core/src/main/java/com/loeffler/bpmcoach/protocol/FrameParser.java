package com.loeffler.bpmcoach.protocol;

import java.util.OptionalInt;

/** Turns raw notification bytes into a {@link Frame} using {@link LaxasfitProtocol}. */
public final class FrameParser {

  private FrameParser() {}

  public static Frame parse(byte[] raw) {
    if (LaxasfitProtocol.isAck(raw)) {
      return new Frame.Ack();
    }
    if (!LaxasfitProtocol.isData(raw)) {
      return new Frame.Unknown();
    }
    return switch (LaxasfitProtocol.cmdId(raw)) {
      case LaxasfitProtocol.DATA_HR ->
          new Frame.HeartRate(reading(LaxasfitProtocol.parseHeartRate(raw)));
      case LaxasfitProtocol.DATA_SPO2 -> new Frame.Spo2(reading(LaxasfitProtocol.parseSpo2(raw)));
      case LaxasfitProtocol.DATA_BP -> bloodPressure(raw);
      default -> new Frame.Unknown();
    };
  }

  private static Frame bloodPressure(byte[] raw) {
    int[] bp = LaxasfitProtocol.parseBloodPressure(raw);
    return bp == null ? new Frame.Unknown() : new Frame.BloodPressure(bp[0], bp[1]);
  }

  private static OptionalInt reading(int value) {
    return value < 0 ? OptionalInt.empty() : OptionalInt.of(value);
  }
}
