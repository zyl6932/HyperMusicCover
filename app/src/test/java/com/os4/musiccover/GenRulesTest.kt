package com.os4.musiccover

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Module code does not own a thread, receiver, host listener or frame callback directly: it
 * asks Gen, which is the only place that can take them back out when this generation retires.
 *
 * Step 2 moves one group of files at a time; gen-baseline.txt lists the ones still to move, and
 * this test holds them there. A new file that makes a raw call fails immediately, and a file
 * that has moved must not creep back.
 *
 * The app's own process is not covered - it is never hot reloaded.
 */
class GenRulesTest {

    private val forbidden = listOf(
        Regex("registerReceiver\\("),
        Regex("new HandlerThread\\("),
        Regex("new Thread\\("),
        Regex("postFrameCallback\\("),
        Regex("addOnPreDrawListener\\("),
        Regex("addOnDrawListener\\("),
        Regex("addOnLayoutChangeListener\\("),
        Regex("addOnAttachStateChangeListener\\("),
        Regex("addOnGlobalLayoutListener\\("),
        Regex("addOnScrollChangedListener\\("),
        Regex("registerContentObserver\\("),
        Regex("registerOnSharedPreferenceChangeListener\\("),
        Regex("registerDisplayListener\\("),
        Regex("Executors\\."),
        Regex("Thread\\s*\\{"),
        Regex("postFrameCallbackDelayed\\("),
        // A media controller's callback, the active-session list, a sensor.
        Regex("\\.registerCallback\\("),
        Regex("addOnActiveSessionsChangedListener\\("),
        Regex("\\.registerListener\\("),
        // A main-looper Handler of a feature's own: its queue is ours, and only one Gen knows
        // of (Gen.handler(), or one wrapped in Gen.watchHandler) is cleared at retirement.
        Regex("Handler\\((android\\.os\\.)?Looper\\.getMainLooper\\(\\)\\)"),
        // A post on a host view (Gen.postOn and its siblings). The receiver's type is not in the
        // source, so this goes by name - views, roots, hosts, layers, parents and a bare `v` -
        // and misses what is named otherwise; a Handler that happens to match says `// gen-ok`.
        Regex("(\\b[A-Za-z_]*([Vv]iew|[Rr]oot|[Hh]ost|[Ll]ayer|[Pp]arent)[A-Za-z_0-9]*|\\bv)" +
            "\\??\\.post(Delayed|OnAnimation(Delayed)?)?\\("),
    )

    /** A raw call that is fine where it is, said so on its own line. */
    private val allowed = listOf(
        Regex("// gen-ok\\b"),
        // Registered, so the sweep clears its queue.
        Regex("Gen\\.watchHandler\\("),
    )

    @Test
    fun moduleCodeGoesThroughGen() {
        val baseline = File("src/test/resources/gen-baseline.txt").readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toSet()
        val root = File("src/main/java/com/os4/musiccover")
        val bad = mutableListOf<String>()

        root.walkTopDown()
            .filter { it.isFile && (it.extension == "java" || it.extension == "kt") }
            .forEach { file ->
                val rel = file.relativeTo(root).path.replace(File.separatorChar, '/')
                if (rel.startsWith("app/") || rel.startsWith("ui/") ||
                    rel.startsWith("updater/") || rel == "Gen.java" ||
                    rel == "MainActivity.kt" || rel in baseline
                ) return@forEach
                file.readLines().forEachIndexed { index, line ->
                    if (forbidden.any { it.containsMatchIn(line) } &&
                        allowed.none { it.containsMatchIn(line) }
                    ) {
                        bad += "$rel:${index + 1}: ${line.trim()}"
                    }
                }
            }

        assertTrue("raw lifecycle calls outside Gen:\n" + bad.joinToString("\n"), bad.isEmpty())
    }
}
