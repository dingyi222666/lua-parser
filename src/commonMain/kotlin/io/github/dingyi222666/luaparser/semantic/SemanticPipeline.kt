package io.github.dingyi222666.luaparser.semantic

import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.semantic.binder.BinderPass
import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
import io.github.dingyi222666.luaparser.semantic.checker.CheckerPass
import io.github.dingyi222666.luaparser.semantic.checker.CheckerPassResult
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachPass
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachmentIndex
import io.github.dingyi222666.luaparser.semantic.model.SemanticAnalysisResult
import io.github.dingyi222666.luaparser.semantic.model.SemanticAnalysisSummary
import io.github.dingyi222666.luaparser.semantic.model.SemanticModel
import io.github.dingyi222666.luaparser.semantic.model.SemanticModelBuilder
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceModuleResolver
import io.github.dingyi222666.luaparser.semantic.workspace.std.BuiltinOverlayLoader
import io.github.dingyi222666.luaparser.semantic.workspace.std.BuiltinOverlaySnapshot
import io.github.dingyi222666.luaparser.semantic.types.resolve.TypeResolver

/**
 * Primary semantic entry point.
 *
 * The pipeline is the only in-repo semantic implementation path. Legacy APIs adapt the
 * snapshot produced here into compatibility models rather than running their own analysis.
 */
class SemanticPipeline(
    private val commentAttachPass: CommentAttachPass = CommentAttachPass(),
    private val binderPass: BinderPass = BinderPass(),
    private val typeResolver: TypeResolver = TypeResolver(),
    private val checkerPass: CheckerPass = CheckerPass(),
    private val semanticModelBuilder: SemanticModelBuilder = SemanticModelBuilder()
) {
    /**
     * Runs the semantic pipeline and returns the public semantic result model.
     */
    fun analyze(chunk: ChunkNode): SemanticAnalysisResult {
        return analyzeSnapshot(chunk).result
    }

    /**
     * Internal pipeline snapshot used by compatibility adapters and focused tests.
     */
    internal fun analyzeSnapshot(
        chunk: ChunkNode,
        context: SemanticWorkspaceContext = SemanticWorkspaceContext()
    ): SemanticPipelineSnapshot {
        val comments = commentAttachPass.attach(chunk)
        val bound = binderPass.bind(chunk, comments, context.overlayGlobals)
        val resolvedBinder = typeResolver.resolve(bound)
        val checker = checkerPass.check(chunk, resolvedBinder)
        val model = semanticModelBuilder.build(chunk, checker.binder, checker.diagnostics, context)
        val result = SemanticAnalysisResult(
            model = model,
            summary = SemanticAnalysisSummary.from(model.getDiagnostics())
        )

        return SemanticPipelineSnapshot(
            chunk = chunk,
            comments = comments,
            binder = checker.binder,
            checker = checker,
            model = model,
            result = result
        )
    }
}

internal data class SemanticWorkspaceContext(
    val currentPath: VirtualPath? = null,
    val workspaceResolver: WorkspaceModuleResolver? = null,
    val overlayGlobals: BuiltinOverlaySnapshot.GlobalsSnapshot = BuiltinOverlayLoader.standaloneGlobals()
)

internal data class SemanticPipelineSnapshot(
    val chunk: ChunkNode,
    val comments: CommentAttachmentIndex,
    val binder: BinderPassResult,
    val checker: CheckerPassResult,
    val model: SemanticModel,
    val result: SemanticAnalysisResult
)
