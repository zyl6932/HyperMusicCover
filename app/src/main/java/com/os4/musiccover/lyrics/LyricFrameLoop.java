package com.os4.musiccover.lyrics;

/** One animation callback, including when drawing synchronously re-enters the host. */
final class LyricFrameLoop implements Runnable {
    interface Host {
        void enqueue(Runnable frame);
        void cancel(Runnable frame);
        boolean frame();
        void idle();
    }

    private final Host host;
    private boolean queued, running, requested;
    private int generation;

    LyricFrameLoop(Host host) {
        this.host = host;
    }

    boolean active() {
        return queued || running;
    }

    void request() {
        if (queued) return;
        if (running) {
            requested = true;
            return;
        }
        queued = true;
        host.enqueue(this);
    }

    void stop() {
        generation++;
        queued = requested = false;
        host.cancel(this);
        if (!running) host.idle();
    }

    @Override public void run() {
        if (!queued || running) return;
        queued = false;
        running = true;
        requested = false;
        int entered = generation;
        boolean more;
        try {
            more = host.frame();
        } finally {
            running = false;
        }
        // A detach/park during a synchronous traversal must not start the loop again.
        if (entered == generation && (more || requested)) request();
        else if (!queued) host.idle();
    }
}
