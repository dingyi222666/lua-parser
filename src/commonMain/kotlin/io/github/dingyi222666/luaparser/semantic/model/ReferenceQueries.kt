package io.github.dingyi222666.luaparser.semantic.model

import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode
import io.github.dingyi222666.luaparser.parser.ast.node.ConstantNode
import io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.IndexExpression
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.parser.ast.node.MemberExpression
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.parser.ast.node.StringCallExpression
import io.github.dingyi222666.luaparser.semantic.SemanticWorkspaceContext
import io.github.dingyi222666.luaparser.semantic.WorkspaceImportedSymbol
import io.github.dingyi222666.luaparser.semantic.api.Symbol
import io.github.dingyi222666.luaparser.semantic.binder.BinderDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationNamespace
import io.github.dingyi222666.luaparser.semantic.binder.Scope
import io.github.dingyi222666.luaparser.semantic.binder.ScopeId
import io.github.dingyi222666.luaparser.semantic.comments.AliasTagSyntax
import io.github.dingyi222666.luaparser.semantic.comments.ClassTagSyntax
import io.github.dingyi222666.luaparser.semantic.comments.FieldTagSyntax
import io.github.dingyi222666.luaparser.semantic.comments.GenericTagSyntax
import io.github.dingyi222666.luaparser.semantic.comments.MethodTagSyntax
import io.github.dingyi222666.luaparser.semantic.checker.ExpressionTypeEvaluator
import io.github.dingyi222666.luaparser.semantic.checker.MemberAccessKind
import io.github.dingyi222666.luaparser.semantic.checker.MemberResolver
import io.github.dingyi222666.luaparser.semantic.checker.allReadableInstanceJavaBeanProperties
import io.github.dingyi222666.luaparser.semantic.checker.allReadableStaticJavaBeanProperties
import io.github.dingyi222666.luaparser.semantic.checker.hydrateJavaProviderType
import io.github.dingyi222666.luaparser.semantic.checker.isJavaBackedModule
import io.github.dingyi222666.luaparser.semantic.checker.isJavaProviderClassReference
import io.github.dingyi222666.luaparser.semantic.checker.javaInstanceSurface
import io.github.dingyi222666.luaparser.semantic.checker.withJavaCallableSurface
import io.github.dingyi222666.luaparser.semantic.types.model.ClassType
import io.github.dingyi222666.luaparser.semantic.types.model.CallableType
import io.github.dingyi222666.luaparser.semantic.types.model.IntersectionType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaArrayType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaClassType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaInstanceType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaMemberKind
import io.github.dingyi222666.luaparser.semantic.types.model.ModuleType
import io.github.dingyi222666.luaparser.semantic.types.model.TableType
import io.github.dingyi222666.luaparser.semantic.types.model.Type
import io.github.dingyi222666.luaparser.semantic.types.model.TypeParameterType
import io.github.dingyi222666.luaparser.semantic.types.model.UnionType
import io.github.dingyi222666.luaparser.semantic.types.resolve.TypeExpansion
import io.github.dingyi222666.luaparser.semantic.types.resolve.intersectionTypeOf
import io.github.dingyi222666.luaparser.semantic.types.resolve.unionTypeOf

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
        val syntheticRange: io.github.dingyi222666.luaparser.parser.ast.node.Range? = null,
        val syntheticHandle: String? = null
    )

    fun getSymbolAt(position: Position, node: BaseASTNode?): Symbol? {
        val importedSymbol = importedSymbolAt(position, node)
        localJavaMemberInitializerSymbolAt(position, node)?.let { return it }
        bindClassTargetLocalSymbolAt(node)?.let { return it }
        importCallTargetLocalSymbolAt(node)?.let { return it }
        importTargetStringSymbolAt(node)?.let { return it }
        val declarationSymbol = resolveDeclarationTokenStartAt(position)
            ?.let { adapters.toDeclarationSymbol(it) }
            ?: resolveExactNodeDeclaration(node)
            ?.let { adapters.toDeclarationSymbol(it) }
            ?: resolveExactDocDeclaration(position)
                ?.let { adapters.toDeclarationSymbol(it) }
            ?: resolveDeclarationAtPosition(position)
            ?.let { adapters.toDeclarationSymbol(it) }
            ?: binder.positionQueries.getDeclarationAt(position)
                ?.let { adapters.toDeclarationSymbol(it) }
            ?: binder.positionQueries.getSymbolAt(position)
                ?.let(adapters::toSymbol)
        if (declarationSymbol != null) {
            // Prefer MODULE-kind imported symbols over synthetic/global fallbacks when the
            // identifier is an activated import alias (not a true local/parameter declaration).
            if (
                declarationSymbol.kind == io.github.dingyi222666.luaparser.semantic.api.SymbolKind.VARIABLE &&
                importedSymbol != null
            ) {
                return toImportedSymbol(importedSymbol)
            }
            // Dynamic import() locals often bind as plain LOCAL with unknown declaredType.
            // Prefer the resolved import-call module/package/array type for hover/goto.
            if (
                declarationSymbol.kind == io.github.dingyi222666.luaparser.semantic.api.SymbolKind.LOCAL ||
                declarationSymbol.kind == io.github.dingyi222666.luaparser.semantic.api.SymbolKind.VARIABLE
            ) {
                importCallTargetLocalSymbolAt(node)?.let { importLocal ->
                    val declaredDisplay = declarationSymbol.type?.displayName
                    val needsImportSurface = declaredDisplay.isNullOrBlank() ||
                        declaredDisplay == "unknown" ||
                        declaredDisplay == "any" ||
                        (importLocal.type?.moduleName != null && declarationSymbol.type?.moduleName.isNullOrBlank())
                    if (needsImportSurface) {
                        return importLocal
                    }
                }
            }
            // True local/parameter/function declarations always win over imported MODULE aliases
            // so local shadowing (`local File = { ... }`) stays preferred after declaration.
            return declarationSymbol
        }

        return when (node) {
            is Identifier -> {
                val parent = runCatching { node.parent }.getOrNull()
                if (parent is MemberExpression && parent.identifier === node) {
                    resolveMemberUsage(parent)
                } else {
                    importedSymbol?.let(::toImportedSymbol)
                        ?: findNearestVisibleValueDeclaration(node.name, position)?.let { adapters.toDeclarationSymbol(it) }
                }
            }

            is MemberExpression -> resolveMemberUsage(node)
            else -> importedSymbol?.let(::toImportedSymbol)
        }
    }

    private fun resolveDeclarationTokenStartAt(position: Position): BinderDeclaration? {
        return binder.declarationIndex.declarations
            .asSequence()
            .mapNotNull { declaration ->
                val range = exactDeclarationTokenRange(declaration) ?: return@mapNotNull null
                if (range.start == position) declaration to range else null
            }
            .sortedWith(compareBy<Pair<BinderDeclaration, io.github.dingyi222666.luaparser.parser.ast.node.Range>>(
                { declarationRank(it.first) },
                { rangeSpan(it.second) },
                { it.first.id.value }
            ))
            .map { it.first }
            .firstOrNull()
    }

    fun visibleValueSymbols(position: Position): List<Symbol> {
        return visibleValueDeclarations(position)
            .mapNotNull { adapters.toDeclarationSymbol(it.declaration) }
    }

    fun visibleValueDeclarations(position: Position): List<VisibleDeclaration> {
        val importedVisible = importedVisibleDeclarations(position)
        val scope = binder.positionQueries.getScopeAt(position) ?: return importedVisible
        val results = mutableListOf<VisibleDeclaration>()
        val seenNames = linkedSetOf<String>()

        var current: Scope? = scope
        var lexicalDepth = 0
        while (current != null) {
            current.declarationIds
                .asReversed()
                .mapNotNull(binder.declarationIndex::getDeclaration)
                .forEach { declaration ->
                    if (
                        declaration.kind.namespace == DeclarationNamespace.VALUE &&
                        declaration.name !in seenNames &&
                        isVisibleAt(declaration, position)
                    ) {
                        seenNames += declaration.name
                        results += VisibleDeclaration(declaration, lexicalDepth)
                    }
                }
            current = current.parentId?.let(binder.scopeGraph::getScope)
            lexicalDepth += 1
        }

        importedVisible.forEach { visible ->
            if (visible.declaration.name !in seenNames) {
                seenNames += visible.declaration.name
                results += visible
            }
        }

        return results
    }

    fun findNearestVisibleValueDeclaration(name: String, position: Position): BinderDeclaration? {
        return visibleValueDeclarations(position)
            .firstOrNull { it.declaration.name == name }
            ?.declaration
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
        val baseType = luaJavaLocalCallType(expression.base)
            ?: evaluator.evaluate(expression.base)
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

        val normalizedBase = TypeExpansion.expandForMemberSurface(baseType, lexicalScopeId, binder)
        val workspaceMember = workspaceModuleMember(expression.base, normalizedBase, expression.identifier.name)
        val declaration = findBackingMemberDeclaration(normalizedBase, expression.identifier.name, resolution.accessKind)
        val fallbackSymbolId = if (workspaceMember == null) {
            javaMemberFallbackSymbolId(normalizedBase, expression.identifier.name)
        } else {
            null
        }
        return declaration?.let { adapters.toDeclarationSymbol(it, resolvedType, resolvedType) }
            ?: workspaceMember?.let {
                adapters.syntheticMemberSymbol(
                    name = expression.identifier.name,
                    kind = resolution.accessKind ?: MemberAccessKind.FIELD,
                    type = resolvedType,
                    declaredType = resolvedType,
                    handleSeed = it.handle,
                    symbolId = it.handle,
                    range = it.member.range
                )
            }
            ?: adapters.syntheticMemberSymbol(
                name = expression.identifier.name,
                kind = resolution.accessKind ?: MemberAccessKind.FIELD,
                type = resolvedType,
                handleSeed = "${baseType.displayName}:${expression.indexer}:${expression.identifier.name}",
                symbolId = fallbackSymbolId
            )
    }

    private fun localJavaMemberInitializerSymbolAt(position: Position, node: BaseASTNode?): Symbol? {
        if (node is Identifier) {
            val parent = runCatching { node.parent }.getOrNull()
            if (parent is MemberExpression && parent.identifier === node) {
                return null
            }
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
        val baseType = luaJavaLocalCallType(initializer.base)
            ?: evaluator.evaluate(initializer.base)
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
        val normalizedBase = TypeExpansion.expandForMemberSurface(baseType, lexicalScopeId, binder)
        if (!isJavaMemberProviderBase(normalizedBase)) {
            return null
        }
        val member = collectMemberSurface(normalizedBase, lexicalScopeId)[expression.identifier.name] ?: return null
        val fallbackSymbolId = javaMemberFallbackSymbolId(normalizedBase, expression.identifier.name) ?: member.syntheticHandle
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

    private fun bindClassTargetLocalSymbolAt(node: BaseASTNode?): Symbol? {
        val constant = node as? ConstantNode ?: return null
        if (constant.constantType != ConstantNode.TYPE.STRING) {
            return null
        }
        val call = enclosingCallExpression(constant) ?: return null
        if (callArguments(call).firstOrNull() !== constant) {
            return null
        }
        if (!isLuaJavaBindClassCall(call)) {
            return null
        }
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

    private fun importCallTargetLocalSymbolAt(node: BaseASTNode?): Symbol? {
        val identifier = node as? Identifier ?: return null
        val parent = runCatching { identifier.parent }.getOrNull()
        if (parent is MemberExpression && parent.identifier === identifier) {
            return null
        }
        val declaration = findVisibleValueDeclarationWithoutImports(
            name = identifier.name,
            position = identifier.range.start,
            excludedDeclarations = emptySet()
        ) ?: return null
        if (declaration.kind != DeclarationKind.LOCAL) {
            return null
        }
        val initializer = localDeclarationInitializer(declaration)
            as? io.github.dingyi222666.luaparser.parser.ast.node.CallExpression
            ?: return null
        if (!isImportCallBase(effectiveCallBase(initializer))) {
            return null
        }
        val targets = importCallTargets(initializer)
        if (targets.isEmpty()) {
            return null
        }
        val importedTypes = targets.mapNotNull { target ->
            resolveLuaJavaImportTarget(target)?.moduleType
        }
        if (importedTypes.isEmpty()) {
            return null
        }
        val resolvedType = if (targets.size == 1 && importedTypes.size == 1) {
            importedTypes.single()
        } else {
            val element = unionTypeOf(importedTypes)
            io.github.dingyi222666.luaparser.semantic.types.model.ArrayType(
                elementType = element,
                name = "Array<${element.displayName}>"
            )
        }
        return adapters.toDeclarationSymbol(declaration, resolvedType, resolvedType)
    }

    private fun importCallTargets(node: io.github.dingyi222666.luaparser.parser.ast.node.CallExpression): List<String> {
        val firstArgument = callArguments(node).firstOrNull()
        return when (firstArgument) {
            is ConstantNode -> {
                if (firstArgument.constantType == ConstantNode.TYPE.STRING) {
                    listOf(firstArgument.stringOf())
                } else {
                    emptyList()
                }
            }
            is io.github.dingyi222666.luaparser.parser.ast.node.ArrayConstructorExpression ->
                firstArgument.values.mapNotNull { expression ->
                    (expression as? ConstantNode)
                        ?.takeIf { it.constantType == ConstantNode.TYPE.STRING }
                        ?.stringOf()
                }
            is io.github.dingyi222666.luaparser.parser.ast.node.TableConstructorExpression ->
                firstArgument.fields.mapNotNull { field ->
                    (field.value as? ConstantNode)
                        ?.takeIf { it.constantType == ConstantNode.TYPE.STRING }
                        ?.stringOf()
                }
            else -> emptyList()
        }
    }

    private fun importTargetStringSymbolAt(node: BaseASTNode?): Symbol? {
        val constant = node as? ConstantNode ?: return null
        if (constant.constantType != ConstantNode.TYPE.STRING) {
            return null
        }
        val call = enclosingCallExpression(constant) ?: return null
        if (!callArguments(call).any { it === constant }) {
            return null
        }
        if (!isImportCallBase(effectiveCallBase(call))) {
            return null
        }
        val target = constant.stringOf()
        val imported = resolveLuaJavaImportTarget(target) ?: return null
        return toImportedSymbol(imported)
    }

    private fun isImportCallBase(expression: ExpressionNode): Boolean {
        return expressionResolvesToImportCallable(expression, emptySet(), linkedSetOf())
    }

    private fun expressionResolvesToImportCallable(
        expression: ExpressionNode,
        excludedDeclarations: Set<io.github.dingyi222666.luaparser.semantic.binder.DeclarationId>,
        visited: MutableSet<io.github.dingyi222666.luaparser.semantic.binder.DeclarationId>
    ): Boolean {
        return when (expression) {
            is Identifier -> {
                if (expression.name == "import") {
                    val declaration = findVisibleValueDeclarationWithoutImports(
                        name = expression.name,
                        position = expression.range.start,
                        excludedDeclarations = excludedDeclarations
                    )
                    // Bare/global `import` is the Android-Lua import callable.
                    if (declaration == null || declaration.origin == io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin.BUILTIN) {
                        return true
                    }
                }
                val declaration = findVisibleValueDeclarationWithoutImports(
                    name = expression.name,
                    position = expression.range.start,
                    excludedDeclarations = excludedDeclarations
                ) ?: return false
                if (!visited.add(declaration.id)) {
                    return false
                }
                val initializer = localDeclarationInitializer(declaration) ?: return false
                when (initializer) {
                    is io.github.dingyi222666.luaparser.parser.ast.node.CallExpression -> {
                        val callee = effectiveCallBase(initializer) as? Identifier
                        val requireName = stringCallTarget(initializer)
                        callee?.name == "require" && requireName == "import"
                    }
                    is Identifier -> expressionResolvesToImportCallable(
                        initializer,
                        excludedDeclarations + localStatementDeclarationIds(declaration),
                        visited
                    )
                    else -> false
                }
            }
            else -> false
        }
    }

    private fun luaJavaLocalCallType(expression: ExpressionNode): Type? {
        val identifier = expression as? Identifier ?: return null
        val declaration = findVisibleValueDeclarationWithoutImports(
            name = identifier.name,
            position = identifier.range.start,
            excludedDeclarations = emptySet()
        ) ?: return null
        return luaJavaLocalInitializerType(declaration)
    }

    private fun luaJavaLocalInitializerType(declaration: BinderDeclaration): Type? {
        if (declaration.kind != DeclarationKind.LOCAL) {
            return null
        }
        val call = localDeclarationInitializer(declaration) as? io.github.dingyi222666.luaparser.parser.ast.node.CallExpression
            ?: return null
        val target = stringCallTarget(call) ?: return null
        return luaJavaHelperCallType(call, target)
            ?: luaJavaHelperCallTypeFromDeclarationChain(call, target, declaration)
    }

    private fun luaJavaHelperCallType(
        call: io.github.dingyi222666.luaparser.parser.ast.node.CallExpression,
        target: String
    ): Type? {
        return when {
            isLuaJavaHelperCall(call, "bindClass") ->
                resolveLuaJavaImportTarget(target)?.moduleType

            isLuaJavaHelperCall(call, "newInstance") ->
                resolveLuaJavaImportTarget(target)?.moduleType
                    ?.javaInstanceSurface()
                    ?.hydrateLuaJavaProviderType()

            else -> null
        }
    }

    private fun luaJavaHelperCallTypeFromDeclarationChain(
        call: io.github.dingyi222666.luaparser.parser.ast.node.CallExpression,
        target: String,
        assignedDeclaration: BinderDeclaration
    ): Type? {
        val base = effectiveCallBase(call) as? Identifier ?: return null
        val baseDeclaration = findVisibleValueDeclarationWithoutImports(
            name = base.name,
            position = base.range.start,
            excludedDeclarations = localStatementDeclarationIds(assignedDeclaration)
        ) ?: return null
        val excluded = localStatementDeclarationIds(assignedDeclaration)
        val helperName = when {
            declarationResolvesToLuaJavaHelperByDeclarationChain(baseDeclaration, "bindClass", excluded, linkedSetOf()) -> "bindClass"
            declarationResolvesToLuaJavaHelperByDeclarationChain(baseDeclaration, "newInstance", excluded, linkedSetOf()) -> "newInstance"
            else -> return null
        }
        val moduleType = resolveLuaJavaImportTarget(target)?.moduleType ?: return null
        return if (helperName == "bindClass") {
            moduleType
        } else {
            moduleType.javaInstanceSurface()
                ?.hydrateLuaJavaProviderType()
        }
    }

    private fun declarationResolvesToLuaJavaHelperByDeclarationChain(
        declaration: BinderDeclaration,
        helperName: String,
        excludedDeclarations: Set<io.github.dingyi222666.luaparser.semantic.binder.DeclarationId>,
        visited: MutableSet<io.github.dingyi222666.luaparser.semantic.binder.DeclarationId>
    ): Boolean {
        if (
            declaration.kind != DeclarationKind.LOCAL ||
            declaration.id in excludedDeclarations ||
            !visited.add(declaration.id)
        ) {
            return false
        }
        // Exclude only the current alias hop's same-statement locals. Do not accumulate prior hops:
        // transitive chains like `bindClass -> bind -> again` must still see earlier alias decls.
        val hopExclusions = localStatementDeclarationIds(declaration)
        return when (val initializer = localDeclarationInitializer(declaration)) {
            is MemberExpression -> isLuaJavaHelperMemberByDeclarationChain(
                initializer,
                helperName,
                hopExclusions
            )
            is Identifier -> {
                val next = findVisibleValueDeclarationWithoutImports(
                    name = initializer.name,
                    position = initializer.range.start,
                    excludedDeclarations = hopExclusions
                ) ?: return false
                declarationResolvesToLuaJavaHelperByDeclarationChain(
                    next,
                    helperName,
                    hopExclusions,
                    visited
                )
            }
            else -> false
        }
    }

    private fun isLuaJavaHelperMemberByDeclarationChain(
        member: MemberExpression,
        helperName: String,
        excludedDeclarations: Set<io.github.dingyi222666.luaparser.semantic.binder.DeclarationId>
    ): Boolean {
        val owner = member.base as? Identifier ?: return false
        if (member.indexer != "." || member.identifier.name != helperName || owner.name != "luajava") {
            return false
        }
        val ownerDeclaration = findVisibleValueDeclarationWithoutImports(
            name = owner.name,
            position = owner.range.start,
            excludedDeclarations = excludedDeclarations
        )
        return ownerDeclaration == null ||
            ownerDeclaration.origin == io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin.BUILTIN
    }

    private fun localDeclarationInitializer(declaration: BinderDeclaration): ExpressionNode? {
        val localStatement = declaration.anchorNode?.parent as? LocalStatement ?: return null
        val initializerIndex = localStatement.init.indexOf(declaration.anchorNode)
        if (initializerIndex < 0) {
            return null
        }
        return localStatement.variables.getOrNull(initializerIndex)
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

    private fun enclosingCallExpression(node: BaseASTNode): io.github.dingyi222666.luaparser.parser.ast.node.CallExpression? {
        var current: BaseASTNode? = node
        while (current != null) {
            if (current is io.github.dingyi222666.luaparser.parser.ast.node.CallExpression) {
                return current
            }
            current = runCatching { current.parent }.getOrNull()
        }
        return null
    }

    private fun enclosingLocalStatement(node: BaseASTNode): LocalStatement? {
        var current: BaseASTNode? = node
        while (current != null) {
            if (current is LocalStatement) {
                return current
            }
            current = runCatching { current.parent }.getOrNull()
        }
        return null
    }

    private fun callArguments(node: io.github.dingyi222666.luaparser.parser.ast.node.CallExpression): List<ExpressionNode> {
        val stringCallBase = node.base as? StringCallExpression
        return buildList {
            if (stringCallBase != null) {
                addAll(stringCallBase.arguments)
            }
            addAll(node.arguments)
        }
    }

    private fun effectiveCallBase(node: io.github.dingyi222666.luaparser.parser.ast.node.CallExpression): ExpressionNode {
        val base = if (node.base is StringCallExpression && node.arguments.isEmpty()) {
            node.base
        } else {
            node.base
        }
        return if (base is StringCallExpression) base.base else base
    }

    private fun isLuaJavaBindClassCall(node: io.github.dingyi222666.luaparser.parser.ast.node.CallExpression): Boolean {
        return isLuaJavaHelperCall(node, "bindClass")
    }

    private fun isLuaJavaHelperCall(
        node: io.github.dingyi222666.luaparser.parser.ast.node.CallExpression,
        helperName: String
    ): Boolean {
        return expressionResolvesToLuaJavaHelper(
            effectiveCallBase(node),
            helperName = helperName,
            excludedDeclarations = emptySet(),
            visited = linkedSetOf()
        )
    }

    private fun expressionResolvesToLuaJavaHelper(
        expression: ExpressionNode,
        helperName: String,
        excludedDeclarations: Set<io.github.dingyi222666.luaparser.semantic.binder.DeclarationId>,
        visited: MutableSet<io.github.dingyi222666.luaparser.semantic.binder.DeclarationId>
    ): Boolean {
        return when (expression) {
            is MemberExpression -> isLuaJavaHelperMember(expression, helperName, excludedDeclarations)
            is Identifier -> {
                val declaration = findVisibleValueDeclarationWithoutImports(
                    name = expression.name,
                    position = expression.range.start,
                    excludedDeclarations = excludedDeclarations
                ) ?: return false
                if (declarationResolvesToLuaJavaHelper(declaration, helperName, excludedDeclarations, visited)) {
                    return true
                }
                // Fresh visited set: the primary walk may have marked this declaration.
                declarationResolvesToLuaJavaHelperByDeclarationChain(
                    declaration,
                    helperName,
                    excludedDeclarations,
                    linkedSetOf()
                )
            }
            else -> false
        }
    }

    private fun declarationResolvesToLuaJavaHelper(
        declaration: BinderDeclaration,
        helperName: String,
        excludedDeclarations: Set<io.github.dingyi222666.luaparser.semantic.binder.DeclarationId>,
        visited: MutableSet<io.github.dingyi222666.luaparser.semantic.binder.DeclarationId>
    ): Boolean {
        if (declaration.kind != DeclarationKind.LOCAL || !visited.add(declaration.id)) {
            return false
        }
        val initializer = localDeclarationInitializer(declaration) ?: return false
        return expressionResolvesToLuaJavaHelper(
            expression = initializer,
            helperName = helperName,
            excludedDeclarations = excludedDeclarations + localStatementDeclarationIds(declaration),
            visited = visited
        )
    }

    private fun isLuaJavaHelperMember(
        member: MemberExpression,
        helperName: String,
        excludedDeclarations: Set<io.github.dingyi222666.luaparser.semantic.binder.DeclarationId>
    ): Boolean {
        val owner = member.base as? Identifier ?: return false
        if (member.indexer != "." || member.identifier.name != helperName || owner.name != "luajava") {
            return false
        }
        val declaration = findVisibleValueDeclarationWithoutImports(
            name = owner.name,
            position = owner.range.start,
            excludedDeclarations = excludedDeclarations
        )
        // Unshadowed `luajava` is the helper owner; only non-builtin bindings shadow it.
        return declaration == null ||
            declaration.origin == io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin.BUILTIN
    }

    private fun resolveLuaJavaImportTarget(target: String) =
        workspaceContext.resolveImportTarget?.invoke(target)
            ?: workspaceContext.workspaceResolver?.importTargetSymbol(target)

    private fun Type.hydrateLuaJavaProviderType(): Type =
        hydrateJavaProviderType { target -> resolveLuaJavaImportTarget(target) }

    private fun localStatementDeclarationIds(
        declaration: BinderDeclaration
    ): Set<io.github.dingyi222666.luaparser.semantic.binder.DeclarationId> {
        val localStatement = declaration.anchorNode?.parent as? LocalStatement ?: return emptySet()
        return localStatement.init.mapNotNull { identifier ->
            binder.declarationIndex.getDeclarations(identifier)
                .firstOrNull { it.kind == DeclarationKind.LOCAL }
                ?.id
        }.toSet()
    }

    private fun findVisibleValueDeclarationWithoutImports(
        name: String,
        position: Position,
        excludedDeclarations: Set<io.github.dingyi222666.luaparser.semantic.binder.DeclarationId>
    ): BinderDeclaration? {
        var scope = binder.positionQueries.getScopeAt(position)
        while (scope != null) {
            scope.declarationIds
                .asReversed()
                .mapNotNull(binder.declarationIndex::getDeclaration)
                .firstOrNull { declaration ->
                    declaration.kind.namespace == DeclarationNamespace.VALUE &&
                        declaration.name == name &&
                        declaration.id !in excludedDeclarations &&
                        isVisibleAt(declaration, position)
                }
                ?.let { return it }
            scope = scope.parentId?.let(binder.scopeGraph::getScope)
        }
        return null
    }

    private fun stringCallTarget(
        node: io.github.dingyi222666.luaparser.parser.ast.node.CallExpression,
        argumentIndex: Int = 0
    ): String? {
        val constant = callArguments(node).getOrNull(argumentIndex) as? ConstantNode ?: return null
        return constant.takeIf { it.constantType == ConstantNode.TYPE.STRING }?.stringOf()
    }

    fun resolveMemberCompletionSurface(expression: MemberExpression): List<Symbol> {
        val lexicalScopeId = binder.positionQueries.getScopeAt(expression.range.start)?.id ?: binder.scopeGraph.rootScope.id
        val baseType = luaJavaLocalCallType(expression.base)
            ?: evaluator.evaluate(expression.base)
                .hydrateJavaProviderType(workspaceContext.resolveImportTarget)
        val members = getMembers(baseType, lexicalScopeId)
        return if (expression.indexer == ":") {
            members.sortedWith(compareBy<Symbol>({ if (it.kind == io.github.dingyi222666.luaparser.semantic.api.SymbolKind.METHOD) 0 else 1 }, { it.name }))
        } else {
            members.sortedWith(compareBy<Symbol>({ if (it.kind == io.github.dingyi222666.luaparser.semantic.api.SymbolKind.FIELD) 0 else 1 }, { it.name }))
        }
    }

    private fun collectMemberSurface(type: Type, lexicalScopeId: ScopeId): Map<String, MemberSurface> {
        val normalized = TypeExpansion.expandForMemberSurface(
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
                normalized.fields.forEach { (name, memberType) ->
                    val workspaceMember = workspaceModuleMember(null, normalized, name)
                    val surfaceType = if (normalized.isJavaBackedModule() && name == "__call") {
                        memberType.withJavaCallableSurface(resolveImportTarget = workspaceContext.resolveImportTarget)
                    } else {
                        memberType.hydrateJavaProviderType(workspaceContext.resolveImportTarget)
                    }
                    put(name, MemberSurface(name, surfaceType, MemberAccessKind.FIELD, syntheticRange = workspaceMember?.member?.range, syntheticHandle = workspaceMember?.handle))
                }
                normalized.methods.forEach { (name, memberType) ->
                    val workspaceMember = workspaceModuleMember(null, normalized, name)
                    val surfaceType = if (normalized.isJavaBackedModule()) {
                        memberType.withJavaCallableSurface(resolveImportTarget = workspaceContext.resolveImportTarget)
                    } else {
                        memberType.hydrateJavaProviderType(workspaceContext.resolveImportTarget)
                    }
                    put(name, MemberSurface(name, surfaceType, MemberAccessKind.METHOD, syntheticRange = workspaceMember?.member?.range, syntheticHandle = workspaceMember?.handle))
                }
                javaClassSurfaceFromModule(normalized)?.let { classType ->
                    collectJavaStaticMemberSurface(classType).forEach { (name, surface) ->
                        if (name !in this) {
                            put(name, surface.copy(syntheticHandle = surface.syntheticHandle ?: "java:${classType.javaName.binaryName}:static:$name"))
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
                merged.mapValues { (_, entries) ->
                    val declaration = entries.mapNotNull(MemberSurface::declaration).distinct().singleOrNull()
                    MemberSurface(
                        name = entries.first().name,
                        type = if (entries.size == 1) entries.single().type else intersectionTypeOf(entries.map(MemberSurface::type)),
                        accessKind = if (entries.all { it.accessKind == MemberAccessKind.METHOD }) MemberAccessKind.METHOD else MemberAccessKind.FIELD,
                        declaration = declaration,
                        declaredType = declaration?.declaredType
                    )
                }
            }

            else -> emptyMap()
        }
    }

    private fun collectJavaStaticMemberSurface(classType: JavaClassType): Map<String, MemberSurface> = buildMap {
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
        classType.allStaticMembers().forEach { (name, member) ->
            val accessKind = javaAccessKind(member.memberKind, member.valueType)
            val surfaceType = if (accessKind == MemberAccessKind.METHOD) {
                member.valueType.withJavaCallableSurface(resolveImportTarget = workspaceContext.resolveImportTarget)
            } else {
                member.valueType.hydrateJavaProviderType(workspaceContext.resolveImportTarget)
            }
            val workspaceMember = workspaceModuleMember(null, classType, name)
            put(
                name,
                MemberSurface(
                    name = name,
                    type = surfaceType,
                    accessKind = accessKind,
                    declaredType = surfaceType,
                    syntheticRange = workspaceMember?.member?.range,
                    syntheticHandle = workspaceMember?.handle ?: "java:${member.owner.binaryName}:static:$name"
                )
            )
        }
        // Conservative readable JavaBean aliases (TASK-177). Never hide direct getter/setter methods.
        classType.allReadableStaticJavaBeanProperties().forEach { property ->
            if (property.name !in this) {
                val surfaceType = property.valueType.hydrateJavaProviderType(workspaceContext.resolveImportTarget)
                put(
                    property.name,
                    MemberSurface(
                        name = property.name,
                        type = surfaceType,
                        accessKind = MemberAccessKind.FIELD,
                        declaredType = surfaceType,
                        syntheticHandle = "java:${classType.javaName.binaryName}:static-bean:${property.name}"
                    )
                )
            }
        }
    }

    private fun collectJavaInstanceMemberSurface(instanceType: JavaInstanceType): Map<String, MemberSurface> = buildMap {
        instanceType.allInstanceMembers().forEach { (name, member) ->
            val accessKind = javaAccessKind(member.memberKind, member.valueType)
            val surfaceType = if (accessKind == MemberAccessKind.METHOD) {
                member.valueType.withJavaCallableSurface(
                    receiverType = instanceType,
                    includeReceiver = false,
                    resolveImportTarget = workspaceContext.resolveImportTarget
                )
            } else {
                member.valueType.hydrateJavaProviderType(workspaceContext.resolveImportTarget)
            }
            val workspaceMember = workspaceModuleMember(null, instanceType, name)
            put(
                name,
                MemberSurface(
                    name = name,
                    type = surfaceType,
                    accessKind = accessKind,
                    declaredType = surfaceType,
                    syntheticRange = workspaceMember?.member?.range,
                    syntheticHandle = workspaceMember?.handle ?: "java:${member.owner.binaryName}:instance:$name"
                )
            )
        }
        // Conservative readable JavaBean aliases (TASK-177). Never hide direct getter/setter methods.
        instanceType.allReadableInstanceJavaBeanProperties().forEach { property ->
            if (property.name !in this) {
                val surfaceType = property.valueType.hydrateJavaProviderType(workspaceContext.resolveImportTarget)
                put(
                    property.name,
                    MemberSurface(
                        name = property.name,
                        type = surfaceType,
                        accessKind = MemberAccessKind.FIELD,
                        declaredType = surfaceType,
                        syntheticHandle = "java:${instanceType.classType.javaName.binaryName}:instance-bean:${property.name}"
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
            ?: if (expectedKind == DeclarationKind.FIELD) {
                classType.superClass?.let { superClass ->
                    findBackingMemberDeclaration(superClass, memberName, accessKind)
                }
            } else {
                classType.superClass?.let { superClass ->
                    findBackingMemberDeclaration(superClass, memberName, accessKind)
                }
            }
    }

    private fun categoryRank(kind: MemberAccessKind): Int {
        return when (kind) {
            MemberAccessKind.FIELD, MemberAccessKind.INDEX -> 0
            MemberAccessKind.METHOD -> 1
        }
    }

    private fun javaAccessKind(kind: JavaMemberKind, valueType: Type): MemberAccessKind {
        return when (kind) {
            JavaMemberKind.METHOD -> MemberAccessKind.METHOD
            JavaMemberKind.FIELD -> if (valueType is CallableType) MemberAccessKind.METHOD else MemberAccessKind.FIELD
        }
    }

    private fun resolveExactNodeDeclaration(node: BaseASTNode?): BinderDeclaration? {
        node ?: return null
        val exactMatches = when (node) {
            is Identifier -> exactAstIdentifierDeclarations(node)
            else -> binder.declarationIndex.getDeclarations(node)
        }
        if (exactMatches.isEmpty()) {
            return null
        }

        return exactMatches.sortedWith(Comparator { a, b ->
            val rangeComparison = compareSpecificity(declarationSiteRange(a), declarationSiteRange(b))
            if (rangeComparison != 0) {
                rangeComparison
            } else {
                a.id.value.compareTo(b.id.value)
            }
        }).firstOrNull()
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

    private fun resolveExactDocDeclaration(position: Position): BinderDeclaration? {
        return binder.declarationIndex.declarations
            .asSequence()
            .filter { it.origin == io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin.DOC_COMMENT }
            .mapNotNull { declaration ->
                val range = declarationSpecificRange(declaration) ?: return@mapNotNull null
                if (isPositionWithin(range.start, range.end, position)) {
                    declaration to range
                } else {
                    null
                }
            }
            .sortedWith(compareBy<Pair<BinderDeclaration, io.github.dingyi222666.luaparser.parser.ast.node.Range>>(
                { rangeSpan(it.second) },
                { it.first.id.value }
            ))
            .map { it.first }
            .firstOrNull()
    }

    private fun resolveDeclarationAtPosition(position: Position): BinderDeclaration? {
        return binder.declarationIndex.declarations
            .asSequence()
            .mapNotNull { declaration ->
                val range = declarationSiteRange(declaration) ?: return@mapNotNull null
                if (isPositionWithin(range.start, range.end, position)) {
                    declaration to range
                } else {
                    null
                }
            }
            .sortedWith(compareBy<Pair<BinderDeclaration, io.github.dingyi222666.luaparser.parser.ast.node.Range>>(
                { declarationRank(it.first) },
                { rangeSpan(it.second) },
                { it.first.id.value }
            ))
            .map { it.first }
            .firstOrNull()
    }

    private fun declarationSiteRange(declaration: BinderDeclaration): io.github.dingyi222666.luaparser.parser.ast.node.Range? {
        return when (declaration.origin) {
            io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin.AST -> declaration.anchorNode?.range ?: declaration.range
            io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin.DOC_COMMENT -> declarationSpecificRange(declaration) ?: declaration.range
            else -> declaration.range
        }
    }

    private fun exactDeclarationTokenRange(declaration: BinderDeclaration): io.github.dingyi222666.luaparser.parser.ast.node.Range? {
        return when (declaration.origin) {
            io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin.AST -> {
                (declaration.anchorNode as? Identifier)?.range ?: declaration.anchorNode?.range
            }

            io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin.DOC_COMMENT -> declarationSpecificRange(declaration)
            else -> null
        }
    }

    private fun declarationSpecificRange(declaration: BinderDeclaration): io.github.dingyi222666.luaparser.parser.ast.node.Range? {
        val tags = declaration.documentation?.docComment?.tags.orEmpty()
        return when (declaration.kind) {
            DeclarationKind.TYPE_ALIAS -> tags.filterIsInstance<AliasTagSyntax>().firstOrNull { it.name == declaration.name }?.range
            DeclarationKind.CLASS -> tags.filterIsInstance<ClassTagSyntax>().firstOrNull { it.name == declaration.name }?.range
            DeclarationKind.FIELD -> tags.filterIsInstance<FieldTagSyntax>().firstOrNull { it.name == declaration.name }?.range
            DeclarationKind.METHOD -> tags.filterIsInstance<MethodTagSyntax>().firstOrNull { it.name == declaration.name }?.range
            DeclarationKind.TYPE_PARAMETER -> {
                tags.filterIsInstance<GenericTagSyntax>()
                    .flatMap { it.parameters }
                    .firstOrNull { it.name == declaration.name }
                    ?.range
            }

            else -> declaration.range
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

    private fun importedVisibleDeclarations(position: Position): List<VisibleDeclaration> {
        return importedSymbolsAt(position)
            .values
            .sortedBy { it.alias }
            .map { imported ->
                VisibleDeclaration(
                    declaration = io.github.dingyi222666.luaparser.semantic.binder.moduleDeclaration(
                        id = io.github.dingyi222666.luaparser.semantic.binder.DeclarationId(-1000000 - imported.alias.hashCode()),
                        name = imported.alias,
                        origin = io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin.BUILTIN,
                        declaredType = imported.moduleType
                    ),
                    lexicalDepth = 0
                )
            }
    }

    private fun importedSymbolsAt(position: Position): Map<String, WorkspaceImportedSymbol> {
        val imported = linkedMapOf<String, WorkspaceImportedSymbol>()
        val visibleDeclarations = visibleValueDeclarationsWithoutImports(position)
        workspaceContext.importedSymbols.forEach { (alias, symbol) ->
            val localDeclaration = visibleDeclarations
                .firstOrNull { declaration -> declaration.name == alias }
            if (localDeclaration == null) {
                imported[alias] = symbol
            }
        }
        return imported
    }

    private fun visibleValueDeclarationsWithoutImports(position: Position): List<BinderDeclaration> {
        val scope = binder.positionQueries.getScopeAt(position) ?: return emptyList()
        val results = mutableListOf<BinderDeclaration>()
        val seenNames = linkedSetOf<String>()

        var current: Scope? = scope
        while (current != null) {
            current.declarationIds
                .asReversed()
                .mapNotNull(binder.declarationIndex::getDeclaration)
                .forEach { declaration ->
                    if (
                        declaration.kind.namespace == DeclarationNamespace.VALUE &&
                        declaration.name !in seenNames &&
                        isVisibleAt(declaration, position)
                    ) {
                        seenNames += declaration.name
                        results += declaration
                    }
                }
            current = current.parentId?.let(binder.scopeGraph::getScope)
        }

        return results
    }

    private fun importedSymbolAt(position: Position, node: BaseASTNode?): WorkspaceImportedSymbol? {
        val identifier = when (node) {
            is Identifier -> node.name
            else -> null
        } ?: return null
        return importedSymbolNamed(identifier, position)
    }

    private fun importedSymbolNamed(name: String, position: Position): WorkspaceImportedSymbol? {
        if (name.isBlank()) {
            return null
        }
        val visibleImported = importedSymbolsAt(position)[name]
        if (visibleImported != null) {
            return visibleImported
        }
        if (visibleValueDeclarationsWithoutImports(position).any { it.name == name }) {
            return null
        }
        return workspaceContext.resolveImportedSymbol?.invoke(name)
    }

    private fun toImportedSymbol(imported: WorkspaceImportedSymbol): Symbol {
        return Symbol(
            name = imported.alias,
            kind = io.github.dingyi222666.luaparser.semantic.api.SymbolKind.MODULE,
            range = null,
            type = adapters.toTypeInfo(imported.moduleType),
            declaredType = adapters.toTypeInfo(imported.moduleType),
            detail = imported.moduleType.displayName,
            symbolId = importedSymbolHandle(imported)
        )
    }

    private fun importedSymbolHandle(imported: WorkspaceImportedSymbol): String {
        return "imported:${imported.providerPath.value}:${imported.alias}"
    }

    private fun rangeSpan(range: io.github.dingyi222666.luaparser.parser.ast.node.Range): Int {
        val lineSpan = (range.end.line - range.start.line) * 10_000
        return lineSpan + (range.end.column - range.start.column)
    }

    private fun isVisibleAt(declaration: BinderDeclaration, position: Position): Boolean {
        val range = declaration.range ?: return true
        return compare(range.start, position) <= 0
    }

    private fun compareSpecificity(a: io.github.dingyi222666.luaparser.parser.ast.node.Range?, b: io.github.dingyi222666.luaparser.parser.ast.node.Range?): Int {
        a ?: return if (b == null) 0 else 1
        b ?: return -1

        val startComparison = compare(b.start, a.start)
        if (startComparison != 0) {
            return startComparison
        }
        return compare(a.end, b.end)
    }

    private fun isPositionWithin(start: Position, end: Position, position: Position): Boolean {
        return compare(start, position) <= 0 && compare(position, end) < 0
    }

    private fun compare(a: Position, b: Position): Int {
        val lineComparison = a.line.compareTo(b.line)
        if (lineComparison != 0) {
            return lineComparison
        }
        return a.column.compareTo(b.column)
    }

    private fun workspaceModuleMember(
        baseExpression: ExpressionNode?,
        baseType: Type,
        memberName: String
    ): io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceModuleResolver.ResolvedExportMember? {
        val resolver = workspaceContext.workspaceResolver ?: return null
        return when (baseType) {
            is ModuleType -> {
                val provider = resolver.activeProvider(baseType.moduleName) ?: return null
                val exportPath = moduleExportPath(baseExpression, provider.path)?.plus(memberName) ?: listOf(memberName)
                resolver.exportedMember(provider.path, exportPath)
                    ?: resolver.exportedMember(provider.path, memberName)
            }
            is ClassType -> {
                val provider = resolver.activeProvider(baseType.name.substringAfterLast('.')) ?: return null
                resolver.exportedMember(provider.path, listOf("__class", memberName))
            }
            is JavaClassType -> {
                val provider = javaProviderFor(baseType) ?: return null
                resolver.exportedMember(provider.path, listOf(memberName))
                    ?: resolver.exportedMember(provider.path, listOf("__class", memberName))
            }
            is JavaInstanceType -> {
                val provider = javaProviderFor(baseType.classType) ?: return null
                resolver.exportedMember(provider.path, listOf("__class", memberName))
            }
            is IntersectionType -> baseType.types.firstNotNullOfOrNull { branch ->
                workspaceModuleMember(baseExpression, branch, memberName)
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

    private fun javaProviderFor(classType: JavaClassType): io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceModuleGraph.ModuleProvider? {
        val resolver = workspaceContext.workspaceResolver ?: return null
        return resolver.activeProvider(classType.javaName.simpleName)
            ?: resolver.activeProvider(classType.javaName.canonicalName)
            ?: resolver.activeProvider(classType.javaName.binaryName)
            ?: resolveLuaJavaImportTarget(classType.javaName.canonicalName)?.let { imported ->
                io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceModuleGraph.ModuleProvider(
                    moduleName = imported.moduleName,
                    path = imported.providerPath,
                    source = io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceModuleGraph.ProviderSource.EXTRA_WORKSPACE_PROVIDER
                )
            }
            ?: resolveLuaJavaImportTarget(classType.javaName.binaryName)?.let { imported ->
                io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceModuleGraph.ModuleProvider(
                    moduleName = imported.moduleName,
                    path = imported.providerPath,
                    source = io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceModuleGraph.ProviderSource.EXTRA_WORKSPACE_PROVIDER
                )
            }
    }

    private fun javaMemberFallbackSymbolId(baseType: Type, memberName: String): String? {
        val classType = javaFallbackClassType(baseType) ?: return null
        val imported = resolveLuaJavaImportTarget(classType.javaName.canonicalName)
            ?: resolveLuaJavaImportTarget(classType.javaName.binaryName)
            ?: return null
        return "imported:${imported.providerPath.value}:$memberName"
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
