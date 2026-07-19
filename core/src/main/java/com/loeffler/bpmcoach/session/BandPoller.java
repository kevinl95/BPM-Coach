package com.loeffler.bpmcoach.session;

import com.loeffler.bpmcoach.protocol.Frame;
import com.loeffler.bpmcoach.protocol.FrameParser;
import com.loeffler.bpmcoach.protocol.FrameReassembler;
import com.loeffler.bpmcoach.protocol.LaxasfitProtocol;
import com.loeffler.bpmcoach.transport.BandConnection;
import com.loeffler.bpmcoach.transport.BleTransport;
import com.loeffler.bpmcoach.transport.Sightings;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Keeps a pool of persistent BLE connections (one per band, capped by what the adapter can hold
 * concurrently) and re-triggers a measurement on each poll round over those same links. Confirmed
 * against real hardware: the earlier connect-per-round model got exactly one good reading and then
 * only dead reconnects, because it reconnected while the band wasn't advertising - a connect to a
 * non-advertising cached device "succeeds" at the API level but never carries a frame. Holding
 * links open avoids that entirely for the resident set.
 *
 * <p>A roster larger than {@link #MAX_LIVE_CONNECTIONS} rotates residency in the pool
 * opportunistically. The rules come straight from a controlled experiment on the real band
 * (2026-07-19): a reconnect works reliably if - and only if - the band was sighted advertising just
 * beforehand, and after a disconnect the band takes a minute or more to advertise again (sometimes
 * not at all until physically nudged). So an unlinked band is admitted only when {@link Sightings}
 * confirms a fresh advertisement, admissions are ordered least-recently-serviced first so nobody
 * starves, and each admission evicts the longest-resident link, which then re-advertises and cycles
 * back in on its own natural cadence. With a roster at or under the cap no eviction ever happens
 * and every band simply stays resident.
 *
 * <p>Bands are polled concurrently, one virtual thread each, fanned out with {@link
 * StructuredTaskScope} (JEP 525, {@code open(Joiner, config)}). A dropped or slow band is a soft
 * failure: the band's own display still shows the reading, so one bad link must never cancel or
 * fail the rest of the round - which is why the joiner runs every subtask to completion instead of
 * cancelling on first failure.
 *
 * <p>Liveness is judged by observed behaviour, never by {@code isConnected()} - that native call
 * lied during the stale-reconnect bug. A round in which a link produces no frames at all (not even
 * an ack) counts as a miss; a link that has NEVER produced a frame is dropped after one silent
 * round (a dead admission shouldn't hold a slot), an established link only after {@link
 * #MAX_MISSES_BEFORE_RECONNECT} consecutive silent rounds. Any frame - ack, empty reading, or a
 * real bpm - resets the counter, since it proves the link still carries traffic.
 */
public final class BandPoller {

  private static final System.Logger LOG = System.getLogger(BandPoller.class.getName());

  // BLE adapters cap concurrent links at roughly 7-10; the pool never grows beyond this.
  public static final int MAX_LIVE_CONNECTIONS = 7;

  // How fresh an advertisement sighting must be to justify a connect attempt. The experiment
  // connected within seconds of a sighting; this window spans the inter-round discovery gap plus
  // scan latency while staying well inside the band's observed advertise-then-doze rhythm.
  private static final Duration SIGHTING_FRESHNESS = Duration.ofSeconds(30);

  // SimpleBleTransport.connect() includes not just the GATT link but a wait for BlueZ to
  // resolve the service table (up to ~6s on its own - see SimpleBleTransport), so this needs
  // margin beyond just the physical connection time.
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
  private static final Duration WRITE_TIMEOUT = Duration.ofSeconds(5);
  // The real band's own measurement cycle is documented at ~8-10s; this leaves real margin
  // beyond that, since a slow/laggy BLE connection interval can delay notification delivery
  // even after the band has actually finished measuring.
  private static final Duration MEASUREMENT_WAIT = Duration.ofSeconds(15);
  // A HeartRate frame with a present bpm arriving faster than this is treated as suspicious
  // rather than final - the payload's own date/record-count/timestamp fields (see
  // LaxasfitProtocol's parseHeartRate javadoc) look like stored-record metadata, and a genuine
  // live measurement is documented to take ~8-10s, so anything much faster than that is more
  // likely an immediate history-sync push than the live result. Set safely below the documented
  // minimum so a real fast reading is never discarded.
  public static final Duration MIN_LIVE_READING_DELAY = Duration.ofSeconds(5);
  // On a still-open, known-good link, re-sending CMD_HR_START is cheap (no reconnect), so give a
  // band that dropped a single notification one more shot within the same round before counting
  // the round as silent.
  private static final int MAX_MEASUREMENT_ATTEMPTS = 2;
  // Consecutive silent rounds before an ESTABLISHED link (one that has produced frames before)
  // is torn down. A never-established link doesn't get this grace - see class javadoc.
  private static final int MAX_MISSES_BEFORE_RECONNECT = 2;
  // Worst case for one round is every serviced band reconnecting: CONNECT_TIMEOUT +
  // MAX_MEASUREMENT_ATTEMPTS * (WRITE_TIMEOUT + MEASUREMENT_WAIT), run concurrently across the
  // pool. This bounds the whole round with real margin above that serial-per-band figure.
  private static final Duration ROUND_TIMEOUT = Duration.ofSeconds(70);

  private final BleTransport transport;
  private final ClassSession session;
  private final Sightings sightings;
  private final Map<String, BandLink> links = new ConcurrentHashMap<>();
  // When each address last completed a live-link service round, surviving link closure - this is
  // what keeps rotation fair: an evicted band's place in the admission queue depends on how long
  // ago it last had a turn, not on whether it currently holds a link. Never-serviced sorts first.
  private final Map<String, Long> lastServicedNanos = new ConcurrentHashMap<>();

  public BandPoller(BleTransport transport, ClassSession session, Sightings sightings) {
    this.transport = transport;
    this.session = session;
    this.sightings = sightings;
  }

  /**
   * Services one poll round: admits unlinked bands (sighting-gated, pool capacity permitting,
   * evicting the longest-resident links when over-cap bands are waiting), re-triggers a measurement
   * on every link in the pool, and drops links for bands no longer assigned. Blocks until every
   * serviced band answers or times out. Links stay open for the next round.
   */
  public void pollRound(List<BandAssignment> assignments) throws InterruptedException {
    Set<String> wanted =
        assignments.stream().map(a -> a.device().address()).collect(Collectors.toUnmodifiableSet());
    retainOnly(wanted);

    List<BandAssignment> toService = planRound(assignments);

    try (var scope =
        StructuredTaskScope.open(
            StructuredTaskScope.Joiner.<Void>allUntil(_ -> false),
            cfg -> cfg.withTimeout(ROUND_TIMEOUT))) {
      for (BandAssignment assignment : toService) {
        scope.fork(
            () -> {
              serviceOne(assignment);
              return null;
            });
      }
      scope.join();
    }
  }

  /** Number of currently open persistent links; never exceeds {@link #MAX_LIVE_CONNECTIONS}. */
  public int openLinkCount() {
    return links.size();
  }

  /** Closes every open link. For app shutdown; safe to call more than once. */
  public void closeAll() {
    for (BandLink link : Set.copyOf(links.values())) {
      link.close();
    }
    links.clear();
  }

  /**
   * Decides who gets serviced this round: everyone already linked, plus admissions. An unlinked
   * band is a candidate only with a fresh sighting (see class javadoc for the experimental basis);
   * candidates fill free pool slots least-recently-serviced first, and any still waiting evict the
   * longest-resident links one-for-one.
   */
  private List<BandAssignment> planRound(List<BandAssignment> assignments) {
    List<BandAssignment> candidates = new ArrayList<>();
    Map<String, BandAssignment> byAddress = new ConcurrentHashMap<>();
    for (BandAssignment assignment : assignments) {
      String address = assignment.device().address();
      byAddress.put(address, assignment);
      if (!links.containsKey(address) && sightings.seenWithin(address, SIGHTING_FRESHNESS)) {
        candidates.add(assignment);
      }
    }
    candidates.sort(
        Comparator.comparingLong(a -> lastServicedNanos.getOrDefault(a.device().address(), 0L)));

    int freeSlots = MAX_LIVE_CONNECTIONS - links.size();
    List<BandAssignment> admitted = new ArrayList<>();
    List<BandAssignment> waiting = new ArrayList<>();
    for (BandAssignment candidate : candidates) {
      (admitted.size() < freeSlots ? admitted : waiting).add(candidate);
    }

    if (!waiting.isEmpty()) {
      // Longest-resident first: over successive rounds this cycles residency through the whole
      // roster instead of repeatedly bouncing the same recent admits.
      List<Map.Entry<String, BandLink>> evictable =
          links.entrySet().stream()
              .sorted(Comparator.comparingLong(e -> e.getValue().admittedNanos))
              .collect(Collectors.toCollection(ArrayList::new));
      for (BandAssignment next : waiting) {
        if (evictable.isEmpty()) {
          break;
        }
        Map.Entry<String, BandLink> evicted = evictable.remove(0);
        LOG.log(
            Level.INFO,
            "Rotating: releasing {0} (resident {1}s) to admit {2}",
            evicted.getKey(),
            (System.nanoTime() - evicted.getValue().admittedNanos) / 1_000_000_000L,
            next.device().address());
        evicted.getValue().close();
        links.remove(evicted.getKey());
        admitted.add(next);
      }
    }

    List<BandAssignment> toService = new ArrayList<>(admitted);
    for (String address : links.keySet()) {
      BandAssignment assignment = byAddress.get(address);
      if (assignment != null) {
        toService.add(assignment);
      }
    }
    return toService;
  }

  private void retainOnly(Set<String> wantedAddresses) {
    for (Map.Entry<String, BandLink> entry : Set.copyOf(links.entrySet())) {
      if (!wantedAddresses.contains(entry.getKey())) {
        entry.getValue().close();
        links.remove(entry.getKey());
      }
    }
  }

  private void serviceOne(BandAssignment assignment) {
    String address = assignment.device().address();
    BandLink link = links.get(address);
    if (link == null) {
      link = connectAndSubscribe(assignment);
      if (link == null) {
        return; // connect failed - soft failure, candidate again next round
      }
      links.put(address, link);
    }
    // The address is stable but the student holding it can be reassigned live; keep the link's
    // record target current so a reading is always attributed to whoever holds the band now.
    link.studentId = assignment.studentId();

    link.sawFrameThisRound.set(false);
    triggerMeasurement(link);

    if (link.sawFrameThisRound.get()) {
      link.misses.set(0);
      lastServicedNanos.put(address, System.nanoTime());
    } else if (!link.everSawFrame.get()) {
      // A link that never produced a single frame is the dead-admission signature (connect
      // "succeeded" against a band that had stopped advertising); don't let it hold a pool slot
      // for another round.
      LOG.log(
          Level.WARNING, "Link to {0} never produced a frame; dropping it immediately", address);
      link.close();
      links.remove(address);
    } else if (link.misses.incrementAndGet() >= MAX_MISSES_BEFORE_RECONNECT) {
      LOG.log(
          Level.WARNING,
          "No frames from {0} for {1} rounds; dropping the link so it reconnects fresh",
          address,
          MAX_MISSES_BEFORE_RECONNECT);
      link.close();
      links.remove(address);
    }
  }

  private BandLink connectAndSubscribe(BandAssignment assignment) {
    String address = assignment.device().address();
    LOG.log(Level.INFO, "Connecting to {0} ({1})", address, assignment.studentId());
    BandConnection connection;
    try {
      connection =
          transport.connect(assignment.device()).get(CONNECT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Could not connect to " + address, e);
      return null;
    }

    BandLink link = new BandLink(address, assignment.studentId(), connection);
    // One subscription for the connection's whole lifetime. A single FrameReassembler per link
    // buffers fragmented notifications across rounds; a late frame from an earlier round still
    // counts down whichever latch is currently active, so a delayed-not-dropped notification is
    // still recognized instead of discarded between rounds.
    connection
        .notifications()
        .subscribe(
            new FrameSubscriber(
                raw -> {
                  for (byte[] complete : link.reassembler.accept(raw)) {
                    handleCompleteFrame(link, complete);
                  }
                }));
    return link;
  }

  private void triggerMeasurement(BandLink link) {
    try {
      for (int attempt = 1; attempt <= MAX_MEASUREMENT_ATTEMPTS; attempt++) {
        CountDownLatch latch = new CountDownLatch(1);
        link.currentLatch.set(latch);
        try {
          link.connection
              .writeCommand(LaxasfitProtocol.withCrc(LaxasfitProtocol.CMD_HR_START))
              .get(WRITE_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
          link.lastWriteNanos.set(System.nanoTime());
        } catch (ExecutionException | TimeoutException e) {
          // A write failure on a persistent link usually means it has genuinely dropped; stop
          // waiting and let the silent-round bookkeeping reconnect it rather than burning the
          // measurement window on a dead link. (InterruptedException is deliberately not caught
          // here - it means the round was cancelled, and propagates to the outer handler.)
          LOG.log(Level.WARNING, "Could not write HR-start command to " + link.address, e);
          return;
        }

        if (latch.await(MEASUREMENT_WAIT.toSeconds(), TimeUnit.SECONDS)) {
          return;
        }
        LOG.log(
            Level.WARNING,
            "No HR frame from {0} within {1}s (attempt {2}/{3})",
            link.address,
            MEASUREMENT_WAIT.toSeconds(),
            attempt,
            MAX_MEASUREMENT_ATTEMPTS);
      }
    } catch (InterruptedException e) {
      // Round cancelled (scope timeout or app shutdown); leave the link open for the next round.
      Thread.currentThread().interrupt();
    }
  }

  private void handleCompleteFrame(BandLink link, byte[] complete) {
    // Any complete frame - even an ack or an empty reading - proves the link still carries
    // traffic, which is exactly what the silent-round reconnect logic keys off of.
    link.sawFrameThisRound.set(true);
    link.everSawFrame.set(true);

    Frame frame = FrameParser.parse(complete);
    LOG.log(
        Level.INFO,
        "Notification from {0}: {1} [{2}]",
        link.address,
        frame.getClass().getSimpleName(),
        LaxasfitProtocol.hex(complete));
    if (!LaxasfitProtocol.crcValid(complete)) {
      // Diagnostic only - LaxasfitProtocol itself deliberately doesn't enforce this (see its
      // javadoc), and that stays untouched; a mismatch here on an already-reassembled,
      // correctly-sized frame is still worth knowing about if it ever happens.
      LOG.log(
          Level.WARNING,
          "CRC mismatch on reassembled frame from {0}: [{1}]",
          link.address,
          LaxasfitProtocol.hex(complete));
    }
    if (!(frame instanceof Frame.HeartRate hr)) {
      return;
    }

    // The documented exchange (Gadgetbridge issue #5640) is START -> band ACK -> DATA -> host
    // ACK: the host is expected to acknowledge every DATA_HR frame it receives, regardless of
    // whether the reading is empty, stale-looking, or good.
    ackHrData(link.connection, link.address);

    if (hr.bpm().isEmpty()) {
      // A genuine "no reading" is still worth recording (history/audit value), but it's not a
      // final answer - keep listening for the rest of this attempt's window instead of counting
      // down the latch on it.
      session.recordReading(link.studentId, hr.bpm());
      return;
    }

    long elapsedMillis = (System.nanoTime() - link.lastWriteNanos.get()) / 1_000_000L;
    if (elapsedMillis < MIN_LIVE_READING_DELAY.toMillis()) {
      // Don't record this at all: we don't trust the value enough to show it, let alone end the
      // measurement on it.
      LOG.log(
          Level.WARNING,
          "Ignoring HR frame from {0} ({1}) only {2}ms after the write - too fast to be the live"
              + " measurement, likely a stored-record push",
          link.address,
          hr.bpm(),
          elapsedMillis);
      return;
    }

    LOG.log(Level.INFO, "HR frame from {0}: {1}", link.address, hr.bpm());
    session.recordReading(link.studentId, hr.bpm());
    link.currentLatch.get().countDown();
  }

  private void ackHrData(BandConnection connection, String address) {
    // Fired without blocking: this runs on the notification-delivery thread (inside
    // FrameSubscriber's onNext, called serially by SubmissionPublisher), and blocking there
    // would stall processing of whatever chunk arrives next - including the trailing fragment
    // of a frame FrameReassembler is still mid-assembling. The underlying writeCommand()
    // already serializes actual native writes against the polling thread's own writes (see
    // SimpleBleTransport.SimplePeripheralConnection), so firing this concurrently is safe.
    connection
        .writeCommand(LaxasfitProtocol.withCrc(LaxasfitProtocol.CMD_HR_DATA_ACK))
        .exceptionally(
            e -> {
              LOG.log(Level.WARNING, "Could not ack HR data to " + address, e);
              return null;
            });
  }

  /** One band's persistent connection and the per-round state its notification handler touches. */
  private static final class BandLink {
    private final String address;
    private final BandConnection connection;
    private final FrameReassembler reassembler = new FrameReassembler();
    private final long admittedNanos = System.nanoTime();
    private final AtomicReference<CountDownLatch> currentLatch =
        new AtomicReference<>(new CountDownLatch(1));
    private final AtomicLong lastWriteNanos = new AtomicLong(System.nanoTime());
    private final AtomicInteger misses = new AtomicInteger();
    private final AtomicBoolean sawFrameThisRound = new AtomicBoolean();
    private final AtomicBoolean everSawFrame = new AtomicBoolean();
    private volatile String studentId;

    BandLink(String address, String studentId, BandConnection connection) {
      this.address = address;
      this.studentId = studentId;
      this.connection = connection;
    }

    void close() {
      connection.close();
    }
  }

  /** Minimal unbounded-request subscriber: forwards every item to a callback. */
  private record FrameSubscriber(Consumer<byte[]> onFrame) implements Flow.Subscriber<byte[]> {
    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(byte[] item) {
      onFrame.accept(item);
    }

    @Override
    public void onError(Throwable throwable) {}

    @Override
    public void onComplete() {}
  }
}
