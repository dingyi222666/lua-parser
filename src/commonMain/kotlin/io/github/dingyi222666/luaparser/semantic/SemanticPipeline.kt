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
import io.github.dingyi222666.luaparser.semantic.checker.javaClassSurface
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
     * Light pipeline for fingerprint-only consumers (the workspace engine's pre-plan
     * upsert pass): parse-attach + bind + type-resolve WITHOUT the checker or model
     * build. The resolved binding can be reused by the authoritative pass for the same chunk.
     */
    internal fun bindForGlobalFingerprint(
        chunk: ChunkNode,
        globals: BuiltinOverlaySnapshot.GlobalsSnapshot,
        comments: CommentAttachmentIndex? = null
    ): BinderPassResult {
        return prepareBinding(
            chunk = chunk,
            context = SemanticWorkspaceContext(overlayGlobals = globals),
            preparedBinder = null,
            comments = comments,
            builtinGlobals = globals
        ).binder
    }

    /**
     * Internal pipeline snapshot used by compatibility adapters and focused tests.
     *
     * Diagnostics come from the same semantic revision consumed by query models.
     */
    internal fun analyzeSnapshot(
        chunk: ChunkNode,
        context: SemanticWorkspaceContext = SemanticWorkspaceContext(),
        checkDiagnostics: Boolean = true,
        preparedBinder: BinderPassResult? = null,
        comments: CommentAttachmentIndex? = null
    ): SemanticPipelineSnapshot {
        val state = prepareBinding(chunk, context, preparedBinder, comments)
        val effectiveContext = state.context
        val resolvedBinder = state.binder
        // CheckerPass is re-entered per call with a fresh expression checker; no pipeline-owned
        // diagnostic buffer is retained between analyzes.
        val checker = if (checkDiagnostics) checkerPass.check(chunk, resolvedBinder, effectiveContext)
            else CheckerPassResult(resolvedBinder, emptyList())
        val model = semanticModelBuilder.build(chunk, checker.binder, checker.diagnostics, effectiveContext)
        val publicDiagnostics = model.getDiagnostics()
        val result = SemanticAnalysisResult(
            model = model,
            // Summary mirrors the diagnostics published by the model.
            summary = SemanticAnalysisSummary.from(publicDiagnostics)
        )

        return SemanticPipelineSnapshot(
            chunk = chunk,
            comments = state.comments,
            binder = checker.binder,
            checker = checker,
            model = model,
            result = result,
            workspaceContext = effectiveContext
        )
    }

    /**
     * Builds the immutable front half of one document revision exactly once. All entry points
     * (fingerprint indexing, query analysis and checker analysis) use this state instead of
     * independently attaching comments, binding declarations and resolving types.
     */
    private fun prepareBinding(
        chunk: ChunkNode,
        context: SemanticWorkspaceContext,
        preparedBinder: BinderPassResult?,
        comments: CommentAttachmentIndex?,
        builtinGlobals: BuiltinOverlaySnapshot.GlobalsSnapshot? = null
    ): DocumentAnalysisState {
        require(preparedBinder == null || preparedBinder.scopeGraph.rootScope.ownerNode === chunk.body) {
            "Prepared binding must belong to the analyzed chunk"
        }
        val effectiveContext = context.withWorkspaceImportEffects()
        val attachments = preparedBinder?.commentAttachments ?: comments ?: commentAttachPass.attach(chunk)
        val bound = preparedBinder ?: binderPass.bind(
            chunk,
            attachments,
            builtinGlobals ?: effectiveContext.overlayGlobals
        )
        return DocumentAnalysisState(
            context = effectiveContext,
            comments = attachments,
            binder = preparedBinder ?: typeResolver.resolve(bound)
        )
    }

    internal fun withWorkspaceContext(snapshot: SemanticPipelineSnapshot, context: SemanticWorkspaceContext): SemanticPipelineSnapshot {
        val model = semanticModelBuilder.build(snapshot.chunk, snapshot.binder, snapshot.checker.diagnostics, context)
        return snapshot.copy(workspaceContext = context, model = model,
            result = SemanticAnalysisResult(model, SemanticAnalysisSummary.from(model.getDiagnostics())))
    }

    /** Completes diagnostics over the same immutable parse and resolved binding. */
    internal fun finishDiagnostics(
        snapshot: SemanticPipelineSnapshot,
        checker: CheckerPassResult = checkerPass.check(snapshot.chunk, snapshot.binder, snapshot.workspaceContext)
    ): SemanticPipelineSnapshot {
        require(checker.binder === snapshot.binder) { "Diagnostics must belong to the analyzed binding" }
        val model = (snapshot.model as? io.github.dingyi222666.luaparser.semantic.model.DefaultSemanticModel)
            ?.withDiagnostics(checker.diagnostics)
            ?: semanticModelBuilder.build(snapshot.chunk, checker.binder, checker.diagnostics, snapshot.workspaceContext)
        return snapshot.copy(
            checker = checker,
            model = model,
            result = SemanticAnalysisResult(model, SemanticAnalysisSummary.from(model.getDiagnostics()))
        )
    }
}

private data class DocumentAnalysisState(
    val context: SemanticWorkspaceContext,
    val comments: CommentAttachmentIndex,
    val binder: BinderPassResult
)

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
    val baseResolveImportTarget: ((String) -> WorkspaceImportedSymbol?)? = null,
    /** Interactive contexts resolve source imports only when a name is queried. */
    val lazyImportedSymbols: Boolean = false,
    /** Require expansion belongs to this inference path, never to a shared resolver. */
    val moduleResolutionPath: Set<VirtualPath> = emptySet(),
    /** Optional measurement hook; called only when an expression misses its type cache. */
    val onExpressionEvaluation: ((io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode) -> Unit)? = null,
    val analysisCheckpoint: () -> Unit = {}
) {
    fun withWorkspaceImportEffects(): SemanticWorkspaceContext {
        val path = currentPath ?: return this
        val resolver = workspaceResolver ?: return this
        val documentImports = if (lazyImportedSymbols) emptyMap() else resolver.importedSymbolsFor(path)
        val activeImports = linkedMapOf<String, WorkspaceImportedSymbol>().apply {
            putAll(importedSymbols)
            putAll(documentImports)
        }
        resolver.deferredDependencyGlobalNamesFor(path).forEach(activeImports::remove)
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
                val activeTarget = resolver.importTargetSymbolFor(path, target)
                val classType = activeTarget?.moduleType?.javaClassSurface()
                if (classType != null && classType.staticMembers.isEmpty() &&
                    classType.instanceMembers.isEmpty() && classType.constructors.isEmpty &&
                    classType.superClass == null && classType.interfaces.isEmpty()) {
                    // A class catalog entry carries identity only. Queries must hydrate
                    // that class through the engine instead of returning the same shell.
                    fallbackResolveImportTarget?.invoke(classType.javaName.binaryName) ?: activeTarget
                } else {
                    activeTarget ?: fallbackResolveImportTarget?.invoke(target)
                        ?: resolver.importTargetSymbol(target)
                }
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

/**
 * Stable symbol id for an imported workspace symbol, shared by the workspace query facade
 * and ReferenceQueries (previously two identical private copies).
 */
internal fun importedSymbolHandle(imported: WorkspaceImportedSymbol): String {
    return "imported:${imported.providerPath.value}:${imported.alias}"
}

internal data class SemanticPipelineSnapshot(
    val chunk: ChunkNode,
    val comments: CommentAttachmentIndex,
    val binder: BinderPassResult,
    val checker: CheckerPassResult,
    val model: SemanticModel,
    val result: SemanticAnalysisResult,
    val workspaceContext: SemanticWorkspaceContext = SemanticWorkspaceContext()
)
