package com.hyper.podsrx.hook;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** Shared by the app and scoped hosts, before a record enters any log buffer. */
public final class LogRedactor {
    private static final Pattern ADDRESS = Pattern.compile("(?i)(?<![0-9a-f])(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}(?![0-9a-f])");
    private static final Pattern SECRET = Pattern.compile(
            "(?i)((?<![a-z0-9_])(?:irk|enc(?:ryption)?[_ -]?key|enc|identity[_ -]?key|link[_ -]?key|serial(?:[_ -]?number)?|password|token)[\\\"']?\\s*[:=]\\s*)"
            + "(\\\"[^\\\"\\r\\n]*\\\"|'[^'\\r\\n]*'|\\[[^\\]\\r\\n]*\\]|(?:[0-9a-f]{2}[ ,:-]+){7,}[0-9a-f]{2}|[^\\s,;}\\]]+)");
    private static final Pattern LONG_HEX = Pattern.compile("(?i)(?<![0-9a-f])[0-9a-f]{32,}(?![0-9a-f])");

    private LogRedactor() { }

    public static String redact(String text) {
        if (text == null) return "";
        String clean = SECRET.matcher(text).replaceAll("$1[REDACTED]");
        clean = ADDRESS.matcher(clean).replaceAll("[DEVICE_ADDRESS]");
        return LONG_HEX.matcher(clean).replaceAll("[REDACTED_HEX]");
    }

    /** Keep valid UTF-8 when enforcing both disk and Binder payload limits. */
    public static String limitUtf8(String text, int maxBytes) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) return text;
        int end = Math.min(maxBytes, bytes.length);
        while (end > 0 && (bytes[end] & 0xc0) == 0x80) end--;
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }
}
