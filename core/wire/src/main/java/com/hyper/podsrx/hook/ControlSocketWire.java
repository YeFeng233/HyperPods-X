package com.hyper.podsrx.hook;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import java.io.Closeable;
import java.io.IOException;

/** A private, bounded byte transport. Protocol parsing and retries stay with the app session. */
public final class ControlSocketWire {
    private ControlSocketWire() {}
    public static final int VERSION = 1;
    public static final String DESCRIPTOR = HubWire.APP + ".ControlSocket.v1";
    public static final String EXTRA_VERSION = "controlSocketVersion";
    public static final String EXTRA_SOCKET = "controlSocket";
    public static final int CONNECT = 1, READ = 2, WRITE = 3, CLOSE = 4;
    public static final int MAX_READ = 65536, MAX_WRITE = 4096;

    public interface Channel extends Closeable {
        void connect() throws IOException;
        boolean isConnected();
        int read(byte[] buffer) throws IOException;
        void write(byte[] packet) throws IOException;
    }

    public static final class Server extends Binder implements Closeable {
        private final int appUid;
        private final Channel channel;

        public Server(int appUid, Channel channel) {
            this.appUid = appUid;
            this.channel = channel;
            attachInterface(null, DESCRIPTOR);
        }

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(DESCRIPTOR);
                return true;
            }
            if (Binder.getCallingUid() != appUid) throw new SecurityException("Unsupported caller");
            data.enforceInterface(DESCRIPTOR);
            if (reply == null) return false;
            // The Bluetooth stack must see the host's identity, not the originating app's Binder UID.
            long identity = Binder.clearCallingIdentity();
            try {
                byte[] result = null;
                switch (code) {
                    case CONNECT -> channel.connect();
                    case READ -> {
                        int size = data.readInt();
                        if (size < 1 || size > MAX_READ) throw new IllegalArgumentException("Invalid read size");
                        byte[] buffer = new byte[size];
                        int count = channel.read(buffer);
                        if (count <= 0 || count > size) throw new IOException("Control channel closed");
                        result = java.util.Arrays.copyOf(buffer, count);
                    }
                    case WRITE -> {
                        if (data.dataAvail() > MAX_WRITE + 4) throw new IllegalArgumentException("Packet too large");
                        byte[] packet = data.createByteArray();
                        if (packet == null || packet.length < 1 || packet.length > MAX_WRITE)
                            throw new IllegalArgumentException("Invalid packet size");
                        channel.write(packet);
                    }
                    case CLOSE -> close();
                    default -> { return false; }
                }
                reply.writeNoException();
                reply.writeInt(HubWire.OK);
                if (code == READ) reply.writeByteArray(result);
            } catch (IOException error) {
                reply.writeNoException();
                reply.writeInt(HubWire.FAILED);
                reply.writeString(error.getClass().getSimpleName());
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
            return true;
        }

        @Override public void close() throws IOException { channel.close(); }
    }

    public static final class Client implements Channel {
        private final IBinder remote;
        private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
        private volatile boolean connected;

        public Client(IBinder remote) { this.remote = remote; }
        @Override public boolean isConnected() { return connected && !closed.get() && remote.isBinderAlive(); }
        @Override public void connect() throws IOException {
            transact(CONNECT, null, 0);
            connected = !closed.get();
            if (!connected) throw new IOException("Control channel closed");
        }
        @Override public int read(byte[] buffer) throws IOException {
            if (buffer.length == 0) return 0;
            byte[] result = transact(READ, null, Math.min(buffer.length, MAX_READ));
            if (result == null || result.length < 1 || result.length > buffer.length)
                throw new IOException("Invalid control read");
            System.arraycopy(result, 0, buffer, 0, result.length);
            return result.length;
        }
        @Override public void write(byte[] packet) throws IOException {
            if (packet.length < 1 || packet.length > MAX_WRITE) throw new IOException("Invalid packet size");
            transact(WRITE, packet, 0);
        }
        @Override public void close() throws IOException {
            if (!closed.compareAndSet(false, true)) return;
            connected = false;
            transact(CLOSE, null, 0);
        }

        private byte[] transact(int code, byte[] packet, int size) throws IOException {
            if (code != CLOSE && closed.get()) throw new IOException("Control channel closed");
            Parcel input = Parcel.obtain(), output = Parcel.obtain();
            try {
                input.writeInterfaceToken(DESCRIPTOR);
                if (code == READ) input.writeInt(size);
                if (code == WRITE) input.writeByteArray(packet);
                if (!remote.transact(code, input, output, 0)) throw new IOException("Unsupported control transport");
                output.readException();
                if (output.readInt() != HubWire.OK) {
                    connected = false;
                    throw new IOException("Host control transport: " + output.readString());
                }
                return code == READ ? output.createByteArray() : null;
            } catch (RemoteException | RuntimeException error) {
                connected = false;
                throw new IOException("Host control transport unavailable", error);
            } finally {
                input.recycle(); output.recycle();
            }
        }
    }
}
