package com.loeffler.bpmcoach.desktop.session;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loeffler.bpmcoach.desktop.transport.MockBleTransport;
import com.loeffler.bpmcoach.domain.Student;
import com.loeffler.bpmcoach.domain.ZoneConfig;
import com.loeffler.bpmcoach.session.BandAssignment;
import com.loeffler.bpmcoach.session.BandPoller;
import com.loeffler.bpmcoach.session.ClassSession;
import com.loeffler.bpmcoach.transport.Sightings;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Drives {@link BandPoller#pollRound} against {@link MockBleTransport} end to end, the same way
 * {@code MainApp}'s poll loop does. Exists to catch exactly the classes of bug found on real
 * hardware: a round-level timeout too tight for connect+write+measure to complete, a second round
 * going silent over a persistent link, or rotation starving over-cap bands.
 */
class BandPollerMockIntegrationTest {

  // Mock bands "advertise" continuously (every scan sees them), so the gate is always open -
  // matching what BandDiscovery reports when it scans a MockBleTransport.
  private static final Sightings ALWAYS_SIGHTED = (address, window) -> true;

  @Test
  void pollRoundRecordsAReadingForAPairedMockBand() throws InterruptedException {
    MockBleTransport transport = new MockBleTransport(1);
    ClassSession session =
        new ClassSession(
            List.of(new Student("emma", "Emma", transport.knownDevices().get(0).address())),
            ZoneConfig.DEFAULT);
    BandPoller poller = new BandPoller(transport, session, ALWAYS_SIGHTED);

    List<BandAssignment> assignments = session.assignments();
    poller.pollRound(assignments);

    // Recorded to history regardless of whether the reading came back present or empty
    // (MockBleTransport has a small chance of simulating "no reading this cycle" too) - a
    // present-vs-empty reading no longer implies anything about session.currentSnapshot()'s
    // `latest`, since an empty reading deliberately leaves that untouched.
    assertFalse(
        session.historyFor(assignments.get(0).studentId()).isEmpty(),
        "expected at least one reading (value or explicit no-reading) to have been recorded");
    poller.closeAll();
    session.close();
  }

  @Test
  void asecondRoundKeepsRecordingOverThePersistentLink() throws InterruptedException {
    // The whole point of persistent connections: a second round must keep producing readings on
    // the same held-open link, not just the first. This is the mock analogue of the real-hardware
    // failure where round one worked and every round after went silent.
    MockBleTransport transport = new MockBleTransport(1);
    ClassSession session =
        new ClassSession(
            List.of(new Student("emma", "Emma", transport.knownDevices().get(0).address())),
            ZoneConfig.DEFAULT);
    BandPoller poller = new BandPoller(transport, session, ALWAYS_SIGHTED);
    List<BandAssignment> assignments = session.assignments();

    poller.pollRound(assignments);
    int afterFirst = session.historyFor(assignments.get(0).studentId()).size();
    poller.pollRound(assignments);
    int afterSecond = session.historyFor(assignments.get(0).studentId()).size();

    assertTrue(
        afterSecond > afterFirst,
        "second round should have added at least one more reading over the persistent link");
    poller.closeAll();
    session.close();
  }

  @Test
  void rotationServicesEveryBandBeyondTheConnectionCapWithinTwoRounds()
      throws InterruptedException {
    // Roster of 9 against a pool of MAX_LIVE_CONNECTIONS (7): round one fills the pool; round two
    // must evict two long-resident links and admit the two bands still waiting. Every student
    // ends up with data, and the pool never exceeds the cap - the mock analogue of a classroom
    // bigger than the adapter's concurrent-connection limit.
    int bandCount = BandPoller.MAX_LIVE_CONNECTIONS + 2;
    MockBleTransport transport = new MockBleTransport(bandCount);
    List<Student> roster =
        IntStream.range(0, bandCount)
            .mapToObj(
                i ->
                    new Student("s" + i, "Student " + i, transport.knownDevices().get(i).address()))
            .toList();
    ClassSession session = new ClassSession(roster, ZoneConfig.DEFAULT);
    BandPoller poller = new BandPoller(transport, session, ALWAYS_SIGHTED);
    List<BandAssignment> assignments = session.assignments();

    poller.pollRound(assignments);
    assertTrue(
        poller.openLinkCount() <= BandPoller.MAX_LIVE_CONNECTIONS,
        "pool must never exceed the adapter's concurrent-connection cap");
    poller.pollRound(assignments);
    assertTrue(poller.openLinkCount() <= BandPoller.MAX_LIVE_CONNECTIONS);

    for (Student student : roster) {
      assertFalse(
          session.historyFor(student.id()).isEmpty(),
          "student %s never got a reading - rotation starved their band".formatted(student.id()));
    }
    poller.closeAll();
    session.close();
  }
}
