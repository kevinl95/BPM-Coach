package com.loeffler.bpmcoach.transport;

import java.time.Duration;

/**
 * Answers "has this device been seen advertising recently?" - the gate for every BLE connect
 * attempt. Established experimentally against the real band: connecting to a device that was
 * sighted advertising moments before produces a live link, while connecting blind to a cached
 * address whose device isn't currently advertising "succeeds" at the API level but yields a dead
 * link that never carries a frame. The desktop module backs this with its continuous background
 * scan; tests back it with a lambda.
 */
@FunctionalInterface
public interface Sightings {

  /** True if {@code address} was seen advertising within the last {@code window}. */
  boolean seenWithin(String address, Duration window);
}
