package io.github.dingyi222666.luaparser.interop.jvm

import io.github.dingyi222666.luaparser.parser.LuaParser
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
    private val workspaceParserFactory: () -> LuaParser = { LuaParser() },
    private val classModuleProvider: JvmClassModuleProvider = JvmClassModuleProvider(),
    private val configuration: JvmWorkspaceConfiguration = JvmWorkspaceConfiguration()
) : LuaWorkspaceEngine(workspaceParserFactory) {
    override fun extraProviders(input: LuaWorkspaceInput): Map<VirtualPath, WorkspaceSnapshot.FileSnapshot> {
        val baseConfiguration = configuration.overlay(JvmWorkspaceConfiguration.fromMetadata(input.metadata))
        val documentFacts = collectDocumentFacts(input)
        val astImportTargets = collectAstImportTargets(input)
        val resolvedConfiguration = configurationWithWildcardImportPrefixes(
            baseConfiguration,
            documentFacts,
            astImportTargets
        )
        // Explicit / bindClass / simple import targets only. Wildcard package members are mounted
        // by packageProvidersFor (package module + shallow class providers) so android.jar wildcards
        // never force full deep reflection of every package class into configuration.classes.
        val sourceDiscoveredClasses = collectSourceDiscoveredClasses(documentFacts, resolvedConfiguration, astImportTargets)
        val packageTargets = collectWildcardImportTargets(baseConfiguration, documentFacts, astImportTargets)
        val packageProviders = classModuleProvider.packageProvidersFor(
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
        // Package shallow class providers first; explicit/full providers win on path collision
        // so bindClass / configured classes keep deep reflection surfaces.
        return packageProviders + classModuleProvider.providersFor(providerConfiguration)
    }

    internal override fun workspaceContext(input: LuaWorkspaceInput, path: io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath, snapshot: WorkspaceSnapshot): SemanticWorkspaceContext {
        val baseConfiguration = configuration.overlay(JvmWorkspaceConfiguration.fromMetadata(input.metadata))
        // Path-scoped only: never re-parse the full multi-file workspace for each document.
        // Snapshot documentFacts from analyzeFile are authoritative; fall back to a single-path collect.
        val currentSource = input.files[path]
            ?: snapshot.files[path]?.semanticFile?.source
        val currentFacts = snapshot.files[path]?.documentFacts
            ?: currentSource?.let { source ->
                DocumentFactsCollector.collect(path, parseWorkspaceSource(source))
            }
        // Configured imports are workspace-wide; source imports stay scoped to the current file.
        val configuredImports = collectConfiguredImports(baseConfiguration)
        val currentAstImportTargets = if (currentSource != null) {
            val targets = linkedSetOf<String>()
            collectImportTargetsFromNode(parseWorkspaceSource(currentSource), targets)
            targets
        } else {
            emptySet()
        }
        val resolvedConfiguration = configurationWithWildcardImportPrefixes(
            baseConfiguration,
            currentFacts?.let { mapOf(path to it) }.orEmpty(),
            currentAstImportTargets
        )
        val sourceImports = collectSourceImports(
            currentFacts,
            resolvedConfiguration,
            currentAstImportTargets
        )
        val activeImports = linkedMapOf<String, WorkspaceImportedSymbol>().apply {
            putAll(configuredImports)
            putAll(sourceImports)
        }
        return SemanticWorkspaceContext(
            currentPath = path,
            workspaceResolver = io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceModuleResolver(snapshot),
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
            unresolvedLuaJavaTargets = collectUnresolvedLuaJavaTargets(currentFacts, resolvedConfiguration)
        )
    }

    private fun collectUnresolvedLuaJavaTargets(
        facts: DocumentFacts?,
        configuration: JvmWorkspaceConfiguration
    ): List<UnresolvedLuaJavaTarget> {
        if (facts == null) {
            return emptyList()
        }
        return facts.jvmClassLoads
            .asSequence()
            .filter { it.kind in diagnosticLuaJavaClassLoadKinds }
            .filter { classModuleProvider.importedClassName(it.target, configuration) == null }
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
        astImportTargets: Collection<String> = emptyList()
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
        return imported
    }

    private fun collectSourceDiscoveredClasses(
        documentFacts: Map<VirtualPath, DocumentFacts>,
        configuration: JvmWorkspaceConfiguration,
        astImportTargets: Collection<String> = emptyList()
    ): Set<String> {
        return buildSet {
            fun addExplicitClassTarget(target: String) {
                // Never expand wildcards/package aliases into full package class lists here.
                // packageProvidersFor mounts package modules + shallow class providers instead.
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
        }
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

    /**
     * Collect import targets from source AST with a more permissive table-sequence rule than
     * DocumentFactsCollector's range-equality check. Ensures import({ "A", "B" }) mounts providers
     * even when integer key ranges are non-degenerate after finishNode.
     */
    private fun collectAstImportTargets(input: LuaWorkspaceInput): Set<String> {
        val targets = linkedSetOf<String>()
        input.files.forEach { (_, source) ->
            val chunk = parseWorkspaceSource(source)
            collectImportTargetsFromNode(chunk, targets)
        }
        return targets
    }

    private fun collectImportTargetsFromNode(
        node: io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode,
        targets: MutableSet<String>
    ) {
        when (node) {
            is io.github.dingyi222666.luaparser.parser.ast.node.CallExpression -> {
                val base = unwrapCallBase(node.base)
                val callee = base as? io.github.dingyi222666.luaparser.parser.ast.node.Identifier
                if (callee?.name == "import" || isRequireImportCall(base) || isIdentifierImportAlias(base)) {
                    extractImportTargetsLoose(node).forEach(targets::add)
                }
                collectImportTargetsFromNode(node.base, targets)
                node.arguments.forEach { collectImportTargetsFromNode(it, targets) }
            }
            is io.github.dingyi222666.luaparser.parser.ast.node.StringCallExpression -> {
                val base = unwrapCallBase(node.base)
                if (base is io.github.dingyi222666.luaparser.parser.ast.node.Identifier &&
                    (base.name == "import" || isIdentifierImportAlias(base))
                ) {
                    node.arguments.mapNotNull(::stringLiteral).forEach(targets::add)
                }
                collectImportTargetsFromNode(node.base, targets)
                node.arguments.forEach { collectImportTargetsFromNode(it, targets) }
            }
            is io.github.dingyi222666.luaparser.parser.ast.node.TableCallExpression -> {
                val base = unwrapCallBase(node.base)
                if (base is io.github.dingyi222666.luaparser.parser.ast.node.Identifier &&
                    (base.name == "import" || isIdentifierImportAlias(base))
                ) {
                    // import { "A", "B" } short table-call form
                    node.arguments.forEach { arg ->
                        when (arg) {
                            is io.github.dingyi222666.luaparser.parser.ast.node.TableConstructorExpression -> {
                                val values = arg.fields
                                    .filter { it !is io.github.dingyi222666.luaparser.parser.ast.node.TableKeyString }
                                    .mapNotNull { stringLiteral(it.value) }
                                    .ifEmpty { arg.fields.mapNotNull { stringLiteral(it.value) } }
                                values.forEach(targets::add)
                            }
                            is io.github.dingyi222666.luaparser.parser.ast.node.ArrayConstructorExpression ->
                                arg.values.mapNotNull(::stringLiteral).forEach(targets::add)
                            else -> stringLiteral(arg)?.let(targets::add)
                        }
                    }
                }
                collectImportTargetsFromNode(node.base, targets)
                node.arguments.forEach { collectImportTargetsFromNode(it, targets) }
            }
            is io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement -> {
                node.init.forEach { collectImportTargetsFromNode(it, targets) }
                node.variables.forEach { collectImportTargetsFromNode(it, targets) }
            }
            is io.github.dingyi222666.luaparser.parser.ast.node.AssignmentStatement -> {
                node.init.forEach { collectImportTargetsFromNode(it, targets) }
                node.variables.forEach { collectImportTargetsFromNode(it, targets) }
            }
            is io.github.dingyi222666.luaparser.parser.ast.node.BlockNode -> {
                node.statements.forEach { collectImportTargetsFromNode(it, targets) }
                node.returnStatement?.let { collectImportTargetsFromNode(it, targets) }
            }
            is io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode -> {
                collectImportTargetsFromNode(node.body, targets)
            }
            is io.github.dingyi222666.luaparser.parser.ast.node.CallStatement -> {
                collectImportTargetsFromNode(node.expression, targets)
            }
            is io.github.dingyi222666.luaparser.parser.ast.node.ReturnStatement -> {
                node.arguments.forEach { collectImportTargetsFromNode(it, targets) }
            }
            is io.github.dingyi222666.luaparser.parser.ast.node.DoStatement -> collectImportTargetsFromNode(node.body, targets)
            is io.github.dingyi222666.luaparser.parser.ast.node.WhileStatement -> {
                collectImportTargetsFromNode(node.condition, targets)
                collectImportTargetsFromNode(node.body, targets)
            }
            is io.github.dingyi222666.luaparser.parser.ast.node.RepeatStatement -> {
                collectImportTargetsFromNode(node.body, targets)
                collectImportTargetsFromNode(node.condition, targets)
            }
            is io.github.dingyi222666.luaparser.parser.ast.node.IfStatement -> {
                node.causes.forEach { cause ->
                    if (cause !is io.github.dingyi222666.luaparser.parser.ast.node.ElseClause) {
                        collectImportTargetsFromNode(cause.condition, targets)
                    }
                    collectImportTargetsFromNode(cause.body, targets)
                }
            }
            is io.github.dingyi222666.luaparser.parser.ast.node.ForNumericStatement -> {
                collectImportTargetsFromNode(node.start, targets)
                collectImportTargetsFromNode(node.end, targets)
                node.step?.let { collectImportTargetsFromNode(it, targets) }
                collectImportTargetsFromNode(node.body, targets)
            }
            is io.github.dingyi222666.luaparser.parser.ast.node.ForGenericStatement -> {
                node.iterators.forEach { collectImportTargetsFromNode(it, targets) }
                collectImportTargetsFromNode(node.body, targets)
            }
            is io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration -> {
                node.body?.let { collectImportTargetsFromNode(it, targets) }
            }
            is io.github.dingyi222666.luaparser.parser.ast.node.MemberExpression -> collectImportTargetsFromNode(node.base, targets)
            is io.github.dingyi222666.luaparser.parser.ast.node.IndexExpression -> {
                collectImportTargetsFromNode(node.base, targets)
                collectImportTargetsFromNode(node.index, targets)
            }
            is io.github.dingyi222666.luaparser.parser.ast.node.BinaryExpression -> {
                node.left?.let { collectImportTargetsFromNode(it, targets) }
                node.right?.let { collectImportTargetsFromNode(it, targets) }
            }
            is io.github.dingyi222666.luaparser.parser.ast.node.UnaryExpression -> collectImportTargetsFromNode(node.arg, targets)
            is io.github.dingyi222666.luaparser.parser.ast.node.TableConstructorExpression -> {
                node.fields.forEach { field ->
                    collectImportTargetsFromNode(field.key, targets)
                    collectImportTargetsFromNode(field.value, targets)
                }
            }
            is io.github.dingyi222666.luaparser.parser.ast.node.ArrayConstructorExpression -> {
                node.values.forEach { collectImportTargetsFromNode(it, targets) }
            }
            else -> Unit
        }
    }

    private fun unwrapCallBase(
        expression: io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode
    ): io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode {
        var current = expression
        while (true) {
            current = when (current) {
                is io.github.dingyi222666.luaparser.parser.ast.node.StringCallExpression -> current.base
                is io.github.dingyi222666.luaparser.parser.ast.node.TableCallExpression -> current.base
                else -> return current
            }
        }
    }

    private fun isRequireImportCall(
        expression: io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode
    ): Boolean = false

    private fun isIdentifierImportAlias(
        expression: io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode
    ): Boolean {
        // Local aliases like local import = require("import") still call as Identifier("import").
        // Additional chained aliases (load/again) are covered by DocumentFacts alias scopes; this
        // AST pass focuses on direct import(...) and import table arguments.
        return expression is io.github.dingyi222666.luaparser.parser.ast.node.Identifier &&
            expression.name == "import"
    }

    private fun extractImportTargetsLoose(
        call: io.github.dingyi222666.luaparser.parser.ast.node.CallExpression
    ): List<String> {
        val arguments = buildList {
            val base = call.base
            if (base is io.github.dingyi222666.luaparser.parser.ast.node.StringCallExpression) {
                addAll(base.arguments)
            }
            if (base is io.github.dingyi222666.luaparser.parser.ast.node.TableCallExpression) {
                addAll(base.arguments)
            }
            addAll(call.arguments)
        }
        val first = arguments.firstOrNull() ?: return emptyList()
        return when (first) {
            is io.github.dingyi222666.luaparser.parser.ast.node.ConstantNode ->
                if (first.constantType == io.github.dingyi222666.luaparser.parser.ast.node.ConstantNode.TYPE.STRING) {
                    listOf(first.stringOf())
                } else emptyList()
            is io.github.dingyi222666.luaparser.parser.ast.node.ArrayConstructorExpression ->
                first.values.mapNotNull(::stringLiteral)
            is io.github.dingyi222666.luaparser.parser.ast.node.TableConstructorExpression -> {
                val sequence = first.fields
                    .filter { it !is io.github.dingyi222666.luaparser.parser.ast.node.TableKeyString }
                    .mapNotNull { stringLiteral(it.value) }
                if (sequence.isNotEmpty()) sequence else first.fields.mapNotNull { stringLiteral(it.value) }
            }
            else -> emptyList()
        }
    }

    private fun stringLiteral(
        expression: io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode?
    ): String? {
        val constant = expression as? io.github.dingyi222666.luaparser.parser.ast.node.ConstantNode ?: return null
        return constant.takeIf {
            it.constantType == io.github.dingyi222666.luaparser.parser.ast.node.ConstantNode.TYPE.STRING
        }?.stringOf()
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

    private fun collectDocumentFacts(input: LuaWorkspaceInput): Map<VirtualPath, DocumentFacts> {
        return input.files.mapValues { (path, source) ->
            DocumentFactsCollector.collect(path, parseWorkspaceSource(source))
        }
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
