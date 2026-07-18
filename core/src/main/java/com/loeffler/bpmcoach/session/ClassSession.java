package com.loeffler.bpmcoach.session;

import com.loeffler.bpmcoach.domain.HeartRateReading;
import com.loeffler.bpmcoach.domain.Student;
import com.loeffler.bpmcoach.domain.Zone;
import com.loeffler.bpmcoach.domain.ZoneConfig;
import com.loeffler.bpmcoach.transport.DiscoveredDevice;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Whole-class state, updated concurrently by {@link BandPoller} (one virtual thread per in-flight
 * band) and observed reactively via {@link #updates()}. No GUI or transport dependency here: the
 * desktop module subscribes and marshals updates onto the JavaFX Application Thread.
 *
 * <p>The roster isn't fixed at construction: {@link #upsertStudent} lets the pairing UI add a
 * student or (re)assign their band live, and {@link #assignments()} always reflects the roster's
 * current state, so a pairing made while the app is running takes effect on the next poll round
 * with no restart needed.
 */
public final class ClassSession implements AutoCloseable {

  private static final int HISTORY_LIMIT_PER_STUDENT = 500;

  private final Map<String, Student> roster = new ConcurrentHashMap<>();
  private final Map<String, StudentStatus> latest = new ConcurrentHashMap<>();
  private final Map<String, ConcurrentLinkedDeque<HeartRateReading>> history =
      new ConcurrentHashMap<>();
  private final AtomicReference<ZoneConfig> zoneConfig;
  private final SubmissionPublisher<ClassSnapshot> publisher = new SubmissionPublisher<>();

  public ClassSession(List<Student> initialRoster, ZoneConfig zoneConfig) {
    for (Student student : initialRoster) {
      this.roster.put(student.id(), student);
      this.latest.put(student.id(), StudentStatus.unknown(student));
    }
    this.zoneConfig = new AtomicReference<>(zoneConfig);
  }

  /**
   * Called by {@link BandPoller} on every parsed heart-rate reading, on whichever virtual thread
   * polled it.
   */
  public void recordReading(String studentId, OptionalInt bpm) {
    Student student = roster.get(studentId);
    if (student == null) {
      return; // unassigned/unknown band; ignore
    }
    Instant now = Instant.now();

    var deque = history.computeIfAbsent(studentId, id -> new ConcurrentLinkedDeque<>());
    deque.addLast(new HeartRateReading(studentId, bpm, now));
    while (deque.size() > HISTORY_LIMIT_PER_STUDENT) {
      deque.pollFirst();
    }

    // A "no reading this cycle" shouldn't blank a tile back to unknown: leave the last known
    // good status on screen rather than flickering it every time one poll comes up empty (a
    // transient miss - dropped notification, band momentarily off-wrist - is common and doesn't
    // mean the previous reading is stale).
    if (bpm.isPresent()) {
      Zone zone = zoneConfig.get().classify(bpm);
      latest.put(studentId, new StudentStatus(student, bpm, zone, now));
    }

    publisher.submit(currentSnapshot());
  }

  /** Adds a new student, or updates an existing one (matched by id) - e.g. a new pairing. */
  public void upsertStudent(Student student) {
    roster.put(student.id(), student);
    latest.compute(
        student.id(),
        (id, existing) ->
            existing == null
                ? StudentStatus.unknown(student)
                : new StudentStatus(
                    student, existing.bpm(), existing.zone(), existing.lastUpdate()));
    publisher.submit(currentSnapshot());
  }

  /**
   * Clears {@code bandAddress} from whichever student currently holds it, if any. Addresses are
   * 1:1.
   */
  public void unassignBand(String bandAddress) {
    if (bandAddress == null) {
      return;
    }
    roster.values().stream()
        .filter(student -> bandAddress.equals(student.assignedBandAddress()))
        .findFirst()
        .ifPresent(student -> upsertStudent(new Student(student.id(), student.name(), null)));
  }

  /** Removes a student from the roster entirely (not just their band assignment). */
  public void removeStudent(String studentId) {
    roster.remove(studentId);
    latest.remove(studentId);
    history.remove(studentId);
    publisher.submit(currentSnapshot());
  }

  public void updateZoneConfig(ZoneConfig config) {
    zoneConfig.set(config);
    publisher.submit(currentSnapshot());
  }

  public ZoneConfig zoneConfig() {
    return zoneConfig.get();
  }

  public List<Student> roster() {
    return roster.values().stream().sorted(Comparator.comparing(Student::name)).toList();
  }

  /**
   * Every currently paired student, resolved to a pollable target. Reflects live pairing changes.
   */
  public List<BandAssignment> assignments() {
    return roster.values().stream()
        .filter(Student::hasBand)
        .map(
            student ->
                new BandAssignment(
                    student.id(),
                    new DiscoveredDevice(student.assignedBandAddress(), student.name(), 0)))
        .toList();
  }

  public ClassSnapshot currentSnapshot() {
    List<StudentStatus> statuses =
        latest.values().stream().sorted(Comparator.comparing(s -> s.student().name())).toList();
    return new ClassSnapshot(Instant.now(), statuses);
  }

  /** Most recent readings first. */
  public List<HeartRateReading> historyFor(String studentId) {
    var deque = history.get(studentId);
    if (deque == null) {
      return List.of();
    }
    return List.copyOf(new ArrayList<>(deque)).reversed();
  }

  public Flow.Publisher<ClassSnapshot> updates() {
    return publisher;
  }

  @Override
  public void close() {
    publisher.close();
  }
}
