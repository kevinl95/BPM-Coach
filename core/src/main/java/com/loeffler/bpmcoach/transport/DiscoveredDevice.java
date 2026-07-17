package com.loeffler.bpmcoach.transport;

/** A BLE peripheral seen during a scan, before any connection is made. */
public record DiscoveredDevice(String address, String name, int rssi) {}
