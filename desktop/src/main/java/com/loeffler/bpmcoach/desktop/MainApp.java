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
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.System.Logger.Level;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
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

  private static final System.Logger LOG = System.getLogger(MainApp.class.getName());

  private static final int DEMO_BAND_COUNT = 8;
  private static final long EMPTY_ROSTER_POLL_DELAY_SECONDS = 2;
  // Slept after every round, unconditionally. Two jobs: it prevents a tight retry spin when a
  // round returns instantly (every band failing fast), and - more importantly - it guarantees
  // BandDiscovery at least one full resumed scan cycle (~6s) between rounds, which is what
  // produces the fresh advertisement sightings BandPoller's rotation admissions are gated on.
  private static final long INTER_ROUND_DISCOVERY_SECONDS = 8;
  // cleanup() makes native BLE teardown calls (disconnect/unsubscribe) that this binding gives
  // no timeout for and that have been observed to block indefinitely - a real process hung past
  // 12s on SIGTERM and only died to SIGKILL. Since cleanup() also runs from the JVM shutdown
  // hook, a hang there is precisely what stops SIGTERM from ever taking effect (and, because the
  // hung process keeps holding the single-instance lock, blocks the next launch too). The clean
  // disconnect is a courtesy that leaves the band advertising for the next launch, never a
  // correctness requirement - the OS reclaims every native handle on exit - so bounding the wait
  // and exiting anyway is always safe.
  private static final Duration CLEANUP_TIMEOUT = Duration.ofSeconds(4);
  // Backstop deadline for the whole shutdown. Must exceed CLEANUP_TIMEOUT so the graceful teardown
  // gets its full window first; past this, a detached OS process SIGKILLs us. See
  // scheduleForceKill.
  private static final Duration FORCE_KILL_DELAY = Duration.ofSeconds(7);

  private ClassSession session;
  private BleTransport transport;
  private BandDiscovery discovery;
  private RosterStore rosterStore;
  private ExecutorService pollingExecutor;
  private volatile BandPoller poller;
  private final AtomicBoolean cleanedUp = new AtomicBoolean();

  // Held for the whole process lifetime; the OS releases it on exit, however abrupt. Never
  // read after acquisition - existence of the lock IS the point.
  @SuppressWarnings("unused")
  private FileLock instanceLock;

  public static void main(String[] args) {
    launch(args);
  }

  @Override
  public void init() {
    // Before anything else - especially before touching the Bluetooth adapter. A second live
    // instance doesn't fail loudly: both instances cycle discovery on the same adapter, BlueZ's
    // per-session duplicate filtering then suppresses re-reports of already-seen devices, and
    // each instance just logs "0 devices" forever. Confirmed against real hardware, twice: an
    // orphaned instance (a killed Gradle client leaving its forked app JVM running) silently
    // broke every scan of the visible instance. Exiting here is deliberately before the
    // shutdown hook is registered, so the refused launch can't clean up the winner's state.
    acquireSingleInstanceLockOrExit();

    // NOT getUnnamed(): confirmed by decompiling JavaFX's own ParametersImpl
    // (isNamedParam/computeNamedParams) that any "--key=value" argument - "--mode=demo" included -
    // is classified as a NAMED parameter and stripped out of getUnnamed() entirely. Checking
    // getUnnamed() here meant this flag has done nothing since the very first commit: the app
    // always launched with SimpleBleTransport regardless of --mode, which is why "demo mode"
    // behaved differently depending on whether real hardware happened to be nearby.
    boolean demo = "demo".equals(getParameters().getNamed().get("mode"));

    rosterStore = new RosterStore(RosterStore.defaultLocation());
    List<Student> savedRoster = rosterStore.load();
    session = new ClassSession(savedRoster, ZoneConfig.DEFAULT);

    transport = demo ? new MockBleTransport(DEMO_BAND_COUNT) : new SimpleBleTransport();
    discovery = new BandDiscovery(transport);

    // Ctrl+C/SIGINT kills the JVM without going through JavaFX's normal stop() lifecycle, which
    // otherwise skips transport.shutdown() entirely - confirmed against real hardware, that
    // leaves a band connected at the OS/BlueZ level with no process left to disconnect it, so the
    // *next* launch's scan finds nothing at all. A shutdown hook runs on both paths.
    //
    // Same shutDown() as the window-close and stop() paths - see its javadoc for why the halt()
    // inside it is required even here (the JVM otherwise waits forever on the non-daemon native
    // BLE thread).
    Runtime.getRuntime().addShutdownHook(new Thread(this::shutDown, "bpm-coach-cleanup"));
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
    // Handle the window's close button explicitly instead of trusting the Application.stop()
    // lifecycle to fire. Confirmed against a real packaged build: closing the window left the
    // process running forever, still logging scan cycles - i.e. stop() was never called at all, so
    // neither the cleanup nor the halt() in it ran. A CLOSE_REQUEST handler is the direct,
    // documented signal that the user clicked X, and doesn't depend on implicit-exit semantics or
    // on how many native top-level windows the platform actually created. shutDown() is idempotent,
    // so the stop()/shutdown-hook paths reaching it too (Platform.exit, SIGTERM) is harmless.
    stage.setOnCloseRequest(event -> shutDown());
    stage.show();

    startPolling();
  }

  private void startPolling() {
    pollingExecutor = Executors.newVirtualThreadPerTaskExecutor();
    // discovery::seenWithin is the connect gate: BandPoller only attempts a connect (initial,
    // reconnect, or rotation admission) for a band sighted advertising recently - connecting to
    // a non-advertising cached address yields a dead link, per the reconnect experiment.
    poller = new BandPoller(transport, session, discovery::seenWithin);
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
            // Scanning and connecting/measuring must not overlap - see BandDiscovery.pause() for
            // the JVM-crash and starved-connection evidence. Discovery resumes between rounds (the
            // persistent band links stay open across the pause), so the pairing view still sees
            // fresh scan results between measurement windows - and rotation admissions get the
            // sightings they're gated on.
            discovery.pause();
            try {
              poller.pollRound(assignments);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              return;
            } finally {
              discovery.resume();
            }
            if (!sleepQuietly(INTER_ROUND_DISCOVERY_SECONDS)) {
              return;
            }
          }
        });
  }

  private void acquireSingleInstanceLockOrExit() {
    Path lockPath = RosterStore.defaultLocation().resolveSibling("app.lock");
    try {
      Files.createDirectories(lockPath.getParent());
      FileChannel channel =
          FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
      instanceLock = channel.tryLock();
    } catch (IOException e) {
      throw new UncheckedIOException("Could not open instance lock " + lockPath, e);
    }
    if (instanceLock == null) {
      LOG.log(
          Level.ERROR,
          "Another BPM Coach instance is already running (instance lock {0} is held). Two"
              + " instances silently break each other's Bluetooth scanning - close the other one"
              + " (check for an orphaned process: ps aux | grep MainApp) and relaunch.",
          lockPath);
      System.exit(1);
    }
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
    // Reached on the Platform.exit() path (not the window-close button - that's handled by the
    // stage's CLOSE_REQUEST handler, see start()). Same idempotent teardown either way.
    shutDown();
  }

  /**
   * The single teardown path, reached from all three ways the app can end: the window's close
   * button ({@code setOnCloseRequest}), the {@link #stop()} lifecycle ({@code Platform.exit()}),
   * and the JVM shutdown hook (Ctrl+C/SIGTERM).
   *
   * <p>Getting the process to actually die turned out to need two layers, both established
   * empirically against a real build:
   *
   * <ul>
   *   <li>{@code halt()} rather than a normal return: SimpleBLE registers a persistent native BLE
   *       event-dispatch thread ("Thread-2") for the app's lifetime, attached via JNI as non-daemon
   *       and never exiting on its own, so the JVM's normal "wait for every non-daemon thread"
   *       shutdown never completes. {@code halt()} skips that wait.
   *   <li>An out-of-process SIGKILL backstop, because {@code halt()} ITSELF intermittently hangs
   *       (~1 in 5 runs, measured): it requests a VM-exit safepoint, and that same native thread
   *       can be in a state the VM can't safepoint over, wedging the process so hard that even
   *       SIGQUIT gets no response. Nothing running inside the JVM can escape that - a watchdog
   *       thread would be frozen at the same safepoint - so {@link #scheduleForceKill} spawns a
   *       separate OS process, before halting, that SIGKILLs us if we're still alive past {@link
   *       #FORCE_KILL_DELAY}. The kernel honours SIGKILL with zero process cooperation.
   * </ul>
   *
   * <p>The bounded {@link #cleanupWithinDeadline} in between is best-effort courtesy (a clean BLE
   * disconnect leaves the band advertising for the next launch); it's allowed to fail or hang
   * because both layers above guarantee the process dies regardless.
   */
  private void shutDown() {
    scheduleForceKill();
    cleanupWithinDeadline();
    Runtime.getRuntime().halt(0);
  }

  /**
   * Spawns a detached OS process that SIGKILLs this JVM after {@link #FORCE_KILL_DELAY} if it's
   * still alive - the only reliable escape from a wedged {@code halt()} (see {@link #shutDown}).
   * Started before the halt, since once the VM wedges no in-JVM code can run. If the halt succeeds
   * normally (the common case), we're long gone before the timer fires and the kill is a no-op.
   */
  private void scheduleForceKill() {
    long pid = ProcessHandle.current().pid();
    long delay = FORCE_KILL_DELAY.toSeconds();
    boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    ProcessBuilder pb =
        windows
            ? new ProcessBuilder(
                "cmd", "/c", "timeout /t " + delay + " >nul & taskkill /F /PID " + pid)
            : new ProcessBuilder("sh", "-c", "sleep " + delay + "; kill -9 " + pid);
    try {
      pb.start();
    } catch (IOException e) {
      // Best-effort: if we can't even spawn the watchdog, halt() is still our first line of
      // defense and works most of the time. Nothing better to do here.
      LOG.log(Level.WARNING, "Could not start exit watchdog process", e);
    }
  }

  /**
   * Runs {@link #cleanup()} but never blocks the caller past {@link #CLEANUP_TIMEOUT}. See that
   * constant for why an unbounded cleanup is actively dangerous here (hung SIGTERM, held lock). The
   * worker is a daemon so that, if a native call really is wedged, its lingering thread can't
   * itself keep the JVM alive.
   */
  private void cleanupWithinDeadline() {
    Thread worker = new Thread(this::cleanup, "bpm-coach-cleanup-worker");
    worker.setDaemon(true);
    worker.start();
    try {
      worker.join(CLEANUP_TIMEOUT.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return;
    }
    if (worker.isAlive()) {
      LOG.log(
          Level.WARNING,
          "BLE cleanup didn''t finish within {0}s (a native disconnect is blocking); exiting"
              + " anyway - the OS reclaims the connection.",
          CLEANUP_TIMEOUT.toSeconds());
    }
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
    // Close the persistent band links explicitly before the transport's belt-and-suspenders
    // sweep, so each open connection gets its clean per-link teardown (disconnect leaves the
    // band advertising for the next launch).
    if (poller != null) {
      poller.closeAll();
    }
    transport.shutdown();
    session.close();
  }
}
