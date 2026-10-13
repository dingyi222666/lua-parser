package io.github.dingyi222666.luaparser.semantic.model

import io.github.dingyi222666.luaparser.parser.compactCallArguments

import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode
import io.github.dingyi222666.luaparser.parser.ast.node.CallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.ConstantNode
import io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.IndexExpression
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.parser.ast.node.MemberExpression
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.parser.ast.node.Range
import io.github.dingyi222666.luaparser.semantic.SemanticWorkspaceContext
import io.github.dingyi222666.luaparser.semantic.WorkspaceImportedSymbol
import io.github.dingyi222666.luaparser.semantic.api.Symbol
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import io.github.dingyi222666.luaparser.semantic.api.TypeInfo
import io.github.dingyi222666.luaparser.semantic.api.TypeInfoKind
import io.github.dingyi222666.luaparser.semantic.importedSymbolHandle
import io.github.dingyi222666.luaparser.semantic.binder.BinderDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationNamespace
import io.github.dingyi222666.luaparser.semantic.binder.Scope
import io.github.dingyi222666.luaparser.semantic.binder.ScopeId
import io.github.dingyi222666.luaparser.semantic.binder.compareRangeSpecificity
import io.github.dingyi222666.luaparser.semantic.binder.isChunkGlobalFunctionDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.isPositionWithin
import io.github.dingyi222666.luaparser.semantic.binder.isVisibleAtPosition
import io.github.dingyi222666.luaparser.semantic.checker.ExpressionTypeEvaluator
import io.github.dingyi222666.luaparser.semantic.checker.enclosingAstNodeOf
import io.github.dingyi222666.luaparser.semantic.checker.JavaBeanPropertySurface
import io.github.dingyi222666.luaparser.semantic.checker.MemberAccessKind
import io.github.dingyi222666.luaparser.semantic.checker.MemberResolver
import io.github.dingyi222666.luaparser.semantic.checker.allReadableInstanceJavaBeanProperties
import io.github.dingyi222666.luaparser.semantic.checker.allReadableStaticJavaBeanProperties
import io.github.dingyi222666.luaparser.semantic.checker.hydrateJavaProviderType
import io.github.dingyi222666.luaparser.semantic.checker.isJavaBackedModule
import io.github.dingyi222666.luaparser.semantic.checker.isJavaProviderClassReference
import io.github.dingyi222666.luaparser.semantic.checker.withJavaCallableSurface
import io.github.dingyi222666.luaparser.semantic.types.model.CallableType
import io.github.dingyi222666.luaparser.semantic.types.model.ClassType
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.IntersectionType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaArrayType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaClassType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaInstanceType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaMemberKind
import io.github.dingyi222666.luaparser.semantic.types.model.JavaMemberType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaSignatureMetadata
import io.github.dingyi222666.luaparser.semantic.types.model.ModuleType
import io.github.dingyi222666.luaparser.semantic.types.model.OverloadedFunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.TableType
import io.github.dingyi222666.luaparser.semantic.types.model.Type
import io.github.dingyi222666.luaparser.semantic.types.model.TypeParameterType
import io.github.dingyi222666.luaparser.semantic.types.model.UnionType
import io.github.dingyi222666.luaparser.semantic.types.model.UnknownType
import io.github.dingyi222666.luaparser.semantic.types.resolve.TypeExpansion
import io.github.dingyi222666.luaparser.semantic.types.resolve.intersectionTypeOf
import io.github.dingyi222666.luaparser.semantic.types.resolve.unionTypeOf
import io.github.dingyi222666.luaparser.semantic.workspace.ModuleExportIdentity

import io.github.dingyi222666.luaparser.util.putIfAbsentCompat

internal class ReferenceQueries(
    private val binder: BinderPassResult,
    private val evaluator: ExpressionTypeEvaluator,
    private val memberResolver: MemberResolver,
    private val adapters: ApiAdapters,
    private val workspaceContext: SemanticWorkspaceContext = SemanticWorkspaceContext()
) {
    internal data class VisibleDeclaration(
        val declaration: BinderDeclaration,
        val lexicalDepth: Int
    )

    internal data class MemberSurface(
        val name: String,
        val type: Type,
        val accessKind: MemberAccessKind,
        val declaration: BinderDeclaration? = null,
        val declaredType: Type? = declaration?.declaredType,
        val syntheticRange: Range? = null,
        val syntheticHandle: String? = null
    )

    fun getSymbolAt(position: Position, node: BaseASTNode?): Symbol? {
        val importedSymbol = importedSymbolAt(position, node)
        localJavaMemberInitializerSymbolAt(position, node)?.let { return it }
        bindClassTargetLocalSymbolAt(node)?.let { return it }
        importTargetStringSymbolAt(node)?.let { return it }
        // One ordered fallback ladder for declaration-backed symbols; each probe is a pure
        // query, so first-non-null short-circuiting preserves the original precedence.
        val declarationSymbol = (resolveDeclarationTokenStartAt(position)
            ?: resolveExactNodeDeclaration(node)
            ?: resolveExactDocDeclaration(position)
            ?: resolveDeclarationAtPosition(position)
            ?: binder.positionQueries.getDeclarationAt(position))
            ?.let { adapters.toDeclarationSymbol(it) }
            ?: binder.positionQueries.getSymbolAt(position)
                ?.let(adapters::toSymbol)
        if (declarationSymbol != null) {
            // Prefer MODULE-kind imported symbols over synthetic/global fallbacks when the
            // identifier is an activated import alias (not a true local/parameter declaration).
            if ((declarationSymbol.kind == SymbolKind.VARIABLE || declarationSymbol.kind == SymbolKind.UNKNOWN) &&
                importedSymbol != null
            ) {
                return toImportedSymbol(importedSymbol)
            }
            // (Import-call local surfaces never reach this point: importCallTargetLocalSymbolAt
            // was already consulted unconditionally at the top of getSymbolAt, so a second
            // identical call here could only observe the same null result.)
            // Prefer declared/inferred FunctionType / ClassType surfaces over bare unknown/any
            // so hover does not collapse annotated or inferable callables/classes to unknown.
            preferDeclaredOrInferredSymbolType(declarationSymbol, node)?.let { return it }
            // True local/parameter/function declarations always win over imported MODULE aliases
            // so local shadowing (`local File = { ... }`) stays preferred after declaration.
            return declarationSymbol
        }

        return when (node) {
            is Identifier -> {
                memberIdentifierBase(node)?.let { return resolveMemberUsage(it) }
                importedSymbol?.let(::toImportedSymbol)
                    ?: findNearestVisibleValueDeclaration(node.name, position)?.let { declaration ->
                        adapters.toDeclarationSymbol(declaration).let {
                            preferDeclaredOrInferredSymbolType(it, node) ?: it
                        }
                    }
            }

            is MemberExpression -> resolveMemberUsage(node)
            else -> importedSymbol?.let(::toImportedSymbol)
        }
    }

    /**
     * When a binder declaration only surfaces bare unknown/any, recover declared/inferred
     * FunctionType / ClassType / ModuleType / Java class surfaces for hover.
     */
    private fun preferDeclaredOrInferredSymbolType(symbol: Symbol, node: BaseASTNode?): Symbol? {
        val current = symbol.type
        val needsUpgrade = current == null || current.isBareWeakType() ||
            (current.kind == TypeInfoKind.UNKNOWN &&
                !current.displayName.contains("fun(") && !current.displayName.contains("fun<"))
        if (!needsUpgrade) {
            return null
        }

        val declaration = resolveDeclarationForSymbol(symbol, node) ?: return null
        val preferredType = preferredDeclarationType(declaration) ?: return null
        if (preferredType === UnknownType) {
            return null
        }
        val preferredInfo = adapters.toTypeInfo(preferredType, declaration) ?: return null
        if (preferredInfo.isBareWeakType() &&
            preferredInfo.kind != TypeInfoKind.FUNCTION &&
            preferredInfo.kind != TypeInfoKind.CLASS &&
            preferredInfo.kind != TypeInfoKind.MODULE
        ) {
            // Only upgrade when the recovered surface is actually richer than bare unknown.
            if (!preferredInfo.displayName.contains("fun(") &&
                !preferredInfo.displayName.contains("fun<") &&
                preferredInfo.moduleName.isNullOrBlank() &&
                preferredType !is ClassType && preferredType !is JavaClassType &&
                preferredType !is JavaInstanceType && preferredType !is FunctionType &&
                preferredType !is OverloadedFunctionType && preferredType !is ModuleType
            ) {
                return null
            }
        }
        return symbol.copy(
            type = preferredInfo,
            declaredType = preferredInfo,
            detail = preferredInfo.displayName
        )
    }

    private fun resolveDeclarationForSymbol(symbol: Symbol, node: BaseASTNode?): BinderDeclaration? {
        return binder.declarationForSymbolHandle(symbol.symbolId?.toSymbolHandle())
            ?: resolveExactNodeDeclaration(node)
            ?: (node as? Identifier)?.let { identifier ->
                findNearestVisibleValueDeclaration(identifier.name, identifier.range.start)
            }
    }

    private fun preferredDeclarationType(declaration: BinderDeclaration): Type? {
        val declared = declaration.declaredType
        // Unannotated fun(...): unknown is "rich" as a kind but must not suppress body
        // inference that yields a concrete return (TextView / View / …).
        if (declared != null &&
            declared !== UnknownType &&
            isPreferredRichType(declared) &&
            !declared.isUnknownReturnOnlyCallable()
        ) {
            return declared
        }

        val inferred = evaluator.declarationValueType(declaration)
            .hydrateJavaProviderType(workspaceContext.resolveImportTarget)

        return when {
            inferred != null && isPreferredRichType(inferred) -> inferred
            declared != null && declared !== UnknownType && !declared.isUnknownReturnOnlyCallable() -> declared
            inferred != null && inferred !== UnknownType -> inferred
            declared != null && declared !== UnknownType -> declared
            else -> null
        }
    }

    private fun isPreferredRichType(type: Type): Boolean {
        return when (type) {
            is FunctionType,
            is OverloadedFunctionType,
            is ClassType,
            is JavaClassType,
            is JavaInstanceType,
            is ModuleType -> true
            // Any non-blank display other than the bare unknown/any markers counts as rich;
            // `fun(`/`fun<` displays always satisfy that, so no explicit callable check is
            // needed in this branch.
            else -> type.displayName.let { display ->
                display.isNotBlank() && display != "unknown" && display != "any"
            }
        }
    }

    private fun resolveDeclarationTokenStartAt(position: Position): BinderDeclaration? {
        return declarationCovering(
            covers = { it.start == position },
            rangeOf = ::exactDeclarationTokenRange,
            originFilter = null,
            comparator = compareBy(
                { declarationRank(it.first) }, { rangeSpan(it.second) }, { it.first.id.value }
            )
        )
    }

    fun visibleValueSymbols(position: Position): List<Symbol> {
        return visibleValueDeclarations(position)
            .mapNotNull { adapters.toDeclarationSymbol(it.declaration) }
    }

    fun visibleValueDeclarations(position: Position, nameFilter: ((String) -> Boolean)? = null): List<VisibleDeclaration> {
        val visible = lexicalValueDeclarations(position).filter { nameFilter?.invoke(it.declaration.name) != false }.associateByTo(linkedMapOf()) { it.declaration.name }
        importedVisibleDeclarations(position, nameFilter).forEach { item ->
            val existing = visible[item.declaration.name]
            if (existing == null || isPreferredVisibleDeclaration(item.declaration, existing.declaration)) {
                visible[item.declaration.name] = item
            }
        }
        return visible.values.toList()
    }

    private fun scopeForFreePositionQuery(position: Position): Scope? =
        binder.positionQueries.scopeForFreePositionQuery(position)

    /** One lexical enumeration for completion, import masking and shared-global masking. */
    private fun lexicalValueDeclarations(position: Position): List<VisibleDeclaration> {
        val visible = linkedMapOf<String, VisibleDeclaration>()
        var scope = scopeForFreePositionQuery(position)
        var depth = 0
        while (scope != null) {
            scope.declarationIds.asReversed().asSequence().mapNotNull(binder.declarationIndex::getDeclaration)
                .filter { it.kind.namespace == DeclarationNamespace.VALUE && isVisibleAt(it, position) }
                .forEach { visible.putIfAbsentCompat(it.name, VisibleDeclaration(it, depth)) }
            scope = scope.parentId?.let(binder.scopeGraph::getScope)
            depth++
        }
        binder.scopeGraph.rootScope.declarationIds.asReversed().asSequence()
            .mapNotNull(binder.declarationIndex::getDeclaration)
            .filter { it.kind.namespace == DeclarationNamespace.VALUE && isVisibleAt(it, position) }
            .forEach { declaration ->
                val existing = visible[declaration.name]
                if (existing == null || isPreferredVisibleDeclaration(declaration, existing.declaration)) {
                    visible[declaration.name] = VisibleDeclaration(declaration, Int.MAX_VALUE / 4)
                }
            }
        return visible.values.toList()
    }

    /** A named lookup visits only that name's declarations along the scope chain. */
    private fun lexicalValueDeclaration(name: String, position: Position): BinderDeclaration? {
        var found: BinderDeclaration? = null
        var scope = scopeForFreePositionQuery(position)
        while (scope != null) {
            found = binder.positionQueries.valueDeclarationsInScope(scope.id, name)
                .firstOrNull { isVisibleAt(it, position) }
            if (found != null) break
            scope = scope.parentId?.let(binder.scopeGraph::getScope)
        }
        binder.positionQueries.valueDeclarationsInScope(binder.scopeGraph.rootScope.id, name)
            .filter { isVisibleAt(it, position) }.forEach { candidate ->
                if (found == null || isPreferredVisibleDeclaration(candidate, found!!)) found = candidate
            }
        return found
    }

    /**
     * Prefer true locals/parameters, then binder builtins with declaredType, then other typed
     * declarations. Avoid unknown synthetics / untyped globals winning over ambient AndroLua
     * globals after require "import".
     */
    private fun isPreferredVisibleDeclaration(
        candidate: BinderDeclaration,
        existing: BinderDeclaration
    ): Boolean {
        val candidateRank = visibleDeclarationRank(candidate)
        val existingRank = visibleDeclarationRank(existing)
        if (candidateRank != existingRank) {
            return candidateRank > existingRank
        }
        // Prefer richer declaredType display when ranks match.
        val candidateTyped = candidate.declaredType != null
        val existingTyped = existing.declaredType != null
        return candidateTyped && !existingTyped
    }

    private fun visibleDeclarationRank(declaration: BinderDeclaration): Int {
        return when {
            declaration.kind == DeclarationKind.PARAMETER -> 500
            declaration.kind == DeclarationKind.LOCAL -> 400
            declaration.kind == DeclarationKind.FUNCTION &&
                declaration.origin != io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin.BUILTIN -> 350
            declaration.origin == io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin.BUILTIN &&
                declaration.declaredType != null -> 300
            declaration.origin == io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin.BUILTIN -> 250
            declaration.declaredType != null -> 200
            declaration.kind == DeclarationKind.FUNCTION -> 150
            declaration.kind == DeclarationKind.GLOBAL -> 100
            else -> 0
        }
    }

    fun findNearestVisibleValueDeclaration(name: String, position: Position): BinderDeclaration? {
        // A lookup for one receiver must not enumerate/evaluate every workspace global.
        // In particular, require-backed export probing calls this for ordinary locals;
        // full enumeration hydrated unrelated Java objects before rejecting that probe.
        return lexicalValueDeclaration(name, position)
            ?: importedSymbolNamed(name, position)?.let(::importedDeclaration)
    }

    fun importedCompletionSymbol(name: String, position: Position): Symbol? {
        return importedSymbolNamed(name, position)?.let(::toImportedSymbol)
    }

    fun getMembers(type: Type, lexicalScopeId: ScopeId = binder.scopeGraph.rootScope.id): List<Symbol> {
        return collectMemberSurface(type, lexicalScopeId)
            .values
            .sortedWith(compareBy<MemberSurface>({ categoryRank(it.accessKind) }, { it.name }))
            .map { member ->
                member.declaration?.let {
                    adapters.toDeclarationSymbol(
                        declaration = it,
                        typeOverride = member.type,
                        declaredTypeOverride = member.type
                    )
                }
                    ?: adapters.syntheticMemberSymbol(
                        name = member.name,
                        kind = member.accessKind,
                        type = member.type,
                        declaredType = member.declaredType,
                        handleSeed = "${type.displayName}:${member.accessKind.name}:${member.name}",
                        symbolId = member.syntheticHandle,
                        range = member.syntheticRange
                    )
            }
    }

    fun resolveMemberUsage(expression: MemberExpression): Symbol? {
        val lexicalScopeId = binder.positionQueries.getScopeAt(expression.range.start)?.id ?: binder.scopeGraph.rootScope.id
        val baseType = evaluator.evaluate(expression.base)
                .hydrateJavaProviderType(workspaceContext.resolveImportTarget)
        val resolution = memberResolver.resolveMember(
            baseType = baseType,
            memberName = expression.identifier.name,
            preferMethod = expression.indexer == ":",
            lexicalScopeId = lexicalScopeId
        )
        if (!resolution.isSuccess) {
            return javaMemberSurfaceFallbackSymbol(expression, baseType, lexicalScopeId)
        }
        val resolvedType = resolution.type?.hydrateJavaProviderType(workspaceContext.resolveImportTarget)

        val normalizedBase = TypeExpansion.expandForSurface(baseType, lexicalScopeId, binder)
        val workspaceMember = workspaceModuleMember(expression.base, normalizedBase, expression.identifier.name)
        val memberType = workspaceMember?.type
            ?.hydrateJavaProviderType(workspaceContext.resolveImportTarget)
            ?: resolvedType
        val declaration = findBackingMemberDeclaration(normalizedBase, expression.identifier.name, resolution.accessKind)
        // Prefer workspace export handles; otherwise use imported:<provider>:<member> so goto on
        // nested interface static helpers (Map$Entry.comparingByKey) still lands on the binary provider.
        // For createProxy instance methods (Runnable.run), prefer the same imported: handle so
        // gotoDefinition resolves to __jvm__/classes/.../Runnable.lua even when export lookup is thin.
        // Multi-interface createProxy: prefer the owning-branch imported/fallback handle over a
        // workspace export from a non-owning intersection arm when both are present.
        val surfaceHandle = collectMemberSurface(normalizedBase, lexicalScopeId)[expression.identifier.name]
            ?.syntheticHandle
        val owningImportedHandle = javaMemberFallbackSymbolId(normalizedBase, expression.identifier.name)
            ?: surfaceHandle?.takeIf { it.startsWith("imported:") }
        val preferredWorkspaceHandle = workspaceMember?.handle?.takeIf { handle ->
            owningImportedHandle == null ||
                handle == owningImportedHandle ||
                ModuleExportIdentity.parse(handle)?.providerPath?.value ==
                    importedProviderPathValue(owningImportedHandle)
        }
        // Single synthetic-member construction for both handle tiers. When a workspace
        // handle is preferred it is also the fallback head (preferredWorkspaceHandle is
        // fallbackSymbolId's first branch) and carries the member range + declaredType;
        // without one, declaredType/range stay unset and the handle seed is the display key.
        val fallbackSymbolId = preferredWorkspaceHandle ?: owningImportedHandle ?: surfaceHandle
        return declaration?.let { adapters.toDeclarationSymbol(it, memberType, memberType) }
            ?: adapters.syntheticMemberSymbol(
                name = expression.identifier.name,
                kind = resolution.accessKind ?: MemberAccessKind.FIELD,
                type = memberType,
                declaredType = preferredWorkspaceHandle?.let { memberType },
                handleSeed = preferredWorkspaceHandle
                    ?: "${baseType.displayName}:${expression.indexer}:${expression.identifier.name}",
                symbolId = fallbackSymbolId,
                range = preferredWorkspaceHandle?.let { workspaceMember?.member?.range }
            )
    }

    private fun localJavaMemberInitializerSymbolAt(position: Position, node: BaseASTNode?): Symbol? {
        if (node is Identifier && memberIdentifierBase(node) != null) {
            return null
        }
        val declaration = resolveDeclarationTokenStartAt(position)
            ?: resolveExactNodeDeclaration(node)
            ?: (node as? Identifier)?.let { identifier ->
                findNearestVisibleValueDeclaration(identifier.name, position)
            }
            ?: return null
        if (declaration.kind != DeclarationKind.LOCAL) {
            return null
        }
        val initializer = localDeclarationInitializer(declaration) as? MemberExpression ?: return null
        val baseType = evaluator.evaluate(initializer.base)
                .hydrateJavaProviderType(workspaceContext.resolveImportTarget)
        if (!isJavaMemberProviderBase(baseType)) {
            return null
        }
        return resolveMemberUsage(initializer)
    }

    private fun javaMemberSurfaceFallbackSymbol(
        expression: MemberExpression,
        baseType: Type,
        lexicalScopeId: ScopeId
    ): Symbol? {
        val normalizedBase = TypeExpansion.expandForSurface(baseType, lexicalScopeId, binder)
        if (!isJavaMemberProviderBase(normalizedBase)) {
            return null
        }
        val member = collectMemberSurface(normalizedBase, lexicalScopeId)[expression.identifier.name] ?: return null
        val owningImportedHandle = javaMemberFallbackSymbolId(normalizedBase, expression.identifier.name)
            ?: member.syntheticHandle?.takeIf { it.startsWith("imported:") }
        val fallbackSymbolId = owningImportedHandle ?: member.syntheticHandle
        return adapters.syntheticMemberSymbol(
            name = expression.identifier.name,
            kind = member.accessKind,
            type = member.type,
            declaredType = member.declaredType ?: member.type,
            handleSeed = "${baseType.displayName}:${expression.indexer}:${expression.identifier.name}",
            symbolId = fallbackSymbolId,
            range = member.syntheticRange
        )
    }

    /** The string-constant node [node] together with its enclosing call, when it is a literal. */
    private fun stringConstantCallAt(node: BaseASTNode?): Pair<ConstantNode, CallExpression>? {
        val constant = node as? ConstantNode ?: return null
        if (constant.constantType != ConstantNode.TYPE.STRING) return null
        val call = enclosingCallExpression(constant) ?: return null
        return constant to call
    }

    private fun bindClassTargetLocalSymbolAt(node: BaseASTNode?): Symbol? {
        val (constant, call) = stringConstantCallAt(node) ?: return null
        if (callArguments(call).firstOrNull() !== constant) return null
        if (!isLuaJavaBindClassCall(call)) return null
        val localStatement = enclosingLocalStatement(call) ?: return null
        val initializerIndex = localStatement.variables.indexOf(call)
        if (initializerIndex < 0) {
            return null
        }
        val declaration = localStatement.init.getOrNull(initializerIndex)
            ?.let(binder.declarationIndex::getDeclarations)
            ?.firstOrNull { it.kind == DeclarationKind.LOCAL }
            ?: return null
        val moduleType = evaluator.evaluate(call)
            .hydrateJavaProviderType(workspaceContext.resolveImportTarget) as? ModuleType
            ?: return null
        return adapters.toDeclarationSymbol(declaration, moduleType, moduleType)
    }

    private fun importTargetStringSymbolAt(node: BaseASTNode?): Symbol? {
        val (constant, call) = stringConstantCallAt(node) ?: return null
        if (!callArguments(call).any { it === constant }) return null
        if (!evaluator.isImportCall(call)) return null
        val target = constant.stringOf()
        val imported = resolveLuaJavaImportTarget(target) ?: return null
        return toImportedSymbol(imported)
    }

    private fun isJavaMemberProviderBase(type: Type): Boolean {
        return when (type) {
            is ModuleType -> type.isJavaBackedModule()
            is JavaClassType,
            is JavaInstanceType -> true
            is IntersectionType -> type.types.any(::isJavaMemberProviderBase)
            is UnionType -> type.types.all(::isJavaMemberProviderBase)
            else -> false
        }
    }

    /**
     * The member expression whose identifier slot [node] occupies — null when [node] is a
     * member-expression base or not inside a member access at all (shared by the
     * symbol-at and initializer probes).
     */
    private fun memberIdentifierBase(node: BaseASTNode): MemberExpression? =
        (runCatching { node.parent }.getOrNull() as? MemberExpression)?.takeIf { it.identifier === node }

    /** Walk-parent ladder is shared with the evaluator: see [enclosingAstNodeOf]. */
    private fun enclosingCallExpression(node: BaseASTNode): CallExpression? =
        enclosingAstNodeOf(node)

    private fun enclosingLocalStatement(node: BaseASTNode): LocalStatement? =
        enclosingAstNodeOf(node)

    private fun callArguments(node: CallExpression): List<ExpressionNode> =
        node.compactCallArguments()

    private fun isLuaJavaBindClassCall(node: CallExpression): Boolean =
        evaluator.isLuaJavaHelperCall(node, "bindClass")

    private fun resolveLuaJavaImportTarget(target: String) =
        workspaceContext.resolveImportTarget?.invoke(target)
            ?: workspaceContext.workspaceResolver?.importTargetSymbol(target)

    fun resolveMemberCompletionSurface(expression: MemberExpression): List<Symbol> {
        val lexicalScopeId = binder.positionQueries.getScopeAt(expression.range.start)?.id ?: binder.scopeGraph.rootScope.id
        val baseType = evaluator.evaluate(expression.base)
                .hydrateJavaProviderType(workspaceContext.resolveImportTarget)
        val members = getMembers(baseType, lexicalScopeId)
        // `:` promotes METHOD to the first sort group; `.` promotes FIELD.
        val sorted = members.sortedWith(
            compareBy<Symbol>(
                { if (it.kind == (if (expression.indexer == ":") SymbolKind.METHOD else SymbolKind.FIELD)) 0 else 1 },
                { it.name }
            )
        )
        return if (expression.indexer != ":") sorted
        else sorted.filterNot { symbol ->
            // `obj:` is a method call site: a FIELD whose value is KNOWN to be non-callable
            // (JavaBean property aliases such as File.path / File.name, plain data fields)
            // cannot be invoked, so it is dropped here. Callable field surfaces (Lua
            // functions stored in table/module fields, `fun(...)` typed @field) stay, as
            // does every METHOD — and so does every field whose type is unknown/absent,
            // because unknown does not imply non-callable. The `.` surface keeps the
            // full member list including the aliases.
            symbol.kind == SymbolKind.FIELD && !hasCallableMemberDisplay(symbol)
        }
    }

    /**
     * Same `fun(` display heuristic the hover / completion-kind paths use to decide that a
     * member's value is callable, applied to the member-completion surface. A
     * [TypeInfoKind.FUNCTION] type info counts as callable even when the display differs.
     *
     * Unknown / absent / `any` typed members count as POTENTIALLY callable (unknown is not
     * a callability signal), so they stay in the `:` surface; only a KNOWN non-callable
     * display is filtered.
     */
    private fun hasCallableMemberDisplay(symbol: Symbol): Boolean {
        if (symbol.declaredType?.kind == TypeInfoKind.FUNCTION || symbol.type?.kind == TypeInfoKind.FUNCTION) {
            return true
        }
        val display = symbol.declaredType?.displayName
            ?: symbol.type?.displayName
            ?: symbol.detail
            // Absent type information carries no callability signal: keep the member.
            ?: return true
        if (display.isBlank() || display == "unknown" || display == "any") {
            // Coarse unknown / any surfaces are not "known non-callable" either.
            return true
        }
        return display.contains("fun(") || display.contains("fun<")
    }

    private fun collectMemberSurface(type: Type, lexicalScopeId: ScopeId): Map<String, MemberSurface> {
        val normalized = TypeExpansion.expandForSurface(
            type.hydrateJavaProviderType(workspaceContext.resolveImportTarget),
            lexicalScopeId,
            binder
        )
        return when (normalized) {
            is TableType -> buildMap {
                normalized.fields.forEach { (name, memberType) ->
                    put(name, MemberSurface(name, memberType.hydrateJavaProviderType(workspaceContext.resolveImportTarget), MemberAccessKind.FIELD))
                }
                normalized.methods.forEach { (name, memberType) ->
                    put(name, MemberSurface(name, memberType.hydrateJavaProviderType(workspaceContext.resolveImportTarget), MemberAccessKind.METHOD))
                }
            }

            is ModuleType -> buildMap {
                sequenceOf(
                    MemberAccessKind.FIELD to normalized.fields,
                    MemberAccessKind.METHOD to normalized.methods
                ).forEach { (accessKind, members) ->
                    members.forEach { (name, memberType) ->
                        val workspaceMember = workspaceModuleMember(null, normalized, name)
                        // Java-backed modules surface every method callable; the `__call`
                        // field slot (module invocation) is callable for the same reason.
                        val structuralType = if (normalized.isJavaBackedModule() &&
                            (accessKind == MemberAccessKind.METHOD || name == "__call")
                        ) {
                            memberType.withJavaCallableSurface(resolveImportTarget = workspaceContext.resolveImportTarget)
                        } else {
                            memberType.hydrateJavaProviderType(workspaceContext.resolveImportTarget)
                        }
                        val surfaceType = workspaceMember?.type
                            ?.hydrateJavaProviderType(workspaceContext.resolveImportTarget)
                            ?: structuralType
                        put(name, MemberSurface(name, surfaceType, accessKind, syntheticRange = workspaceMember?.member?.range, syntheticHandle = workspaceMember?.handle))
                    }
                }
                // Expand static members from __class even when the reflected surface is
                // JavaInstanceType (classType holds staticMembers) rather than bare JavaClassType.
                javaClassSurfaceFromModule(normalized)?.let { classType ->
                    collectJavaStaticMemberSurface(classType).forEach { (name, surface) ->
                        val existing = this[name]
                        when {
                            // New static member (field or method) from reflection — or prefer
                            // static METHOD/fun surfaces over non-callable field collisions
                            // (nested interface static helpers such as Map$Entry.comparingByKey).
                            // Both cases insert the identical reflected surface.
                            existing == null ||
                                (
                                    existing.accessKind != MemberAccessKind.METHOD &&
                                        surface.accessKind == MemberAccessKind.METHOD
                                    ) -> put(
                                name,
                                surface.copy(
                                    syntheticHandle = surface.syntheticHandle
                                        ?: "java:${classType.javaName.binaryName}:static:$name"
                                )
                            )
                            // Keep module-table FIELD when reflection also reports FIELD, but
                            // upgrade synthetic handles / declared types from the static surface
                            // so goto/hover stay on the binary provider for System.out/err etc.
                            existing.accessKind == MemberAccessKind.FIELD &&
                                surface.accessKind == MemberAccessKind.FIELD -> put(
                                name,
                                surface.copy(
                                    syntheticHandle = surface.syntheticHandle
                                        ?: existing.syntheticHandle
                                        ?: "java:${classType.javaName.binaryName}:static:$name",
                                    // Prefer richer reflected type when module table only has a shell.
                                    type = surface.type,
                                    declaredType = surface.declaredType ?: surface.type
                                )
                            )
                            // Never demote an existing METHOD to FIELD.
                            else -> Unit
                        }
                    }
                }
            }

            is ClassType -> buildMap {
                normalized.getAllFields().forEach { (name, memberType) ->
                    val declaration = findBackingMemberDeclaration(normalized, name, MemberAccessKind.FIELD)
                    put(name, MemberSurface(name, memberType.hydrateJavaProviderType(workspaceContext.resolveImportTarget), MemberAccessKind.FIELD, declaration))
                }
                normalized.getAllMethods().forEach { (name, memberType) ->
                    val declaration = findBackingMemberDeclaration(normalized, name, MemberAccessKind.METHOD)
                    val surfaceType = if (normalized.isJavaProviderClassReference()) {
                        memberType.withJavaCallableSurface(
                            receiverType = normalized,
                            includeReceiver = false,
                            resolveImportTarget = workspaceContext.resolveImportTarget
                        )
                    } else {
                        memberType.hydrateJavaProviderType(workspaceContext.resolveImportTarget)
                    }
                    put(name, MemberSurface(name, surfaceType, MemberAccessKind.METHOD, declaration))
                }
            }

            is JavaClassType -> collectJavaStaticMemberSurface(normalized)

            is JavaInstanceType -> collectJavaInstanceMemberSurface(normalized)

            is JavaArrayType -> mapOf(
                "length" to MemberSurface(
                    name = "length",
                    type = io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType.NUMBER,
                    accessKind = MemberAccessKind.FIELD,
                    syntheticHandle = "java-array:${normalized.displayName}:length"
                )
            )

            is TypeParameterType -> normalized.constraint?.let { collectMemberSurface(it, lexicalScopeId) }.orEmpty()

            is UnionType -> {
                val branchMaps = normalized.types.map { collectMemberSurface(it, lexicalScopeId) }
                if (branchMaps.isEmpty()) {
                    emptyMap()
                } else {
                    val sharedNames = branchMaps.map { it.keys }.reduce { acc, names -> acc.intersect(names) }
                    sharedNames.associateWith { name ->
                        val entries = branchMaps.mapNotNull { it[name] }
                        val declaration = entries.mapNotNull(MemberSurface::declaration).distinct().singleOrNull()
                        MemberSurface(
                            name = name,
                            type = unionTypeOf(entries.map(MemberSurface::type)),
                            accessKind = if (entries.all { it.accessKind == MemberAccessKind.METHOD }) MemberAccessKind.METHOD else MemberAccessKind.FIELD,
                            declaration = declaration,
                            declaredType = declaration?.declaredType
                        )
                    }
                }
            }

            is IntersectionType -> {
                val merged = linkedMapOf<String, MutableList<MemberSurface>>()
                normalized.types.forEach { branch ->
                    collectMemberSurface(branch, lexicalScopeId).values.forEach { entry ->
                        merged.getOrPut(entry.name) { mutableListOf() } += entry
                    }
                }
                merged.mapValues { (memberName, entries) ->
                    val declaration = entries.mapNotNull(MemberSurface::declaration).distinct().singleOrNull()
                    // Preserve the owning-branch syntheticHandle so multi-interface createProxy
                    // members (run vs compare) still goto the interface provider that declared them.
                    val owningImported = javaMemberFallbackSymbolId(normalized, memberName)
                    val preferredHandle = entries
                        .mapNotNull(MemberSurface::syntheticHandle)
                        .firstOrNull { handle ->
                            owningImported != null &&
                                (handle == owningImported ||
                                    ModuleExportIdentity.parse(handle)?.providerPath?.value ==
                                        importedProviderPathValue(owningImported))
                        }
                        ?: entries.mapNotNull(MemberSurface::syntheticHandle)
                            .firstOrNull { it.startsWith("imported:") }
                        ?: owningImported
                        ?: entries.mapNotNull(MemberSurface::syntheticHandle).distinct().singleOrNull()
                        ?: entries.mapNotNull(MemberSurface::syntheticHandle).firstOrNull()
                    val preferredRange = entries
                        .firstOrNull { it.syntheticHandle == preferredHandle }
                        ?.syntheticRange
                        ?: entries.mapNotNull(MemberSurface::syntheticRange)
                            .distinct()
                            .singleOrNull()
                        ?: entries.mapNotNull(MemberSurface::syntheticRange).firstOrNull()
                    MemberSurface(
                        name = entries.first().name,
                        type = if (entries.size == 1) entries.single().type else intersectionTypeOf(entries.map(MemberSurface::type)),
                        accessKind = if (entries.all { it.accessKind == MemberAccessKind.METHOD }) MemberAccessKind.METHOD else MemberAccessKind.FIELD,
                        declaration = declaration,
                        declaredType = declaration?.declaredType,
                        syntheticRange = preferredRange,
                        syntheticHandle = preferredHandle
                    )
                }
            }

            else -> emptyMap()
        }
    }

    private fun collectJavaStaticMemberSurface(classType: JavaClassType): Map<String, MemberSurface> {
        val innerClassNames = classType.allInnerClasses().keys
        val reflected = collectJavaReflectedMemberSurface(
            classType = classType,
            members = classType.allStaticMembers(),
            signatureMetadataOf = { it.signatureMetadata },
            beanProperties = classType.allReadableStaticJavaBeanProperties(),
            receiverType = null,
            handleFlavor = "static",
            // The pre-merge bean-alias loop saw the already-inserted inner-class slots and
            // never overwrote them; reserve those names so that guarantee is preserved.
            reservedNames = innerClassNames
        )
        // Inner-class name slots are inserted first; a same-named static member still
        // overwrites the slot in place (LinkedHashMap put semantics), as in the prior
        // inlined inner-class + static-member ladders.
        return buildMap {
            classType.allInnerClasses().forEach { (name, innerClass) ->
                put(
                    name,
                    MemberSurface(
                        name = name,
                        type = innerClass,
                        accessKind = MemberAccessKind.FIELD,
                        syntheticHandle = "java:${classType.javaName.binaryName}:inner:$name"
                    )
                )
            }
            putAll(reflected)
        }
    }

    private fun collectJavaInstanceMemberSurface(instanceType: JavaInstanceType): Map<String, MemberSurface> =
        collectJavaReflectedMemberSurface(
            classType = instanceType.classType,
            members = instanceType.allInstanceMembers(),
            signatureMetadataOf = { it.signatureMetadata },
            beanProperties = instanceType.allReadableInstanceJavaBeanProperties(),
            receiverType = instanceType,
            handleFlavor = "instance"
        )

    /**
     * Single owner for the reflected Java member-surface ladder shared by static
     * (JavaClassType) and instance (JavaInstanceType) expansion: access kind from the
     * reflected memberKind, callable surface for methods, workspace export handle
     * preference, then conservative JavaBean aliases that never hide direct members.
     */
    private fun <M : JavaMemberType> collectJavaReflectedMemberSurface(
        classType: JavaClassType,
        members: Map<String, M>,
        signatureMetadataOf: (M) -> List<JavaSignatureMetadata>,
        beanProperties: List<JavaBeanPropertySurface>,
        receiverType: JavaInstanceType?,
        handleFlavor: String,
        // Names whose existing slots (e.g. inner classes) the bean-alias pass must not overwrite.
        reservedNames: Set<String> = emptySet()
    ): Map<String, MemberSurface> = buildMap {
        members.forEach { (name, member) ->
            val accessKind = javaAccessKind(member.memberKind)
            val surfaceType = if (accessKind == MemberAccessKind.METHOD) {
                // withJavaCallableSurface's defaults (receiverType = null, includeReceiver
                // = false) are exactly the pre-merge static-branch call shape.
                member.valueType.withJavaCallableSurface(
                    receiverType = receiverType,
                    includeReceiver = false,
                    signatureMetadata = signatureMetadataOf(member)
                )
            } else {
                member.valueType
            }
            val workspaceMember = indexedJavaCompletionMember(classType, name)
            // Prefer imported:<providerPath>:<name> so gotoDefinition lands on the binary
            // provider for nested interface static helpers / createProxy instance methods
            // even when the export surface omits the member.
            put(
                name,
                MemberSurface(
                    name = name,
                    type = surfaceType,
                    accessKind = accessKind,
                    declaredType = surfaceType,
                    syntheticRange = workspaceMember?.member?.range,
                    syntheticHandle = workspaceMember?.handle
                        ?: "java:${member.owner.binaryName}:$handleFlavor:$name"
                )
            )
        }
        // Conservative readable JavaBean aliases (TASK-177). Never hide direct getter/setter methods.
        beanProperties.forEach { property ->
            if (property.name !in this && property.name !in reservedNames) {
                val surfaceType = property.valueType
                put(
                    property.name,
                    MemberSurface(
                        name = property.name,
                        type = surfaceType,
                        accessKind = MemberAccessKind.FIELD,
                        declaredType = surfaceType,
                        syntheticHandle = "java:${classType.javaName.binaryName}:$handleFlavor-bean:${property.name}"
                    )
                )
            }
        }
    }

    private fun findBackingMemberDeclaration(
        baseType: Type,
        memberName: String,
        accessKind: MemberAccessKind?
    ): BinderDeclaration? {
        val classType = baseType as? ClassType ?: return null
        val classDeclaration = binder.declarationIndex.declarations.firstOrNull {
            it.kind == DeclarationKind.CLASS && it.name == classType.name
        } ?: return null

        val expectedKind = when (accessKind) {
            MemberAccessKind.METHOD -> DeclarationKind.METHOD
            else -> DeclarationKind.FIELD
        }

        return binder.declarationIndex
            .getOwnedDeclarations(io.github.dingyi222666.luaparser.semantic.binder.DeclarationOwner.Declaration(classDeclaration.id))
            .firstOrNull { it.kind == expectedKind && it.name == memberName }
            ?: classType.superClass?.let { superClass ->
                // FIELD and METHOD lookups walk the superclass chain identically; the
                // FIELD/METHOD if/else this replaces held two byte-identical branches.
                findBackingMemberDeclaration(superClass, memberName, accessKind)
            }
    }

    private fun categoryRank(kind: MemberAccessKind): Int {
        return when (kind) {
            MemberAccessKind.FIELD, MemberAccessKind.INDEX -> 0
            MemberAccessKind.METHOD -> 1
        }
    }

    /**
     * Trust reflected memberKind for Java static/instance members. Do NOT promote
     * JavaMemberKind.FIELD to METHOD merely because the field's value type is CallableType
     * (JavaClassType implements CallableType for constructors): that mis-classification
     * demotes real static fields (System.out/err, Integer.TYPE-like Class constants) to
     * METHOD and can overwrite the module-table FIELD surface during static expansion.
     * Callable static helpers are already modeled as JavaMemberKind.METHOD.
     */
    private fun javaAccessKind(kind: JavaMemberKind): MemberAccessKind =
        if (kind == JavaMemberKind.METHOD) MemberAccessKind.METHOD else MemberAccessKind.FIELD

    private fun resolveExactNodeDeclaration(node: BaseASTNode?): BinderDeclaration? {
        node ?: return null
        val exactMatches = when (node) {
            is Identifier -> exactAstIdentifierDeclarations(node)
            else -> binder.declarationIndex.getDeclarations(node)
        }
        if (exactMatches.isEmpty()) {
            return null
        }

        return exactMatches.sortedWith { a, b ->
            compareSpecificity(declarationSiteRange(a), declarationSiteRange(b))
                .takeIf { it != 0 } ?: a.id.value.compareTo(b.id.value)
        }.firstOrNull()
    }

    private fun exactAstIdentifierDeclarations(node: Identifier): List<BinderDeclaration> {
        val directMatches = binder.declarationIndex.getDeclarations(node)
        if (directMatches.isNotEmpty()) {
            return directMatches
        }

        return binder.declarationIndex.declarations.filter { declaration ->
            declaration.origin == io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin.AST &&
                declaration.name == node.name &&
                declaration.anchorNode is Identifier &&
                declaration.anchorNode.range == node.range
        }
    }

    /**
     * Single owner for the "declaration whose site range covers the caret, most specific
     * wins" picker shared by exact doc-comment matching and fallback position resolution.
     */
    /**
     * Single owner for the "declaration whose site range satisfies [covers], best comparator
     * order wins" picker shared by exact doc-comment matching, fallback position resolution,
     * and exact-token-start matching.
     */
    private fun declarationCovering(
        covers: (Range) -> Boolean,
        rangeOf: (BinderDeclaration) -> Range?,
        originFilter: ((BinderDeclaration) -> Boolean)?,
        comparator: Comparator<Pair<BinderDeclaration, Range>>
    ): BinderDeclaration? {
        return binder.declarationIndex.declarations
            .asSequence()
            .filter { originFilter?.invoke(it) ?: true }
            .mapNotNull { declaration ->
                val range = rangeOf(declaration) ?: return@mapNotNull null
                if (covers(range)) declaration to range else null
            }
            .sortedWith(comparator)
            .map { it.first }
            .firstOrNull()
    }

    private fun resolveExactDocDeclaration(position: Position): BinderDeclaration? {
        return declarationCovering(
            covers = { isPositionWithin(it.start, it.end, position) },
            rangeOf = BinderDeclaration::declarationSpecificRange,
            originFilter = { it.origin == io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin.DOC_COMMENT },
            comparator = compareBy({ rangeSpan(it.second) }, { it.first.id.value })
        )
    }

    private fun resolveDeclarationAtPosition(position: Position): BinderDeclaration? {
        return declarationCovering(
            covers = { isPositionWithin(it.start, it.end, position) },
            rangeOf = this::declarationSiteRange,
            originFilter = null,
            comparator = compareBy(
                { declarationRank(it.first) }, { rangeSpan(it.second) }, { it.first.id.value }
            )
        )
    }

    private fun declarationSiteRange(declaration: BinderDeclaration): Range? {
        return when (declaration.origin) {
            io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin.AST -> declaration.anchorNode?.range ?: declaration.range
            io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin.DOC_COMMENT -> declaration.declarationSpecificRange() ?: declaration.range
            else -> declaration.range
        }
    }

    private fun exactDeclarationTokenRange(declaration: BinderDeclaration): Range? {
        return when (declaration.origin) {
            io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin.AST -> declaration.anchorNode?.range

            io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin.DOC_COMMENT -> declaration.declarationSpecificRange()
            else -> null
        }
    }

    private fun declarationRank(declaration: BinderDeclaration): Int {
        return when (declaration.origin) {
            io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin.AST -> 0
            io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin.DOC_COMMENT -> 1
            io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin.SYNTHETIC -> 2
            io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin.BUILTIN -> 3
        }
    }

    private var sharedGlobalsCache: List<WorkspaceImportedSymbol>? = null

    /**
     * AndroLua project scripts share one Lua global environment: chunk-level globals
     * assigned in any project file are runtime-visible in every other file. Enumerate
     * them (excluding locally-visible and imported names) once per model so completion
     * offers cross-file globals without an import edge.
     */
    private fun sharedWorkspaceGlobals(position: Position, nameFilter: ((String) -> Boolean)?): List<WorkspaceImportedSymbol> {
        val all = sharedGlobalsCache ?: run {
            val resolver = workspaceContext.workspaceResolver ?: return emptyList()
            val path = workspaceContext.currentPath ?: return emptyList()
            (resolver.allSharedGlobalSymbols(path, nameFilter) + loadlayoutInjectedGlobalSymbols())
                .also { if (nameFilter == null) sharedGlobalsCache = it }
        }
        val localNames = visibleValueDeclarationsWithoutImports(position).mapTo(HashSet()) { it.name }
        val importedNames = workspaceContext.importedSymbols.keys
        return all.filter { symbol -> nameFilter?.invoke(symbol.alias) != false &&
            symbol.alias !in localNames && symbol.alias !in importedNames }
    }

    /**
     * Ids this document's `loadlayout(t)` / `loadlayout(t, nil)` calls register into _G
     * (typed as their layout-row view class). Same shared-environment enumeration surface
     * as cross-file globals, scoped to the current document's layout calls.
     */
    private fun loadlayoutInjectedGlobalSymbols(): List<WorkspaceImportedSymbol> {
        val path = workspaceContext.currentPath ?: return emptyList()
        val globals = evaluator.loadlayoutInjectedGlobals(
            ExpressionTypeEvaluator.Context(lexicalScopeId = binder.scopeGraph.rootScope.id)
        )
        return globals.map { (name, type) ->
            WorkspaceImportedSymbol(
                alias = name,
                moduleName = "loadlayout",
                providerPath = path,
                moduleType = ModuleType(moduleName = "LuaLayoutIds"),
                valueType = type,
                kind = SymbolKind.VARIABLE
            )
        }
    }

    private fun importedVisibleDeclarations(position: Position, nameFilter: ((String) -> Boolean)?): List<VisibleDeclaration> {
        return (importedSymbolsAt(position, nameFilter).values + sharedWorkspaceGlobals(position, nameFilter))
            .sortedBy { it.alias }
            .map { imported -> VisibleDeclaration(importedDeclaration(imported), lexicalDepth = 0) }
    }

    private fun importedDeclaration(imported: WorkspaceImportedSymbol): BinderDeclaration {
        val id = io.github.dingyi222666.luaparser.semantic.binder.DeclarationId(-1000000 - imported.alias.hashCode())
        val origin = io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin.BUILTIN
        // The three factories share identical (id, name, origin, declaredType) arguments —
        // only the produced DeclarationKind differs.
        val alias = imported.alias
        val valueType = imported.valueType
        return when (imported.kind) {
            SymbolKind.FUNCTION ->
                io.github.dingyi222666.luaparser.semantic.binder.functionDeclaration(id = id, name = alias, origin = origin, declaredType = valueType)
            SymbolKind.MODULE ->
                io.github.dingyi222666.luaparser.semantic.binder.moduleDeclaration(id = id, name = alias, origin = origin, declaredType = valueType)
            else ->
                io.github.dingyi222666.luaparser.semantic.binder.globalDeclaration(id = id, name = alias, origin = origin, declaredType = valueType)
        }
    }

    private fun importedSymbolsAt(position: Position, nameFilter: ((String) -> Boolean)? = null): Map<String, WorkspaceImportedSymbol> {
        val imported = linkedMapOf<String, WorkspaceImportedSymbol>()
        val localNames = visibleValueDeclarationsWithoutImports(position).mapTo(HashSet()) { it.name }
        val activeImports = workspaceContext.importedSymbols.toMutableMap()
        workspaceContext.currentPath?.let { path ->
            workspaceContext.workspaceResolver?.importedSymbolsFor(path, includeDependencyGlobals = true, nameFilter = nameFilter)
                ?.let(activeImports::putAll)
        }
        activeImports.forEach { (alias, symbol) ->
            if (alias !in localNames && nameFilter?.invoke(alias) != false) {
                imported[alias] = symbol
            }
        }
        return imported
    }

    private fun visibleValueDeclarationsWithoutImports(position: Position): List<BinderDeclaration> =
        lexicalValueDeclarations(position).map { it.declaration }

    private fun importedSymbolAt(position: Position, node: BaseASTNode?): WorkspaceImportedSymbol? =
        (node as? Identifier)?.let { importedSymbolNamed(it.name, position) }

    private fun importedSymbolNamed(name: String, position: Position): WorkspaceImportedSymbol? {
        if (name.isBlank()) {
            return null
        }
        if (lexicalValueDeclaration(name, position) != null) {
            return null
        }
        return workspaceContext.importedSymbols[name]
            ?: workspaceContext.resolveImportedSymbol?.invoke(name)
    }

    private fun toImportedSymbol(imported: WorkspaceImportedSymbol): Symbol {
        val typeInfo = adapters.toTypeInfo(imported.valueType)
        return Symbol(
            name = imported.alias,
            kind = imported.kind,
            range = null,
            type = typeInfo,
            declaredType = typeInfo,
            detail = imported.valueType.displayName,
            symbolId = importedSymbolHandle(imported)
        )
    }

    private fun importedProviderPathValue(handle: String?): String? {
        if (handle == null || !handle.startsWith("imported:")) {
            return null
        }
        val body = handle.removePrefix("imported:")
        val separator = body.lastIndexOf(':')
        if (separator <= 0 || separator >= body.lastIndex) {
            return null
        }
        return body.substring(0, separator)
    }

    private fun rangeSpan(range: Range): Int {
        val lineSpan = (range.end.line - range.start.line) * 10_000
        return lineSpan + (range.end.column - range.start.column)
    }

    /**
     * Canonical strict positional-visibility ladder — now the shared binder predicate
     * (binder/DeclarationVisibility.kt). This file's free-position completion / hover /
     * goto queries treat builtins and chunk-global functions as file-global.
     */
    private fun isVisibleAt(declaration: BinderDeclaration, position: Position): Boolean =
        declaration.isVisibleAtPosition(
            position,
            isChunkGlobalFunction = { binder.isChunkGlobalFunctionDeclaration(it) },
            builtinIsFileGlobal = true,
            chunkGlobalIsFileGlobal = true
        )

    private fun compareSpecificity(a: Range?, b: Range?): Int {
        if (a == null) return if (b == null) 0 else 1
        if (b == null) return -1
        return compareRangeSpecificity(a, b)
    }

    private fun workspaceModuleMember(
        baseExpression: ExpressionNode?,
        baseType: Type,
        memberName: String
    ): io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceModuleResolver.ResolvedExportMember? {
        val resolver = workspaceContext.workspaceResolver ?: return null
        return when (baseType) {
            is ModuleType -> {
                val provider = resolver.activeProvider(baseType.moduleName)
                    ?: javaFallbackClassType(baseType)?.let { javaProviderFor(it) }
                    ?: return null
                val exportPath = moduleExportPath(baseExpression, provider.path)?.plus(memberName) ?: listOf(memberName)
                resolver.exportedMember(provider.path, exportPath)
                    ?: resolver.exportedMember(provider.path, memberName)
                    ?: resolver.exportedMember(provider.path, listOf("__class", memberName))
            }
            is ClassType -> {
                val provider = resolver.activeProvider(baseType.name.substringAfterLast('.')) ?: return null
                resolver.exportedMember(provider.path, listOf("__class", memberName))
            }
            is JavaClassType -> javaClassExportMember(baseType, memberName)
            // Static helpers live on the class module surface; instance members under __class.
            is JavaInstanceType -> javaClassExportMember(baseType.classType, memberName)
            is IntersectionType -> {
                // Prefer the branch that owns the member for multi-interface createProxy definitions.
                baseType.types.firstNotNullOfOrNull { branch ->
                    if (!branchExposesJavaMember(branch, memberName)) {
                        null
                    } else {
                        workspaceModuleMember(baseExpression, branch, memberName)
                    }
                } ?: baseType.types.firstNotNullOfOrNull { branch ->
                    workspaceModuleMember(baseExpression, branch, memberName)
                }
            }
            is UnionType -> {
                val matches = baseType.types.mapNotNull { branch ->
                    workspaceModuleMember(baseExpression, branch, memberName)
                }
                matches.distinctBy { it.handle }.singleOrNull()
            }
            else -> null
        }
    }

    /** Class-module member lookup: direct export first, then the __class static slot. */
    private fun javaClassExportMember(
        classType: JavaClassType,
        memberName: String
    ): io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceModuleResolver.ResolvedExportMember? {
        val provider = javaProviderFor(classType) ?: return null
        return workspaceContext.workspaceResolver?.exportedMember(provider.path, listOf(memberName))
            ?: workspaceContext.workspaceResolver?.exportedMember(provider.path, listOf("__class", memberName))
    }

    private fun indexedJavaCompletionMember(
        classType: JavaClassType,
        memberName: String
    ): io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceModuleResolver.ResolvedExportMember? {
        val resolver = workspaceContext.workspaceResolver ?: return null
        // Listing candidates must not load every declaring class for navigation.
        // Definition queries resolve missing providers separately when requested.
        val provider = resolver.activeProvider(classType.javaName.canonicalName)
            ?: resolver.activeProvider(classType.javaName.binaryName)
            ?: resolver.activeProvider(classType.javaName.simpleName)
            ?: return null
        return resolver.exportedMember(provider.path, listOf(memberName))
            ?: resolver.exportedMember(provider.path, listOf("__class", memberName))
    }

    private fun javaProviderFor(classType: JavaClassType): io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceModuleGraph.ModuleProvider? {
        val resolver = workspaceContext.workspaceResolver ?: return null
        return resolver.activeProvider(classType.javaName.simpleName)
            ?: resolver.activeProvider(classType.javaName.canonicalName)
            ?: resolver.activeProvider(classType.javaName.binaryName)
            ?: (resolveLuaJavaImportTarget(classType.javaName.canonicalName)
                ?: resolveLuaJavaImportTarget(classType.javaName.binaryName))?.let { imported ->
                io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceModuleGraph.ModuleProvider(
                    moduleName = imported.moduleName,
                    path = imported.providerPath,
                    source = io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceModuleGraph.ProviderSource.EXTRA_WORKSPACE_PROVIDER
                )
            }
    }

    private fun javaMemberFallbackSymbolId(baseType: Type, memberName: String): String? {
        when (baseType) {
            is IntersectionType -> {
                // Multi-interface createProxy: pick the branch that actually declares the member
                // so run → Runnable and compare → Comparator, not the first intersection arm.
                for (branch in baseType.types) {
                    if (!branchExposesJavaMember(branch, memberName)) continue
                    javaMemberFallbackSymbolId(branch, memberName)?.let { return it }
                }
                return baseType.types.firstNotNullOfOrNull { javaMemberFallbackSymbolId(it, memberName) }
            }
            is UnionType -> {
                val handles = baseType.types.mapNotNull { branch ->
                    javaMemberFallbackSymbolId(branch, memberName)
                }.distinct()
                return handles.singleOrNull()
            }
            else -> {
                val classType = javaFallbackClassType(baseType) ?: return null
                val imported = resolveLuaJavaImportTarget(classType.javaName.canonicalName)
                    ?: resolveLuaJavaImportTarget(classType.javaName.binaryName)
                    ?: resolveLuaJavaImportTarget(classType.javaName.simpleName)
                    ?: return null
                return "imported:${imported.providerPath.value}:$memberName"
            }
        }
    }

    private fun branchExposesJavaMember(type: Type, memberName: String): Boolean {
        return when (type) {
            is JavaInstanceType ->
                memberName in type.allInstanceMembers() ||
                    memberName in type.classType.allStaticMembers()
            is JavaClassType ->
                memberName in type.allInstanceMembers() ||
                    memberName in type.allStaticMembers()
            is ModuleType ->
                javaClassSurfaceFromModule(type)?.let { branchExposesJavaMember(it, memberName) } == true ||
                    memberName in type.fields ||
                    memberName in type.methods
            is IntersectionType -> type.types.any { branchExposesJavaMember(it, memberName) }
            is UnionType -> type.types.all { branchExposesJavaMember(it, memberName) }
            else -> false
        }
    }

    private fun javaClassSurfaceFromModule(moduleType: ModuleType): JavaClassType? {
        return when (val classSurface = moduleType.fields["__class"]) {
            is JavaClassType -> classSurface
            is JavaInstanceType -> classSurface.classType
            else -> null
        }
    }

    private fun javaFallbackClassType(baseType: Type): JavaClassType? {
        return when (baseType) {
            is ModuleType -> javaClassSurfaceFromModule(baseType)
            is JavaClassType -> baseType
            is JavaInstanceType -> baseType.classType
            is IntersectionType -> baseType.types.firstNotNullOfOrNull { branch -> javaFallbackClassType(branch) }
            is UnionType -> baseType.types.mapNotNull { branch -> javaFallbackClassType(branch) }
                .distinctBy { it.javaName.binaryName }
                .singleOrNull()
            else -> null
        }
    }

    private fun moduleExportPath(
        expression: ExpressionNode?,
        providerPath: io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
    ): List<String>? {
        return when (expression) {
            is Identifier -> null
            is MemberExpression -> {
                val basePath = moduleExportPath(expression.base, providerPath) ?: emptyList()
                val export = workspaceModuleMember(expression.base, evaluator.evaluate(expression.base), expression.identifier.name)
                when {
                    export?.providerPath == providerPath -> export.member.exportPath
                    else -> basePath + expression.identifier.name
                }
            }
            is IndexExpression -> {
                val key = (expression.index as? ConstantNode)
                    ?.takeIf { it.constantType == ConstantNode.TYPE.STRING }
                    ?.stringOf()
                    ?: return null
                val basePath = moduleExportPath(expression.base, providerPath) ?: emptyList()
                basePath + key
            }
            else -> null
        }
    }
}

/**
 * Shared "callable whose every signature returns only unknown" predicate (hover preference
 * and node-type preference): such unannotated shells must not freeze a concrete body
 * inference that yields a real return type.
 */
internal fun Type.isUnknownReturnOnlyCallable(): Boolean {
    val callable = this as? CallableType ?: return false
    return callable.callSignatures.isNotEmpty() &&
        callable.callSignatures.all { signature -> signature.returnType == UnknownType }
}

/**
 * Shared bare-weak-type predicate (hover/resolve preference ordering): unannotated
 * `fun(...): unknown` shells, blank/unknown/any displays, and unmodule'd UNKNOWN kinds
 * lose to body-inferred or structured surfaces. Single owner for the model's
 * preference ranking and the workspace facade's hover fallback choice.
 */
internal fun TypeInfo.isBareWeakType(): Boolean {
    val display = displayName
    // Unannotated fun(...): unknown shells lose to body-inferred returns (TextView, ...).
    if (display.endsWith(": unknown") ||
        display.endsWith(": any") ||
        display.contains("): unknown") ||
        display.contains("): any")
    ) {
        if (display.contains("fun(") || display.contains("fun<")) {
            return true
        }
    }
    return display.isBlank() ||
        display == "unknown" ||
        display == "any" ||
        (kind == TypeInfoKind.UNKNOWN &&
            !display.contains("fun(") &&
            !display.startsWith("Array<") &&
            moduleName.isNullOrBlank())
}
