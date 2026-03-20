package io.github.dingyi222666.luaparser.semantic.workspace

data class AnalysisProgress(
    val phase: Phase,
    val currentFile: VirtualPath? = null,
    val completedFiles: Int = 0,
    val totalFiles: Int = 0
) {
    enum class Phase {
        PARSING,
        BINDING,
        CHECKING,
        COMPLETE
    }
}
