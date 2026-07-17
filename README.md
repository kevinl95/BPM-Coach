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
                (StructuredTaskScope fan-out) - the concurrent polling pipeline
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

`BandPoller` logs each stage of polling a real band (connect, HR-start write,
whether a reading arrived) via `java.lang.System.Logger`, which prints to the
console by default with no extra setup - watch for `WARNING` lines when
`./gradlew :desktop:run` is running. A `Could not connect` or `Could not
write` warning points to the BLE link itself. A `No HR frame from <address>
within 15s (attempt N/2)` means the connection and command write both
succeeded but no result came back in time - `BandPoller` retries the HR-start
command once more on the same still-open connection before giving up, since
cheap BLE bands can genuinely drop a notification (not just delay it) under
their own aggressive low-power connection interval; if you see the band's own
screen show a reading that never shows up here even after both attempts,
that's this dropped-notification behavior, a hardware/radio characteristic
rather than an app bug.

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
same hex frame vectors (spec reference frames plus real captures). One note
from that port: the real finger-off/no-reading capture's own checksum does
**not** self-validate under `crc()` - confirmed by running the original,
unmodified harness - which is the actual empirical case behind
`parseHeartRate`'s documented decision not to enforce inbound CRC (BLE
already guarantees link integrity; some firmware variants compute the
inbound checksum over a different window). What still holds, and is what's
tested, is that the frame parses correctly regardless.

## License

BPM Coach's own code is Apache License 2.0 (see [`LICENSE`](LICENSE)). See
[`NOTICE.md`](NOTICE.md) for third-party protocol/library credit and the
SimpleBLE license note.
