package com.os4.musiccover.lyrics;
import org.junit.Test;
import static org.junit.Assert.*;

public class LyriconAvailabilityTest {
    @Test public void initialProviderBeforeConnectedIsBufferedButNotAssumedRunning() {
        LyriconAvailability state = new LyriconAvailability();
        state.connection("connecting");
        state.provider(true);
        assertFalse(state.observed());
        assertFalse(state.active());
        state.connection("connected");
        assertTrue(state.observed());
        assertTrue(state.active());
        assertEquals("active", state.status());
    }
    @Test public void noPackageInferenceBeforeFrameworkHandshake() {
        LyriconAvailability state = new LyriconAvailability();
        state.connection("connecting");
        state.provider(true);
        assertFalse(state.observed());
        assertFalse(state.active());
        state.connection("no service");
        assertEquals("no service", state.status());
        assertFalse(state.connected());
    }
    @Test public void disconnectRetainsDetectionButNeverClaimsRunning() {
        LyriconAvailability state = new LyriconAvailability();
        state.connection("connected");
        assertTrue(state.observed());
        assertEquals("connected", state.status());
        state.provider(true);
        assertEquals("active", state.status());
        state.connection("disconnected");
        assertTrue(state.observed());
        assertFalse(state.active());
        assertFalse(state.connected());
        state.connection("reconnected");
        assertEquals("connected", state.status());
        state.provider(true);
        assertTrue(state.active());
        state.provider(false);
        assertEquals("connected", state.status());
    }
}
