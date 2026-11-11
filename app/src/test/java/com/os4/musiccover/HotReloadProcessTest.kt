package com.os4.musiccover

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [HotReloadPolicy.SUPPORTED] is what the retirement lets go of; the process names in
 * [FeatureRegistry] are what the adoption puts back (HotReload.install looks the process up
 * there). A process on one list and not the other retires and then adopts nothing - the process
 * ends up inert until a restart, which is exactly the failure this scans for.
 *
 * Read off the source, like HookIdTest: FeatureRegistry cannot be loaded in a JVM test (its
 * features reference Main, which needs the Xposed API), so its process names are literals inside
 * `processes(...)`, and this collects them.
 */
class HotReloadProcessTest {

    private val literal = Regex("\"([^\"]*)\"")
    // A package, or a package's process (高德's com.autonavi.minimap:locationservice).
    private val idShape = Regex("^[a-z][a-z0-9_]*\\.[a-z0-9_.]+(:[A-Za-z][A-Za-z0-9_]*)?$")

    private fun read(path: String) = File("src/main/java/com/os4/musiccover/$path").readText()

    private fun supported(): Set<String> {
        val policy = read("HotReloadPolicy.kt")
        val block = Regex("val SUPPORTED: Set<String> = setOf\\(([^)]*)\\)")
            .find(policy)?.groupValues?.get(1)
            ?: throw AssertionError("SUPPORTED not found in HotReloadPolicy.kt")
        return literal.findAll(block).map { it.groupValues[1] }.filter { it.isNotEmpty() }.toSet()
    }

    private fun registryProcesses(): List<String> {
        val source = read("feature/FeatureRegistry.java")
        val blocks = Regex("processes\\(([^)]*)\\)").findAll(source).map { it.groupValues[1] }
            .toList()
        assertTrue("no processes(...) found in FeatureRegistry.java", blocks.size > 1)
        val out = blocks.flatMap { b ->
            literal.findAll(b).map { it.groupValues[1] }.filter { idShape.matches(it) }.toList()
        }
        // A constant in there would be read as nothing at all; the names have to be literals.
        for (b in blocks) {
            val stripped = literal.replace(b, "").replace(",", "").trim()
            assertTrue("processes($b) holds something other than literals",
                stripped.isEmpty() || stripped == "String... names")
        }
        return out
    }

    @Test
    fun everySupportedProcessHasATarget() {
        assertEquals("SUPPORTED and FeatureRegistry's processes disagree",
            supported().sorted(), registryProcesses().toSet().sorted())
    }

    @Test
    fun noProcessIsClaimedTwice() {
        val all = registryProcesses()
        assertEquals("a process is listed under two targets", all.size, all.toSet().size)
    }

    @Test
    fun theInstallerThrowsForAnythingElse() {
        assertTrue("install() must refuse an unknown process",
            read("hotreload/HotReload.java").contains("no installer for "))
    }
}
