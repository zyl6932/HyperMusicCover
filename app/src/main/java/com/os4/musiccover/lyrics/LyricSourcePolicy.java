package com.os4.musiccover.lyrics;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;

public final class LyricSourcePolicy {
    public static final String DEFAULT_SOURCES = "lyricon,metadata,local,amll,ttmlhub,qq,netease,kugou,kuwo,lrclib";
    public static volatile Config current = new Config(false, true, DEFAULT_SOURCES, LocalLyricFormat.TTML);

    private LyricSourcePolicy() { }

    public static String normalizeSources(String order) {
        return normalize(order, DEFAULT_SOURCES);
    }

    private static String normalize(String order, String defaults) {
        LinkedHashSet<String> allowed = new LinkedHashSet<>(Arrays.asList(defaults.split(",")));
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (order != null) {
            for (String entry : order.split(",")) {
                String key = entry.trim().toLowerCase(Locale.ROOT);
                if (allowed.contains(key)) result.add(key);
            }
        }
        result.addAll(allowed);
        return String.join(",", result);
    }

    /** Ordinal positions are unique: moving one source shifts the others stably. */
    public static String move(String order, String source, int position) {
        java.util.List<String> items = new java.util.ArrayList<>(Arrays.asList(order.split(",")));
        if (!items.remove(source)) return order;
        items.add(Math.max(0, Math.min(items.size(), position - 1)), source);
        return String.join(",", items);
    }

    /** Sequential selection: a fast lower-priority answer cannot race the preferred source. */
    static <T> T first(Config config, java.util.function.Function<String, T> read,
                       java.util.function.BooleanSupplier stillCurrent) {
        for (String source : config.sources()) {
            if (!stillCurrent.getAsBoolean()) return null;
            if (!config.permits(source)) continue;
            T result = read.apply(source);
            if (!stillCurrent.getAsBoolean()) return null;
            if (result != null) return result;
        }
        return null;
    }

    public static boolean network(String source) {
        return !"metadata".equals(source) && !"local".equals(source) && !"lyricon".equals(source);
    }

    public static final class Config {
        public final boolean custom;
        public final boolean allowNetwork;
        public final String sourceOrder;
        public final String localFormat;

        public Config(boolean custom, boolean allowNetwork, String sourceOrder, String localFormat) {
            this.custom = custom;
            this.allowNetwork = allowNetwork;
            this.sourceOrder = normalizeSources(sourceOrder);
            this.localFormat = LocalLyricFormat.normalize(localFormat);
        }

        public String[] sources() {
            return (custom ? sourceOrder : DEFAULT_SOURCES).split(",");
        }

        public String[] formats() {
            return LocalLyricFormat.formats(localFormat);
        }

        public boolean permits(String source) {
            return rank(source) != Integer.MAX_VALUE && (!network(source) || allowNetwork);
        }

        public boolean mayReplace(String previous, String candidate) {
            return permits(candidate) && (!permits(previous) || rank(candidate) <= rank(previous));
        }

        private int rank(String source) {
            String[] order = sources();
            for (int index = 0; index < order.length; index++) {
                if (order[index].equals(source)) return index;
            }
            return Integer.MAX_VALUE;
        }
    }
}
