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
        val nodePositionIndex = NodePositionIndex(chunk)
        val referenceQueries = ReferenceQueries(binder, evaluator, memberResolver, adapters, context)
        val nodeTypeIndex = NodeTypeIndex(binder, evaluator, adapters)
        val completionProvider = CompletionProvider(nodePositionIndex, referenceQueries, adapters)

        return DefaultSemanticModel(
            binder = binder,
            adapters = adapters,
            referenceQueries = referenceQueries,
            nodePositionIndex = nodePositionIndex,
            nodeTypeIndex = nodeTypeIndex,
            completionProvider = completionProvider,
            diagnostics = diagnostics
        )
    }
}
