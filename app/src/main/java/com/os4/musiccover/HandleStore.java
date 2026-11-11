package com.os4.musiccover;

import com.os4.musiccover.hotreload.HotReload;
import java.lang.reflect.Executable;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

/**
 * The old generation's hooks, waiting to be claimed by the new one as it installs.
 *
 * <p>A hook is paired only when both the id and the executable match. The same id on a
 * different executable is a hook whose target changed in this version; it cannot be replaced
 * (replaceHook keeps the old executable), so it stays pending and is unhooked at the end while
 * the new one is installed normally. A replacement that throws is the same case one step later:
 * the old handle goes back to pending, the new hook is installed alongside it, and the pending
 * one is unhooked by [unhookRemaining].
 *
 * <p>Not for general use: only one adoption runs at a time (the framework serialises hot
 * reloads per process), and the methods are synchronized so the hook installer can be called
 * from whichever thread the framework picks.
 */
public final class HandleStore {

    private final List<XposedInterface.HookHandle> pending = new ArrayList<>();
    private final List<XposedInterface.HookHandle> installed = new ArrayList<>();
    private final List<String> replacedIds = new ArrayList<>();
    private final List<String> addedIds = new ArrayList<>();

    public HandleStore(List<XposedInterface.HookHandle> old) {
        if (old != null) pending.addAll(old);
    }

    /** The old handle with this id and executable, taken out of pending; null when none. */
    public synchronized XposedInterface.HookHandle take(String id, Executable target) {
        for (int i = 0; i < pending.size(); i++) {
            XposedInterface.HookHandle h = pending.get(i);
            if (id.equals(h.getId()) && target.equals(h.getExecutable())) {
                pending.remove(i);
                return h;
            }
        }
        return null;
    }

    /** A replace that failed: the old handle goes back, to be unhooked if nothing else claims it. */
    public synchronized void keep(XposedInterface.HookHandle handle) {
        if (handle != null) pending.add(handle);
    }

    /** A hook this generation installed by replacing an old one. */
    public synchronized void replaced(String id, XposedInterface.HookHandle handle) {
        replacedIds.add(id);
        installed.add(handle);
    }

    /** A hook this generation installed from scratch. */
    public synchronized void added(String id, XposedInterface.HookHandle handle) {
        addedIds.add(id);
        installed.add(handle);
    }

    /** The hooks this generation installed, for the failure path's orderly undo. */
    public synchronized List<XposedInterface.HookHandle> installed() {
        return new ArrayList<>(installed);
    }

    public synchronized void clearInstalled() {
        installed.clear();
    }

    /** Unhook everything never claimed. Returns how many. */
    public synchronized int unhookRemaining() {
        int removed = 0;
        for (XposedInterface.HookHandle h : pending) {
            try {
                h.unhook();
            } catch (Throwable t) {
                Xp.w(HotReload.TAG + "unhook of a left-over handle failed: " + t);
            }
            removed++;
        }
        pending.clear();
        return removed;
    }

    public synchronized int replacedCount() {
        return replacedIds.size();
    }

    public synchronized int addedCount() {
        return addedIds.size();
    }

    /** The ids of the old handles nothing claimed, for the adoption log. */
    public synchronized List<String> remainingLabels() {
        List<String> out = new ArrayList<>(pending.size());
        for (XposedInterface.HookHandle h : pending) out.add(HotReload.labelOf(h));
        return out;
    }

    /** The ids this generation installed, replaced and added together. */
    public synchronized List<String> newIds() {
        List<String> out = new ArrayList<>(replacedIds.size() + addedIds.size());
        out.addAll(replacedIds);
        out.addAll(addedIds);
        return out;
    }
}
