package com.os4.musiccover.island

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationBlurTargetsTest {
    private class Node(val name: String, val kind: NotificationBlurTargets.Kind, vararg val children: Node)
    private fun content(name: String, vararg children: Node) = Node(name, NotificationBlurTargets.Kind.CONTENT, *children)
    private fun container(name: String, vararg children: Node) = Node(name, NotificationBlurTargets.Kind.CONTAINER, *children)
    private fun selected(root: Node) = NotificationBlurTargets.collect(root, { it.kind }, { it.children.toList() }).map { it.name }

    @Test fun templatesAndExternalIconsAreBlurredOnceWithoutTheGlass() {
        val root = container("row", Node("glass", NotificationBlurTargets.Kind.EXCLUDED, content("background icon")),
            content("private template", content("template icon")), content("public template"),
            container("injected wrapper", content("app icon")))
        assertEquals(listOf("private template", "public template", "app icon"), selected(root))
    }

    @Test fun groupedHeadersAndDeeplyWrappedChildIconsKeepTheSameBlurCoverage() {
        val icon = content("child icon")
        val deep = (0..6).fold(icon) { child, i -> container("wrapper $i", child) }
        val root = container("summary", container("children", content("group header", content("header icon")),
            container("child row", Node("child glass", NotificationBlurTargets.Kind.EXCLUDED), deep)))
        assertEquals(listOf("group header", "child icon"), selected(root))
    }
}
