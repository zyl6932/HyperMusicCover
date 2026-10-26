package com.os4.musiccover.lyrics;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import java.util.function.Function;

/** Same-directory, exact-stem matching; a rejected file never blocks the next format. */
final class LocalLyricFiles {
    static final int MAX_BYTES = 512 * 1024;

    private LocalLyricFiles() { }

    static <T> T first(String audio, String[] formats, Function<File, T> read) {
        File track = new File(audio);
        File directory = track.getParentFile();
        if (directory == null) return null;
        String name = track.getName();
        int dot = name.lastIndexOf('.');
        String stem = dot <= 0 ? name : name.substring(0, dot);
        File[] candidates = directory.listFiles();
        if (candidates == null) return null;
        Arrays.sort(candidates, Comparator.comparing(File::getName));
        for (String format : formats) {
            for (File file : candidates) {
                if (!file.getName().equalsIgnoreCase(stem + "." + format)
                        || !file.isFile() || file.length() == 0L || file.length() > MAX_BYTES) continue;
                T result = read.apply(file);
                if (result != null) return result;
            }
        }
        return null;
    }
}
