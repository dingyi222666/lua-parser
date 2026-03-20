package io.github.dingyi222666.luaparser.semantic.workspace

fun interface ProgressReporter {
    fun report(progress: AnalysisProgress)

    companion object {
        val NONE: ProgressReporter = ProgressReporter { }
    }
}
