package com.os4.musiccover

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which way the module's packages may depend on each other, read off the import lines.
 *
 * - The hook side (everything SystemUI and the other hosts load) does not import the app side
 *   (app/, ui/, updater/, MainActivity): those are the settings app, and pull Compose and
 *   activities into the hosts' class loaders.
 * - The app side imports the hook side only through the classes made to be shared, which import
 *   nothing of the module themselves (CONTRACTS).
 * - The feature packages do not import each other in a cycle: one that does cannot be taken
 *   out, or loaded without, the other.
 * - The settings table (Settings.java) does not import a feature: the features register with it.
 *
 * What breaks a rule today is listed in package-baseline.txt, one import per line, and stays
 * there until it is undone. A new one fails at once; so does a line in the baseline that no
 * longer happens, so the list only gets shorter (architecture plan, stage 0).
 *
 * Imports only: a fully qualified name in the code and a reference within one package are not
 * seen, which is why the root package's own files (Main and the rest) are not checked against
 * the features they install.
 */
class PackageDirectionTest {

    private val root = File("src/main/java/com/os4/musiccover")

    private val appPackages = setOf("app", "ui", "updater")

    private val features = setOf("immersive", "island", "lyrics", "pickup", "shade", "transit")

    /** Root classes both sides use by design. Each imports nothing of the module. */
    private val contracts = setOf("R", "BuildConfig", "SettingSpec", "HotReloadPolicy", "LogLevel",
        "ProbeGuard", "LauncherIcon")

    private val importLine =
        Regex("""^\s*import\s+(?:static\s+)?com\.os4\.musiccover\.([A-Za-z0-9_.]+)""")

    private class Import(val file: String, val target: String)

    /** "island/LockIslands.kt" -> "island"; a file in the root package -> "". */
    private fun packageOfFile(rel: String) = rel.substringBefore('/', "")

    /** "island.LockIslands" -> "island"; "R", "Main" -> "". Kotlin top-level functions count too. */
    private fun packageOfTarget(target: String): String {
        val first = target.substringBefore('.')
        return if (target.contains('.') && first.first().isLowerCase()) first else ""
    }

    private fun appSide(rel: String) = packageOfFile(rel) in appPackages || rel == "MainActivity.kt"

    private fun appTarget(target: String) = packageOfTarget(target) in appPackages || target == "MainActivity"

    private fun imports(): List<Import> = root.walkTopDown()
        .filter { it.isFile && (it.extension == "java" || it.extension == "kt") }
        .flatMap { file ->
            val rel = file.relativeTo(root).path.replace(File.separatorChar, '/')
            file.readLines().mapNotNull { line ->
                importLine.find(line)?.groupValues?.get(1)?.trimEnd('.')?.let { Import(rel, it) }
            }
        }.toList()

    @Test
    fun packagesDependTheAgreedWay() {
        val all = imports()

        // The feature graph, for telling an edge in a cycle from one that is not.
        val edges = HashMap<String, MutableSet<String>>()
        for (i in all) {
            val from = packageOfFile(i.file)
            val to = packageOfTarget(i.target)
            if (from in features && to in features && from != to) edges.getOrPut(from) { HashSet() } += to
        }
        fun reaches(from: String, to: String): Boolean {
            val seen = HashSet<String>()
            val todo = ArrayDeque(listOf(from))
            while (todo.isNotEmpty()) {
                val p = todo.removeFirst()
                if (p == to) return true
                if (seen.add(p)) todo.addAll(edges[p].orEmpty())
            }
            return false
        }

        val found = LinkedHashSet<String>()
        for (i in all) {
            val from = packageOfFile(i.file)
            val to = packageOfTarget(i.target)
            val rule = when {
                !appSide(i.file) && appTarget(i.target) -> "hook-imports-app"
                appSide(i.file) && !appTarget(i.target) && i.target !in contracts -> "app-imports-hook"
                from in features && to in features && from != to && reaches(to, from) -> "feature-cycle"
                i.file == "Settings.java" && to in features -> "settings-imports-feature"
                else -> null
            } ?: continue
            found += "$rule ${i.file} -> ${i.target}"
        }

        val baseline = File("src/test/resources/package-baseline.txt").readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toSet()
        val added = found - baseline
        val gone = baseline - found
        assertTrue("imports against the package rules (undo them, or argue for the baseline):\n" +
            added.joinToString("\n"), added.isEmpty())
        assertTrue("undone, so out of package-baseline.txt now:\n" + gone.joinToString("\n"), gone.isEmpty())
    }
}
