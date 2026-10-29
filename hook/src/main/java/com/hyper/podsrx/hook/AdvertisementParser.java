package com.hyper.podsrx.hook;

import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import com.hyper.podsrx.protocol.AppleAdvertisement;
import com.hyper.podsrx.protocol.Battery;
import com.hyper.podsrx.protocol.CaseStatusCodec;
import com.hyper.podsrx.protocol.Proximity;
import com.hyper.podsrx.protocol.ProximityCodec;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns Apple advertisements into the bound headset's readings. The proximity message must come
 * from the bound identity; the case status uses a rotating address the IRK cannot resolve, so it
 * is recognised by decrypting with the proximity key instead. Main thread only.
 */
final class AdvertisementParser {
    interface Sink {
        void proximity(Proximity status, long capturedAt, String source);
        void caseStatus(List<Battery> values, String source);
    }

    int broadcasts, trusted, candidates, matches;
    /** Case status messages heard, and those the bound headset's key decrypted. */
    int caseMessages, caseDecoded;

    void parse(ScanResult result, String address, byte[] irk, byte[] enc, String source, Sink sink) {
        ScanRecord record = result.getScanRecord();
        if (record == null || address.isEmpty()) return;
        String from = result.getDevice().getAddress();
        boolean bound = address.equalsIgnoreCase(from) || (irk != null && ProximityCodec.INSTANCE.matchesIdentity(from, irk));
        if (bound) matches++;
        byte[] data = record.getManufacturerSpecificData(0x004c);
        if (data == null) return;
        broadcasts++;
        List<byte[]> messages = AppleAdvertisement.INSTANCE.proximityMessages(data);
        candidates += messages.size();
        List<byte[]> proximity = new ArrayList<>();
        for (byte[] message : messages) {
            if (message[1] == CaseStatusCodec.LENGTH) {
                caseMessages++;
                List<Battery> values = CaseStatusCodec.INSTANCE.decode(message, enc);
                if (values == null) continue;
                caseDecoded++;
                List<Battery> known = new ArrayList<>();
                for (Battery value : values) if (value.getLevel() != null) known.add(value);
                sink.caseStatus(known, source);
            } else proximity.add(message);
        }
        if (!bound) return;
        long capturedAt = result.getTimestampNanos() / 1_000_000;
        for (byte[] message : proximity) {
            Proximity status = ProximityCodec.INSTANCE.decode(message, enc);
            if (status == null) continue;
            trusted++;
            sink.proximity(status, capturedAt, source);
        }
    }
}
