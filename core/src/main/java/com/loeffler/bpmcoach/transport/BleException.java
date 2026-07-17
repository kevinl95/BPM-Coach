package com.loeffler.bpmcoach.transport;

/** Any transport-level BLE failure: connect timeout, GATT error, adapter missing, etc. */
public class BleException extends RuntimeException {

  public BleException(String message) {
    super(message);
  }

  public BleException(String message, Throwable cause) {
    super(message, cause);
  }
}
