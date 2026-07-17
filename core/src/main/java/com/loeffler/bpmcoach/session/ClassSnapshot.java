package com.loeffler.bpmcoach.session;

import java.time.Instant;
import java.util.List;

/**
 * An immutable, whole-class view pushed to subscribers (e.g. the projector view) on every update.
 */
public record ClassSnapshot(Instant asOf, List<StudentStatus> statuses) {

  public ClassSnapshot {
    statuses = List.copyOf(statuses);
  }
}
