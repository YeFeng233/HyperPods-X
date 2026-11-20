package com.hyper.podsrx.hook;

import android.os.Handler;
import android.os.MessageQueue;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/** Android head tracker HID v1 over the Linux UHID API. All structures use the packed kernel ABI. */
final class UhidHeadTracker implements AutoCloseable {
    private static final int DESTROY = 1, GET_REPORT = 9, GET_REPORT_REPLY = 10;
    private static final int CREATE2 = 11, INPUT2 = 12, SET_REPORT = 13, SET_REPORT_REPLY = 14;
    private static final int BUS_BLUETOOTH = 5;
    private static final int CONFIG_REPORT = 1, IDENTITY_REPORT = 2, MOTION_REPORT = 3;
    private static final byte[] DESCRIPTION = "#AndroidHeadTracker#1.0".getBytes(StandardCharsets.US_ASCII);
    // Sensors / Other: Custom. Feature reports carry the description, UUID, and reporting controls.
    // The input report carries three orientation axes, three velocity axes, and a reset counter.
    private static final byte[] DESCRIPTOR = HexFormat.of().parseHex(
        "052009e1a101"
        + "85020a0803150026ff0075089517b1030a0203150026ff0075089510b103"
        + "85010a16031500250175019501a1020a40080a4108b100c0"
        + "0a19031500250175019501a1020a55080a5108b100c0"
        + "0a0e031500253f350a456475069501660110550db102"
        + "85030a440516018026ff7f37604f46ed47a1b0b91265005508751095038102"
        + "0a450516018026ff7f35e045205500751095038102"
        + "0a4605150026ff00350045005500750895018102c0"
    );

    private final FileDescriptor fd;
    private final MessageQueue queue;
    private final byte[] identity;
    private final byte[] events = new byte[4380];
    private int config;
    private long lastReport;
    private boolean closed;

    UhidHeadTracker(String address, Handler handler) throws IOException {
        queue = handler.getLooper().getQueue();
        identity = ByteBuffer.allocate(1 + DESCRIPTION.length + 16)
            .put((byte) IDENTITY_REPORT).put(DESCRIPTION).putLong(0).put((byte) 'B').put((byte) 'T')
            .put(HexFormat.of().parseHex(address.replace(":", ""))).array();
        try { fd = Os.open("/dev/uhid", OsConstants.O_RDWR | OsConstants.O_CLOEXEC | OsConstants.O_NONBLOCK, 0); }
        catch (ErrnoException error) { throw new IOException("Cannot open /dev/uhid", error); }
        try {
            ByteBuffer create = packet(CREATE2, 276 + DESCRIPTOR.length);
            create.put("HyperPods Head Tracker".getBytes(StandardCharsets.US_ASCII));
            create.position(4 + 128 + 64);
            create.put(address.getBytes(StandardCharsets.US_ASCII));
            create.position(4 + 256);
            create.putShort((short) DESCRIPTOR.length).putShort((short) BUS_BLUETOOTH);
            create.putInt(0).putInt(0).putInt(1).putInt(0).put(DESCRIPTOR);
            write(create);
            queue.addOnFileDescriptorEventListener(fd, MessageQueue.OnFileDescriptorEventListener.EVENT_INPUT,
                (ignored, event) -> readEvent());
        } catch (IOException | RuntimeException error) {
            close();
            throw error;
        }
    }

    synchronized void pose(float[] rotation) throws IOException {
        if (closed) throw new IOException("Head tracker device closed");
        if ((config & 3) != 3) return;
        long now = SystemClock.elapsedRealtime();
        long interval = 10 + (config >>> 2) * 90 / 63;
        if (now - lastReport < interval) return;
        ByteBuffer report = packet(INPUT2, 16);
        report.putShort((short) 14).put((byte) MOTION_REPORT);
        // MotionCalibration already places the vector in Android's head-tracker frame.
        for (float axis : rotation) {
            double normalized = Math.max(-1, Math.min(1, axis / Math.PI));
            report.putShort((short) Math.round(normalized * 32767));
        }
        report.putShort((short) 0).putShort((short) 0).putShort((short) 0).put((byte) 0);
        write(report);
        lastReport = now;
    }

    private synchronized int readEvent() {
        if (closed) return 0;
        try {
            int length = Os.read(fd, events, 0, events.length);
            if (length < 4) return MessageQueue.OnFileDescriptorEventListener.EVENT_INPUT;
            ByteBuffer event = ByteBuffer.wrap(events, 0, length).order(ByteOrder.nativeOrder());
            int type = event.getInt();
            if (type == GET_REPORT && length >= 10) {
                int id = event.getInt();
                int number = Byte.toUnsignedInt(event.get());
                boolean feature = event.get() == 0;
                byte[] payload = !feature ? null : switch (number) {
                    case IDENTITY_REPORT -> identity;
                    case CONFIG_REPORT -> new byte[]{CONFIG_REPORT, (byte) config};
                    default -> null;
                };
                int size = payload == null ? 0 : payload.length;
                ByteBuffer reply = packet(GET_REPORT_REPLY, 8 + size);
                reply.putInt(id).putShort((short) (payload == null ? OsConstants.EINVAL : 0)).putShort((short) size);
                if (payload != null) reply.put(payload);
                write(reply);
            } else if (type == SET_REPORT && length >= 12) {
                int id = event.getInt();
                boolean feature = event.get() == CONFIG_REPORT && event.get() == 0;
                int size = Short.toUnsignedInt(event.getShort());
                boolean valid = feature && size == 2 && length >= 14 && event.get() == CONFIG_REPORT;
                if (valid) config = Byte.toUnsignedInt(event.get());
                write(packet(SET_REPORT_REPLY, 6).putInt(id).putShort((short) (valid ? 0 : OsConstants.EINVAL)));
            }
        } catch (ErrnoException error) {
            if (error.errno != OsConstants.EAGAIN && error.errno != OsConstants.EINTR) {
                close();
                DiagnosticLog.w("HyperPods-Spatial", "UHID event read failed", error);
                return 0;
            }
        } catch (IOException error) {
            close();
            DiagnosticLog.w("HyperPods-Spatial", "UHID report reply failed", error);
            return 0;
        }
        return MessageQueue.OnFileDescriptorEventListener.EVENT_INPUT;
    }

    private static ByteBuffer packet(int type, int payloadLength) {
        return ByteBuffer.allocate(4 + payloadLength).order(ByteOrder.nativeOrder()).putInt(type);
    }

    private void write(ByteBuffer packet) throws IOException {
        try {
            if (Os.write(fd, packet.array(), 0, packet.position()) != packet.position())
                throw new IOException("Short UHID event write");
        } catch (ErrnoException error) { throw new IOException("UHID event write failed", error); }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        queue.removeOnFileDescriptorEventListener(fd);
        try { write(packet(DESTROY, 0)); } catch (IOException ignored) {}
        try { Os.close(fd); } catch (ErrnoException ignored) {}
    }
}
