# BPM Coach

A cross-platform desktop app that lets a PE teacher monitor a whole class's
heart rates live from ultra-cheap (~$1) BLE fitness bands, for **effort-based
grading (time-in-zone)** instead of speed-based grading. One laptop drives a
projector showing every student's effort zone (green/yellow/red) in real time,
and logs per-student history for after-class review.

The differentiator is cost: these generic "M4"-style bands run about a dollar
each in bulk, versus thousands for a classroom set of Polar/Garmin straps.

Built for the Hackster.io **"Modern Java in the Wild"** contest.

## Architecture

```
core/                       no GUI, no OS-specific code - independently testable
  protocol/     LaxasfitProtocol (byte-for-byte protocol) + Frame (sealed
                interface) + FrameParser + FrameReassembler
  domain/       Student, Band, ZoneConfig, Zone, HeartRateReading (records)
  transport/    BleTransport interface - the seam between domain and BLE stack
  session/      ClassSession (reactive, java.util.concurrent.Flow) + BandPoller
                (StructuredTaskScope fan-out, persistent per-band links)
  persistence/  RosterStore - persists paired students/bands across launches

desktop/                    JavaFX GUI + the two BleTransport implementations
  transport/  SimpleBleTransport (real bands, via SimpleJavaBLE)
              MockBleTransport   (simulated bands, demo mode)
              BandDiscovery      (continuous background scan)
  ui/         ProjectorView, HistoryView, PairingView, ZoneConfigView
```

`BleTransport` is the only interface the domain layer depends on. The real and
mock transports drive the exact same `ClassSession`/`BandPoller` pipeline - demo
mode is the same app with a different transport plugged in, not a separate path.

The bands speak a simple Nordic-UART-based protocol reverse-engineered by
**fr3ts0n** for [Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge)
([issue #5640](https://codeberg.org/Freeyourgadget/Gadgetbridge/issues/5640)).
`LaxasfitProtocol` is an independent Java implementation of it, verified against
the spec and real hardware captures. Full credit in [`NOTICE.md`](NOTICE.md).

## Modern Java, used where it fits

Targets **Java 26** (toolchain auto-provisioned by Gradle; no local install
required). `--enable-preview` is wired into compile/test/run for
`StructuredTaskScope` (JEP 525, still preview in 26).

| Feature | Where | Why |
|---|---|---|
| Records | `Student`, `ZoneConfig`, `HeartRateReading`, `Frame` variants | Immutable values crossing thread boundaries (pollers → GUI) |
| Sealed interface + pattern matching | `Frame` dispatch in `FrameParser`/`BandPoller` | Compiler-checked exhaustiveness across frame types |
| Virtual threads | `BandPoller`, one per in-flight band | Polling is blocking I/O (~8-10s/measurement); one thread per band is cheap |
| `StructuredTaskScope` (JEP 525) | `BandPoller.pollRound` | Fans out a batch concurrently; `Joiner.allUntil` runs every subtask to completion so one dropped band never cancels the rest |
| Sequenced collections | `ClassSession.historyFor` | `.reversed()` for "most recent first" |
| `java.util.concurrent.Flow` | `ClassSession` → GUI | Keeps `core` GUI-agnostic; desktop marshals onto the FX thread |

## Running it

Needs only a JDK to *start* Gradle; the wrapper auto-provisions the real Java 26
toolchain via [Foojay](https://github.com/gradle/foojay-toolchains) on first run.

```bash
./gradlew :desktop:run                    # live mode (default) - real bands, needs a BT adapter
./gradlew :desktop:run --args=--mode=demo # demo mode - simulated bands, no hardware
```

Both modes start with an empty roster. On the **Pairing** tab, scan results
appear live as bands come into range; pick one, name it, click **Pair**. The
pairing is written to `~/.bpmcoach/roster.tsv` and picked up on the next poll
round with no restart. It's reloaded on the next launch, and `BandDiscovery`
scans continuously in the background, so a previously-paired band starts being
polled again as soon as it's back in range. Demo mode is the easiest way to see
the whole flow: pair a few "Mock Band N" entries and watch the Projector tab.

## Cross-platform BLE

BPM Coach uses [SimpleBLE](https://github.com/simpleble/simpleble) (via its Java
binding, SimpleJavaBLE), which wraps three native backends behind one API:

| OS | Backend | Status |
|---|---|---|
| Linux | BlueZ | Primary development/test platform |
| Windows | WinRT | SimpleBLE-backed; not yet field-tested on real hardware |
| macOS | CoreBluetooth | SimpleBLE-backed; not yet field-tested on real hardware |

SimpleJavaBLE isn't on Maven Central, so its jar is vendored under
`desktop/libs/` (it bundles its own per-architecture native libraries
internally). It's licensed **BUSL-1.1** - free for non-commercial use including
this project; see [`NOTICE.md`](NOTICE.md).

Note: SimpleJavaBLE has no `requestConnectionPriority` equivalent, so the app
can't force the tight connection interval the Android reference client relies
on. The connection handling below works around that.

## How the polling works

Each paired band gets **one persistent BLE connection**, held open across poll
rounds, with `CMD_HR_START` re-issued each round. This replaced an earlier
connect-per-round design that reliably got one reading then only stale
reconnects: on this binding a `connect()` to a non-advertising cached device
"succeeds" but yields a dead link, so re-connecting every round was the problem.
Link health is judged by observed frames (a round with no frames at all is a
miss; two consecutive misses drop the link), never by `isConnected()`, which
lies here.

Per the documented protocol, the host must acknowledge every `DATA_HR` frame
(`CMD_HR_DATA_ACK`); `BandPoller` sends this on every reading.

**Connection cap.** BLE adapters allow ~7-10 concurrent connections, so the
persistent pool is capped at `BandPoller.MAX_LIVE_CONNECTIONS` (7). A larger
roster rotates residency: an unlinked band is admitted only when `BandDiscovery`
has sighted it advertising recently (connecting blind yields a dead link),
least-recently-serviced first, evicting the longest-resident link. At or under
the cap nothing rotates.

## Troubleshooting

**"Another instance is already running" with no visible window.** Only one
live-mode instance can run at a time (a lock at `~/.bpmcoach/app.lock`); two
would fight over the adapter and silently break each other's scanning. If you
see this with no window, an orphaned process is holding the lock:
`ps aux | grep MainApp`, kill it, relaunch. (Shutdown is force-terminated by a
watchdog so this shouldn't happen, but a hard `kill -9` mid-run can still leave
one.)

**Band shows no data.** `BandPoller` logs every stage to the console. Useful
lines: `Could not connect`/`Could not write` (link problem); an `Ack [FD 00 05
1C 02 0D 00 0A 01]` within a second of the write is the band's documented
acknowledgment of the measure command - if it's absent, the command never
reached the band; if it's present but no `HeartRate` frame follows in ~15s, the
measurement side didn't deliver (cheap bands do drop notifications under their
low-power interval).

**Band not found even in a raw scan.** If `bluetoothctl scan on` can't see it
either, the band stopped advertising - reset it from its own menu (**MORE →
RESET**) and confirm it advertises again before relaunching.

## Packaging

Native, no-JVM-required app-images via jlink + jpackage, through the
**Badass-Runtime** Gradle plugin (`org.beryx.runtime`, the non-modular
counterpart to Badass-JLink). **jpackage can't cross-compile**, so each OS's
image is built on that OS:

```bash
./gradlew :desktop:jpackageImage   # → desktop/build/jpackage/bpm-coach/
```

The result runs via `bin/bpm-coach` (`.exe` on Windows) with no JDK on the
target. `.github/workflows/release.yml` builds all three OSes in a matrix and
attaches the zipped images to a GitHub Release on any `v*` tag (keep the tag in
sync with `version` in the root `build.gradle.kts`; note jpackage's macOS
bundler rejects any version starting with `0`).

The `runtime {}` block in `desktop/build.gradle.kts` declares its module set and
javafx `--module-path` explicitly rather than relying on the plugin's
auto-detection, which can't parse Java 26 class files - see the comments there.

## Testing

```bash
./gradlew test
```

`LaxasfitProtocolTest` is a direct JUnit 5 port of the original verification
harness against the same hex frame vectors. `FrameReassemblerTest` covers frame
reassembly across the 20-byte ATT MTU boundary and resync after a dropped
fragment; `BandPollerMockIntegrationTest` drives the full poll/rotation pipeline
against `MockBleTransport`.

One correction the tests encode: a vector originally labelled "finger-not-seated"
is actually a genuine 82 bpm reading truncated by one byte at the MTU boundary
(its CRC reconciles once the missing `0x52` = 82 is restored). Live readings were
silently landing on the truncated first chunk, which is why `FrameReassembler`
buffers by the frame's own length prefix and emits only complete frames.

## License

BPM Coach's own code is Apache License 2.0 (see [`LICENSE`](LICENSE)).
[`NOTICE.md`](NOTICE.md) covers third-party protocol/library credit and the
SimpleBLE license.
