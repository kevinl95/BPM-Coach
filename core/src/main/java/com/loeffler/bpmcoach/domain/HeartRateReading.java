package com.loeffler.bpmcoach.domain;

import java.time.Instant;
import java.util.OptionalInt;

/** One heart-rate sample for a student, timestamped for the history view. */
public record HeartRateReading(String studentId, OptionalInt bpm, Instant timestamp) {

  public HeartRateReading {
    if (studentId == null || studentId.isBlank()) {
      throw new IllegalArgumentException("studentId must not be blank");
    }
  }
}
