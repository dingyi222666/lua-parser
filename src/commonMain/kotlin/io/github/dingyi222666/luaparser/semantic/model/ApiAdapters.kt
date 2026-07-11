package io.github.dingyi222666.luaparser.semantic.model

import io.github.dingyi222666.luaparser.parser.ast.node.Range
import io.github.dingyi222666.luaparser.semantic.api.CompletionItemKind
import io.github.dingyi222666.luaparser.semantic.api.Scope
import io.github.dingyi222666.luaparser.semantic.api.ScopeKind
import io.github.dingyi222666.luaparser.semantic.api.Symbol
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import io.github.dingyi222666.luaparser.semantic.api.TypeInfo
import io.github.dingyi222666.luaparser.semantic.api.TypeInfoKind
import io.github.dingyi222666.luaparser.semantic.binder.BinderDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
import io.github.dingyi222666.luaparser.semantic.binder.BinderSymbol
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationId
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOwner
import io.github.dingyi222666.luaparser.semantic.binder.ScopeId
import io.github.dingyi222666.luaparser.semantic.comments.AliasTagSyntax
import io.github.dingyi222666.luaparser.semantic.comments.ClassTagSyntax
import io.github.dingyi222666.luaparser.semantic.comments.FieldTagSyntax
import io.github.dingyi222666.luaparser.semantic.comments.GenericTagSyntax
import io.github.dingyi222666.luaparser.semantic.comments.MethodTagSyntax
import io.github.dingyi222666.luaparser.semantic.checker.MemberAccessKind
import io.github.dingyi222666.luaparser.semantic.types.model.ClassType
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaClassType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaInstanceType
import io.github.dingyi222666.luaparser.semantic.types.model.ModuleType
import io.github.dingyi222666.luaparser.semantic.types.model.OverloadedFunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.TableType
import io.github.dingyi222666.luaparser.semantic.types.model.Type

internal class ApiAdapters(
    private val binder: BinderPassResult
) {
    private val typeKeysByType = linkedMapOf<Type, String>()
    private val typesByKey = linkedMapOf<String, Type>()
    private var nextTypeKey = 1

    fun toTypeInfo(type: Type?, declaration: BinderDeclaration? = null): TypeInfo? {
        if (type == null) {
            return null
        }

        val key = typeKeysByType.getOrPut(type) {
            "type:${nextTypeKey++}".also { createdKey -> typesByKey[createdKey] = type }
        }
        val moduleType = type as? ModuleType
        val typeKind = when {
            declaration?.kind == DeclarationKind.MODULE || moduleType != null -> TypeInfoKind.MODULE
            type is ClassType -> TypeInfoKind.CLASS
            type is JavaClassType || type is JavaInstanceType -> TypeInfoKind.CLASS
            type is FunctionType || type is OverloadedFunctionType -> TypeInfoKind.FUNCTION
            type is TableType -> TypeInfoKind.TABLE
            else -> TypeInfoKind.UNKNOWN
        }
        return TypeInfo(
            displayName = type.displayName,
            detail = type.displayName,
            typeKey = key,
            kind = typeKind,
            moduleName = moduleType?.moduleName ?: declaration?.takeIf { it.kind == DeclarationKind.MODULE }?.name
        )
    }

    fun typeByKey(typeKey: String?): Type? = typeKey?.let(typesByKey::get)

    fun toSymbol(symbol: BinderSymbol): Symbol? {
        val declaration = binder.declarationIndex.getPrimaryDeclaration(symbol.id) ?: return null
        return toSymbol(symbol, declaration)
    }

    fun toSymbol(symbol: BinderSymbol, declaration: BinderDeclaration): Symbol {
        return Symbol(
            name = symbol.name,
            kind = declaration.kind.toSymbolKind(),
            range = declarationDisplayRange(declaration),
            type = toTypeInfo(declaration.declaredType, declaration),
            declaredType = toTypeInfo(declaration.declaredType, declaration),
            detail = declaration.declaredType?.displayName,
            symbolId = binderHandle(symbol.id.value)
        )
    }

    fun toDeclarationSymbol(declaration: BinderDeclaration): Symbol? {
        val symbolId = declaration.symbolId
        val symbol = symbolId?.let(binder.declarationIndex::getSymbol)
        return if (symbol != null) {
            toSymbol(symbol, declaration)
        } else {
            Symbol(
                name = declaration.name,
                kind = declaration.kind.toSymbolKind(),
                range = declarationDisplayRange(declaration),
                type = toTypeInfo(declaration.declaredType, declaration),
                declaredType = toTypeInfo(declaration.declaredType, declaration),
                detail = declaration.declaredType?.displayName,
                symbolId = declarationHandle(declaration.id)
            )
        }
    }

    fun toDeclarationSymbol(
        declaration: BinderDeclaration,
        typeOverride: Type? = declaration.declaredType,
        declaredTypeOverride: Type? = declaration.declaredType
    ): Symbol {
        val base = toDeclarationSymbol(declaration)!!
        return base.copy(
            type = toTypeInfo(typeOverride, declaration),
            declaredType = toTypeInfo(declaredTypeOverride, declaration),
            detail = declaredTypeOverride?.displayName ?: typeOverride?.displayName
        )
    }

    fun syntheticMemberSymbol(
        name: String,
        kind: MemberAccessKind,
        type: Type?,
        declaredType: Type? = null,
        handleSeed: String,
        symbolId: String? = null,
        range: Range? = null
    ): Symbol {
        return Symbol(
            name = name,
            kind = when (kind) {
                MemberAccessKind.METHOD -> SymbolKind.METHOD
                MemberAccessKind.FIELD, MemberAccessKind.INDEX -> SymbolKind.FIELD
            },
            range = range,
            type = toTypeInfo(type),
            declaredType = toTypeInfo(declaredType),
            detail = declaredType?.displayName ?: type?.displayName,
            symbolId = symbolId ?: "synthetic:$handleSeed"
        )
    }

    fun toScope(
        scope: io.github.dingyi222666.luaparser.semantic.binder.Scope,
        symbols: List<Symbol>
    ): Scope {
        val ownerName = scope.ownerDeclarationId
            ?.let(binder.declarationIndex::getDeclaration)
            ?.name

        return Scope(
            kind = scope.kind.toApiScopeKind(),
            range = scope.range,
            symbols = symbols,
            name = ownerName,
            scopeId = scopeHandle(scope.id)
        )
    }

    fun completionKind(symbol: Symbol): CompletionItemKind {
        return when (symbol.kind) {
            SymbolKind.PARAMETER -> CompletionItemKind.PARAMETER
            SymbolKind.LOCAL, SymbolKind.VARIABLE -> CompletionItemKind.VARIABLE
            SymbolKind.FUNCTION -> CompletionItemKind.FUNCTION
            SymbolKind.METHOD -> CompletionItemKind.METHOD
            SymbolKind.FIELD -> CompletionItemKind.FIELD
            SymbolKind.CLASS -> CompletionItemKind.CLASS
            SymbolKind.TYPE_ALIAS -> CompletionItemKind.TYPE_ALIAS
            SymbolKind.MODULE -> CompletionItemKind.MODULE
            else -> CompletionItemKind.TEXT
        }
    }

    fun binderHandle(value: Int): String = "binder:$value"

    fun declarationHandle(value: DeclarationId): String = "declaration:${value.value}"

    fun scopeHandle(scopeId: ScopeId): String = "scope:${scopeId.value}"

    private fun declarationDisplayRange(declaration: BinderDeclaration): Range? {
        if (declaration.origin != io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin.DOC_COMMENT) {
            return declaration.range
        }

        val tags = declaration.documentation?.docComment?.tags.orEmpty()
        return when (declaration.kind) {
            DeclarationKind.TYPE_ALIAS -> tags.filterIsInstance<AliasTagSyntax>().firstOrNull { it.name == declaration.name }?.range
            DeclarationKind.CLASS -> tags.filterIsInstance<ClassTagSyntax>().firstOrNull { it.name == declaration.name }?.range
            DeclarationKind.FIELD -> tags.filterIsInstance<FieldTagSyntax>().firstOrNull { it.name == declaration.name }?.range
            DeclarationKind.METHOD -> tags.filterIsInstance<MethodTagSyntax>().firstOrNull { it.name == declaration.name }?.range
            DeclarationKind.TYPE_PARAMETER -> tags.filterIsInstance<GenericTagSyntax>()
                .flatMap { it.parameters }
                .firstOrNull { it.name == declaration.name }
                ?.range

            else -> null
        } ?: declaration.range
    }
}

internal data class SymbolHandle(
    val binderSymbolId: Int? = null,
    val declarationId: Int? = null
)

internal fun String.toSymbolHandle(): SymbolHandle? {
    return when {
        startsWith("binder:") -> SymbolHandle(binderSymbolId = substringAfter(':').toIntOrNull())
        startsWith("declaration:") -> SymbolHandle(declarationId = substringAfter(':').toIntOrNull())
        else -> null
    }
}

private fun DeclarationKind.toSymbolKind(): SymbolKind {
        return when (this) {
            DeclarationKind.LOCAL -> SymbolKind.LOCAL
            DeclarationKind.GLOBAL -> SymbolKind.VARIABLE
            DeclarationKind.FUNCTION -> SymbolKind.FUNCTION
            DeclarationKind.MODULE -> SymbolKind.MODULE
            DeclarationKind.PARAMETER -> SymbolKind.PARAMETER
            DeclarationKind.CLASS -> SymbolKind.CLASS
            DeclarationKind.TYPE_ALIAS -> SymbolKind.TYPE_ALIAS
        DeclarationKind.TYPE_PARAMETER -> SymbolKind.TYPE_ALIAS
        DeclarationKind.FIELD -> SymbolKind.FIELD
        DeclarationKind.METHOD -> SymbolKind.METHOD
    }
}

private fun io.github.dingyi222666.luaparser.semantic.binder.ScopeKind.toApiScopeKind(): ScopeKind {
    return when (this) {
        io.github.dingyi222666.luaparser.semantic.binder.ScopeKind.CHUNK -> ScopeKind.CHUNK
        io.github.dingyi222666.luaparser.semantic.binder.ScopeKind.BLOCK -> ScopeKind.BLOCK
        io.github.dingyi222666.luaparser.semantic.binder.ScopeKind.FUNCTION -> ScopeKind.FUNCTION
        io.github.dingyi222666.luaparser.semantic.binder.ScopeKind.MODULE -> ScopeKind.MODULE
        io.github.dingyi222666.luaparser.semantic.binder.ScopeKind.LOOP -> ScopeKind.LOOP
        io.github.dingyi222666.luaparser.semantic.binder.ScopeKind.CONDITIONAL -> ScopeKind.CONDITIONAL
    }
}

internal fun BinderDeclaration.isOwnedBy(owner: BinderDeclaration): Boolean {
    return this.owner == DeclarationOwner.Declaration(owner.id)
}
