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
import io.github.dingyi222666.luaparser.semantic.checker.LuaLayoutPropertySuggestion
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
     *
     * Each call is independent: checker/model diagnostics are rebuilt from the
     * supplied chunk and never accumulated across analyzes on the same instance.
     */
    fun analyze(chunk: ChunkNode): SemanticAnalysisResult {
        return analyzeSnapshot(chunk).result
    }

    /**
     * Internal pipeline snapshot used by compatibility adapters and focused tests.
     *
     * Well-formed binding-only locals with no type/call/unused surface report
     * diagnosticCount 0; intentional error fixtures still surface real diagnostics.
     */
    internal fun analyzeSnapshot(
        chunk: ChunkNode,
        context: SemanticWorkspaceContext = SemanticWorkspaceContext()
    ): SemanticPipelineSnapshot {
        val effectiveContext = context.withWorkspaceImportEffects()
        val comments = commentAttachPass.attach(chunk)
        val bound = binderPass.bind(chunk, comments, effectiveContext.overlayGlobals)
        val resolvedBinder = typeResolver.resolve(bound)
        // CheckerPass is re-entered per call with a fresh expression checker; no pipeline-owned
        // diagnostic buffer is retained between analyzes.
        val checker = checkerPass.check(chunk, resolvedBinder, effectiveContext)
        val model = semanticModelBuilder.build(chunk, checker.binder, checker.diagnostics, effectiveContext)
        val publicDiagnostics = model.getDiagnostics()
        val result = SemanticAnalysisResult(
            model = model,
            // Summary always mirrors model diagnostics (including clean-analyze noise filtering).
            summary = SemanticAnalysisSummary.from(publicDiagnostics)
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
    val unresolvedLuaJavaTargets: List<UnresolvedLuaJavaTarget> = emptyList(),
    // Embedder-extended layout properties for loadlayout completions, keyed by the class
    // name written in the layout table (or its Java simple name). Sourced from workspace
    // metadata `lua.layout.properties` via LuaLayoutPropertiesMetadata.parse.
    val layoutPropertyExtensions: Map<String, List<LuaLayoutPropertySuggestion>> = emptyMap(),
    // Engine-provided fallback lambdas captured when withWorkspaceImportEffects composed the
    // active resolve* lambdas. Unlike the composed lambdas — which capture the per-update
    // WorkspaceModuleResolver — these never reference a resolver (e.g. JvmWorkspaceEngine's
    // resolveImportTarget captures classModuleProvider/configuration only), so the fallback
    // chain stays re-derivable when a workspace engine re-points a stored context at a fresh
    // resolver: null the composed resolve* and re-run withWorkspaceImportEffects.
    val baseResolveImportedSymbol: ((String) -> WorkspaceImportedSymbol?)? = null,
    val baseResolveImportTarget: ((String) -> WorkspaceImportedSymbol?)? = null
) {
    fun withWorkspaceImportEffects(): SemanticWorkspaceContext {
        val path = currentPath ?: return this
        val resolver = workspaceResolver ?: return this
        val documentImports = resolver.importedSymbolsFor(path)
        val activeImports = linkedMapOf<String, WorkspaceImportedSymbol>().apply {
            putAll(importedSymbols)
            putAll(documentImports)
        }
        // The context's own lambdas win; baseResolve* only backs the re-pointed case where the
        // composed lambdas were stripped because they captured the stale per-update resolver.
        val fallbackResolveImportedSymbol = resolveImportedSymbol ?: baseResolveImportedSymbol
        val fallbackResolveImportTarget = resolveImportTarget ?: baseResolveImportTarget
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
                    ?: resolver.importTargetSymbol(target)
            },
            // Re-publish the surviving fallbacks so repeated re-pointing keeps the chain derivable.
            baseResolveImportedSymbol = fallbackResolveImportedSymbol,
            baseResolveImportTarget = fallbackResolveImportTarget
        )
    }
}

internal data class UnresolvedLuaJavaTarget(
    val target: String,
    val helperName: String,
    val range: Range?,
    // Wildcard imports are declarative scoping whose members may be supplied at runtime
    // by workspace dex the analyzer cannot mount (e.g. libs/classes.dex) — a different
    // epistemic state from an explicit bindClass string, so they diagnose at INFO.
    val fromWildcardImport: Boolean = false
)

internal data class WorkspaceImportedSymbol(
    val alias: String,
    val moduleName: String,
    val providerPath: VirtualPath,
    val moduleType: io.github.dingyi222666.luaparser.semantic.types.model.ModuleType,
    val valueType: io.github.dingyi222666.luaparser.semantic.types.model.Type = moduleType,
    val kind: io.github.dingyi222666.luaparser.semantic.api.SymbolKind =
        io.github.dingyi222666.luaparser.semantic.api.SymbolKind.MODULE,
    val definitionRange: Range? = null,
    val extendsExistingGlobal: Boolean = false
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
