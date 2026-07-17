package com.loeffler.bpmcoach.desktop.ui;

import com.loeffler.bpmcoach.domain.Zone;

/** Maps a {@link Zone} to its CSS style class; actual colors live in bpmcoach.css. */
final class ZoneColors {

  private ZoneColors() {}

  static String styleClass(Zone zone) {
    return switch (zone) {
      case LOW -> "zone-low";
      case TARGET -> "zone-target";
      case HIGH -> "zone-high";
      case UNKNOWN -> "zone-unknown";
    };
  }
}
