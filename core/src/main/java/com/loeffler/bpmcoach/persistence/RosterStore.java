package com.loeffler.bpmcoach.persistence;

import com.loeffler.bpmcoach.domain.Student;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists the roster (student names and their paired band addresses) as a small tab-separated
 * file, so a band paired via the pairing UI is recognized again on the next launch without needing
 * a database or JSON dependency for something this simple.
 */
public final class RosterStore {

  private final Path file;

  public RosterStore(Path file) {
    this.file = file;
  }

  public static Path defaultLocation() {
    return Path.of(System.getProperty("user.home"), ".bpmcoach", "roster.tsv");
  }

  public List<Student> load() {
    if (!Files.exists(file)) {
      return List.of();
    }
    try {
      List<Student> students = new ArrayList<>();
      for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
        if (line.isBlank()) {
          continue;
        }
        String[] parts = line.split("\t", -1);
        if (parts.length < 2) {
          continue;
        }
        String bandAddress = parts.length > 2 && !parts[2].isEmpty() ? parts[2] : null;
        students.add(new Student(parts[0], parts[1], bandAddress));
      }
      return List.copyOf(students);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load roster from " + file, e);
    }
  }

  public void save(List<Student> students) {
    try {
      if (file.getParent() != null) {
        Files.createDirectories(file.getParent());
      }
      List<String> lines =
          students.stream()
              .map(
                  s ->
                      String.join(
                          "\t",
                          sanitize(s.id()),
                          sanitize(s.name()),
                          s.assignedBandAddress() == null ? "" : sanitize(s.assignedBandAddress())))
              .toList();
      Files.write(file, lines, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to save roster to " + file, e);
    }
  }

  private static String sanitize(String value) {
    return value.replace("\t", " ").replace("\n", " ");
  }
}
