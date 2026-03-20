package io.github.dingyi222666.luaparser.semantic.workspace

data class WorkspaceDelta(
    val upserts: Map<VirtualPath, String> = emptyMap(),
    val removals: Set<VirtualPath> = emptySet()
) {
    init {
        require(upserts.keys.none { it in removals }) { "WorkspaceDelta cannot upsert and remove the same path." }
    }

    fun isEmpty(): Boolean = upserts.isEmpty() && removals.isEmpty()
}
