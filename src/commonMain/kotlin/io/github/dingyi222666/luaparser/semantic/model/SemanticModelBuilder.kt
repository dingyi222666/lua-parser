package io.github.dingyi222666.luaparser.semantic.model

import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.semantic.SemanticWorkspaceContext
import io.github.dingyi222666.luaparser.semantic.api.Diagnostic
import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
import io.github.dingyi222666.luaparser.semantic.checker.ExpressionTypeEvaluator
import io.github.dingyi222666.luaparser.semantic.checker.MemberResolver

class SemanticModelBuilder {
    fun build(
        chunk: ChunkNode,
        binder: BinderPassResult,
        diagnostics: List<Diagnostic>
    ): SemanticModel {
        return build(chunk, binder, diagnostics, SemanticWorkspaceContext())
    }

    internal fun build(
        chunk: ChunkNode,
        binder: BinderPassResult,
        diagnostics: List<Diagnostic>,
        context: SemanticWorkspaceContext
    ): SemanticModel {
        val adapters = ApiAdapters(binder)
        val evaluator = ExpressionTypeEvaluator(binder, context)
        val memberResolver = MemberResolver(binder)
        val referenceQueries = ReferenceQueries(binder, evaluator, memberResolver, adapters, context)
        // A workspace pass builds a model per document; the position index and the providers
        // that depend on it are only needed for documents that actually get queried.
        val nodePositionIndex = lazy { NodePositionIndex(chunk) }

        return DefaultSemanticModel(
            binder = binder,
            adapters = adapters,
            referenceQueries = referenceQueries,
            nodePositionIndex = nodePositionIndex,
            nodeTypeIndex = NodeTypeIndex(binder, evaluator, adapters),
            completionProvider = lazy {
                CompletionProvider(nodePositionIndex.value, referenceQueries, adapters)
            },
            signatureHelpProvider = lazy {
                SignatureHelpProvider(binder, nodePositionIndex.value, evaluator)
            },
            diagnostics = diagnostics
        )
    }
}
