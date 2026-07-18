package com.loeffler.bpmcoach.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loeffler.bpmcoach.domain.Student;
import com.loeffler.bpmcoach.domain.Zone;
import com.loeffler.bpmcoach.domain.ZoneConfig;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClassSessionTest {

  @Test
  void upsertStudentAddsANewPairing() {
    ClassSession session = new ClassSession(List.of(), ZoneConfig.DEFAULT);
    session.upsertStudent(new Student("emma", "Emma", "AA:BB:CC:01"));

    assertEquals(1, session.roster().size());
    assertEquals(1, session.assignments().size());
    assertEquals("AA:BB:CC:01", session.assignments().get(0).device().address());
  }

  @Test
  void unassignBandClearsWhicheverStudentHeldIt() {
    ClassSession session =
        new ClassSession(List.of(new Student("emma", "Emma", "AA:BB:CC:01")), ZoneConfig.DEFAULT);

    session.unassignBand("AA:BB:CC:01");

    assertTrue(session.assignments().isEmpty());
    assertNull(session.roster().get(0).assignedBandAddress());
  }

  @Test
  void unassignBandIsANoOpWhenNobodyHoldsIt() {
    ClassSession session =
        new ClassSession(List.of(new Student("emma", "Emma", "AA:BB:CC:01")), ZoneConfig.DEFAULT);

    session.unassignBand("does-not-exist");

    assertEquals(1, session.assignments().size());
  }

  @Test
  void reassigningAnAddressToAnotherStudentDoesNotDuplicateIt() {
    ClassSession session =
        new ClassSession(
            List.of(new Student("emma", "Emma", "AA:BB:CC:01"), new Student("liam", "Liam", null)),
            ZoneConfig.DEFAULT);

    // pairing UI's actual sequence: free the address, then assign it elsewhere
    session.unassignBand("AA:BB:CC:01");
    session.upsertStudent(new Student("liam", "Liam", "AA:BB:CC:01"));

    assertEquals(1, session.assignments().size());
    assertEquals("liam", session.assignments().get(0).studentId());
  }

  @Test
  void removeStudentDeletesThemFromTheRosterEntirely() {
    ClassSession session =
        new ClassSession(
            List.of(new Student("emma", "Emma", "AA:BB:CC:01"), new Student("liam", "Liam", null)),
            ZoneConfig.DEFAULT);

    session.removeStudent("emma");

    assertEquals(1, session.roster().size());
    assertEquals("liam", session.roster().get(0).id());
    assertTrue(session.assignments().isEmpty());
    assertTrue(
        session.currentSnapshot().statuses().stream()
            .noneMatch(s -> s.student().id().equals("emma")));
  }

  @Test
  void removeStudentIsANoOpForAnUnknownId() {
    ClassSession session =
        new ClassSession(List.of(new Student("emma", "Emma", "AA:BB:CC:01")), ZoneConfig.DEFAULT);

    session.removeStudent("does-not-exist");

    assertEquals(1, session.roster().size());
  }

  @Test
  void upsertPreservesLatestReadingWhenOnlyTheBandChanges() {
    ClassSession session =
        new ClassSession(List.of(new Student("emma", "Emma", "AA:BB:CC:01")), ZoneConfig.DEFAULT);
    session.recordReading("emma", java.util.OptionalInt.of(130));

    session.upsertStudent(new Student("emma", "Emma", "AA:BB:CC:02"));

    StudentStatus status = session.currentSnapshot().statuses().get(0);
    assertEquals(java.util.OptionalInt.of(130), status.bpm());
  }

  @Test
  void aNoReadingCycleDoesNotBlankAPreviousGoodReading() {
    ClassSession session =
        new ClassSession(List.of(new Student("emma", "Emma", "AA:BB:CC:01")), ZoneConfig.DEFAULT);
    session.recordReading("emma", java.util.OptionalInt.of(82));

    session.recordReading("emma", java.util.OptionalInt.empty());

    StudentStatus status = session.currentSnapshot().statuses().get(0);
    assertEquals(java.util.OptionalInt.of(82), status.bpm());
    assertEquals(Zone.LOW, status.zone());
  }

  @Test
  void aNoReadingCycleIsStillRecordedInHistory() {
    ClassSession session =
        new ClassSession(List.of(new Student("emma", "Emma", "AA:BB:CC:01")), ZoneConfig.DEFAULT);

    session.recordReading("emma", java.util.OptionalInt.empty());

    assertEquals(1, session.historyFor("emma").size());
    assertTrue(session.historyFor("emma").get(0).bpm().isEmpty());
  }
}
