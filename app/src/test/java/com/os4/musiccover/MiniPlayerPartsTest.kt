package com.os4.musiccover

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mini player's parts reach the controller only through what they are given
 * (MiniPlayerController plan, M2; the interfaces are in island/MiniPlayerParts.kt).
 *
 * A part still handed the whole controller (`c: MiniPlayerController`) is listed in
 * mini-parts-baseline.txt with every member it reaches through `c.` - and through `c.<part>.`
 * into another part - one per line. A member it did not reach before fails at once; so does a
 * line that no longer happens, so the list only gets shorter. A part with no lines left must not
 * take the controller again.
 *
 * MiniPlayerProbe keeps the controller for good: `op mini` reads all of it. It may not write.
 *
 * Read off the source text: a local `c` (a child view, a media controller) in a part that still
 * has the controller is counted too, and goes when that part is narrowed.
 */
class MiniPlayerPartsTest {

    private val island = File("src/main/java/com/os4/musiccover/island")

    private val baselineFile = File("src/test/resources/mini-parts-baseline.txt")

    private val probe = "MiniPlayerProbe.kt"

    private val takesController = Regex("""\bc\s*:\s*MiniPlayerController\b""")

    /** `c.x`, `this@Part.c.x`, `c::x`, and `c.part.y` for a part field. */
    private val ref = Regex("""(?<![\w.])(?:this@\w+\.)?c(\.|::)([A-Za-z_]\w*)(?:\.([A-Za-z_]\w*))?""")

    private val write = Regex("""(?<![\w.])(?:this@\w+\.)?c\.[\w.]+\s*(?:[-+*/]?=(?!=)|\+\+|--)""")

    private val partFields = setOf("shortcutDiscs", "probe", "appearMotion", "swapMotion", "spreadMotion",
        "noteDragMotion", "noteMorphMotion", "groupMotion", "stackHost", "cardExchange", "binding",
        "sessionSource")

    private fun code(file: File) = file.readLines().filter {
        val t = it.trim()
        !t.startsWith("//") && !t.startsWith("*") && !t.startsWith("/*")
    }

    private fun refs(lines: List<String>): Set<String> = lines.flatMap { line ->
        ref.findAll(line).map { m ->
            val (sep, a, b) = m.destructured
            if (sep == "." && a in partFields && b.isNotEmpty()) "c.$a.$b" else "c$sep$a"
        }.toList()
    }.toSortedSet()

    @Test
    fun partsReachOnlyWhatTheyAreGiven() {
        val baseline = baselineFile.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            .groupBy({ it.substringBefore(' ') }, { it.substringAfter(' ') })
            .mapValues { it.value.toSortedSet() }
        val problems = ArrayList<String>()
        val files = island.listFiles { f -> f.extension == "kt" && f.name != "MiniPlayerController.kt" }!!.sortedBy { it.name }
        for (file in files) {
            val lines = code(file)
            val holds = lines.any { takesController.containsMatchIn(it) }
            val listed = baseline[file.name].orEmpty()
            if (file.name == probe) {
                lines.filter { write.containsMatchIn(it) }.forEach { problems += "${file.name} writes the controller: ${it.trim()}" }
                continue
            }
            if (!holds) {
                if (listed.isNotEmpty()) problems += "${file.name} no longer takes the controller: drop its ${listed.size} baseline lines"
                continue
            }
            if (listed.isEmpty()) {
                problems += "${file.name} takes the whole controller: give it what it needs (MiniPlayerParts.kt)"
                continue
            }
            val found = refs(lines)
            (found - listed).forEach { problems += "${file.name} reaches $it, not in the baseline" }
            (listed - found).forEach { problems += "${file.name} no longer reaches $it: drop it from the baseline" }
        }
        (baseline.keys - files.map { it.name }.toSet()).forEach { problems += "baseline lists $it, which is gone" }
        assertTrue(problems.joinToString("\n", "\n"), problems.isEmpty())
    }
}
