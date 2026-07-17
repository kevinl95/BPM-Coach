package com.loeffler.bpmcoach.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loeffler.bpmcoach.desktop.transport.BandDiscovery;
import com.loeffler.bpmcoach.desktop.transport.MockBleTransport;
import com.loeffler.bpmcoach.domain.Student;
import com.loeffler.bpmcoach.domain.ZoneConfig;
import com.loeffler.bpmcoach.persistence.RosterStore;
import com.loeffler.bpmcoach.session.BandAssignment;
import com.loeffler.bpmcoach.session.ClassSession;
import com.loeffler.bpmcoach.transport.DiscoveredDevice;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * End-to-end proof of the pairing flow requested by the user: scan, pick a band, name it, and have
 * a *separate* app launch recognize it. Drives the exact sequence {@code PairingView} performs
 * (unassignBand -> upsertStudent -> RosterStore.save), then constructs a brand new {@link
 * ClassSession} + {@link RosterStore} pointed at the same file to stand in for "the next launch",
 * and confirms the pairing carried over and the band is discoverable again.
 */
class PairingFlowIntegrationTest {

  @Test
  void pairingSurvivesACleanRestart() throws IOException, InterruptedException {
    Path rosterFile = Files.createTempFile("roster", ".tsv");
    Files.delete(rosterFile);

    // --- "First launch": scan, discover a band, pair it, persist ---
    MockBleTransport transportRunOne = new MockBleTransport(3);
    BandDiscovery discoveryRunOne = new BandDiscovery(transportRunOne);
    discoveryRunOne.start();
    DiscoveredDevice discovered = awaitFirstDiscovery(discoveryRunOne);
    discoveryRunOne.stop();

    RosterStore rosterStoreRunOne = new RosterStore(rosterFile);
    ClassSession sessionRunOne = new ClassSession(rosterStoreRunOne.load(), ZoneConfig.DEFAULT);
    assertTrue(sessionRunOne.roster().isEmpty(), "fresh install starts with no roster file");

    // Exactly PairingView.pairSelected()'s sequence for a brand-new name.
    sessionRunOne.unassignBand(discovered.address());
    sessionRunOne.upsertStudent(new Student("emma", "Emma", discovered.address()));
    rosterStoreRunOne.save(sessionRunOne.roster());
    sessionRunOne.close();

    // --- "Next launch": nothing in memory carries over except the file on disk ---
    RosterStore rosterStoreRunTwo = new RosterStore(rosterFile);
    List<Student> reloadedRoster = rosterStoreRunTwo.load();
    assertEquals(1, reloadedRoster.size());
    assertEquals("Emma", reloadedRoster.get(0).name());
    assertEquals(discovered.address(), reloadedRoster.get(0).assignedBandAddress());

    ClassSession sessionRunTwo = new ClassSession(reloadedRoster, ZoneConfig.DEFAULT);
    List<BandAssignment> assignments = sessionRunTwo.assignments();
    assertEquals(1, assignments.size());
    assertEquals(discovered.address(), assignments.get(0).device().address());

    // The same physical band (same simulated address) really is "around" again this launch.
    MockBleTransport transportRunTwo = new MockBleTransport(3);
    BandDiscovery discoveryRunTwo = new BandDiscovery(transportRunTwo);
    discoveryRunTwo.start();
    awaitFirstDiscovery(discoveryRunTwo);
    assertTrue(
        discoveryRunTwo.knownDevices().containsKey(discovered.address()),
        "the previously paired band should be recognized as in range on the next launch");
    discoveryRunTwo.stop();
    sessionRunTwo.close();
  }

  @Test
  void reassigningAnAlreadyPairedBandMovesItInsteadOfDuplicating() throws IOException {
    Path rosterFile = Files.createTempFile("roster", ".tsv");
    RosterStore rosterStore = new RosterStore(rosterFile);
    ClassSession session =
        new ClassSession(
            List.of(new Student("emma", "Emma", "ADDR-1"), new Student("liam", "Liam", null)),
            ZoneConfig.DEFAULT);

    // Re-pair the same band ("ADDR-1") to Liam, mirroring PairingView.pairSelected().
    session.unassignBand("ADDR-1");
    session.upsertStudent(new Student("liam", "Liam", "ADDR-1"));
    rosterStore.save(session.roster());

    List<Student> saved = rosterStore.load();
    long holdersOfAddr1 =
        saved.stream().filter(s -> "ADDR-1".equals(s.assignedBandAddress())).count();
    assertEquals(1, holdersOfAddr1, "a band address must never be held by two students at once");
    assertEquals(1, session.assignments().size());
    session.close();
  }

  private static DiscoveredDevice awaitFirstDiscovery(BandDiscovery discovery)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      var known = discovery.knownDevices();
      if (!known.isEmpty()) {
        return known.values().iterator().next();
      }
      Thread.sleep(50);
    }
    throw new AssertionError("no device discovered within 10s");
  }
}
