package com.loeffler.bpmcoach.domain;

/**
 * Effort zone for time-in-zone grading. {@code UNKNOWN} means no recent reading is available (band
 * not connected yet, or finger not seated).
 */
public enum Zone {
  UNKNOWN,
  LOW,
  TARGET,
  HIGH
}
