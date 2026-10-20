package io.github.dingyi222666.luaparser.semantic.checker

import io.github.dingyi222666.luaparser.semantic.api.Diagnostic
import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult

data class CheckerPassResult(
    val binder: BinderPassResult,
    val diagnostics: List<Diagnostic>
)
