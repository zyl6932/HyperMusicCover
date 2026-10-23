package com.os4.musiccover.lyrics;

import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import org.junit.Test;
import static org.junit.Assert.*;

public class LyricHttpTest {
    @Test public void disabledNetworkNeverConnectsEvenForDirectFollowUpRequests() throws Exception {
        LyricSourcePolicy.Config previous = LyricSourcePolicy.current;
        try (ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            LyricSourcePolicy.current = new LyricSourcePolicy.Config(false, false, null, null);
            String url = "http://127.0.0.1:" + server.getLocalPort() + "/lyrics";
            assertNull(LyricHttp.get(url, "test").body);
            assertEquals(0, LyricHttp.request(url, "test", "{}").code);
            server.setSoTimeout(200);
            assertThrows(SocketTimeoutException.class, server::accept);
        } finally {
            LyricSourcePolicy.current = previous;
        }
    }
}
