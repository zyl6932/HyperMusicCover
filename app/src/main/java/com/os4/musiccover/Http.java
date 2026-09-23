package com.os4.musiccover;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * One GET, with the connection left open for the next.
 *
 * Shared by the by-name lyric routes, which are the only things here that talk to a catalogue:
 * NcmLyrics and WebLyrics. They ask different hosts but they ask them the same way - a plain
 * GET, no key, no cookie, a few seconds at most - and the connection handling is the half that
 * is easy to get subtly wrong, so it lives in one place.
 *
 * disconnect() is deliberately not called on a request that worked: consecutive requests here go
 * to the same host, and leaving the connection in the keep-alive pool is what makes the second
 * one cost 48-66ms instead of the ~250ms the first one does. The saving is the DNS lookup, the
 * TCP handshake and the TLS handshake, measured at ~50ms of TLS alone on this device.
 */
final class Http {

    private Http() {
    }

    /**
     * What came back: the body, and the status it came with.
     *
     * The status is here for the one caller that has to tell an honest "no" from a request that
     * never arrived - LrcLib answers 404 for a song it does not have, and a song that is
     * genuinely absent is worth remembering as absent where a network's bad minute is not.
     */
    static final class Reply {
        /** The body, or null when there is none to read - including for a 404. */
        final String body;
        /** The HTTP status, or 0 when the request did not arrive at all. */
        final int code;

        Reply(String body, int code) {
            this.body = body;
            this.code = code;
        }

        boolean ok() {
            return body != null;
        }
    }

    private static final int CONNECT_TIMEOUT_MS = 4000;
    private static final int READ_TIMEOUT_MS = 6000;

    /** `tag` is the caller's log prefix, so one account of a failed request says whose it was. */
    static Reply get(String url, String tag) {
        long started = android.os.SystemClock.uptimeMillis();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            // Named rather than left to the platform's default: LrcLib asks callers to identify
            // themselves, and an unattributed request is one its operators cannot tell apart
            // from a scraper.
            conn.setRequestProperty("User-Agent", "HyperMusicCover");
            int code = conn.getResponseCode();
            if (code != 200) {
                Xp.log("[" + tag + "] HTTP " + code + " in "
                        + (android.os.SystemClock.uptimeMillis() - started) + "ms");
                return new Reply(null, code);
            }
            return new Reply(read(conn.getInputStream()), 200);
        } catch (Throwable t) {
            Xp.log("[" + tag + "] request failed after "
                    + (android.os.SystemClock.uptimeMillis() - started) + "ms: " + t);
            // Only a connection that failed is torn down; a healthy one stays pooled.
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
            return new Reply(null, 0);
        }
    }

    /**
     * The same request with the parts the other catalogues need: a body to POST (QQ Music takes
     * its queries as JSON), headers of their own (Kuwo's lyric host answers only a client it
     * recognises), and the body as bytes (Kuwo's is compressed and not UTF-8). `postBody` null is
     * a GET; `headers` are name, value pairs. Null body and code 0 when nothing arrived.
     */
    static Raw request(String url, String tag, String postBody, String... headers) {
        long started = android.os.SystemClock.uptimeMillis();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "HyperMusicCover");
            for (int i = 0; i + 1 < headers.length; i += 2) {
                conn.setRequestProperty(headers[i], headers[i + 1]);
            }
            if (postBody != null) {
                byte[] b = postBody.getBytes("UTF-8");
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(b.length);
                java.io.OutputStream os = conn.getOutputStream();
                os.write(b);
                os.close();
            }
            int code = conn.getResponseCode();
            if (code != 200) {
                Xp.log("[" + tag + "] HTTP " + code + " in "
                        + (android.os.SystemClock.uptimeMillis() - started) + "ms");
                return new Raw(null, code);
            }
            return new Raw(bytes(conn.getInputStream()), 200);
        } catch (Throwable t) {
            Xp.log("[" + tag + "] request failed after "
                    + (android.os.SystemClock.uptimeMillis() - started) + "ms: " + t);
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
            return new Raw(null, 0);
        }
    }

    /** request()'s answer: the bytes as they came, and the status. */
    static final class Raw {
        final byte[] body;
        final int code;

        Raw(byte[] body, int code) {
            this.body = body;
            this.code = code;
        }

        boolean ok() {
            return body != null;
        }

        String text() {
            try {
                return body == null ? null : new String(body, "UTF-8");
            } catch (Exception e) {
                return null;
            }
        }
    }

    private static String read(InputStream in) throws Exception {
        return new String(bytes(in), "UTF-8");
    }

    private static byte[] bytes(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(32768);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        // Read to the end and closed, not disconnected: that is the condition for the socket to
        // go back to the pool rather than be thrown away.
        in.close();
        return out.toByteArray();
    }
}
