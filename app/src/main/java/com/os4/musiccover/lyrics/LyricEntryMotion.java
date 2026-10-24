package com.os4.musiccover.lyrics;

/** Page motion follows an explicit page request, never a clock/container opacity sample. */
public final class LyricEntryMotion {
    private float progress, target;
    private boolean admitted;
    public boolean step(boolean requested, boolean ready, float dt, boolean cut) {
        if (requested && ready) admitted = true;
        target = requested && admitted ? 1f : 0f;
        float previous = progress;
        if (cut) progress = target;
        else if (Float.isFinite(dt) && dt > 0f) {
            float tau = target > progress ? 0.16f : 0.12f;
            progress = target + (progress - target) * (float) Math.exp(-Math.min(dt, 0.05f) / tau);
            if (Math.abs(progress - target) < 0.004f) progress = target;
        }
        if (!requested && progress == 0f) admitted = false;
        return progress != previous;
    }
    public static float opacity(float page, float clock, boolean independent) {
        float p = Float.isFinite(page) ? Math.max(0f, Math.min(1f, page)) : 0f;
        float c = Float.isFinite(clock) ? Math.max(0f, Math.min(1f, clock)) : 1f;
        return independent ? p : p * c;
    }
    public float progress() { return progress; }
    public boolean moving() { return progress != target; }
    public void reset() { progress = target = 0f; admitted = false; }
}
