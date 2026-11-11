package com.os4.musiccover.lyrics;

import org.junit.Test;
import java.util.ArrayDeque;
import static org.junit.Assert.*;

public class LyricFrameLoopTest {
    private static final class Host implements LyricFrameLoop.Host {
        final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        final LyricFrameLoop loop = new LyricFrameLoop(this);
        Runnable duringFrame = () -> {};
        boolean more = true;
        int frames, idles;

        public void enqueue(Runnable frame) { queue.add(frame); }
        public void cancel(Runnable frame) { queue.removeIf(r -> r == frame); }
        public boolean frame() { frames++; duringFrame.run(); return more; }
        public void idle() { idles++; }
        void next() { queue.remove().run(); }
    }

    @Test public void synchronousHdrTraversalCannotMultiplyTheFrameQueue() {
        Host host = new Host();
        // drawFirst -> doTraversal -> preDraw -> kick, before the outer frame returns.
        host.duringFrame = () -> {
            assertTrue(host.loop.active());
            host.loop.request();
            host.loop.request();
        };
        host.loop.request();
        for (int i = 0; i < 2400; i++) {
            assertEquals(1, host.queue.size());
            host.next();
            assertEquals(1, host.queue.size());
        }
        assertEquals(2400, host.frames);
    }

    @Test public void pauseDrainsTheLoopAndAPlaybackChangeCanRestartIt() {
        Host host = new Host();
        host.loop.request();
        host.loop.request();
        host.next();
        host.more = false;
        host.next();
        assertTrue(host.queue.isEmpty());
        assertFalse(host.loop.active());
        assertEquals(1, host.idles);
        host.more = true;
        host.loop.request();
        assertEquals(1, host.queue.size());
        host.next();
        assertEquals(1, host.queue.size());
    }

    @Test public void parkOrDetachDuringTraversalCannotRepostAFrame() {
        Host host = new Host();
        host.duringFrame = host.loop::stop;
        host.loop.request();
        host.next();
        assertFalse(host.loop.active());
        assertTrue(host.queue.isEmpty());
        host.duringFrame = () -> {};
        host.loop.request();
        assertEquals(1, host.queue.size());
        host.loop.stop();
        assertTrue(host.queue.isEmpty());
        // A stale callback already extracted by Choreographer does no work after cancellation.
        host.loop.run();
        assertEquals(1, host.frames);
    }

    @Test public void aRedrawRequestedDuringTheLastFrameIsKeptExactlyOnce() {
        Host host = new Host();
        host.more = false;
        host.duringFrame = host.loop::request;
        host.loop.request();
        host.next();
        assertEquals(1, host.queue.size());
        host.duringFrame = () -> {};
        host.next();
        assertTrue(host.queue.isEmpty());
        assertFalse(host.loop.active());
    }
}
