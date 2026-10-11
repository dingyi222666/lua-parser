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
 * The only [SemanticModel] implementation the pipeline produces. Callers that already hold one
 * (the workspace file, the language server) reuse its [nodePositionIndex] instead of building a
 * second one over the same chunk — indexing is a full-AST walk, so they share the model's copy.
 */
internal class DefaultSemanticModel(
    private val binder: BinderPassResult,
    private val adapters: ApiAdapters,
    private val referenceQueries: ReferenceQueries,
    private val nodePositionIndexLazy: Lazy<NodePositionIndex>,
    private val nodeTypeIndex: NodeTypeIndex,
    private val completionProviderLazy: Lazy<CompletionProvider>,
    private val luaLayoutCompletionProviderLazy: Lazy<LuaLayoutCompletionProvider>,
    private val signatureHelpProviderLazy: Lazy<SignatureHelpProvider>,
    private val diagnostics: List<Diagnostic>
) : SemanticModel {
    val nodePositionIndex by nodePositionIndexLazy
    private val completionProvider by completionProviderLazy
    private val luaLayoutCompletionProvider by luaLayoutCompletionProviderLazy
    private val signatureHelpProvider by signatureHelpProviderLazy

    internal fun withDiagnostics(diagnostics: List<Diagnostic>) = DefaultSemanticModel(
        binder, adapters, referenceQueries, nodePositionIndexLazy, nodeTypeIndex,
        completionProviderLazy, luaLayoutCompletionProviderLazy, signatureHelpProviderLazy, diagnostics
    )

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

    override fun getCompletionsAt(position: Position): List<CompletionItem> =
        getCompletionsMatching(position, null)

    internal fun getCompletionsMatching(position: Position, nameFilter: ((String) -> Boolean)?): List<CompletionItem> {
        // Layout-table key contexts replace lexical completions with the enclosing view
        // class's Lua property keys (loadlayout `k -> setCap(k)` semantics).
        luaLayoutCompletionProvider.getCompletionsAt(position)?.let { items ->
            return items.filter { nameFilter?.invoke(it.label) != false }
        }
        return completionProvider.getCompletionsAt(position, nameFilter)
    }

    override fun getSignatureHelpAt(position: Position): SignatureHelp? {
        return signatureHelpProvider.getSignatureHelpAt(position)
    }

    override fun getCallableHoverAt(position: Position): CallableHoverInfo? {
        return signatureHelpProvider.getCallableHoverAt(position)
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
