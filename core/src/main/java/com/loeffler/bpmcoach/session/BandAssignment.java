package com.loeffler.bpmcoach.session;

import com.loeffler.bpmcoach.transport.DiscoveredDevice;

/** Pairs a student with the band device that should be polled on their behalf. */
public record BandAssignment(String studentId, DiscoveredDevice device) {}
