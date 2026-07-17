package com.loeffler.bpmcoach.protocol;

import java.util.Arrays;

/**
 * Laxasfit / "M4" (Bluetrum) fitness band protocol.
 *
 * <p>Protocol reverse-engineered by fr3ts0n for Gadgetbridge (issue #5640, merged as PR #5774).
 * This class implements the frame encode/decode and CRC independently so it can be unit-tested with
 * no hardware.
 *
 * <p>Transport (Nordic UART Service): Service : 6e400001-b5a3-f393-e0a9-e50e24dcca9f Write :
 * 6e400002-... (send commands here) Notify : 6e400003-... (subscribe for ACKs + data frames)
 *
 * <p>Frame layout: df | len(2, BE) | crc(1) | cmd(3) | payloadLen(2, BE) | payload... 'df' = data
 * message, 'fd' = ack message CRC = 8-bit sum of every byte in the telegram, with the CRC byte
 * itself treated as 0 while summing.
 */
public final class LaxasfitProtocol {

  public static final byte MSG_DATA = (byte) 0xDF;
  public static final byte MSG_ACK = (byte) 0xFD;

  // Command triples (cmd bytes at offset 4..6).
  // Start heart-rate measurement: 02 10 0d, payload 01 01
  public static final byte[] CMD_HR_START = {
    (byte) 0xDF, 0x00, 0x06, 0x00, 0x02, 0x10, 0x0D, 0x00, 0x01, 0x01
  };
  // Start SpO2 measurement: 02 10 1c, payload 01 01
  public static final byte[] CMD_SPO2_START = {
    (byte) 0xDF, 0x00, 0x06, 0x00, 0x02, 0x10, 0x1C, 0x00, 0x01, 0x01
  };
  // Retrieve last stored HR (fast, no ~10s measure): 05 10 06
  public static final byte[] CMD_HR_LAST = {
    (byte) 0xDF, 0x00, 0x06, 0x00, 0x05, 0x10, 0x06, 0x00, 0x01, 0x00
  };

  private LaxasfitProtocol() {}

  /** 8-bit additive checksum; the CRC slot (index 3) is treated as 0. */
  public static byte crc(byte[] frame) {
    int sum = 0;
    for (int i = 0; i < frame.length; i++) {
      if (i == 3) continue; // skip the CRC byte itself
      sum += (frame[i] & 0xFF);
    }
    return (byte) (sum & 0xFF);
  }

  /** Returns a copy of the frame with byte[3] set to the correct CRC. */
  public static byte[] withCrc(byte[] frame) {
    byte[] out = Arrays.copyOf(frame, frame.length);
    out[3] = crc(out);
    return out;
  }

  public static boolean crcValid(byte[] frame) {
    if (frame.length < 4) return false;
    return crc(frame) == frame[3];
  }

  // --- Frame classification ---------------------------------------------

  public static boolean isData(byte[] f) {
    return f.length > 0 && f[0] == MSG_DATA;
  }

  public static boolean isAck(byte[] f) {
    return f.length > 0 && f[0] == MSG_ACK;
  }

  /** The 3-byte command id (offset 4..6), or null if the frame is too short. */
  public static int cmdId(byte[] f) {
    if (f.length < 7) return -1;
    return ((f[4] & 0xFF) << 16) | ((f[5] & 0xFF) << 8) | (f[6] & 0xFF);
  }

  // Data-frame command ids we care about (offset 4..6 of a df frame).
  public static final int DATA_HR = 0x050104; // heart rate result
  public static final int DATA_BP = 0x050105; // blood pressure result
  public static final int DATA_SPO2 = 0x05010E; // blood oxygen result

  /**
   * Extract heart rate from a DATA_HR frame. Layout: df 00 11 crc 05 01 04 00 0c
   * <date2><nrec2><time4><00 00> <bpm> bpm is the final payload byte. Returns -1 if not a valid HR
   * reading.
   */
  public static int parseHeartRate(byte[] f) {
    if (!isData(f) || cmdId(f) != DATA_HR) return -1;
    // Inbound CRC intentionally not enforced: BLE link layer already
    // guarantees payload integrity, and some firmware variants compute
    // the inbound sum over a record-count-dependent window. Outbound
    // command CRC (withCrc) IS enforced, which is what the band checks.
    int bpm = f[f.length - 1] & 0xFF;
    return bpm == 0 ? -1 : bpm; // 0 == no valid reading this cycle
  }

  /** SpO2 percentage from a DATA_SPO2 frame, or -1. */
  public static int parseSpo2(byte[] f) {
    if (!isData(f) || cmdId(f) != DATA_SPO2) return -1;
    int pct = f[f.length - 1] & 0xFF;
    return pct == 0 ? -1 : pct;
  }

  /** Systolic/diastolic from a DATA_BP frame, or null. */
  public static int[] parseBloodPressure(byte[] f) {
    if (!isData(f) || cmdId(f) != DATA_BP) return null;
    int sys = f[f.length - 2] & 0xFF;
    int dia = f[f.length - 1] & 0xFF;
    if (sys == 0 && dia == 0) return null;
    return new int[] {sys, dia};
  }

  // --- Hex helpers for logging/tests ------------------------------------

  public static byte[] hex(String s) {
    s = s.replaceAll("[^0-9A-Fa-f]", "");
    byte[] out = new byte[s.length() / 2];
    for (int i = 0; i < out.length; i++)
      out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
    return out;
  }

  public static String hex(byte[] b) {
    StringBuilder sb = new StringBuilder();
    for (byte x : b) sb.append(String.format("%02X ", x));
    return sb.toString().trim();
  }
}
