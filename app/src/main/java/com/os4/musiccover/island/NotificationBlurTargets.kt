package com.os4.musiccover.island

/** Select disjoint content subtrees, including icons/headers outside NotificationContentView. */
internal object NotificationBlurTargets {
    enum class Kind { CONTAINER, CONTENT, EXCLUDED }

    fun <T> collect(root: T, kind: (T) -> Kind, children: (T) -> List<T>): List<T> {
        val result = ArrayList<T>()
        fun walk(node: T, depth: Int) {
            when (kind(node)) {
                Kind.EXCLUDED -> return
                Kind.CONTENT -> { result += node; return }
                Kind.CONTAINER -> if (depth < 16) children(node).forEach { walk(it, depth + 1) }
            }
        }
        walk(root, 0)
        return result
    }
}
