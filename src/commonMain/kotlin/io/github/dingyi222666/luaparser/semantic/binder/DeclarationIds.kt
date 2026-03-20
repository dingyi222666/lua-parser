package io.github.dingyi222666.luaparser.semantic.binder

import kotlin.jvm.JvmInline

@JvmInline
value class DeclarationId(val value: Int)

@JvmInline
value class ScopeId(val value: Int)

@JvmInline
value class SymbolId(val value: Int)
