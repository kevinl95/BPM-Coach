package com.loeffler.bpmcoach.domain;

/** A discovered or known BLE band, identified by its transport-level address. */
public record Band(String address, String displayName) {

  public Band {
    if (address == null || address.isBlank()) {
      throw new IllegalArgumentException("address must not be blank");
    }
  }
}
