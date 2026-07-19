# BPM Coach

A cross-platform desktop app that lets a PE teacher monitor a whole class's
heart rates live from ultra-cheap (~$1) BLE fitness bands, for **effort-based
grading (time-in-zone)** instead of speed-based grading. One laptop drives a
projector showing every student's effort zone (green/yellow/red) in real
time; readings are logged per-student for after-class review.

Built for the Hackster.io **"Modern Java in the Wild"** contest.

The differentiator is cost: these bands run about a dollar each in bulk,
versus thousands of dollars for a classroom set of Polar/Garmin straps.

## Why this hardware

The bands are cheap, generic "M4"-style Bluetrum fitness trackers that speak
a simple Nordic-UART-based protocol. That protocol was reverse-engineered by
**fr3ts0n** for the [Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge)
project ([issue #5640](https://codeberg.org/Freeyourgadget/Gadgetbridge/issues/5640),
merged as PR #5774) - full credit in [`NOTICE.md`](NOTICE.md). BPM Coach's
`LaxasfitProtocol` class is an independent Java implementation of that
published protocol, verified against both the spec and real hardware
captures (see Testing, below).

## Architecture

```
core/                       no GUI, no OS-specific code - independently testable
  protocol/     LaxasfitProtocol (ground truth, byte-for-byte port) + Frame
                (sealed interface) + FrameParser (bytes -> Frame)
  domain/       Student, Band, ZoneConfig, Zone, HeartRateReading (records)
  transport/    BleTransport interface - the seam between domain and BLE stack
  session/      ClassSession (reactive, java.util.concurrent.Flow) + BandPoller
                (StructuredTaskScope fan-out, persistent per-band links) - the
                concurrent polling pipeline
  persistence/  RosterStore - persists paired students/bands across launches

desktop/                    JavaFX GUI + the two BleTransport implementations
  transport/ SimpleBleTransport (real bands, via SimpleJavaBLE)
             MockBleTransport   (simulated bands, demo mode)
             BandDiscovery      (continuous background scan, for pairing +
                                 recognizing previously-paired bands)
  ui/        ProjectorView, HistoryView, PairingView, ZoneConfigView
```

`BleTransport` is the one interface the domain layer depends on; both the
real and mock implementations drive the exact same `ClassSession`/
`BandPoller` pipeline. Demo mode isn't a separate code path - it's the same
app with a different transport plugged in.

## Pairing bands

The roster isn't hardcoded - it starts empty on a fresh install. On the
**Pairing** tab: scan results appear live as bands come into range, pick one,
type a name, click **Pair**. That pairing (band address -> student name) is
written to `~/.bpmcoach/roster.tsv` immediately, and `ClassSession`'s roster
updates live, so polling picks it up on the very next round with no restart.

On the next launch, that roster is loaded back in, and `BandDiscovery` scans
continuously in the background for as long as the app runs - so a
previously-paired band is recognized (and starts being polled) as soon as
it's back in range, with no re-pairing needed. Typing an existing student's
name re-points their pairing at the newly selected band instead of creating a
duplicate; **Unpair** removes the selected student from the roster entirely.
This works identically in demo mode against `MockBleTransport`'s
simulated bands, which is also the easiest way to see the whole flow with no
hardware: pair a few "Mock Band N" entries and watch them show up on the
Projector tab.

## Cross-platform BLE

Desktop BLE on the JVM isn't uniform across OSes, so this was evaluated
first. BPM Coach uses [SimpleBLE](https://github.com/simpleble/simpleble)
(via its Java binding, SimpleJavaBLE), which wraps three different native
backends behind one API:

| OS | Backend | Status |
|---|---|---|
| Linux | BlueZ | Primary development/test platform |
| Windows | WinRT | SimpleBLE-backed; not field-tested by us on real hardware yet |
| macOS | CoreBluetooth | SimpleBLE-backed; not field-tested by us on real hardware yet |

SimpleJavaBLE isn't published to Maven Central, so its jar and per-OS native
libraries are vendored under `desktop/libs/` and
`desktop/src/main/resources/native/<os>/`. It's licensed under **BUSL-1.1**
(free for non-commercial use, including this project) - see
[`NOTICE.md`](NOTICE.md) for the full license note. Unlike the Android
reference client this project started from, SimpleJavaBLE has no
`requestConnectionPriority` equivalent - each OS's BLE stack owns that
negotiation internally.

## Modern Java, used where it genuinely fits

Targets **Java 26** (toolchain auto-provisioned by Gradle; no local install
required). `--enable-preview` is wired into compile/test/run because
`StructuredTaskScope` is still preview in 26 (JEP 525, 6th preview) - the
current `open(Joiner, Configuration)` factory form, not the older constructor.

| Feature | Where | Why |
|---|---|---|
| Records | `Student`, `Band`, `ZoneConfig`, `HeartRateReading`, `Frame` variants | Immutable values crossing thread boundaries (pollers -> GUI) |
| Sealed interface + pattern matching | `Frame` dispatch in `FrameParser`/`BandPoller` | Compiler-checked exhaustiveness; a new frame type forces every switch site to be updated |
| Virtual threads | `BandPoller`, one per in-flight band | Polling is blocking I/O (~8-10s per measurement); cheap "one thread per band" instead of callback spaghetti |
| `StructuredTaskScope` (JEP 525) | `BandPoller.pollBatch` | Fans out a batch of ~6 bands concurrently; `Joiner.allUntil(_ -> false)` runs every subtask to completion so one dropped band never cancels the rest |
| Sequenced collections | `ClassSession.historyFor` | `.reversed()` gives "most recent first" without a manual reverse |
| `java.util.concurrent.Flow` | `ClassSession` -> GUI | Keeps `core` GUI-agnostic; desktop subscribes and marshals onto the FX thread |

**Vector API (JEP 529, 11th incubator): deliberately not used.** There's no
real SIMD-worthy numeric hot path here - the CRC is an 8-bit sum over <25
bytes, and class-wide aggregates run over at most a few dozen students. Both
are far below where SIMD pays off; forcing it in would be exactly the "old
logic, new coat of paint" the contest warns against.

## Running it

Requires nothing but a JDK to *start* Gradle (the wrapper auto-provisions
the real Java 26 toolchain via [Foojay](https://github.com/gradle/foojay-toolchains)
on first run).

```bash
# Live mode (default) - real bands via SimpleJavaBLE. This is how the app
# is actually meant to run; it needs a Bluetooth adapter.
./gradlew :desktop:run

# Demo mode - simulated bands, no hardware required. Use this for judging,
# for a demo without bands on hand, or if this machine has no Bluetooth.
./gradlew :desktop:run --args=--mode=demo
```

First launch starts with an empty roster in both modes - use the **Pairing**
tab to scan and assign names to bands (see "Pairing bands" above). Pairings
persist across launches automatically.

### Troubleshooting a paired band that shows no data

`BandPoller` logs every notification it receives (reassembled, with raw hex)
plus each stage of polling a real band (connect, HR-start write, whether a
reading arrived) via `java.lang.System.Logger`, which prints to the console
by default with no extra setup - watch for `WARNING` lines when
`./gradlew :desktop:run` is running.

- `Could not connect` / `Could not write` points to the BLE link itself.
- `Notification from <address>: HeartRate [<hex>]` with an empty bpm is a
  genuine "no reading yet" - `BandPoller` keeps listening for the rest of
  that attempt's window rather than giving up on it (a real live measurement
  can still arrive after an earlier empty one).
- `Ignoring HR frame from <address> (<bpm>) only <N>ms after the write` means
  a present reading arrived faster than plausible for a real ~8-10s
  measurement and was treated as a stored-record push, not the live result -
  see the CRC-truncation note above for why that distinction matters.
- `No HR frame from <address> within 15s (attempt N/2)` means the whole
  window elapsed with nothing accepted - `BandPoller` retries the HR-start
  command once more on the same still-open connection before giving up,
  since cheap BLE bands can genuinely drop a notification (not just delay
  it) under their own aggressive low-power connection interval. If the
  band's own screen shows a reading that never shows up here even after both
  attempts, that's this dropped-notification behavior, a hardware/radio
  characteristic rather than an app bug.
- `CRC mismatch on reassembled frame` on a frame that's otherwise the
  correct, complete length is worth reporting - unlike the truncation case
  above, this would be a genuine anomaly once reassembly is in place.

### Scans find other devices, but never the band (0 devices / "Unknown device")

Before blaming the band, check for a second running instance of the app:

```bash
ps aux | grep MainApp | grep -v grep
```

Two live-mode instances on one machine silently break each other: both cycle
discovery on the same adapter, BlueZ's per-session duplicate filtering then
suppresses re-reports of already-seen devices, and each instance logs
`Scan cycle N complete: 0 device(s)` forever while `connect()` fails with
`Unknown device <address> - scan() before connect()`. This happened twice
during development, both times from an orphaned instance: killing a
`./gradlew :desktop:run` invocation (Ctrl+C on the client, a closed terminal,
`timeout`) can kill the Gradle *client* while the forked daemon and the app
JVM it launched keep running, invisibly, for days.

The app now refuses to start a second instance (a file lock at
`~/.bpmcoach/app.lock`, released automatically by the OS however the process
exits) and says so explicitly, so this failure mode is loud instead of
silent. If you see that refusal message with no visible app window, the
orphaned process from the `ps` line above is the one holding the lock - kill
it and relaunch.

### Band not showing up at all (not even in a raw OS-level scan)

If `BandDiscovery`'s scan cycles keep reporting the band's address as not
found, and a raw `bluetoothctl scan on` (interactive mode - see below) can't
see it either, the band itself has stopped advertising - no code change fixes
that, since nothing can connect to a device that isn't broadcasting. This
showed up after this band had been through a lot of rapid connect/disconnect
cycling during development. Fix: reset the band itself, from its own on-device
menu - **scroll to MORE, then scroll to RESET**. After resetting, confirm it's
advertising again before relaunching the app:

```bash
bluetoothctl        # interactive mode - a plain `bluetoothctl scan on` in a
                     # non-interactive shell returns immediately and doesn't
                     # stream results
scan on
# watch for "[NEW] Device <address> B01H_M4" (or your band's address/name)
scan off
exit
```

### One good reading, then silence

Confirmed against real hardware: the first connect/measure worked and
returned a genuine reading, then every subsequent round timed out - and the
diagnostic ack below showed *nothing at all* came back on those rounds, so
the command wasn't reaching the band. The tell was in the timing: the first
connect took ~8s (a real BLE handshake), but every reconnect after that
returned in ~3s and wrote immediately, with the write reporting success yet
no response ever arriving. That's a **stale connection** - after the first
connect/measure/disconnect cycle, `connect()` claims success and
`isConnected()` returns true, but the actual radio link is dead, so the
write goes into a void. It lines up with the one thing the Android reference
client flags that this desktop BLE binding structurally can't do:
`requestConnectionPriority(HIGH)`, which the reference explicitly credits
for fixing exactly this GATT timeout.

The fix is architectural: `BandPoller` now holds **one persistent connection
per band** and re-issues `CMD_HR_START` on that same open link every round,
instead of connecting and disconnecting each time. This sidesteps the
reconnect churn entirely and fits continuous classroom monitoring better
than connect-per-reading did. Link health is judged purely by observed
behaviour - a round with no frames at all (not even an ack) counts as a
miss, and only after two consecutive silent rounds is the link torn down and
reconnected. `isConnected()` is deliberately never trusted for this, since
it was the very call that lied during the stale-reconnect bug.

**Scaling past the adapter's connection cap.** BLE adapters cap concurrent
connections at ~7-10, so the persistent pool holds at most
`BandPoller.MAX_LIVE_CONNECTIONS` (7) links. A larger roster rotates
*residency* in the pool opportunistically, with rules taken directly from a
controlled reconnect experiment on the real band:

- Reconnects work reliably **if and only if** the band was sighted
  advertising just beforehand (verified: repeated disconnect/reconnect
  cycles in one process, each gated on a fresh sighting, all came up live
  with the command-ack arriving in ~70ms). Connecting blind to a cached
  address is what produced every dead link.
- After a disconnect the band takes a minute or more to re-advertise -
  sometimes not at all until nudged, if it's lying still. On a wrist in an
  active PE class it should stay awake; that part is untested.

So: an unlinked band is admitted to the pool only on a fresh advertisement
sighting (`BandDiscovery` timestamps every sighting and `BandPoller` checks
it before any connect), admissions go least-recently-serviced first so
nobody starves, and each admission evicts the longest-resident link, which
then re-advertises and cycles back in naturally. At or under the cap,
nothing ever rotates - every band just stays resident. A roster of 9 mock
bands is covered by an integration test asserting every student gets data
within two rounds and the pool never exceeds the cap.

Independently of the connection model, the documented protocol flow
(Gadgetbridge issue #5640, the same capture our reference 86bpm test vector
comes from) has the host acknowledge every `DATA_HR` frame with a fixed
reply (`LaxasfitProtocol.CMD_HR_DATA_ACK`); `BandPoller` sends this ack
unconditionally for every `DATA_HR` frame (empty, stale-looking, or good) as
soon as it's parsed - verified against the documented frame bytes and
independently re-derived via `LaxasfitProtocol.crc()` before being added.

Two related ideas surfaced during that investigation that are **not**
implemented, since they couldn't be verified with the same confidence (and
this app already got one clean reading through with neither of them in
place, which argues against either being strictly required):

- **Session initialization.** The vendor app and Gadgetbridge reportedly run
  an init sequence (set date/time, request device info) before doing
  anything else. Worth trying if the ack fix alone isn't enough.
- **Periodic/timed auto-measurement.** If the band's vendor app exposes a
  "measure every N minutes" setting, `CMD_HR_LAST` (already defined in
  `LaxasfitProtocol`, currently unused) could read the band's own stored
  result instantly instead of this app driving a ~10-15s remote-triggered
  measurement per band per poll - a meaningfully better fit for polling a
  whole classroom. Nobody's confirmed the enable-periodic-measurement
  command bytes exist for this band family yet.

(Credit: this whole thread - the missing ack, and the protocol citations
above - came from a second AI reviewer, Fable, given the repo to review
independently. Verified against the primary source and re-derived
arithmetically before implementing, same as the frame-truncation finding.)

A useful diagnostic fell out of this: the band's *immediate* ack to the
HR-start command is a documented, fixed frame - it shows up in the logs as
`Ack [FD 00 05 1C 02 0D 00 0A 01]` within a second of the write. If a poll
round doesn't even show that, the problem isn't the measurement (the band
never heard the command, or its reply never made it back over the radio);
if the ack arrives but no `HeartRate` frame follows within ~10-15s, the
command was received and the measurement side is what didn't deliver.

### Native crashes (SIGSEGV) under concurrent scan + connection

Two hard JVM crashes during development (SIGSEGV in `jni_CallObjectMethodV`
on the binding's native event thread - a use-after-free, not a catchable
exception) shared one circumstance: `BandDiscovery`'s continuous scan loop
was restarting scan sessions at the same time as a connection was going
through a state transition (one crash during teardown right after a
successful reading, one mid-connect). Two app-side defenses:

- `SimpleBleTransport.close()` runs in the deliberately unconventional
  order *disconnect, drain, unsubscribe*: `unsubscribe()` frees the native
  side's only reference to the notification callback (the binding keeps no
  Java-side reference - confirmed by bytecode inspection), so the physical
  link is dropped first, guaranteeing no new dispatch can start, and a
  short drain lets anything mid-flight finish before the callback is freed.
  The poller also waits for its HR data ack write to actually flush before
  releasing the connection for teardown.
- Scanning and connecting no longer overlap at all: the poll loop pauses
  `BandDiscovery` for the duration of each poll round and resumes it
  between rounds. Beyond the crash, scanning concurrently with a live
  connection starves it of radio time on a cheap adapter - which matched
  whole rounds where not even the immediate command-ack above arrived.

## Packaging

Native, no-JVM-required bundles via [jlink](https://openjdk.org/jeps/282) +
[jpackage](https://openjdk.org/jeps/392) (through the Badass-JLink Gradle
plugin). **jpackage cannot cross-compile** - each OS's installer must be
built on that OS:

```bash
./gradlew :desktop:jpackage
```

produces a `.deb`/`.rpm` on Linux, `.msi`/`.exe` on Windows, `.pkg`/`.dmg` on
macOS, in `desktop/build/jlinkbase/jlinkjars` / `desktop/build/jpackage`.

The installed app defaults to live mode, same as running from source - there's
no separate packaging-time override needed. A double-clicked icon has no way
to pass `--mode=demo`, but that's the point: the shipped product should assume
real hardware unless told otherwise.

## Testing

```bash
./gradlew test
```

`core/src/test/java/.../protocol/LaxasfitProtocolTest.java` is a direct
JUnit 5 port of the original manual verification harness, against the exact
same hex frame vectors (spec reference frames plus real captures).

**Correction, found during real-hardware debugging:** the vector originally
labeled "finger-not-seated" in that harness is not a no-reading capture at
all - it's a genuine 82 bpm reading, truncated by exactly one byte. Its
length prefix says 21 bytes; it's 20. Its CRC "failure" reconciles exactly
once the missing trailing byte (0x52 = 82 decimal) is accounted for. This
matters beyond the test suite: BLE notifications are capped by the ATT MTU
(20 bytes of payload at the common default), so a 21-byte protocol frame
arrives as two separate notifications, and every live reading was silently
landing on the truncated first chunk - always reading a zeroed pad byte as
"no reading," regardless of the band's actual measurement. `FrameReassembler`
now buffers notification bytes per connection and uses the frame's own
length prefix to emit only complete frames to `FrameParser`; `BandPoller`
also no longer accepts an empty reading as final within an attempt (it keeps
listening for the rest of the window instead) and rejects any present
reading arriving suspiciously fast after the write, on the theory that a
frame shaped like a stored-record push (it carries date/record-count/
timestamp fields, see `parseHeartRate`'s javadoc) isn't the live measurement.
See `FrameReassemblerTest` for the reconstruction proof, and
`core/src/main/java/.../protocol/FrameReassembler.java`'s javadoc for the
full account. (Credit: found by a second AI reviewer - Fable - given the repo
to review independently; verified here by hand before acting on it.)

## License

BPM Coach's own code is Apache License 2.0 (see [`LICENSE`](LICENSE)). See
[`NOTICE.md`](NOTICE.md) for third-party protocol/library credit and the
SimpleBLE license note.
