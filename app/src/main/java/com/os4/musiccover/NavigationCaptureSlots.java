package com.os4.musiccover;

import java.util.HashSet;

/** Main-thread ownership of native requests, including timed-out requests awaiting a callback. */
final class NavigationCaptureSlots<T> {
    private final HashSet<T> outstanding = new HashSet<>();
    boolean acquire(T request) {
        return outstanding.size() < 2 && outstanding.add(request);
    }
    boolean finish(T request) { return outstanding.remove(request); }
    int size() { return outstanding.size(); }
}
