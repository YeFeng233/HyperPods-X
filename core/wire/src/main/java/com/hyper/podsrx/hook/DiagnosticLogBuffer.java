package com.hyper.podsrx.hook;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;

/** Whole records, bounded by UTF-8 bytes and count, independently in each process. */
public final class DiagnosticLogBuffer {
    private final int maxBytes;
    private final int maxRecords;
    private final ArrayDeque<String> records = new ArrayDeque<>();
    private int bytes;

    public DiagnosticLogBuffer(int maxBytes, int maxRecords) {
        if (maxBytes < 2 || maxRecords < 1) throw new IllegalArgumentException("Invalid log capacity");
        this.maxBytes = maxBytes;
        this.maxRecords = maxRecords;
    }

    public synchronized String add(String record) {
        String clean = LogRedactor.limitUtf8(LogRedactor.redact(record), Math.min(8192, maxBytes - 1)) + "\n";
        int size = clean.getBytes(StandardCharsets.UTF_8).length;
        while (!records.isEmpty() && (bytes + size > maxBytes || records.size() >= maxRecords)) {
            bytes -= records.removeFirst().getBytes(StandardCharsets.UTF_8).length;
        }
        records.addLast(clean);
        bytes += size;
        return clean;
    }

    public synchronized String snapshot() { return String.join("", records); }
}
