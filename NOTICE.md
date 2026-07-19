# Notices

## Protocol reverse-engineering

The Laxasfit/"M4" (Bluetrum) BLE protocol implemented in
`core/src/main/java/com/loeffler/bpmcoach/protocol/LaxasfitProtocol.java`
was reverse-engineered by **fr3ts0n** for the
[Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge) project
([issue #5640](https://codeberg.org/Freeyourgadget/Gadgetbridge/issues/5640),
merged as PR #5774). BPM Coach's frame encode/decode and CRC logic is an
independent Java implementation of that published protocol, verified against
both the spec and real hardware captures.

## SimpleBLE / SimpleJavaBLE

The desktop module's cross-platform BLE transport is built on
[SimpleBLE](https://github.com/simpleble/simpleble) (and its Java binding,
SimpleJavaBLE), which wraps WinRT (Windows), CoreBluetooth (macOS), and
BlueZ (Linux) behind a single API. As of January 2025, SimpleBLE is licensed
under the **Business Source License 1.1 (BUSL-1.1)**:

- Free to use for non-commercial purposes, including development, testing,
  and non-commercial projects such as this hackathon submission.
- Commercial use requires a commercial license from the SimpleBLE authors
  (free tiers are available for small projects/early-stage companies).
- Each release converts to GPL-3 four years after it ships.

Full license text: <https://github.com/simpleble/simpleble/blob/main/LICENSE.md>

The `simplejavable-1.0.0.jar` vendored under `desktop/libs/` is an unmodified
binary from SimpleBLE's [v1.0.0 release](https://github.com/simpleble/simpleble/releases/tag/v1.0.0),
vendored here because SimpleJavaBLE is not published to Maven Central. The
jar bundles its own per-architecture native libraries internally (under
`native/x64/` and `native/aarch64/`, extracted at runtime by its own
`NativeLibraryLoader`) - nothing else needs to be vendored separately.

## BPM Coach itself

BPM Coach's own source is licensed under Apache License 2.0 (see `LICENSE`).
The BUSL-1.1 terms above apply only to the vendored SimpleBLE/SimpleJavaBLE
binaries, not to this project's code.
