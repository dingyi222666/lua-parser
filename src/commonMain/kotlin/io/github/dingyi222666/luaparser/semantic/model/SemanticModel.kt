package io.github.dingyi222666.luaparser.semantic.model

import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.semantic.api.CompletionItem
import io.github.dingyi222666.luaparser.semantic.api.Diagnostic
import io.github.dingyi222666.luaparser.semantic.api.Scope
import io.github.dingyi222666.luaparser.semantic.api.Symbol
import io.github.dingyi222666.luaparser.semantic.api.TypeInfo

/**
 * Public semantic query surface produced by [io.github.dingyi222666.luaparser.semantic.SemanticPipeline].
 */
interface SemanticModel {
    fun getSymbolAt(position: Position): Symbol?

    fun getTypeAt(node: BaseASTNode): TypeInfo?

    fun getDeclaredType(symbol: Symbol): TypeInfo?

    fun getInferredType(symbol: Symbol): TypeInfo?

    fun getMembers(type: TypeInfo): List<Symbol>

    fun getCompletionsAt(position: Position): List<CompletionItem>

    fun getDiagnostics(): List<Diagnostic>

    fun getScopeAt(position: Position): Scope?
}

object EmptySemanticModel : SemanticModel {
    override fun getSymbolAt(position: Position): Symbol? = null

    override fun getTypeAt(node: BaseASTNode): TypeInfo? = null

    override fun getDeclaredType(symbol: Symbol): TypeInfo? = null

    override fun getInferredType(symbol: Symbol): TypeInfo? = null

    override fun getMembers(type: TypeInfo): List<Symbol> = emptyList()

    override fun getCompletionsAt(position: Position): List<CompletionItem> = emptyList()

    override fun getDiagnostics(): List<Diagnostic> = emptyList()

    override fun getScopeAt(position: Position): Scope? = null
}
