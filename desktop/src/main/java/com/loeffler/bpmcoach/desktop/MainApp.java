package com.loeffler.bpmcoach.desktop;

import com.loeffler.bpmcoach.desktop.transport.BandDiscovery;
import com.loeffler.bpmcoach.desktop.transport.MockBleTransport;
import com.loeffler.bpmcoach.desktop.transport.SimpleBleTransport;
import com.loeffler.bpmcoach.desktop.ui.HistoryView;
import com.loeffler.bpmcoach.desktop.ui.PairingView;
import com.loeffler.bpmcoach.desktop.ui.ProjectorView;
import com.loeffler.bpmcoach.desktop.ui.ZoneConfigView;
import com.loeffler.bpmcoach.domain.Student;
import com.loeffler.bpmcoach.domain.ZoneConfig;
import com.loeffler.bpmcoach.persistence.RosterStore;
import com.loeffler.bpmcoach.session.BandAssignment;
import com.loeffler.bpmcoach.session.BandPoller;
import com.loeffler.bpmcoach.session.ClassSession;
import com.loeffler.bpmcoach.transport.BleTransport;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.stage.Stage;

/**
 * Entry point. {@code --mode=live} uses {@link SimpleBleTransport} (real bands); anything else (the
 * default) uses {@link MockBleTransport}, so the app - including pairing - is demoable with only 2
 * physical bands on hand. Both transports drive the identical {@link ClassSession}/{@link
 * BandPoller} pipeline.
 *
 * <p>The roster (student names and their paired band addresses) is loaded from {@link RosterStore}
 * at startup and written back on every pairing change, so a band paired in a previous session is
 * recognized again once {@link BandDiscovery} sees it in range - no hardcoded demo roster, no
 * re-pairing required.
 */
public final class MainApp extends Application {

  private static final int DEMO_BAND_COUNT = 8;
  private static final long EMPTY_ROSTER_POLL_DELAY_SECONDS = 2;

  private ClassSession session;
  private BleTransport transport;
  private BandDiscovery discovery;
  private RosterStore rosterStore;
  private ExecutorService pollingExecutor;

  public static void main(String[] args) {
    launch(args);
  }

  @Override
  public void init() {
    boolean live = getParameters().getUnnamed().contains("--mode=live");

    rosterStore = new RosterStore(RosterStore.defaultLocation());
    List<Student> savedRoster = rosterStore.load();
    session = new ClassSession(savedRoster, ZoneConfig.DEFAULT);

    transport = live ? new SimpleBleTransport() : new MockBleTransport(DEMO_BAND_COUNT);
    discovery = new BandDiscovery(transport);
  }

  @Override
  public void start(Stage stage) {
    discovery.start();

    TabPane tabs =
        new TabPane(
            new Tab("Projector", new ProjectorView(session)),
            new Tab("History", new HistoryView(session)),
            new Tab("Pairing", new PairingView(session, discovery, rosterStore)),
            new Tab("Zone Config", new ZoneConfigView(session)));
    tabs.getTabs().forEach(tab -> tab.setClosable(false));

    Scene scene = new Scene(tabs, 1280, 800);
    scene.getStylesheets().add(getClass().getResource("/css/bpmcoach.css").toExternalForm());

    stage.setTitle("BPM Coach");
    stage.setScene(scene);
    stage.show();

    startPolling();
  }

  private void startPolling() {
    pollingExecutor = Executors.newVirtualThreadPerTaskExecutor();
    BandPoller poller = new BandPoller(transport, session);
    pollingExecutor.submit(
        () -> {
          while (!Thread.currentThread().isInterrupted()) {
            // Re-read on every round, not just at startup, so a pairing made while the app
            // is running (or a previously-paired band that just came into range) is picked
            // up on the very next round - no restart needed.
            List<BandAssignment> assignments = session.assignments();
            if (assignments.isEmpty()) {
              if (!sleepQuietly(EMPTY_ROSTER_POLL_DELAY_SECONDS)) {
                return;
              }
              continue;
            }
            for (List<BandAssignment> batch : BandPoller.batch(assignments)) {
              if (Thread.currentThread().isInterrupted()) {
                return;
              }
              try {
                poller.pollBatch(batch);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
              }
            }
          }
        });
  }

  /** Returns false if interrupted (caller should stop), true if the sleep completed normally. */
  private static boolean sleepQuietly(long seconds) {
    try {
      TimeUnit.SECONDS.sleep(seconds);
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  @Override
  public void stop() {
    discovery.stop();
    if (pollingExecutor != null) {
      pollingExecutor.shutdownNow();
    }
    session.close();
  }
}
