package com.os4.musiccover;

import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Adds the module's policy to the OEM combine's inputs, so it emits through its own coroutine. */
final class NotificationSink {
    private final List<WeakReference<Object>> policies = new ArrayList<>();
    private int requestedMode;
    private long emissions;
    private final List<String> history = new ArrayList<>();
    private String hookStatus = "not installed";

    synchronized void hookStatus(String status) {
        hookStatus = status;
    }

    synchronized void record(Object[] original, Object[] effective, String bound) {
        emissions++;
        if (original.length < 7) return;
        String state = "inputs=" + original.length + " policy="
                + (original.length == 8 ? original[7] : "absent")
                + " fingerprint=" + original[5] + "/" + original[6]
                + " effective=" + effective[5] + "/" + effective[6] + " bound=" + bound;
        if (history.isEmpty() || !history.get(history.size() - 1).equals(state)) {
            history.add(state);
            if (history.size() > 8) history.remove(0);
        }
    }

    synchronized String describe() {
        int live = 0;
        for (WeakReference<Object> policy : policies) if (policy.get() != null) live++;
        return "hook=v4 " + hookStatus + "\npolicyFlows=" + live + " requestedMode=" + requestedMode + " emissions=" + emissions
                + "\n" + String.join("\n", history);
    }

    synchronized boolean attach(Object combinedFlow, int mode) throws ReflectiveOperationException {
        ClassLoader loader = combinedFlow.getClass().getClassLoader();
        for (Field field : combinedFlow.getClass().getDeclaredFields()) {
            if (!field.getType().isArray()) continue;
            Class<?> flowClass = field.getType().getComponentType();
            // The caller is restricted to the OEM combine and its array factory.
            // Avoid even comparing a Kotlin binary-name literal: R8 adapts that too.
            if (!flowClass.isInterface()) continue;
            field.setAccessible(true);
            Object[] original = (Object[]) field.get(combinedFlow);
            if (original == null || original.length != 7) continue;
            // Derive the host name at runtime. A literal Class.forName Kotlin name is
            // rewritten by our R8 to the module's obfuscated class, absent in SystemUI.
            Object policy = Class.forName(flowClass.getPackage().getName() + ".StateFlowKt", false, loader)
                    .getMethod("MutableStateFlow", Object.class).invoke(null, mode);
            Object[] inputs = (Object[]) Array.newInstance(flowClass, 8);
            System.arraycopy(original, 0, inputs, 0, 7);
            inputs[7] = policy;
            field.set(combinedFlow, inputs);
            policies.add(new WeakReference<>(policy));
            return true;
        }
        return false;
    }

    synchronized Object[] attachToCombine(Object factory, Object[] sources, int mode)
            throws ReflectiveOperationException {
        if (sources.length != 7) return sources;
        if (!attach(factory, mode)) return sources;
        for (Field field : factory.getClass().getDeclaredFields()) {
            if (field.getType() != sources.getClass()) continue;
            field.setAccessible(true);
            Object[] inputs = (Object[]) field.get(factory);
            if (inputs != null && inputs.length == 8) return inputs;
        }
        throw new IllegalStateException("policy attached without matching combine array");
    }

    static Object[] withAvoidance(Object[] values, int fallbackMode) {
        Object[] result = values.clone();
        // The first seven inputs remain exactly the OEM's. Input eight wakes the combine
        // and carries the effective policy used for THIS emission, not a later global value.
        int mode = result.length == 8 && result[7] instanceof Integer ? (Integer) result[7] : fallbackMode;
        if ((result.length == 7 || result.length == 8) && mode != 0) {
            result[5] = mode == 2;
            result[6] = mode == 2;
        }
        return result;
    }

    synchronized boolean refresh(int mode) throws ReflectiveOperationException {
        requestedMode = mode;
        boolean updated = false;
        Iterator<WeakReference<Object>> it = policies.iterator();
        while (it.hasNext()) {
            Object policy = it.next().get();
            if (policy == null) { it.remove(); continue; }
            Method setValue = Class.forName(policy.getClass().getPackage().getName()
                    + ".MutableStateFlow", false, policy.getClass().getClassLoader())
                    .getMethod("setValue", Object.class);
            setValue.invoke(policy, mode);
            updated = true;
        }
        return updated;
    }
}
