package io.github.dingyi222666.luaparser.interop.jvm

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.ArrayConstructorExpression
import io.github.dingyi222666.luaparser.parser.ast.node.AttributeIdentifier
import io.github.dingyi222666.luaparser.parser.ast.node.CallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.CommentStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ConstantNode
import io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode
import io.github.dingyi222666.luaparser.parser.ast.node.ForGenericStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ForNumericStatement
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.LambdaDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.MemberExpression
import io.github.dingyi222666.luaparser.parser.ast.node.StringCallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.TableCallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.TableConstructorExpression
import io.github.dingyi222666.luaparser.parser.ast.node.TableKeyString
import io.github.dingyi222666.luaparser.parser.ast.visitor.ASTVisitor
import io.github.dingyi222666.luaparser.semantic.SemanticWorkspaceContext
import io.github.dingyi222666.luaparser.semantic.UnresolvedLuaJavaTarget
import io.github.dingyi222666.luaparser.semantic.WorkspaceImportedSymbol
import io.github.dingyi222666.luaparser.semantic.workspace.DocumentFacts
import io.github.dingyi222666.luaparser.semantic.workspace.DocumentFactsCollector
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceInput
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceSnapshot

/**
 * JVM workspace engine for Android-Lua / LuaJava reflective modules.
 * Host android.jar discovery uses ANDROID_HOME/SDK_ROOT and well-known SDK roots; never G:/.
 */
class JvmWorkspaceEngine(
    private val workspaceParserFactory: (LuaVersion) -> LuaParser = { version -> LuaParser(version) },
    private val classModuleProvider: JvmClassModuleProvider = JvmClassModuleProvider(),
    private val configuration: JvmWorkspaceConfiguration = JvmWorkspaceConfiguration()
) : LuaWorkspaceEngine(workspaceParserFactory) {
    /**
     * Per-document facts + AST import targets, keyed by path and guarded by the exact source text.
     *
     * `extraProviders` is called with the *whole* workspace on every build and every update, so
     * re-deriving these for untouched files made a single keystroke walk every file twice. Both
     * products are deterministic in (path, source), so an edit only invalidates its own document.
     */
    private class DocumentImportFacts(
        val source: String,
        val facts: DocumentFacts,
        val importTargets: Set<String>,
        val freeClassNames: Set<String>
    )

    private val documentImportFactsCache = mutableMapOf<VirtualPath, DocumentImportFacts>()

    private fun documentImportFacts(path: VirtualPath, source: String): DocumentImportFacts {
        documentImportFactsCache[path]?.takeIf { it.source == source }?.let { return it }
        val scan = scanDocument(parseWorkspaceSource(path, source))
        return DocumentImportFacts(
            source = source,
            // Facts come from the engine-wide memo so a document is walked once, not once here
            // and again in analyzeFile.
            facts = documentFacts(path, source),
            importTargets = scan.importTargets,
            freeClassNames = scan.freeClassNames
        ).also { documentImportFactsCache[path] = it }
    }

    override fun extraProviders(input: LuaWorkspaceInput): Map<VirtualPath, WorkspaceSnapshot.FileSnapshot> {
        val baseConfiguration = configuration.overlay(JvmWorkspaceConfiguration.fromMetadata(input.metadata))
        // Single parse per file feeds both document facts and AST import discovery.
        val perFile = input.files.mapValues { (path, source) -> documentImportFacts(path, source) }
        documentImportFactsCache.keys.retainAll(input.files.keys)
        val documentFacts = perFile.mapValues { (_, entry) -> entry.facts }
        val astImportTargets = perFile.values.flatMapTo(linkedSetOf()) { it.importTargets }
        val resolvedConfiguration = configurationWithWildcardImportPrefixes(
            baseConfiguration,
            documentFacts,
            astImportTargets
        )
        // Explicit / bindClass / simple import targets only. Wildcard package modules list under
        // packageProvidersFor (packages/ paths only). Shallow class providers for package
        // members come from packageMemberClassProvidersFor (classes/ paths) so package-list
        // keys never mix __jvm__/classes prefixes.
        // Free UpperCamel identifiers still activate path-scoped import aliases in
        // workspaceContext (Android-Lua bare Locale/TextView). They must NOT auto-mount
        // reflective class providers into extraProviders: short require("Arrays") without
        // CLASSES_METADATA / explicit import/bindClass must stay unresolved (TASK-628).
        val sourceDiscoveredClasses = collectSourceDiscoveredClasses(
            documentFacts,
            resolvedConfiguration,
            astImportTargets,
            freeClassNames = emptySet()
        )
        val packageTargets = collectWildcardImportTargets(baseConfiguration, documentFacts, astImportTargets)
            .mapNotNull(::normalizePackageProviderTarget)
            .toCollection(linkedSetOf())
        val packageProviders = classModuleProvider.packageProvidersFor(
            packageTargets,
            resolvedConfiguration
        )
        val packageMemberClassProviders = classModuleProvider.packageMemberClassProvidersFor(
            packageTargets,
            resolvedConfiguration
        )
        val providerConfiguration = if (sourceDiscoveredClasses.isEmpty()) {
            resolvedConfiguration
        } else {
            resolvedConfiguration.copy(
                classes = (resolvedConfiguration.classes + sourceDiscoveredClasses)
                    .toCollection(linkedSetOf())
            )
        }
        // Package modules + shallow class providers first; explicit/full providers win on
        // path collision so bindClass / configured classes keep deep reflection surfaces.
        return packageProviders + packageMemberClassProviders + classModuleProvider.providersFor(providerConfiguration)
    }

    internal override fun workspaceContext(
        input: LuaWorkspaceInput,
        path: VirtualPath,
        snapshot: WorkspaceSnapshot
    ): SemanticWorkspaceContext {
        val baseConfiguration = configuration.overlay(JvmWorkspaceConfiguration.fromMetadata(input.metadata))
        // Path-scoped only: never re-parse the full multi-file workspace for each document.
        // Snapshot documentFacts from analyzeFile are authoritative; fall back to a single-path collect.
        val currentSource = input.files[path]
            ?: snapshot.files[path]?.semanticFile?.source
        // Facts and import targets were already derived for this exact source in extraProviders;
        // reuse them instead of re-walking the document a second time per analyzed file.
        val currentEntry = currentSource?.let { source -> documentImportFacts(path, source) }
        val currentFacts = snapshot.files[path]?.documentFacts ?: currentEntry?.facts
        // Configured imports are workspace-wide; source imports stay scoped to the current file.
        val configuredImports = collectConfiguredImports(baseConfiguration)
        val currentAstImportTargets = currentEntry?.importTargets.orEmpty()
        val resolvedConfiguration = configurationWithWildcardImportPrefixes(
            baseConfiguration,
            currentFacts?.let { mapOf(path to it) }.orEmpty(),
            currentAstImportTargets
        )
        val freeClassNames = currentEntry?.freeClassNames.orEmpty()
        val sourceImports = collectSourceImports(
            currentFacts,
            resolvedConfiguration,
            currentAstImportTargets,
            freeClassNames
        )
        val activeImports = linkedMapOf<String, WorkspaceImportedSymbol>().apply {
            putAll(configuredImports)
            putAll(sourceImports)
        }
        val workspaceResolver = io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceModuleResolver(snapshot)
        return SemanticWorkspaceContext(
            currentPath = path,
            workspaceResolver = workspaceResolver,
            overlayGlobals = snapshot.builtinOverlay.globals,
            // Only base configured imports go in the context map; document source imports are
            // re-applied by SemanticWorkspaceContext.withWorkspaceImportEffects() for this path.
            // Keep activeImports here so current-file analysis already sees both layers.
            importedSymbols = activeImports,
            resolveImportedSymbol = { name ->
                activeImports[name]
            },
            // Dynamic import()/import "..." targets must resolve even when not yet on the
            // sourceImports activation set for this path (engine-level JVM resolution).
            resolveImportTarget = { target ->
                classModuleProvider.importedSymbolForTarget(target, resolvedConfiguration)
            },
            unresolvedLuaJavaTargets = collectUnresolvedLuaJavaTargets(
                currentFacts,
                resolvedConfiguration,
                workspaceResolver
            )
        )
    }

    private fun collectUnresolvedLuaJavaTargets(
        facts: DocumentFacts?,
        configuration: JvmWorkspaceConfiguration,
        workspaceResolver: io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceModuleResolver
    ): List<UnresolvedLuaJavaTarget> {
        if (facts == null) {
            return emptyList()
        }
        return facts.jvmClassLoads
            .asSequence()
            .filter { it.kind in diagnosticLuaJavaClassLoadKinds }
            .filter { fact ->
                classModuleProvider.importedClassName(fact.target, configuration) == null &&
                    (fact.kind != DocumentFacts.JvmClassLoadKind.IMPORT_CALL ||
                        workspaceResolver.importTargetSymbol(fact.target) == null)
            }
            .map { fact ->
                UnresolvedLuaJavaTarget(
                    target = fact.target,
                    helperName = luaJavaHelperName(fact.kind),
                    range = fact.range
                )
            }
            .distinctBy { target ->
                listOf(
                    target.range?.start?.line,
                    target.range?.start?.column,
                    target.range?.end?.line,
                    target.range?.end?.column,
                    target.helperName,
                    target.target
                )
            }
            .toList()
    }

    private fun collectConfiguredImports(
        configuration: JvmWorkspaceConfiguration
    ): Map<String, WorkspaceImportedSymbol> {
        val imported = linkedMapOf<String, WorkspaceImportedSymbol>()
        configuration.normalized().androluaImports.forEach { importText ->
            // Prefer package/class symbol resolution over expanding every wildcard member.
            classModuleProvider.importedSymbolForTarget(importText, configuration)?.let { symbol ->
                imported[symbol.alias] = symbol
            }
            // Bare AndroLua short names (TextView/Button) still need class alias activation.
            if (!isWildcardOrPackageTarget(importText)) {
                classModuleProvider.importedClassNames(importText, configuration)
                    .mapNotNull { classModuleProvider.importedSymbol(it, configuration) }
                    .forEach { imported[it.alias] = it }
            }
        }
        return imported
    }

    private fun collectSourceImports(
        facts: DocumentFacts?,
        configuration: JvmWorkspaceConfiguration,
        astImportTargets: Collection<String> = emptyList(),
        freeClassNames: Collection<String> = emptyList()
    ): Map<String, WorkspaceImportedSymbol> {
        val imported = linkedMapOf<String, WorkspaceImportedSymbol>()
        fun activate(target: String) {
            // Package/wildcard targets activate package module only; class members resolve via
            // WorkspaceModuleResolver.packageMembers + shallow class providers (not deep expand).
            classModuleProvider.importedSymbolForTarget(target, configuration)?.let { symbol ->
                imported[symbol.alias] = symbol
            }
            if (!isWildcardOrPackageTarget(target)) {
                classModuleProvider.importedClassNames(target, configuration)
                    .mapNotNull { classModuleProvider.importedSymbol(it, configuration) }
                    .forEach { imported[it.alias] = it }
            }
        }
        facts?.sourceImports?.forEach { importFact ->
            activate(importFact.target)
        }
        // AST-derived table import targets keep path-scoped activation even when
        // DocumentFacts sequence-key filtering drops import({ "A", "B" }) entries.
        astImportTargets.forEach(::activate)
        // Free UpperCamel identifiers (Locale / TextView) fall back through default Android-Lua
        // import prefixes when not bound by an explicit import/loadLib/bindClass target.
        freeClassNames.forEach { name ->
            if (name !in imported) {
                activate(name)
            }
        }
        return imported
    }

    private fun collectSourceDiscoveredClasses(
        documentFacts: Map<VirtualPath, DocumentFacts>,
        configuration: JvmWorkspaceConfiguration,
        astImportTargets: Collection<String> = emptyList(),
        freeClassNames: Collection<String> = emptyList()
    ): Set<String> {
        return buildSet {
            fun addExplicitClassTarget(target: String) {
                // Never expand wildcards/package aliases into full package class lists here.
                // packageProvidersFor + packageMemberClassProvidersFor mount package modules
                // and shallow class providers instead.
                if (isWildcardOrPackageTarget(target)) {
                    return
                }
                classModuleProvider.importedClassName(target, configuration)?.let(::add)
            }
            documentFacts.values.forEach { facts ->
                facts.sourceImports.forEach { importFact ->
                    addExplicitClassTarget(importFact.target)
                }
                facts.jvmClassLoads.forEach { fact ->
                    when (fact.kind) {
                        DocumentFacts.JvmClassLoadKind.IMPORT_CALL,
                        DocumentFacts.JvmClassLoadKind.BIND_CLASS_CALL,
                        DocumentFacts.JvmClassLoadKind.NEW_INSTANCE_CALL,
                        DocumentFacts.JvmClassLoadKind.CREATE_PROXY_CALL,
                        DocumentFacts.JvmClassLoadKind.LOAD_LIB_CALL,
                        // createArray("pkg.Foo", ...) mounts element class provider without separate bindClass.
                        DocumentFacts.JvmClassLoadKind.CREATE_ARRAY_CALL -> {
                            addExplicitClassTarget(fact.target)
                        }
                    }
                }
            }
            astImportTargets.forEach(::addExplicitClassTarget)
            // freeClassNames: intentionally not mounted into extraProviders (TASK-628).
            // Path-scoped activation still uses free names in collectSourceImports.
            @Suppress("UNUSED_PARAMETER")
            val ignoredFreeClassNames = freeClassNames
        }
    }

    private class DocumentScan(
        /**
         * Import targets read straight off the AST, with a more permissive table-sequence rule
         * than DocumentFactsCollector's range-equality check. Ensures `import({ "A", "B" })`
         * mounts providers even when integer key ranges are non-degenerate after finishNode.
         */
        val importTargets: Set<String>,
        /**
         * Free UpperCamel identifiers used as class-like roots. Used only to mount reflective
         * providers for the Android-Lua default import-prefix fallback (Locale / File / TextView
         * without an explicit import). Never invents non-loadable names;
         * [JvmClassModuleProvider.importedClassName] still has to resolve each candidate.
         */
        val freeClassNames: Set<String>
    )

    /**
     * Single walk producing both AST import targets and free class-like identifiers.
     *
     * The two used to be separate back-to-back traversals of the same chunk. The binding-position
     * skips below (parameters, loop variables, member names) only ever elide `Identifier` nodes,
     * which can never contain a call, so import discovery is unaffected by sharing this traversal.
     */
    private fun scanDocument(chunk: ChunkNode): DocumentScan {
        val names = linkedSetOf<String>()
        val targets = linkedSetOf<String>()
        chunk.accept(
            object : ASTVisitor<Unit> {
                override fun visitCallExpression(node: CallExpression, value: Unit) {
                    if (isImportCallee(unwrapCallBase(node.base))) {
                        // Compact short-call forms keep the first argument on the inner node.
                        importTargetsOf(node.base.compactCallArguments() + node.arguments)
                            .forEach(targets::add)
                    }
                    super.visitCallExpression(node, value)
                }

                override fun visitIdentifier(node: Identifier, value: Unit) {
                    if (isClassLikeSimpleName(node.name)) {
                        names += node.name
                    }
                }

                /** `Foo.Bar` roots at `Foo`; the member name is not a class-like root. */
                override fun visitMemberExpression(node: MemberExpression, value: Unit) {
                    visitExpressionNode(node.base, value)
                }

                /** Declared names/bodies only: parameter names are bindings, not class roots. */
                override fun visitFunctionDeclaration(node: FunctionDeclaration, value: Unit) {
                    node.identifier?.let { visitExpressionNode(it, value) }
                    node.body?.let { visitBlockNode(it, value) }
                }

                override fun visitLambdaDeclaration(node: LambdaDeclaration, value: Unit) {
                    visitExpressionNode(node.expression, value)
                }

                /** Loop variables are fresh bindings. */
                override fun visitForGenericStatement(node: ForGenericStatement, value: Unit) {
                    visitExpressionNodes(node.iterators, value)
                    visitBlockNode(node.body, value)
                }

                override fun visitForNumericStatement(node: ForNumericStatement, value: Unit) {
                    visitExpressionNode(node.start, value)
                    visitExpressionNode(node.end, value)
                    node.step?.let { visitExpressionNode(it, value) }
                    visitBlockNode(node.body, value)
                }

                override fun visitAttributeIdentifier(identifier: AttributeIdentifier, value: Unit) = Unit
                override fun visitCommentStatement(commentStatement: CommentStatement, value: Unit) = Unit
            },
            Unit
        )
        return DocumentScan(importTargets = targets, freeClassNames = names)
    }

    private fun isClassLikeSimpleName(name: String): Boolean {
        if (name.isEmpty() || name in NON_CLASS_FREE_IDENTIFIERS) {
            return false
        }
        val first = name.first()
        if (!first.isUpperCase() || !first.isLetter()) {
            return false
        }
        return name.all { ch -> ch.isLetterOrDigit() || ch == '_' }
    }

    private fun isWildcardOrPackageTarget(importText: String): Boolean {
        val normalized = importText.removePrefix("import ").trim()
        val target = normalized.substringAfter(':', normalized).trim()
        if (target.isBlank()) {
            return false
        }
        if (target.endsWith(".*")) {
            return true
        }
        // Package-name aliases (android.widget / java.util) are dotted lowercase segments.
        if ('.' !in target) {
            return false
        }
        val segments = target.split('.')
        return segments.size >= 2 && segments.all { segment ->
            segment.isNotEmpty() &&
                segment.first().isLowerCase() &&
                segment.all { ch -> ch.isLetterOrDigit() || ch == '_' }
        }
    }

    /** `import "x"` / `import { ... }` keep the callee under a compact short-call node. */
    private fun unwrapCallBase(expression: ExpressionNode): ExpressionNode {
        var current = expression
        while (true) {
            current = when (current) {
                is StringCallExpression -> current.base
                is TableCallExpression -> current.base
                else -> return current
            }
        }
    }

    /**
     * Local aliases like `local import = require("import")` still call as Identifier("import").
     * Chained aliases (load/again) are covered by DocumentFacts alias scopes; this AST pass
     * focuses on direct import(...) and import table arguments.
     */
    private fun isImportCallee(expression: ExpressionNode): Boolean {
        return expression is Identifier && expression.name == "import"
    }

    private fun ExpressionNode.compactCallArguments(): List<ExpressionNode> {
        return (this as? CallExpression)?.arguments.orEmpty()
    }

    private fun importTargetsOf(arguments: List<ExpressionNode>): List<String> {
        return arguments.flatMap { argument ->
            when (argument) {
                is ArrayConstructorExpression -> argument.values.mapNotNull(::stringLiteral)
                is TableConstructorExpression -> {
                    // Sequence slots hold the targets; fall back to all field values when integer
                    // keys survive parsing and the sequence filter yields nothing.
                    argument.fields.filter { it !is TableKeyString }.mapNotNull { stringLiteral(it.value) }
                        .ifEmpty { argument.fields.mapNotNull { stringLiteral(it.value) } }
                }
                else -> listOfNotNull(stringLiteral(argument))
            }
        }
    }

    private fun stringLiteral(expression: ExpressionNode?): String? {
        val constant = expression as? ConstantNode ?: return null
        return constant.takeIf { it.constantType == ConstantNode.TYPE.STRING }?.stringOf()
    }

    private fun collectWildcardImportTargets(
        configuration: JvmWorkspaceConfiguration,
        documentFacts: Map<VirtualPath, DocumentFacts>,
        astImportTargets: Collection<String> = emptyList()
    ): Set<String> {
        // Mount package module providers for:
        // - wildcard proxies: import "android.widget.*" / import("android.widget.*")
        // - package-name aliases: import("android.widget") / import("java.util")
        // Package modules power widget./util. member completions and hover moduleName.
        return buildSet {
            fun maybeAddPackageTarget(importText: String) {
                if (wildcardImportPrefix(importText) != null || isPackageNameAliasTarget(importText)) {
                    add(importText)
                }
            }
            configuration.normalized().androluaImports.forEach(::maybeAddPackageTarget)
            documentFacts.values.forEach { facts ->
                facts.sourceImports.forEach { importFact ->
                    maybeAddPackageTarget(importFact.target)
                }
            }
            astImportTargets.forEach(::maybeAddPackageTarget)
        }
    }

    private fun configurationWithWildcardImportPrefixes(
        configuration: JvmWorkspaceConfiguration,
        documentFacts: Map<VirtualPath, DocumentFacts>,
        astImportTargets: Collection<String> = emptyList()
    ): JvmWorkspaceConfiguration {
        val normalized = configuration.normalized()
        val wildcardPrefixes = linkedSetOf<String>().apply {
            normalized.androluaImports.forEach { importText ->
                wildcardImportPrefix(importText)?.let { add(it) }
            }
        }
        documentFacts.values.forEach { facts ->
            facts.sourceImports.forEach { importFact ->
                wildcardImportPrefix(importFact.target)?.let(wildcardPrefixes::add)
            }
        }
        astImportTargets.forEach { target ->
            wildcardImportPrefix(target)?.let(wildcardPrefixes::add)
        }
        if (wildcardPrefixes.isEmpty()) {
            return normalized
        }
        return normalized.copy(
            importPrefixes = (normalized.importPrefixes + wildcardPrefixes).distinct()
        )
    }

    private fun wildcardImportPrefix(importText: String): String? {
        val normalized = importText.removePrefix("import ").trim()
        if (!normalized.endsWith(".*")) {
            return null
        }
        return normalized.substringAfter(':', normalized).removeSuffix(".*").takeIf(String::isNotBlank)
    }

    /**
     * Normalize package-list targets to wildcard form (`pkg.*`).
     * [JvmClassModuleProvider.packageProvidersFor] is wildcard-only so package-name aliases
     * such as `android.widget` are rewritten to `android.widget.*` before mount.
     */
    private fun normalizePackageProviderTarget(importText: String): String? {
        wildcardImportPrefix(importText)?.let { return "$it.*" }
        packageNameAliasPrefix(importText)?.let { return "$it.*" }
        return null
    }

    private fun luaJavaHelperName(kind: DocumentFacts.JvmClassLoadKind): String {
        return when (kind) {
            DocumentFacts.JvmClassLoadKind.BIND_CLASS_CALL -> "bindClass"
            DocumentFacts.JvmClassLoadKind.NEW_INSTANCE_CALL -> "newInstance"
            DocumentFacts.JvmClassLoadKind.CREATE_PROXY_CALL -> "createProxy"
            DocumentFacts.JvmClassLoadKind.LOAD_LIB_CALL -> "loadLib"
            DocumentFacts.JvmClassLoadKind.IMPORT_CALL,
            DocumentFacts.JvmClassLoadKind.CREATE_ARRAY_CALL -> kind.name
        }
    }

    private companion object {
        val diagnosticLuaJavaClassLoadKinds: Set<DocumentFacts.JvmClassLoadKind> = setOf(
            DocumentFacts.JvmClassLoadKind.BIND_CLASS_CALL,
            DocumentFacts.JvmClassLoadKind.NEW_INSTANCE_CALL,
            DocumentFacts.JvmClassLoadKind.CREATE_PROXY_CALL,
            DocumentFacts.JvmClassLoadKind.LOAD_LIB_CALL
        )

        // UpperCamel free identifiers that must not mount as JVM class roots.
        // Filters host/Lua-ish false positives; real classes (Locale/TextView/File) stay eligible.
        private val NON_CLASS_FREE_IDENTIFIERS: Set<String> = setOf(
            "And",
            "Or",
            "Not",
            "True",
            "False",
            "Nil",
            "Self",
            "This",
            "Super",
            "Global"
        )
    }


    private fun isPackageNameAliasTarget(importText: String): Boolean {
        return packageNameAliasPrefix(importText) != null
    }

    private fun packageNameAliasPrefix(importText: String): String? {
        val normalized = importText.removePrefix("import ").trim()
        val target = normalized.substringAfter(':', normalized).trim()
        if (target.isBlank() || target.endsWith(".*") || '.' !in target) {
            return null
        }
        val segments = target.split('.')
        if (segments.size < 2) {
            return null
        }
        // Package segments are lowercase-leading (android / widget / util); class simple names
        // are UpperCamelCase and must not mount as package modules.
        val packageLike = segments.all { segment ->
            segment.isNotEmpty() &&
                segment.first().isLowerCase() &&
                segment.all { ch -> ch.isLetterOrDigit() || ch == '_' }
        }
        return target.takeIf { packageLike }
    }
}
