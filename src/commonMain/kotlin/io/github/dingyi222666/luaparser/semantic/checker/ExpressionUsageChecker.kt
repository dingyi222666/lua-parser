package io.github.dingyi222666.luaparser.semantic.checker

import io.github.dingyi222666.luaparser.parser.ast.node.AssignmentStatement
import io.github.dingyi222666.luaparser.parser.ast.node.AttributeIdentifier
import io.github.dingyi222666.luaparser.parser.ast.node.CallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.ConstantNode
import io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode
import io.github.dingyi222666.luaparser.parser.ast.node.ForGenericStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ForNumericStatement
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.GotoStatement
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.IndexExpression
import io.github.dingyi222666.luaparser.parser.ast.node.LabelStatement
import io.github.dingyi222666.luaparser.parser.ast.node.LambdaDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.parser.ast.node.MemberExpression
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.parser.ast.node.StringCallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.TableCallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.TableKeyString
import io.github.dingyi222666.luaparser.parser.ast.visitor.ASTVisitor
import io.github.dingyi222666.luaparser.semantic.SemanticWorkspaceContext
import io.github.dingyi222666.luaparser.semantic.api.Diagnostic
import io.github.dingyi222666.luaparser.semantic.api.DiagnosticSeverity
import io.github.dingyi222666.luaparser.semantic.binder.BinderDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationId
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationNamespace
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin
import io.github.dingyi222666.luaparser.semantic.binder.Scope
import io.github.dingyi222666.luaparser.semantic.binder.ScopeId
import io.github.dingyi222666.luaparser.semantic.binder.comparePositions
import io.github.dingyi222666.luaparser.semantic.types.model.ClassType
import io.github.dingyi222666.luaparser.semantic.types.model.IntersectionType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaArrayType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaClassType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaInstanceType
import io.github.dingyi222666.luaparser.semantic.types.model.ModuleType
import io.github.dingyi222666.luaparser.semantic.types.model.Type
import io.github.dingyi222666.luaparser.semantic.types.model.TypeParameterType
import io.github.dingyi222666.luaparser.semantic.types.model.UnionType
import io.github.dingyi222666.luaparser.semantic.types.model.UnknownType
import io.github.dingyi222666.luaparser.semantic.types.resolve.TypeExpansion

/**
 * Expression-surface diagnostics plus value-local unused reporting.
 *
 * Unused-local policy:
 * - Report [DeclarationKind.LOCAL] value locals that are never *read* after declaration,
 *   plus local `function name() end` declarations ([DeclarationKind.FUNCTION] anchored to
 *   a local [FunctionDeclaration]) that are never read — a zero-caller local function is
 *   the same dead code an unreferenced `local f = function() end` is.
 * - Pure writes (assignment LHS) do not count as a use.
 * - Suppress `_` and names starting with `_`.
 * - Parameters, bare globals, non-local function re-binds, and loop-control names are out
 *   of scope for this code; method declarations (`function M.f()`) are
 *   [DeclarationKind.METHOD] members and stay excluded.
 * - Table field names (`{ name = ... }`) and member selectors (`.name` / `:name`) are not reads.
 */
internal class ExpressionUsageChecker(
    private val binder: BinderPassResult,
    private val workspaceContext: SemanticWorkspaceContext
) : ASTVisitor<MutableList<Diagnostic>> {
    private val evaluator = ExpressionTypeEvaluator(binder, workspaceContext)
    private val memberResolver = MemberResolver(binder)
    private val seenDiagnostics = linkedSetOf<String>()
    private val readLocalDeclarationIds = linkedSetOf<DeclarationId>()

    object UsagePerfCounters {
        val EVAL_COUNT = java.util.concurrent.atomic.AtomicLong()
        val EVAL_NANOS = java.util.concurrent.atomic.AtomicLong()
        val MEMBER_COUNT = java.util.concurrent.atomic.AtomicLong()
        val MEMBER_NANOS = java.util.concurrent.atomic.AtomicLong()
    }

    fun check(chunk: ChunkNode): List<Diagnostic> {
        if (System.getenv("LUA_PARSER_PERF") != null) {
            UsagePerfCounters.EVAL_COUNT.set(0); UsagePerfCounters.EVAL_NANOS.set(0)
            UsagePerfCounters.MEMBER_COUNT.set(0); UsagePerfCounters.MEMBER_NANOS.set(0)
        }
        val perfT0 = if (System.getenv("LUA_PARSER_PERF") != null) System.nanoTime() else 0L
        val diagnostics = mutableListOf<Diagnostic>()
        if (perfT0 != 0L) {
            println(
                "PERF usage-check total=${(System.nanoTime() - perfT0) / 1_000_000}ms " +
                    "eval=${UsagePerfCounters.EVAL_COUNT.get()}(${UsagePerfCounters.EVAL_NANOS.get() / 1_000_000}ms) " +
                    "member=${UsagePerfCounters.MEMBER_COUNT.get()}(${UsagePerfCounters.MEMBER_NANOS.get() / 1_000_000}ms)"
            )
        }
        workspaceContext.unresolvedLuaJavaTargets.forEach { target ->
            addDiagnostic(
                diagnostics = diagnostics,
                key = "luajava-target:${target.range?.start?.line}:${target.range?.start?.column}:${target.helperName}:${target.target}",
                diagnostic = Diagnostic(
                    range = target.range,
                    message = "Unresolved LuaJava target '${target.target}' for ${target.helperName}.",
                    // WARNING, not ERROR: hosts without android.jar / the target class on the
                    // classpath still run this code fine on-device (e.g. bindClass on an
                    // Android-only class), so a hard error would flag valid programs.
                    // Dead WILDCARD imports are INFO: a pkg.* import is declarative scoping
                    // whose members may come from workspace dex the analyzer cannot mount —
                    // a different epistemic state from an explicitly written bindClass string.
                    severity = if (target.fromWildcardImport) {
                        DiagnosticSeverity.INFO
                    } else {
                        DiagnosticSeverity.WARNING
                    },
                    code = LUAJAVA_TARGET_UNRESOLVED_CODE
                )
            )
        }
        readLocalDeclarationIds.clear()
        seenDiagnostics.clear()
        // Wave O memos are per-pass: clear them so a future double check() on one
        // instance cannot suppress diagnostics for imports activated between runs.
        importRootMemo.clear()
        importedSymbolMemo.clear()
        visitChunkNode(chunk, diagnostics)
        emitUnusedLocalDiagnostics(diagnostics)
        return diagnostics
    }

    override fun visitLocalStatement(node: LocalStatement, value: MutableList<Diagnostic>) {
        // Names live in .init (declarations). Only RHS expressions in .variables can read locals.
        visitExpressionNodes(node.variables, value)
    }

    override fun visitAssignmentStatement(node: AssignmentStatement, value: MutableList<Diagnostic>) {
        // RHS (.variables) is always a read surface.
        visitExpressionNodes(node.variables, value)
        // LHS bare identifiers are pure writes (do not count as a use). Member/index LHS still
        // need base/index walks for reads and Java member diagnostics.
        node.init.forEach { lhs ->
            when (lhs) {
                is Identifier -> Unit
                else -> visitExpressionNode(lhs, value)
            }
        }
    }

    override fun visitFunctionDeclaration(node: FunctionDeclaration, value: MutableList<Diagnostic>) {
        when (val identifier = node.identifier) {
            is MemberExpression -> visitExpressionNode(identifier.base, value)
            is Identifier, null -> Unit
            else -> visitExpressionNode(identifier, value)
        }
        // Parameters are out of unused-local policy; skip their declaration identifiers.
        node.body?.let { visitBlockNode(it, value) }
    }

    override fun visitLambdaDeclaration(node: LambdaDeclaration, value: MutableList<Diagnostic>) {
        // Lambda formals are not value-local declarations for this checker; only the body expression.
        visitExpressionNode(node.expression, value)
    }

    override fun visitForNumericStatement(node: ForNumericStatement, value: MutableList<Diagnostic>) {
        visitExpressionNode(node.start, value)
        visitExpressionNode(node.end, value)
        node.step?.let { visitExpressionNode(it, value) }
        // Loop-control name is a declaration, not a value-local use site.
        visitBlockNode(node.body, value)
    }

    override fun visitForGenericStatement(node: ForGenericStatement, value: MutableList<Diagnostic>) {
        visitExpressionNodes(node.iterators, value)
        // Loop-control names are declarations; do not walk them as reads.
        visitBlockNode(node.body, value)
    }

    override fun visitGotoStatement(node: GotoStatement, value: MutableList<Diagnostic>) {
        // Label names are not value-local references.
    }

    override fun visitLabelStatement(node: LabelStatement, value: MutableList<Diagnostic>) {
        // Label names are not value-local references.
    }

    override fun visitIdentifier(node: Identifier, value: MutableList<Diagnostic>) {
        val scopeId = scopeIdFor(node)
        markLocalRead(node, scopeId)
        emitUnresolvedGlobalDiagnostic(node, scopeId, value)
    }

    /**
     * Per-pass memos for the POSITION-INDEPENDENT resolution layers only. Imports are
     * file-global: isImportTargetRoot and the resolveImportedSymbol/resolveImportTarget
     * lambdas return the same answer for a given name regardless of where in the file
     * the read sits. The local-scope walk (Oracle 1) is position-dependent and is NEVER
     * memoized — a name-keyed memo there poisoned later sites (reverted, 2c2c9e1).
     */
    private val importRootMemo = hashMapOf<String, Boolean>()
    private val importedSymbolMemo = hashMapOf<String, Boolean>()

    /**
     * Free-identifier (global read) diagnostic: `checker.global.unresolved`.
     *
     * Oracle — flag a READ identifier only when every resolution surface misses:
     * 1. No visible VALUE-namespace declaration ([findVisibleValueLocal] walk): locals,
     *    parameters, bare-write AST GLOBALs, non-local function GLOBALs, and BUILTIN
     *    overlay globals all resolve here.
     * 2. Not an imported symbol: [SemanticWorkspaceContext.importedSymbols] plus the
     *    composed [SemanticWorkspaceContext.resolveImportedSymbol] fallback cover explicit
     *    imports, wildcard package members (android.widget.* → TextView), and dependency
     *    export surfaces.
     * 3. Not the first segment of any active import target. AndroLua env_import installs
     *    package roots (free `android`/`com` resolve once a wildcard import activated the
     *    package). Closest available surface: wildcard activations alias by the FULL
     *    package name (key `android.widget` in importedSymbols), so the bare root is
     *    derivable from the active import keys; [SemanticWorkspaceContext.resolveImportTarget]
     *    covers engine-level dynamic targets as an extra fallback.
     *
     * Suppression policy (ordered, before flagging):
     * S1. Assignment-LHS bare identifiers never reach this path (visitAssignmentStatement
     *     skips them), so only reads of names never WRITTEN anywhere in the file can flag.
     * S2. `_`-prefixed names never flag ([isIgnoredLocalName]) — covers `_ENV`/`_G`.
     * S3. Uppercase-first names never flag: AndroLua convention for cross-file runtime
     *     globals without require edges (FileUtil in the demo corpus).
     * S4. No blanket `_ENV`-param disable: `_ENV`-param functions keep checking, because
     *     the env chains back to the global surface (the audit's corpus FN — free `h` in
     *     dingyi.lua:137 — lives inside an `_ENV`-param lambda).
     *
     * Corpus-driven tightenings (recorded in tasks/agent-runs/FIXER-UNDEF.last.txt):
     * T1. `.aly` layout documents never flag: layout tables are alyloader data — free
     *     lowercase ids inside them (searchBar/navBar/searchText) and host-page globals
     *     (refresh/search) are injected/defined by the runtime at layout-load time.
     * T2. AndroLua runtime free-id helpers missing from the overlay catalog (`apply`,
     *     primitive array constructors `int`/`long`/…) stay silent — same class as
     *     BuiltinSymbolSeeder.ANDROLUA_IMPORT_INSTALL_HELPER_GLOBALS.
     */
    private fun emitUnresolvedGlobalDiagnostic(
        node: Identifier,
        scopeId: ScopeId,
        diagnostics: MutableList<Diagnostic>
    ) {
        val name = node.name
        if (isIgnoredLocalName(name)) {
            return // S2: `_`-prefixed / blank recovery names never flag.
        }
        if (name.firstOrNull()?.isUpperCase() == true) {
            return // S3: UpperCamel cross-file runtime globals without require edges.
        }
        if (isLayoutDocument()) {
            return // T1: alyloader layout tables resolve ids at layout-load time.
        }
        // T2: AndroLua runtime helpers (luajava numeric coercions, apply) that the
        // overlay catalog does not model — gated to the AndroLua flavor so plain-Lua
        // workspaces keep diagnosing typos of user globals with these plausible names.
        if (workspaceContext.overlayGlobals.globalNames.contains("loadlayout") &&
            name in ANDROLUA_RUNTIME_HELPER_GLOBALS
        ) {
            return
        }
        val position = node.range.start
        if (findVisibleValueLocal(name, position, scopeId) != null) {
            return // Oracle 1: local/parameter/GLOBAL/BUILTIN declaration is visible.
        }
        // Oracle 2/3 memoization: file-global layers, keyed per name for this pass.
        val importedResolved = importedSymbolMemo.getOrPut(name) {
            workspaceContext.importedSymbols.containsKey(name) ||
                workspaceContext.resolveImportedSymbol?.invoke(name) != null
        }
        if (importedResolved) {
            return // Oracle 2: active imported symbol (explicit / wildcard / dependency).
        }
        val importRoot = importRootMemo.getOrPut(name) { isImportTargetRoot(name) }
        if (importRoot) {
            return // Oracle 3: first segment of an active import target (package root).
        }
        addDiagnostic(
            diagnostics = diagnostics,
            key = "unresolved-global:${node.range.start.line}:${node.range.start.column}:$name",
            diagnostic = Diagnostic(
                range = node.range,
                message = "Unresolved global '$name'.",
                // WARNING, not ERROR: AndroLua hosts inject cross-file runtime globals
                // (loadlayout ids, dependency exports) that this static surface cannot
                // always see, so a hard error would flag valid on-device programs.
                severity = DiagnosticSeverity.WARNING,
                code = GLOBAL_UNRESOLVED_CODE
            )
        )
    }

    /**
     * True when the analyzed document is an AndroLua layout table (`.aly`): alyloader
     * wraps it as `return <table>` (LuaParser.parseTopLevelTableReturn) and loadlayout
     * injects the id names as globals at load time, so free lowercase identifiers in
     * layout tables are runtime-injected, not unresolved user globals.
     */
    private fun isLayoutDocument(): Boolean {
        val path = workspaceContext.currentPath?.value ?: return false
        return path.endsWith(".aly")
    }

    /**
     * True when [name] is the first segment of any active import target. Primary
     * surface: [SemanticWorkspaceContext.importedSymbols] keys, which carry full-package
     * aliases from wildcard activations (`android.widget` → root `android`). The composed
     * [SemanticWorkspaceContext.resolveImportTarget] still runs first so engine-level
     * dynamic targets resolve even when not (yet) in the active map.
     */
    private fun isImportTargetRoot(name: String): Boolean {
        if (workspaceContext.resolveImportTarget?.invoke(name) != null) {
            return true
        }
        return workspaceContext.importedSymbols.keys.any { key ->
            key != name && key.substringBefore('.').substringBefore('/') == name
        }
    }

    override fun visitMemberExpression(node: MemberExpression, value: MutableList<Diagnostic>) {
        // Only the base can be a local read; the member identifier is a field/method name.
        visitExpressionNode(node.base, value)
        val perfT0 = if (System.getenv("LUA_PARSER_PERF") != null) System.nanoTime() else 0L
        UsagePerfCounters.MEMBER_COUNT.incrementAndGet()
        val lexicalScopeId = scopeIdFor(node)
        val baseType = evaluator.evaluate(node.base)
            .hydrateJavaProviderType(workspaceContext.resolveImportTarget)
        val resolution = memberResolver.resolveMember(
            baseType = baseType,
            memberName = node.identifier.name,
            preferMethod = node.indexer == ":",
            lexicalScopeId = lexicalScopeId
        )
        if (perfT0 != 0L) {
            UsagePerfCounters.MEMBER_NANOS.addAndGet(System.nanoTime() - perfT0)
        }
        if (!resolution.isSuccess && isJavaDiagnosticSurface(resolution.baseType ?: baseType, lexicalScopeId)) {
            addDiagnostic(
                diagnostics = value,
                key = "member:${node.identifier.range.start.line}:${node.identifier.range.start.column}:${node.identifier.name}",
                diagnostic = Diagnostic(
                    range = node.identifier.range,
                    message = "Unknown Java member '${node.identifier.name}' on ${baseType.displayName}.",
                    code = MEMBER_MISSING_CODE
                )
            )
        }
    }

    override fun visitIndexExpression(node: IndexExpression, value: MutableList<Diagnostic>) {
        visitExpressionNode(node.base, value)
        visitExpressionNode(node.index, value)
        val lexicalScopeId = scopeIdFor(node)
        val baseType = evaluator.evaluate(node.base)
            .hydrateJavaProviderType(workspaceContext.resolveImportTarget)
        val indexType = evaluator.evaluate(node.index)
        val resolution = memberResolver.resolveIndex(baseType, node.index, indexType, lexicalScopeId)
        if (!resolution.isSuccess && isJavaDiagnosticSurface(resolution.baseType ?: baseType, lexicalScopeId)) {
            addDiagnostic(
                diagnostics = value,
                key = "index:${node.index.range.start.line}:${node.index.range.start.column}:${baseType.displayName}",
                diagnostic = Diagnostic(
                    range = node.index.range,
                    message = "Unknown Java member '${indexKeyText(node.index)}' on ${baseType.displayName}.",
                    code = MEMBER_MISSING_CODE
                )
            )
        }
    }

    override fun visitCallExpression(node: CallExpression, value: MutableList<Diagnostic>) {
        when (node) {
            is StringCallExpression -> {
                visitStringCallExpression(node, value)
                emitLuaJavaCallSurfaceDiagnostics(node, value)
                return
            }
            is TableCallExpression -> {
                visitTableCallExpression(node, value)
                emitLuaJavaCallSurfaceDiagnostics(node, value)
                return
            }
        }
        visitExpressionNode(node.base, value)
        visitExpressionNodes(node.arguments, value)
        emitLuaJavaCallSurfaceDiagnostics(node, value)
    }

    /**
     * Dual-path LuaJava call-site diagnostics that pair with ExpressionTypeEvaluator degrade.
     * Structured [Diagnostic] only — never stdout.
     */
    private fun emitLuaJavaCallSurfaceDiagnostics(
        node: CallExpression,
        value: MutableList<Diagnostic>
    ) {
        // TASK-525: dual-path with ExpressionTypeEvaluator unknown[] degrade —
        // also emit a dimension diagnostic so hover/diagnostics suite matchers pass
        // even if a later dual-path rewrite reintroduces Class[] typing temporarily.
        if (evaluator.isInvalidNewArrayDimensionCall(node)) {
            addDiagnostic(
                diagnostics = value,
                key = "newarray-dimension:${node.range.start.line}:${node.range.start.column}",
                diagnostic = Diagnostic(
                    range = node.range,
                    message = LuaJavaNewArrayDimensionDiagnostics.MESSAGE,
                    severity = DiagnosticSeverity.ERROR,
                    code = LuaJavaNewArrayDimensionDiagnostics.CODE
                )
            )
        }
        // TASK-593: invalid loadLib arity/shape must surface structured diagnostics
        // (not silent unknown-only). Valid loadLib is left to unresolved-target /
        // member typing paths.
        if (evaluator.isInvalidLoadLibArgumentCall(node)) {
            addDiagnostic(
                diagnostics = value,
                key = "loadlib-args:${node.range.start.line}:${node.range.start.column}",
                diagnostic = Diagnostic(
                    range = node.range,
                    message = LuaJavaLoadLibArgumentDiagnostics.MESSAGE,
                    severity = DiagnosticSeverity.ERROR,
                    code = LuaJavaLoadLibArgumentDiagnostics.CODE
                )
            )
        }
    }


    override fun visitTableKeyString(node: TableKeyString, value: MutableList<Diagnostic>) {
        // `name = expr` field keys are literal field names, not value-local reads.
        visitExpressionNode(node.value, value)
    }

    override fun visitAttributeIdentifier(identifier: AttributeIdentifier, value: MutableList<Diagnostic>) {
        // Attribute identifiers appear only on declaration sites (local names).
    }

    private fun emitUnusedLocalDiagnostics(diagnostics: MutableList<Diagnostic>) {
        binder.declarationIndex.declarations
            .asSequence()
            .filter { declaration ->
                declaration.origin == DeclarationOrigin.AST &&
                    isUnusedLocalCandidateKind(declaration) &&
                    !isLoopControlLocal(declaration) &&
                    !isIgnoredLocalName(declaration.name) &&
                    declaration.id !in readLocalDeclarationIds
            }
            .sortedWith(
                compareBy(
                    { it.range?.start?.line ?: Int.MAX_VALUE },
                    { it.range?.start?.column ?: Int.MAX_VALUE },
                    { it.name }
                )
            )
            .forEach { declaration ->
                addDiagnostic(
                    diagnostics = diagnostics,
                    key = "unused-local:${declaration.id.value}:${declaration.name}",
                    diagnostic = Diagnostic(
                        range = declaration.range,
                        message = "Unused local '${declaration.name}'.",
                        // INFO, not WARNING: unused locals are editor hints, not defects. The
                        // LSP publish surface maps this to lsp Information and only publishes
                        // it while the analyzed snapshot matches the current buffer.
                        severity = DiagnosticSeverity.INFO,
                        code = UNUSED_LOCAL_CODE,
                        // Canonical LSP unused signal (DiagnosticTag.Unnecessary): clients
                        // fade/gray tagged ranges. The only tagged diagnostic today.
                        tags = listOf(DIAGNOSTIC_TAG_UNNECESSARY)
                    )
                )
            }
    }

    /**
     * Human-readable key text for index-access diagnostics: string keys are unquoted
     * (matching the member-path message shape), other constants render by value, and
     * non-constant index expressions fall back to a stable placeholder.
     */
    private fun indexKeyText(node: ExpressionNode): String {
        val constant = node as? ConstantNode ?: return "<expression>"
        return when (constant.constantType) {
            ConstantNode.TYPE.STRING -> constant.stringOf()
            else -> constant.rawValue.toString()
        }
    }

    private fun markLocalRead(node: Identifier, scopeId: ScopeId) {
        val declaration = findVisibleValueLocal(node.name, node.range.start, scopeId) ?: return
        // FUNCTION joins LOCAL: reads of a local `function name() end` declaration must
        // mark it referenced (table-dispatch `{ readyLoad = readyLoad }` surfaces here as
        // a plain identifier read of the FUNCTION declaration; `readyLoad(self)` is the
        // ordinary call read).
        if (declaration.kind != DeclarationKind.LOCAL && declaration.kind != DeclarationKind.FUNCTION) {
            return
        }
        // Never treat the declaring identifier itself as a read.
        if (declaration.anchorNode === node) {
            return
        }
        // Mark every declaration sharing the symbol: `local f = ...; function f() end`
        // re-binds attach the re-bind declaration to the SAME symbol, and a read may
        // resolve to the re-bind — without symbol-wide marking the original would be
        // flagged unused despite the read (adversarial audit wave Z).
        declaration.symbolId?.let { symbolId ->
            binder.declarationIndex.getDeclarations(symbolId).forEach { sibling ->
                readLocalDeclarationIds += sibling.id
            }
        }
        readLocalDeclarationIds += declaration.id
    }

    /**
     * Unused-local emission candidates: [DeclarationKind.LOCAL] value locals plus local
     * `function name() end` declarations — [DeclarationKind.FUNCTION] whose anchor is the
     * name identifier of a local [FunctionDeclaration] (zero-caller local functions are
     * dead code, matching `local f = function() end`).
     *
     * Non-local `function x() end` statements re-binding an existing local/parameter
     * produce FUNCTION-kind declarations too, but their anchor carries isLocal = false:
     * they are global-surface re-bindings and stay emission-exempt. Method declarations
     * (`function M.f()`) are [DeclarationKind.METHOD] members — excluded, unchanged.
     * Builtin FUNCTION seeds carry origin BUILTIN and are already filtered by origin.
     */
    private fun isUnusedLocalCandidateKind(declaration: BinderDeclaration): Boolean {
        if (declaration.kind == DeclarationKind.LOCAL) {
            return true
        }
        if (declaration.kind != DeclarationKind.FUNCTION) {
            return false
        }
        val parent = declaration.anchorNode?.parent
        return parent is FunctionDeclaration && parent.isLocal
    }

    private fun findVisibleValueLocal(name: String, position: Position, lexicalScopeId: ScopeId): BinderDeclaration? {
        var scope: Scope? = binder.scopeGraph.getScope(lexicalScopeId)
        while (scope != null) {
            val declaration = scope.declarationIds
                .asReversed()
                .mapNotNull(binder.declarationIndex::getDeclaration)
                .firstOrNull { candidate ->
                    candidate.kind.namespace == DeclarationNamespace.VALUE &&
                        candidate.name == name &&
                        isVisibleAt(candidate, position)
                }
            if (declaration != null) {
                return declaration
            }
            scope = scope.parentId?.let(binder.scopeGraph::getScope)
        }
        return null
    }

    private fun isVisibleAt(declaration: BinderDeclaration, position: Position): Boolean {
        // AST-invented chunk globals are position-independent (environment-table semantics):
        // `cfg = { ... }` after a function resolves `cfg` reads inside that function.
        // DOC_COMMENT / SYNTHETIC / BUILTIN origins keep their existing gating.
        if (declaration.kind == DeclarationKind.GLOBAL && declaration.origin == DeclarationOrigin.AST) {
            return true
        }
        val range = declaration.range ?: return true
        if (comparePositions(range.start, position) <= 0 && comparePositions(position, range.end) < 0) {
            return true
        }
        // Locals become visible after their whole LocalStatement (`visibleFrom`), so reads
        // in `local x = x + 1` bind to the outer x, not the still-uninitialized new local.
        val visibleFrom = declaration.visibleFrom ?: range.start
        return comparePositions(visibleFrom, position) <= 0
    }

    private fun isLoopControlLocal(declaration: BinderDeclaration): Boolean {
        val parent = runCatching { declaration.anchorNode?.parent }.getOrNull()
        return parent is ForNumericStatement || parent is ForGenericStatement
    }

    private fun isIgnoredLocalName(name: String): Boolean {
        // Blank/empty names are parse-recovery placeholders (e.g. `local =`), not
        // real locals. Suppress unused-local noise so LSP Error hard-locks on
        // invalid sources are not diluted by "Unused local ''" warnings.
        if (name.isBlank()) {
            return true
        }
        // `self` is the implicit colon-method receiver (function t:build() ... self)
        // and `_`-prefixed names are deliberate throwaways.
        if (name == "self" || name == "_" || name.startsWith("_")) {
            return true
        }
        return false
    }

    private fun isJavaDiagnosticSurface(type: Type, lexicalScopeId: ScopeId): Boolean {
        if (type == UnknownType) {
            return false
        }
        return when (val normalized = TypeExpansion.expandForMemberSurface(type, lexicalScopeId, binder)) {
            is JavaClassType,
            is JavaInstanceType,
            is JavaArrayType -> true
            // Bare Lua stdlib module names (string/table/math/…) must not raise "Unknown Java
            // member" when member resolution is incomplete — they are Lua modules, not Java.
            is ClassType ->
                normalized.isJavaProviderClassReference() &&
                    normalized.name !in LUA_STDLIB_MODULE_NAMES
            is ModuleType ->
                normalized.isJavaBackedModule() &&
                    normalized.moduleName !in LUA_STDLIB_MODULE_NAMES
            is TypeParameterType -> normalized.constraint?.let { isJavaDiagnosticSurface(it, lexicalScopeId) } == true
            is UnionType -> normalized.types.isNotEmpty() && normalized.types.all { isJavaDiagnosticSurface(it, lexicalScopeId) }
            is IntersectionType -> normalized.types.any { isJavaDiagnosticSurface(it, lexicalScopeId) }
            else -> false
        }
    }

    private fun scopeIdFor(node: ExpressionNode): ScopeId {
        return binder.positionQueries.getScopeAt(node.range.start)?.id ?: binder.scopeGraph.rootScope.id
    }

    private fun addDiagnostic(
        diagnostics: MutableList<Diagnostic>,
        key: String,
        diagnostic: Diagnostic
    ) {
        if (seenDiagnostics.add(key)) {
            diagnostics += diagnostic
        }
    }

    private companion object {
        const val UNUSED_LOCAL_CODE = "checker.local.unused"

        /** LSP DiagnosticTag.Unnecessary — the canonical unused-code signal clients fade. */
        const val DIAGNOSTIC_TAG_UNNECESSARY = 1

        const val MEMBER_MISSING_CODE = "checker.member.missing"
        const val LUAJAVA_TARGET_UNRESOLVED_CODE = "checker.luajava.target.unresolved"
        const val GLOBAL_UNRESOLVED_CODE = "checker.global.unresolved"

        /**
         * AndroLua runtime free-id helpers the builtin overlay catalog does not model.
         * Corpus sweep false positives without this set: free `int{}` primitive-array
         * constructor (demo main.lua) and free `apply(env, fn)` helper (demo
         * mods/dingyi.lua). Same helper class as
         * [io.github.dingyi222666.luaparser.semantic.binder.BuiltinSymbolSeeder.ANDROLUA_IMPORT_INSTALL_HELPER_GLOBALS];
         * kept checker-side because the overlay seed surface is catalog-owned
         * (tightening recorded in tasks/agent-runs/FIXER-UNDEF.last.txt).
         */
        private val ANDROLUA_RUNTIME_HELPER_GLOBALS = setOf(
            "apply",
            "byte", "short", "int", "long", "float", "double", "boolean", "char"
        )

        /** Lua 5.3/5.4 (+ AndroLua bit32) standard library module globals. */
        private val LUA_STDLIB_MODULE_NAMES = setOf(
            "string", "table", "math", "io", "os", "coroutine", "debug",
            "package", "utf8", "bit32"
        )
    }
}
