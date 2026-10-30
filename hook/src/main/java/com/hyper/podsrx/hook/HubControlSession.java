package com.hyper.podsrx.hook;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import com.hyper.podsrx.protocol.Aacp;
import com.hyper.podsrx.protocol.BasicModeAllowlist;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Host-owned socket only: the app remains the sole AACP handshake/command/state owner. */
final class HubControlSession implements ControlSocketWire.Channel {
    private static final String TAG = "HyperPods-X";
    private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "hyperpods-connect-deadline");
        thread.setDaemon(true);
        return thread;
    });
    private final BluetoothManager manager;
    private final String address;
    private final Object lock = new Object();
    private volatile BluetoothSocket socket;
    private volatile boolean closed;
    private boolean connecting;

    HubControlSession(BluetoothManager manager, String address) {
        this.manager = manager;
        this.address = address;
    }

    @Override public void connect() throws IOException {
        synchronized (lock) {
            if (closed || connecting || socket != null) throw new IOException("Control socket already used");
            connecting = true;
        }
        try {
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null || !adapter.isEnabled()) throw new IOException("Bluetooth disabled");
            BluetoothDevice device = adapter.getRemoteDevice(address);
            if (device.getBondState() != BluetoothDevice.BOND_BONDED ||
                    !Boolean.TRUE.equals(BluetoothDevice.class.getMethod("isConnected").invoke(device)))
                throw new IOException("Paired headset is not connected");
            addBasicModeWhitelist(device);
            Method factory = BluetoothDevice.class.getDeclaredMethod("createL2capSocket", Integer.TYPE);
            factory.setAccessible(true);
            BluetoothSocket opened = (BluetoothSocket) factory.invoke(device, Aacp.PSM);
            if (opened == null) throw new IOException("Classic L2CAP factory unavailable");
            synchronized (lock) {
                if (closed) { opened.close(); throw new IOException("Control socket closed"); }
                socket = opened;
            }
            var deadline = DEADLINES.schedule(() -> {
                if (!opened.isConnected()) close();
            }, 12, TimeUnit.SECONDS);
            try { opened.connect(); }
            finally { deadline.cancel(false); }
            if (closed) throw new IOException("Control socket closed");
            DiagnosticLog.i(TAG, "Host BR/EDR AACP transport connected (basic mode, authenticated)");
        } catch (Exception error) {
            close();
            DiagnosticLog.w(TAG, "Host AACP connect failed", error);
            throw new IOException("Host AACP connect failed", error);
        }
    }

    @Override public boolean isConnected() {
        BluetoothSocket current = socket;
        return !closed && current != null && current.isConnected();
    }

    private BluetoothSocket connectedSocket() throws IOException {
        BluetoothSocket current = socket;
        if (closed || current == null || !current.isConnected()) throw new IOException("Control channel closed");
        return current;
    }

    @Override public int read(byte[] buffer) throws IOException {
        return connectedSocket().getInputStream().read(buffer);
    }

    @Override public void write(byte[] packet) throws IOException {
        BluetoothSocket current = connectedSocket();
        synchronized (current) {
            current.getOutputStream().write(packet);
            current.getOutputStream().flush();
        }
    }

    private static void addBasicModeWhitelist(BluetoothDevice device) throws Exception {
        Class<?> properties = Class.forName("android.os.SystemProperties");
        String key = "persist." + "bluetooth." + "l2cap." + "connect." + "fcr." + "basic.mode.whitelist";
        synchronized (HubControlSession.class) {
            Method get = properties.getMethod("get", String.class, String.class);
            String current = (String) get.invoke(null, key, "");
            String updated = BasicModeAllowlist.INSTANCE.add(current, device.getAddress());
            if (!current.equals(updated)) {
                properties.getMethod("set", String.class, String.class).invoke(null, key, updated);
                if (!updated.equals(get.invoke(null, key, ""))) throw new IOException("Basic-mode configuration rejected");
            }
        }
    }

    @Override public void close() {
        BluetoothSocket current;
        synchronized (lock) {
            if (closed) return;
            closed = true;
            current = socket;
            socket = null;
        }
        if (current != null) try { current.close(); } catch (IOException ignored) {}
    }
}
