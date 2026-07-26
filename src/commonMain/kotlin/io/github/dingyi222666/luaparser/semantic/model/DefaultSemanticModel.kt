package io.github.dingyi222666.luaparser.semantic.model

import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.semantic.api.CompletionItem
import io.github.dingyi222666.luaparser.semantic.api.Diagnostic
import io.github.dingyi222666.luaparser.semantic.api.Scope
import io.github.dingyi222666.luaparser.semantic.api.SignatureHelp
import io.github.dingyi222666.luaparser.semantic.api.Symbol
import io.github.dingyi222666.luaparser.semantic.api.TypeInfo
import io.github.dingyi222666.luaparser.semantic.binder.BinderDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationId
import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
import io.github.dingyi222666.luaparser.semantic.binder.SymbolId

/**
 * Lets callers that already hold a [SemanticModel] reuse its position index instead of building a
 * second one over the same chunk. Indexing is a full-AST walk, so the workspace file and the
 * language server share the model's copy rather than each rebuilding it.
 */
internal interface NodePositionIndexProvider {
    val nodePositionIndex: NodePositionIndex
}

internal class DefaultSemanticModel(
    private val binder: BinderPassResult,
    private val adapters: ApiAdapters,
    private val referenceQueries: ReferenceQueries,
    nodePositionIndex: Lazy<NodePositionIndex>,
    private val nodeTypeIndex: NodeTypeIndex,
    completionProvider: Lazy<CompletionProvider>,
    signatureHelpProvider: Lazy<SignatureHelpProvider>,
    private val diagnostics: List<Diagnostic>
) : SemanticModel, NodePositionIndexProvider {
    override val nodePositionIndex by nodePositionIndex
    private val completionProvider by completionProvider
    private val signatureHelpProvider by signatureHelpProvider

    override fun getSymbolAt(position: Position): Symbol? {
        return referenceQueries.getSymbolAt(position, nodePositionIndex.findInnermost(position))
    }

    override fun getTypeAt(node: BaseASTNode): TypeInfo? {
        return nodeTypeIndex.getTypeAt(node)
    }

    override fun getDeclaredType(symbol: Symbol): TypeInfo? {
        val declaration = resolveDeclaration(symbol)
        return declaration
            ?.declaredType
            ?.let { adapters.toTypeInfo(it, declaration) }
            ?: symbol.declaredType
    }

    override fun getInferredType(symbol: Symbol): TypeInfo? {
        return resolveDeclaration(symbol)
            ?.let(nodeTypeIndex::getInferredType)
            ?: symbol.type
    }

    override fun getMembers(type: TypeInfo): List<Symbol> {
        val internalType = adapters.typeByKey(type.typeKey) ?: return emptyList()
        return referenceQueries.getMembers(internalType)
    }

    override fun getCompletionsAt(position: Position): List<CompletionItem> {
        return completionProvider.getCompletionsAt(position)
    }

    override fun getSignatureHelpAt(position: Position): SignatureHelp? {
        return signatureHelpProvider.getSignatureHelpAt(position)
    }

    override fun getDiagnostics(): List<Diagnostic> = diagnostics

    override fun getScopeAt(position: Position): Scope? {
        val scope = binder.positionQueries.getScopeAt(position) ?: return null
        return adapters.toScope(scope, referenceQueries.visibleValueSymbols(position))
    }

    private fun resolveDeclaration(symbol: Symbol): BinderDeclaration? {
        val handle = symbol.symbolId?.toSymbolHandle() ?: return null
        val binderSymbolId = handle.binderSymbolId?.let(::SymbolId)
        if (binderSymbolId != null) {
            return binder.declarationIndex.getPrimaryDeclaration(binderSymbolId)
        }
        val declarationId = handle.declarationId?.let(::DeclarationId)
        if (declarationId != null) {
            return binder.declarationIndex.getDeclaration(declarationId)
        }
        return null
    }
}
