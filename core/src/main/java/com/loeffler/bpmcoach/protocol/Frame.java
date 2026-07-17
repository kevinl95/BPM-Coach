package com.loeffler.bpmcoach.protocol;

import java.util.OptionalInt;

/**
 * A decoded Laxasfit notification frame. Wraps the byte-level parsing in {@link LaxasfitProtocol}
 * (left untouched as verified ground truth) in a sealed hierarchy so every call site that
 * dispatches on frame type is checked exhaustively by the compiler at each switch.
 */
public sealed interface Frame {

  /** Heart-rate result. Empty {@code bpm} means "no reading this cycle" (finger not seated). */
  record HeartRate(OptionalInt bpm) implements Frame {}

  /** SpO2 result. Empty {@code percent} means "no reading this cycle". */
  record Spo2(OptionalInt percent) implements Frame {}

  /** Blood-pressure result. */
  record BloodPressure(int systolic, int diastolic) implements Frame {}

  /** Command acknowledgement from the band. */
  record Ack() implements Frame {}

  /** Any frame that doesn't match a known data command. */
  record Unknown() implements Frame {}
}
