package com.linguareader.shared.update;

import java.net.URLConnection;

/** API-1-only length lookup: URLConnection.getContentLengthLong requires Android API 24. */
public final class ReleaseContentLength {
    private ReleaseContentLength() {}

    /** Returns a nonnegative 64-bit header length, or the API-1 int fallback, or -1 when unknown. */
    public static long read(URLConnection connection) {
        String header = connection.getHeaderField("Content-Length");
        if (header != null) {
            try {
                long parsed = Long.parseLong(header.trim());
                if (parsed >= 0) return parsed;
            } catch (NumberFormatException ignored) {
                // Malformed/overflowing headers retain the standard unknown-length fallback.
            }
        }
        int fallback = connection.getContentLength();
        return fallback >= 0 ? (long) fallback : -1L;
    }
}
