package com.loeffler.bpmcoach.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class ZoneConfigTest {

  private final ZoneConfig config = new ZoneConfig(120, 170);

  @Test
  void noReadingIsUnknownZone() {
    assertEquals(Zone.UNKNOWN, config.classify(OptionalInt.empty()));
  }

  @Test
  void atOrBelowLowMaxIsLowZone() {
    assertEquals(Zone.LOW, config.classify(OptionalInt.of(90)));
    assertEquals(Zone.LOW, config.classify(OptionalInt.of(120)));
  }

  @Test
  void betweenLowAndTargetMaxIsTargetZone() {
    assertEquals(Zone.TARGET, config.classify(OptionalInt.of(121)));
    assertEquals(Zone.TARGET, config.classify(OptionalInt.of(170)));
  }

  @Test
  void aboveTargetMaxIsHighZone() {
    assertEquals(Zone.HIGH, config.classify(OptionalInt.of(171)));
    assertEquals(Zone.HIGH, config.classify(OptionalInt.of(210)));
  }

  @Test
  void targetMaxMustExceedLowMax() {
    assertThrows(IllegalArgumentException.class, () -> new ZoneConfig(150, 150));
  }
}
