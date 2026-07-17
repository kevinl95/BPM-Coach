package com.loeffler.bpmcoach.desktop.session;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loeffler.bpmcoach.desktop.transport.MockBleTransport;
import com.loeffler.bpmcoach.domain.Student;
import com.loeffler.bpmcoach.domain.ZoneConfig;
import com.loeffler.bpmcoach.session.BandAssignment;
import com.loeffler.bpmcoach.session.BandPoller;
import com.loeffler.bpmcoach.session.ClassSession;
import com.loeffler.bpmcoach.session.StudentStatus;
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

    StudentStatus status = session.currentSnapshot().statuses().get(0);
    assertTrue(
        status.lastUpdate() != null,
        "expected a reading (value or explicit no-reading) to have been recorded");
    session.close();
  }
}
