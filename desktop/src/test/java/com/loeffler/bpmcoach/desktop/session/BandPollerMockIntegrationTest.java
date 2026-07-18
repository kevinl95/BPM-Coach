package com.loeffler.bpmcoach.desktop.session;

import static org.junit.jupiter.api.Assertions.assertFalse;

import com.loeffler.bpmcoach.desktop.transport.MockBleTransport;
import com.loeffler.bpmcoach.domain.Student;
import com.loeffler.bpmcoach.domain.ZoneConfig;
import com.loeffler.bpmcoach.session.BandAssignment;
import com.loeffler.bpmcoach.session.BandPoller;
import com.loeffler.bpmcoach.session.ClassSession;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Drives {@link BandPoller#pollBatch} against {@link MockBleTransport} end to end, the same way
 * {@code MainApp}'s poll loop does. Exists to catch exactly the class of bug found on real
 * hardware: a batch-level timeout too tight for connect+write+measure to complete serially, which
 * would show up here as no reading ever reaching {@link ClassSession}.
 */
class BandPollerMockIntegrationTest {

  @Test
  void pollBatchRecordsAReadingForAPairedMockBand() throws InterruptedException {
    MockBleTransport transport = new MockBleTransport(1);
    ClassSession session =
        new ClassSession(
            List.of(new Student("emma", "Emma", transport.knownDevices().get(0).address())),
            ZoneConfig.DEFAULT);
    BandPoller poller = new BandPoller(transport, session);

    List<BandAssignment> assignments = session.assignments();
    poller.pollBatch(assignments);

    // Recorded to history regardless of whether the reading came back present or empty
    // (MockBleTransport has a small chance of simulating "no reading this cycle" too) - a
    // present-vs-empty reading no longer implies anything about session.currentSnapshot()'s
    // `latest`, since an empty reading deliberately leaves that untouched.
    assertFalse(
        session.historyFor(assignments.get(0).studentId()).isEmpty(),
        "expected at least one reading (value or explicit no-reading) to have been recorded");
    session.close();
  }
}
