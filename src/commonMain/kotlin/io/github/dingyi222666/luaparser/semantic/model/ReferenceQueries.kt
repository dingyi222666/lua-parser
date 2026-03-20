package io.github.dingyi222666.luaparser.semantic.model

import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.MemberExpression
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.semantic.SemanticWorkspaceContext
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
import io.github.dingyi222666.luaparser.semantic.types.model.ClassType
import io.github.dingyi222666.luaparser.semantic.types.model.IntersectionType
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
            return declarationSymbol
        }

        return when (node) {
            is Identifier -> {
                val parent = runCatching { node.parent }.getOrNull()
                if (parent is MemberExpression && parent.identifier === node) {
                    resolveMemberUsage(parent)
                } else {
                    findNearestVisibleValueDeclaration(node.name, position)?.let { adapters.toDeclarationSymbol(it) }
                }
            }

            is MemberExpression -> resolveMemberUsage(node)
            else -> null
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
        val scope = binder.positionQueries.getScopeAt(position) ?: return emptyList()
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

        return results
    }

    fun findNearestVisibleValueDeclaration(name: String, position: Position): BinderDeclaration? {
        return visibleValueDeclarations(position)
            .firstOrNull { it.declaration.name == name }
            ?.declaration
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
        val resolution = memberResolver.resolveMember(
            baseType = baseType,
            memberName = expression.identifier.name,
            preferMethod = expression.indexer == ":",
            lexicalScopeId = lexicalScopeId
        )
        if (!resolution.isSuccess) {
            return null
        }

        val normalizedBase = TypeExpansion.expandForMemberSurface(baseType, lexicalScopeId, binder)
        val workspaceMember = workspaceModuleMember(normalizedBase, expression.identifier.name)
        val declaration = findBackingMemberDeclaration(normalizedBase, expression.identifier.name, resolution.accessKind)
        return declaration?.let { adapters.toDeclarationSymbol(it, resolution.type, resolution.type) }
            ?: workspaceMember?.let {
                adapters.syntheticMemberSymbol(
                    name = expression.identifier.name,
                    kind = resolution.accessKind ?: MemberAccessKind.FIELD,
                    type = resolution.type,
                    declaredType = resolution.type,
                    handleSeed = it.handle,
                    symbolId = it.handle,
                    range = it.member.range
                )
            }
            ?: adapters.syntheticMemberSymbol(
                name = expression.identifier.name,
                kind = resolution.accessKind ?: MemberAccessKind.FIELD,
                type = resolution.type,
                handleSeed = "${baseType.displayName}:${expression.indexer}:${expression.identifier.name}"
            )
    }

    fun resolveMemberCompletionSurface(expression: MemberExpression): List<Symbol> {
        val lexicalScopeId = binder.positionQueries.getScopeAt(expression.range.start)?.id ?: binder.scopeGraph.rootScope.id
        val baseType = evaluator.evaluate(expression.base)
        val members = getMembers(baseType, lexicalScopeId)
        return if (expression.indexer == ":") {
            members.sortedWith(compareBy<Symbol>({ if (it.kind == io.github.dingyi222666.luaparser.semantic.api.SymbolKind.METHOD) 0 else 1 }, { it.name }))
        } else {
            members.sortedWith(compareBy<Symbol>({ if (it.kind == io.github.dingyi222666.luaparser.semantic.api.SymbolKind.FIELD) 0 else 1 }, { it.name }))
        }
    }

    private fun collectMemberSurface(type: Type, lexicalScopeId: ScopeId): Map<String, MemberSurface> {
        val normalized = TypeExpansion.expandForMemberSurface(type, lexicalScopeId, binder)
        return when (normalized) {
            is TableType -> buildMap {
                normalized.fields.forEach { (name, memberType) ->
                    put(name, MemberSurface(name, memberType, MemberAccessKind.FIELD))
                }
                normalized.methods.forEach { (name, memberType) ->
                    put(name, MemberSurface(name, memberType, MemberAccessKind.METHOD))
                }
            }

            is ModuleType -> buildMap {
                normalized.fields.forEach { (name, memberType) ->
                    val workspaceMember = workspaceModuleMember(normalized, name)
                    put(name, MemberSurface(name, memberType, MemberAccessKind.FIELD, syntheticRange = workspaceMember?.member?.range, syntheticHandle = workspaceMember?.handle))
                }
                normalized.methods.forEach { (name, memberType) ->
                    val workspaceMember = workspaceModuleMember(normalized, name)
                    put(name, MemberSurface(name, memberType, MemberAccessKind.METHOD, syntheticRange = workspaceMember?.member?.range, syntheticHandle = workspaceMember?.handle))
                }
            }

            is ClassType -> buildMap {
                normalized.getAllFields().forEach { (name, memberType) ->
                    val declaration = findBackingMemberDeclaration(normalized, name, MemberAccessKind.FIELD)
                    put(name, MemberSurface(name, memberType, MemberAccessKind.FIELD, declaration))
                }
                normalized.getAllMethods().forEach { (name, memberType) ->
                    val declaration = findBackingMemberDeclaration(normalized, name, MemberAccessKind.METHOD)
                    put(name, MemberSurface(name, memberType, MemberAccessKind.METHOD, declaration))
                }
            }

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
        baseType: Type,
        memberName: String
    ): io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceModuleResolver.ResolvedExportMember? {
        val moduleType = baseType as? ModuleType ?: return null
        val resolver = workspaceContext.workspaceResolver ?: return null
        val provider = resolver.activeProvider(moduleType.moduleName) ?: return null
        return resolver.exportedMember(provider.path, memberName)
    }
}
