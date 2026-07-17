package com.loeffler.bpmcoach.session;

import com.loeffler.bpmcoach.domain.Student;
import com.loeffler.bpmcoach.domain.Zone;
import java.time.Instant;
import java.util.OptionalInt;

/** The most recent known state of one student, as shown on a projector tile. */
public record StudentStatus(Student student, OptionalInt bpm, Zone zone, Instant lastUpdate) {

  public static StudentStatus unknown(Student student) {
    return new StudentStatus(student, OptionalInt.empty(), Zone.UNKNOWN, null);
  }
}
