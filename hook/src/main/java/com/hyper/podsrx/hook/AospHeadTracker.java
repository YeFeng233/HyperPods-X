package com.hyper.podsrx.hook;

import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** SystemUI controls the Spatializer. Bluetooth owns the virtual HID sensor and its lifetime. */
final class AospHeadTracker extends Binder {
    private static final String ACTION = HubWire.APP + ".AOSP_HEAD_TRACKER";
    private static final String AUDIO_EFFECTS = "android.permission.MODIFY_DEFAULT_AUDIO_EFFECTS";
    private static final int CREATE = FIRST_CALL_TRANSACTION, POSE = CREATE + 1, STOP = POSE + 1;
    private static final long SENSOR_TIMEOUT_MS = 2000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final int systemUiUid;
    private final Runnable timeout = this::stop;
    private UhidHeadTracker tracker;
    private IBinder owner;
    private IBinder.DeathRecipient death;
    private boolean closed;

    private AospHeadTracker(Context context) throws Exception {
        systemUiUid = context.getPackageManager().getPackageUid(HubWire.SYSTEM_UI, 0);
    }

    static void install(Context context) throws Exception {
        AospHeadTracker host = new AospHeadTracker(context);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                if (!ACTION.equals(intent.getAction()) || !ReloadLifecycle.isActive()) return;
                Bundle result = new Bundle();
                result.putBinder("tracker", host);
                setResultExtras(result);
                setResultCode(1);
            }
        };
        context.registerReceiver(receiver, new IntentFilter(ACTION), AUDIO_EFFECTS, host.handler, Context.RECEIVER_EXPORTED);
        ReloadLifecycle.add(() -> {
            context.unregisterReceiver(receiver);
            host.close();
        });
    }

    @Override protected synchronized boolean onTransact(int code, Parcel input, Parcel output, int flags) throws RemoteException {
        if (code < CREATE || code > STOP) return super.onTransact(code, input, output, flags);
        if (Binder.getCallingUid() != systemUiUid) throw new SecurityException("Expected System UI");
        input.enforceInterface(ACTION);
        IBinder token = input.readStrongBinder();
        if (closed || !ReloadLifecycle.isActive()) throw new IllegalStateException("Head tracker bridge has reloaded");
        try {
            if (code == CREATE) {
                String address = input.readString();
                input.enforceNoDataAvail();
                HubHost hub = HubHost.current();
                if (!BluetoothAdapter.checkBluetoothAddress(address) || hub == null
                        || !address.equalsIgnoreCase(hub.address()) || token == null)
                    throw new IllegalArgumentException("Expected the bound AirPods and a session owner");
                stop();
                tracker = new UhidHeadTracker(address, handler);
                owner = token;
                death = () -> handler.post(() -> stop(token));
                try { token.linkToDeath(death, 0); }
                catch (RemoteException error) { stop(); throw error; }
                armTimeout();
            } else if (code == POSE) {
                float[] vector = input.createFloatArray();
                input.enforceNoDataAvail();
                if (tracker == null || owner != token) throw new IllegalStateException("Head tracker session ended");
                if (vector == null || vector.length != 3) throw new IllegalArgumentException("Expected three rotation axes");
                for (float axis : vector) if (!Float.isFinite(axis)) throw new IllegalArgumentException("Invalid rotation axis");
                tracker.pose(vector);
                armTimeout();
            } else {
                input.enforceNoDataAvail();
                stop(token);
            }
        } catch (IOException error) {
            stop();
            throw new IllegalStateException(error.getMessage(), error);
        }
        output.writeNoException();
        return true;
    }

    private void armTimeout() {
        handler.removeCallbacks(timeout);
        handler.postDelayed(timeout, SENSOR_TIMEOUT_MS);
    }

    private synchronized void close() {
        closed = true;
        stop();
    }

    private synchronized void stop(IBinder token) {
        if (owner == token) stop();
    }

    private synchronized void stop() {
        handler.removeCallbacks(timeout);
        if (owner != null && death != null) owner.unlinkToDeath(death, 0);
        owner = null;
        death = null;
        if (tracker != null) tracker.close();
        tracker = null;
    }

    static Client start(Context context, String address) throws Exception {
        // Called on a Binder thread; the ordered broadcast completes on SystemUI's main thread.
        CompletableFuture<IBinder> result = new CompletableFuture<>();
        context.sendOrderedBroadcast(new Intent(ACTION).setPackage(HubWire.AOSP_HOST)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND), null, new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                Bundle extras = getResultExtras(false);
                result.complete(getResultCode() == 1 && extras != null ? extras.getBinder("tracker") : null);
            }
        }, null, 0, null, null);
        IBinder endpoint = result.get(1200, TimeUnit.MILLISECONDS);
        if (endpoint == null) throw new IllegalStateException("AOSP Bluetooth head tracker bridge unavailable");
        Client client = new Client(endpoint);
        client.send(CREATE, address, null);
        return client;
    }

    static final class Client {
        private final Binder token = new Binder();
        private final IBinder endpoint;

        Client(IBinder endpoint) { this.endpoint = endpoint; }
        void pose(float[] vector) throws RemoteException { send(POSE, null, vector); }
        void stop() throws RemoteException { send(STOP, null, null); }

        private void send(int code, String address, float[] vector) throws RemoteException {
            Parcel input = Parcel.obtain();
            Parcel output = Parcel.obtain();
            try {
                input.writeInterfaceToken(ACTION);
                input.writeStrongBinder(token);
                if (code == CREATE) input.writeString(address);
                if (code == POSE) input.writeFloatArray(vector);
                if (!endpoint.transact(code, input, output, 0)) throw new RemoteException("Head tracker bridge unavailable");
                output.readException();
            } finally {
                output.recycle();
                input.recycle();
            }
        }
    }
}
