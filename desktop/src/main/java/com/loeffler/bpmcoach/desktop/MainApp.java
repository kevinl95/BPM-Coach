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
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.stage.Stage;

/**
 * Entry point. Defaults to {@link SimpleBleTransport} (real bands) - that's how the app is actually
 * used. Pass {@code --mode=demo} to use {@link MockBleTransport} instead, so the app - including
 * pairing - is demoable with only 2 physical bands on hand, or none at all. Both transports drive
 * the identical {@link ClassSession}/{@link BandPoller} pipeline.
 *
 * <p>The roster (student names and their paired band addresses) is loaded from {@link RosterStore}
 * at startup and written back on every pairing change, so a band paired in a previous session is
 * recognized again once {@link BandDiscovery} sees it in range - no hardcoded demo roster, no
 * re-pairing required.
 */
public final class MainApp extends Application {

  private static final int DEMO_BAND_COUNT = 8;
  private static final long EMPTY_ROSTER_POLL_DELAY_SECONDS = 2;
  // A round can return almost instantly if every band fails fast (e.g. not yet seen by
  // BandDiscovery's scan this session: SimpleBleTransport.connect() rejects an undiscovered
  // address immediately, it doesn't wait out the connect timeout). Without a floor, that turns
  // into a tight CPU-spinning retry loop instead of just waiting for the next scan cycle to
  // possibly find the band.
  private static final long MIN_ROUND_INTERVAL_SECONDS = 5;

  private ClassSession session;
  private BleTransport transport;
  private BandDiscovery discovery;
  private RosterStore rosterStore;
  private ExecutorService pollingExecutor;
  private final AtomicBoolean cleanedUp = new AtomicBoolean();

  public static void main(String[] args) {
    launch(args);
  }

  @Override
  public void init() {
    boolean demo = getParameters().getUnnamed().contains("--mode=demo");

    rosterStore = new RosterStore(RosterStore.defaultLocation());
    List<Student> savedRoster = rosterStore.load();
    session = new ClassSession(savedRoster, ZoneConfig.DEFAULT);

    transport = demo ? new MockBleTransport(DEMO_BAND_COUNT) : new SimpleBleTransport();
    discovery = new BandDiscovery(transport);

    // Ctrl+C/SIGINT kills the JVM without going through JavaFX's normal stop() lifecycle, which
    // otherwise skips transport.shutdown() entirely - confirmed against real hardware, that
    // leaves a band connected at the OS/BlueZ level with no process left to disconnect it, so the
    // *next* launch's scan finds nothing at all. A shutdown hook runs on both paths.
    Runtime.getRuntime().addShutdownHook(new Thread(this::cleanup, "bpm-coach-cleanup"));
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
            long roundStart = System.nanoTime();
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
            long elapsedSeconds = (System.nanoTime() - roundStart) / 1_000_000_000L;
            long remaining = MIN_ROUND_INTERVAL_SECONDS - elapsedSeconds;
            if (remaining > 0 && !sleepQuietly(remaining)) {
              return;
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
    cleanup();
  }

  /** Idempotent: reachable both from the normal stop() lifecycle and the shutdown hook. */
  private void cleanup() {
    if (!cleanedUp.compareAndSet(false, true)) {
      return;
    }
    discovery.stop();
    if (pollingExecutor != null) {
      pollingExecutor.shutdownNow();
    }
    transport.shutdown();
    session.close();
  }
}
