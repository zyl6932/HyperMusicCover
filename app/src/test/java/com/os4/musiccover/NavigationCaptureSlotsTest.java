package com.os4.musiccover;

import org.junit.Test;
import static org.junit.Assert.*;

public class NavigationCaptureSlotsTest {
    @Test public void hungNativeRequestAllowsOneReplacementButCannotAccumulateUnboundedRetries() {
        NavigationCaptureSlots<Object> slots = new NavigationCaptureSlots<>();
        Object hung = new Object(), replacement = new Object();
        assertTrue(slots.acquire(hung));
        // A timeout expires a result, but does not claim that its native callback completed.
        assertTrue(slots.acquire(replacement));
        for (int retries = 0; retries < 100; retries++) assertFalse(slots.acquire(new Object()));
        assertEquals(2, slots.size());
    }

    @Test public void lateCallbackFreesOnlyItsOwnSlotAndCannotPublishTwice() {
        NavigationCaptureSlots<Object> slots = new NavigationCaptureSlots<>();
        Object old = new Object(), active = new Object(), next = new Object();
        slots.acquire(old);
        slots.acquire(active);
        assertTrue(slots.finish(old));
        assertFalse(slots.finish(old));
        assertFalse(slots.finish(new Object()));
        assertEquals(1, slots.size());
        assertTrue(slots.acquire(next));
        assertEquals(2, slots.size());
        assertTrue(slots.finish(active));
        assertTrue(slots.finish(next));
        assertEquals(0, slots.size());
    }
}
