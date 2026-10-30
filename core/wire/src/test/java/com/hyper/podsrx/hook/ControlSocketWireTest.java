package com.hyper.podsrx.hook;

import android.os.Binder;
import android.os.Parcel;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public class ControlSocketWireTest {
    private static class FakeChannel implements ControlSocketWire.Channel {
        boolean connected, closed;
        byte[] written;
        @Override public void connect() throws IOException {
            if (closed) throw new IOException("Closed");
            connected = true;
        }
        @Override public boolean isConnected() { return connected && !closed; }
        @Override public int read(byte[] buffer) throws IOException {
            if (!isConnected()) throw new IOException("Closed");
            byte[] report = {4, 0, 4, 0, 6, 0, 0, 1};
            int count = Math.min(buffer.length, report.length);
            System.arraycopy(report, 0, buffer, 0, count);
            return count;
        }
        @Override public void write(byte[] packet) throws IOException {
            if (!isConnected()) throw new IOException("Closed");
            written = packet.clone();
        }
        @Override public void close() { closed = true; }
    }

    @Test public void transportsExactBytesAndCloses() throws Exception {
        FakeChannel channel = new FakeChannel();
        var server = new ControlSocketWire.Server(Binder.getCallingUid(), channel);
        var client = new ControlSocketWire.Client(server);
        client.connect();
        assertTrue(client.isConnected());
        byte[] packet = {4, 0, 4, 0, 15, 0, -1, -1, -1, -1};
        client.write(packet);
        assertArrayEquals(packet, channel.written);
        byte[] buffer = new byte[128];
        assertEquals(8, client.read(buffer));
        assertArrayEquals(new byte[]{4,0,4,0,6,0,0,1}, Arrays.copyOf(buffer,8));
        client.close();
        client.close();
        assertTrue(channel.closed);
        assertFalse(client.isConnected());
        assertThrows(IOException.class, () -> client.write(packet));
    }

    @Test public void hostRevocationRejectsAnOldEndpoint() throws Exception {
        var server = new ControlSocketWire.Server(Binder.getCallingUid(), new FakeChannel());
        var client = new ControlSocketWire.Client(server);
        client.connect();
        server.close();
        assertThrows(IOException.class, () -> client.read(new byte[20]));
        assertThrows(IOException.class, client::connect);
    }

    @Test public void anotherUidCannotOpenOrClose() {
        FakeChannel channel = new FakeChannel();
        var server = new ControlSocketWire.Server(Binder.getCallingUid() + 1, channel);
        Parcel in = Parcel.obtain(), out = Parcel.obtain();
        try {
            in.writeInterfaceToken(ControlSocketWire.DESCRIPTOR);
            in.setDataPosition(0);
            assertThrows(SecurityException.class, () -> server.onTransact(ControlSocketWire.CONNECT,in,out,0));
            in.setDataPosition(0);
            assertThrows(SecurityException.class, () -> server.onTransact(ControlSocketWire.CLOSE,in,out,0));
            assertFalse(channel.connected);
            assertFalse(channel.closed);
        } finally { in.recycle(); out.recycle(); }
    }

    @Test public void rejectsOversizedRequestsBeforeIo() {
        FakeChannel channel = new FakeChannel();
        var server = new ControlSocketWire.Server(Binder.getCallingUid(), channel);
        Parcel in = Parcel.obtain(), out = Parcel.obtain();
        try {
            in.writeInterfaceToken(ControlSocketWire.DESCRIPTOR);
            in.writeInt(ControlSocketWire.MAX_READ+1);
            in.setDataPosition(0);
            assertThrows(IllegalArgumentException.class, () -> server.onTransact(ControlSocketWire.READ,in,out,0));
            in.setDataSize(0); in.setDataPosition(0);
            in.writeInterfaceToken(ControlSocketWire.DESCRIPTOR);
            in.writeByteArray(new byte[ControlSocketWire.MAX_WRITE+1]);
            in.setDataPosition(0);
            assertThrows(IllegalArgumentException.class, () -> server.onTransact(ControlSocketWire.WRITE,in,out,0));
            assertNull(channel.written);
        } finally { in.recycle(); out.recycle(); }
    }

    @Test public void closeCanCancelABlockedConnect() throws Exception {
        CountDownLatch started = new CountDownLatch(1), stopped = new CountDownLatch(1);
        FakeChannel channel = new FakeChannel() {
            @Override public void connect() throws IOException {
                started.countDown();
                try { if (!stopped.await(3,TimeUnit.SECONDS)) throw new IOException("Timeout"); }
                catch (InterruptedException e) { throw new IOException(e); }
                throw new IOException("Cancelled");
            }
            @Override public void close() { super.close(); stopped.countDown(); }
        };
        var server = new ControlSocketWire.Server(Binder.getCallingUid(), channel);
        var client = new ControlSocketWire.Client(server);
        Thread worker = new Thread(() -> assertThrows(IOException.class, client::connect));
        worker.start();
        assertTrue(started.await(2,TimeUnit.SECONDS));
        client.close();
        worker.join(2000);
        assertFalse(worker.isAlive());
        assertFalse(client.isConnected());
    }

    @Test public void closeCanCancelABlockedRead() throws Exception {
        CountDownLatch reading = new CountDownLatch(1), stopped = new CountDownLatch(1);
        FakeChannel channel = new FakeChannel() {
            @Override public int read(byte[] buffer) throws IOException {
                reading.countDown();
                try { if (!stopped.await(3,TimeUnit.SECONDS)) throw new IOException("Timeout"); }
                catch (InterruptedException e) { throw new IOException(e); }
                return -1;
            }
            @Override public void close() { super.close(); stopped.countDown(); }
        };
        var client = new ControlSocketWire.Client(new ControlSocketWire.Server(Binder.getCallingUid(), channel));
        client.connect();
        java.util.concurrent.atomic.AtomicBoolean failed = new java.util.concurrent.atomic.AtomicBoolean();
        Thread reader = new Thread(() -> {
            try { client.read(new byte[32]); }
            catch (IOException expected) { failed.set(true); }
        });
        reader.start();
        assertTrue(reading.await(2,TimeUnit.SECONDS));
        client.close();
        reader.join(2000);
        assertFalse(reader.isAlive());
        assertTrue(failed.get());
        assertFalse(client.isConnected());
    }
}
