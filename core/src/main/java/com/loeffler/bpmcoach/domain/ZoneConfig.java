package com.loeffler.bpmcoach.domain;

import java.util.OptionalInt;

/**
 * BPM thresholds used to classify a reading into an effort {@link Zone} for time-in-zone grading.
 * {@code bpm <= lowMax} is light effort (LOW/yellow), {@code lowMax < bpm <= targetMax} is the
 * graded effort zone (TARGET/green), and anything above is HIGH/red (over-effort, worth a teacher's
 * attention).
 */
public record ZoneConfig(int lowMax, int targetMax) {

  public static final ZoneConfig DEFAULT = new ZoneConfig(120, 170);

  public ZoneConfig {
    if (lowMax < 0 || targetMax <= lowMax) {
      throw new IllegalArgumentException(
          "targetMax (%d) must be greater than lowMax (%d) >= 0".formatted(targetMax, lowMax));
    }
  }

  public Zone classify(OptionalInt bpm) {
    if (bpm.isEmpty()) {
      return Zone.UNKNOWN;
    }
    int value = bpm.getAsInt();
    if (value <= lowMax) {
      return Zone.LOW;
    }
    if (value <= targetMax) {
      return Zone.TARGET;
    }
    return Zone.HIGH;
  }
}
