package io.github.dingyi222666.luaparser.semantic.model

import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.semantic.api.CompletionItem
import io.github.dingyi222666.luaparser.semantic.api.Diagnostic
import io.github.dingyi222666.luaparser.semantic.api.Scope
import io.github.dingyi222666.luaparser.semantic.api.SignatureHelp
import io.github.dingyi222666.luaparser.semantic.api.Symbol
import io.github.dingyi222666.luaparser.semantic.api.TypeInfo

/**
 * Public semantic query surface produced by [io.github.dingyi222666.luaparser.semantic.SemanticPipeline].
 *
 * Position convention: every query that takes a [Position] expects the parser's
 * 1-based coordinates — [Position.line] AND [Position.column] both start at 1
 * (see [io.github.dingyi222666.luaparser.parser.ast.node.Position], whose origin
 * is `Position(1, 1)`). This is NOT the LSP convention, which is 0-based; LSP
 * adapters must convert before calling these methods.
 */
interface SemanticModel {
    /**
     * Returns the symbol at [position], or null when nothing is there.
     *
     * [position] uses the parser's 1-based [Position] convention: both
     * [Position.line] and [Position.column] start at 1.
     */
    fun getSymbolAt(position: Position): Symbol?

    fun getTypeAt(node: BaseASTNode): TypeInfo?

    fun getDeclaredType(symbol: Symbol): TypeInfo?

    fun getInferredType(symbol: Symbol): TypeInfo?

    fun getMembers(type: TypeInfo): List<Symbol>

    /**
     * Returns completion items offered at [position].
     *
     * [position] uses the parser's 1-based [Position] convention: both
     * [Position.line] and [Position.column] start at 1.
     */
    fun getCompletionsAt(position: Position): List<CompletionItem>

    /**
     * Returns signature help for the call enclosing [position], or null.
     *
     * [position] uses the parser's 1-based [Position] convention: both
     * [Position.line] and [Position.column] start at 1.
     */
    fun getSignatureHelpAt(position: Position): SignatureHelp?

    fun getCallableHoverAt(position: Position): CallableHoverInfo? = null

    fun getDiagnostics(): List<Diagnostic>

    /**
     * Returns the innermost scope containing [position], or null.
     *
     * [position] uses the parser's 1-based [Position] convention: both
     * [Position.line] and [Position.column] start at 1.
     */
    fun getScopeAt(position: Position): Scope?
}

data class CallableHoverInfo(
    val displayName: String
)

object EmptySemanticModel : SemanticModel {
    override fun getSymbolAt(position: Position): Symbol? = null

    override fun getTypeAt(node: BaseASTNode): TypeInfo? = null

    override fun getDeclaredType(symbol: Symbol): TypeInfo? = null

    override fun getInferredType(symbol: Symbol): TypeInfo? = null

    override fun getMembers(type: TypeInfo): List<Symbol> = emptyList()

    override fun getCompletionsAt(position: Position): List<CompletionItem> = emptyList()

    override fun getSignatureHelpAt(position: Position): SignatureHelp? = null

    override fun getCallableHoverAt(position: Position): CallableHoverInfo? = null

    override fun getDiagnostics(): List<Diagnostic> = emptyList()

    override fun getScopeAt(position: Position): Scope? = null
}
