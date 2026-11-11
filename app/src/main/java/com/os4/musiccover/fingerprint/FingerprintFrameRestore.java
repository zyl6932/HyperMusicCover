package com.os4.musiccover.fingerprint;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;

/** Frames replaced on a fingerprint surface must be repainted when hiding ends. */
public final class FingerprintFrameRestore<T> {
    private final Map<T, Integer> frames = new WeakHashMap<>();

    public synchronized void suppressed(T animation, int resource) {
        frames.put(animation, resource);
    }

    public synchronized void painted(T animation) {
        frames.remove(animation);
    }

    public synchronized int pendingCount() {
        return frames.size();
    }

    public void restore(BiConsumer<T, Integer> draw) {
        Map<T, Integer> snapshot;
        synchronized (this) {
            snapshot = new java.util.HashMap<>(frames);
            frames.clear();
        }
        snapshot.forEach(draw);
    }
}
