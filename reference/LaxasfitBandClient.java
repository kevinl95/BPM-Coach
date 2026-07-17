import android.bluetooth.*;
import android.content.Context;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * Minimal single-band BLE client for Laxasfit/"M4" (Bluetrum) bands.
 *
 * This is the spine of one connection. For the PE-class use case you run
 * one of these per band, fanned out with structured concurrency (Java 21+
 * StructuredTaskScope) from the desktop/Android host — see fanOutExample().
 *
 * Flow:
 *   1. connect + discover
 *   2. requestConnectionPriority(HIGH)  <-- fixes the GATT_CONN_TIMEOUT you
 *      hit: the band asks for latency:55 which Android keeps dropping.
 *   3. enable notifications on TX (6e400003)
 *   4. write HR-start to RX (6e400002)
 *   5. parse the DATA_HR notification, read bpm from the last byte
 */
public class LaxasfitBandClient {

    private static final UUID SVC =
        UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9f");
    private static final UUID RX  =  // we WRITE commands here
        UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9f");
    private static final UUID TX  =  // we SUBSCRIBE for data here
        UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9f");
    private static final UUID CCCD =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    public interface Listener {
        void onHeartRate(int bpm);
        void onError(String reason);
    }

    private final BluetoothDevice device;
    private final Context ctx;
    private final Listener listener;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic rxChar, txChar;

    public LaxasfitBandClient(Context ctx, BluetoothDevice device, Listener l) {
        this.ctx = ctx; this.device = device; this.listener = l;
    }

    public void start() {
        gatt = device.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE);
    }

    public void close() {
        if (gatt != null) { gatt.disconnect(); gatt.close(); gatt = null; }
    }

    /** Kick off a fresh heart-rate measurement (band replies in ~8-10s). */
    public void requestHeartRate() {
        if (rxChar == null) { listener.onError("not ready"); return; }
        byte[] cmd = LaxasfitProtocol.withCrc(LaxasfitProtocol.CMD_HR_START);
        rxChar.setValue(cmd);
        rxChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        gatt.writeCharacteristic(rxChar);
    }

    private final BluetoothGattCallback cb = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                listener.onError("disconnected (status " + status + ")");
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            // The fix for the latency:55 timeout you saw in nRF Connect:
            g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);

            BluetoothGattService s = g.getService(SVC);
            if (s == null) { listener.onError("UART service missing"); return; }
            rxChar = s.getCharacteristic(RX);
            txChar = s.getCharacteristic(TX);
            if (rxChar == null || txChar == null) { listener.onError("chars missing"); return; }

            // subscribe to TX notifications
            g.setCharacteristicNotification(txChar, true);
            BluetoothGattDescriptor d = txChar.getDescriptor(CCCD);
            d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            g.writeDescriptor(d);
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g,
                                                      BluetoothGattCharacteristic c) {
            if (!c.getUuid().equals(TX)) return;
            byte[] frame = c.getValue();
            int bpm = LaxasfitProtocol.parseHeartRate(frame);
            if (bpm > 0) listener.onHeartRate(bpm);
            // frames with bpm==0 are "still measuring / no contact" -> ignore
        }
    };

    /*
     * Structured-concurrency fan-out sketch (host side, Java 21 preview):
     *
     * try (var scope = new StructuredTaskScope<Integer>()) {
     *     for (BluetoothDevice band : classSet) {
     *         scope.fork(() -> {
     *             var latch = new CompletableFuture<Integer>();
     *             var client = new LaxasfitBandClient(ctx, band,
     *                 new Listener() {
     *                     public void onHeartRate(int bpm){ latch.complete(bpm); }
     *                     public void onError(String r){ latch.complete(-1); }
     *                 });
     *             client.start();
     *             // give the band its measurement window, then reap
     *             client.requestHeartRate();
     *             Integer bpm = latch.get(12, TimeUnit.SECONDS);
     *             client.close();
     *             return bpm;
     *         });
     *     }
     *     scope.join();   // all bands measured concurrently, ~12s total
     * }
     *
     * BLE adapters cap ~7-10 concurrent links, so batch the class in groups
     * of ~6 and rotate. The band's own display shows the reading too, so a
     * dropped link is a soft failure, not a lost data point.
     */
}
