package com.loeffler.bpmcoach.domain;

/** A student in the class roster, optionally assigned to a {@link Band}. */
public record Student(String id, String name, String assignedBandAddress) {

  public Student {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("id must not be blank");
    }
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
  }

  public boolean hasBand() {
    return assignedBandAddress != null && !assignedBandAddress.isBlank();
  }
}
