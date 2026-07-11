package io.github.dingyi222666.luaparser.semantic

import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.Range
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
    private val semanticModelBuilder: SemanticModelBuilder = SemanticModelBuilder()
) {
    private val checkerPass = CheckerPass()
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
        val effectiveContext = context.withWorkspaceImportEffects()
        val comments = commentAttachPass.attach(chunk)
        val bound = binderPass.bind(chunk, comments, effectiveContext.overlayGlobals)
        val resolvedBinder = typeResolver.resolve(bound)
        val checker = checkerPass.check(chunk, resolvedBinder, effectiveContext)
        val model = semanticModelBuilder.build(chunk, checker.binder, checker.diagnostics, effectiveContext)
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
            result = result,
            workspaceContext = effectiveContext
        )
    }
}

internal data class SemanticWorkspaceContext(
    val currentPath: VirtualPath? = null,
    val workspaceResolver: WorkspaceModuleResolver? = null,
    val overlayGlobals: BuiltinOverlaySnapshot.GlobalsSnapshot = BuiltinOverlayLoader.standaloneGlobals(),
    val importedSymbols: Map<String, WorkspaceImportedSymbol> = emptyMap(),
    val resolveImportedSymbol: ((String) -> WorkspaceImportedSymbol?)? = null,
    val resolveImportTarget: ((String) -> WorkspaceImportedSymbol?)? = null,
    val unresolvedLuaJavaTargets: List<UnresolvedLuaJavaTarget> = emptyList()
) {
    fun withWorkspaceImportEffects(): SemanticWorkspaceContext {
        val path = currentPath ?: return this
        val resolver = workspaceResolver ?: return this
        val documentImports = resolver.importedSymbolsFor(path)
        val activeImports = linkedMapOf<String, WorkspaceImportedSymbol>().apply {
            putAll(importedSymbols)
            putAll(documentImports)
        }
        val fallbackResolveImportedSymbol = resolveImportedSymbol
        val fallbackResolveImportTarget = resolveImportTarget
        return copy(
            // Expose the current-file active import set so lexical completions and symbol queries
            // see MODULE-kind imported Java classes/packages for this file only.
            importedSymbols = activeImports,
            resolveImportedSymbol = { name ->
                activeImports[name]
                    ?: resolver.importedSymbolFor(path, name)
                    ?: fallbackResolveImportedSymbol?.invoke(name)
            },
            resolveImportTarget = { target ->
                // Prefer path-scoped source import activation first, then engine fallback
                // (configured imports / unrestricted JVM target resolution for dynamic calls).
                resolver.importTargetSymbolFor(path, target)
                    ?: fallbackResolveImportTarget?.invoke(target)
            }
        )
    }
}

internal data class UnresolvedLuaJavaTarget(
    val target: String,
    val helperName: String,
    val range: Range?
)

internal data class WorkspaceImportedSymbol(
    val alias: String,
    val moduleName: String,
    val providerPath: VirtualPath,
    val moduleType: io.github.dingyi222666.luaparser.semantic.types.model.ModuleType
)

internal data class SemanticPipelineSnapshot(
    val chunk: ChunkNode,
    val comments: CommentAttachmentIndex,
    val binder: BinderPassResult,
    val checker: CheckerPassResult,
    val model: SemanticModel,
    val result: SemanticAnalysisResult,
    val workspaceContext: SemanticWorkspaceContext = SemanticWorkspaceContext()
)
