package com.loeffler.bpmcoach.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loeffler.bpmcoach.domain.Student;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class RosterStoreTest {

  @Test
  void loadOnMissingFileReturnsEmptyList() {
    RosterStore store = new RosterStore(Path.of("/nonexistent/path/roster.tsv"));
    assertEquals(List.of(), store.load());
  }

  @Test
  void savedRosterRoundTripsIncludingUnpairedStudents() throws IOException {
    Path file = Files.createTempFile("roster", ".tsv");
    Files.delete(file); // exercise save() creating the file (and parent dirs) from scratch
    RosterStore store = new RosterStore(file);

    List<Student> original =
        List.of(
            new Student("emma", "Emma", "AA:BB:CC:DD:EE:01"), new Student("liam", "Liam", null));
    store.save(original);

    List<Student> loaded = store.load();
    assertEquals(2, loaded.size());
    assertTrue(loaded.contains(new Student("emma", "Emma", "AA:BB:CC:DD:EE:01")));
    assertTrue(loaded.contains(new Student("liam", "Liam", null)));
  }

  @Test
  void savingOverwritesPreviousContent() throws IOException {
    Path file = Files.createTempFile("roster", ".tsv");
    RosterStore store = new RosterStore(file);

    store.save(List.of(new Student("a", "Ava", "ADDR-1")));
    store.save(List.of(new Student("b", "Ben", null)));

    List<Student> loaded = store.load();
    assertEquals(1, loaded.size());
    assertEquals("Ben", loaded.get(0).name());
    assertNull(loaded.get(0).assignedBandAddress());
  }
}
