package io.github.dingyi222666.luaparser.semantic.binder

import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode
import io.github.dingyi222666.luaparser.parser.ast.node.Range
import io.github.dingyi222666.luaparser.semantic.types.model.Type
import io.github.dingyi222666.luaparser.semantic.types.syntax.TypeSyntax

/**
 * Kind-specialized factories over the single [BinderDeclaration] constructor. They exist so
 * binding sites read as `localDeclaration(...)` / `functionDeclaration(...)` instead of
 * `BinderDeclaration(kind = DeclarationKind.LOCAL, ...)`; all defaults mirror the
 * constructor's own defaults, so the two layers cannot drift.
 */
fun localDeclaration(
    id: DeclarationId,
    name: String,
    origin: DeclarationOrigin = DeclarationOrigin.AST,
    owner: DeclarationOwner = DeclarationOwner.Root,
    anchorNode: BaseASTNode? = null,
    range: Range? = anchorNode?.range,
    documentation: DeclarationDocumentation? = null,
    declaredTypeSyntax: TypeSyntax? = null,
    declaredType: Type? = null,
    symbolId: SymbolId? = null
): BinderDeclaration = BinderDeclaration(
    id = id, name = name, kind = DeclarationKind.LOCAL, origin = origin, owner = owner,
    anchorNode = anchorNode, range = range, documentation = documentation,
    declaredTypeSyntax = declaredTypeSyntax, declaredType = declaredType, symbolId = symbolId
)

fun functionDeclaration(
    id: DeclarationId,
    name: String,
    origin: DeclarationOrigin = DeclarationOrigin.AST,
    owner: DeclarationOwner = DeclarationOwner.Root,
    anchorNode: BaseASTNode? = null,
    range: Range? = anchorNode?.range,
    documentation: DeclarationDocumentation? = null,
    declaredTypeSyntax: TypeSyntax? = null,
    declaredType: Type? = null,
    symbolId: SymbolId? = null
): BinderDeclaration = BinderDeclaration(
    id = id, name = name, kind = DeclarationKind.FUNCTION, origin = origin, owner = owner,
    anchorNode = anchorNode, range = range, documentation = documentation,
    declaredTypeSyntax = declaredTypeSyntax, declaredType = declaredType, symbolId = symbolId
)

fun moduleDeclaration(
    id: DeclarationId,
    name: String,
    origin: DeclarationOrigin = DeclarationOrigin.AST,
    owner: DeclarationOwner = DeclarationOwner.Root,
    anchorNode: BaseASTNode? = null,
    range: Range? = anchorNode?.range,
    documentation: DeclarationDocumentation? = null,
    declaredTypeSyntax: TypeSyntax? = null,
    declaredType: Type? = null,
    symbolId: SymbolId? = null
): BinderDeclaration = BinderDeclaration(
    id = id, name = name, kind = DeclarationKind.MODULE, origin = origin, owner = owner,
    anchorNode = anchorNode, range = range, documentation = documentation,
    declaredTypeSyntax = declaredTypeSyntax, declaredType = declaredType, symbolId = symbolId
)

fun globalDeclaration(
    id: DeclarationId,
    name: String,
    origin: DeclarationOrigin = DeclarationOrigin.AST,
    owner: DeclarationOwner = DeclarationOwner.Root,
    anchorNode: BaseASTNode? = null,
    range: Range? = anchorNode?.range,
    documentation: DeclarationDocumentation? = null,
    declaredTypeSyntax: TypeSyntax? = null,
    declaredType: Type? = null,
    symbolId: SymbolId? = null
): BinderDeclaration = BinderDeclaration(
    id = id, name = name, kind = DeclarationKind.GLOBAL, origin = origin, owner = owner,
    anchorNode = anchorNode, range = range, documentation = documentation,
    declaredTypeSyntax = declaredTypeSyntax, declaredType = declaredType, symbolId = symbolId
)

fun parameterDeclaration(
    id: DeclarationId,
    name: String,
    origin: DeclarationOrigin = DeclarationOrigin.AST,
    owner: DeclarationOwner,
    anchorNode: BaseASTNode? = null,
    range: Range? = anchorNode?.range,
    documentation: DeclarationDocumentation? = null,
    declaredTypeSyntax: TypeSyntax? = null,
    declaredType: Type? = null,
    symbolId: SymbolId? = null
): BinderDeclaration = BinderDeclaration(
    id = id, name = name, kind = DeclarationKind.PARAMETER, origin = origin, owner = owner,
    anchorNode = anchorNode, range = range, documentation = documentation,
    declaredTypeSyntax = declaredTypeSyntax, declaredType = declaredType, symbolId = symbolId
)

fun classDeclaration(
    id: DeclarationId,
    name: String,
    origin: DeclarationOrigin = DeclarationOrigin.DOC_COMMENT,
    owner: DeclarationOwner = DeclarationOwner.Root,
    anchorNode: BaseASTNode? = null,
    range: Range? = anchorNode?.range,
    documentation: DeclarationDocumentation? = null,
    declaredTypeSyntax: TypeSyntax? = null,
    declaredType: Type? = null,
    symbolId: SymbolId? = null
): BinderDeclaration = BinderDeclaration(
    id = id, name = name, kind = DeclarationKind.CLASS, origin = origin, owner = owner,
    anchorNode = anchorNode, range = range, documentation = documentation,
    declaredTypeSyntax = declaredTypeSyntax, declaredType = declaredType, symbolId = symbolId
)

fun aliasDeclaration(
    id: DeclarationId,
    name: String,
    origin: DeclarationOrigin = DeclarationOrigin.DOC_COMMENT,
    owner: DeclarationOwner = DeclarationOwner.Root,
    anchorNode: BaseASTNode? = null,
    range: Range? = anchorNode?.range,
    documentation: DeclarationDocumentation? = null,
    declaredTypeSyntax: TypeSyntax? = null,
    declaredType: Type? = null,
    symbolId: SymbolId? = null
): BinderDeclaration = BinderDeclaration(
    id = id, name = name, kind = DeclarationKind.TYPE_ALIAS, origin = origin, owner = owner,
    anchorNode = anchorNode, range = range, documentation = documentation,
    declaredTypeSyntax = declaredTypeSyntax, declaredType = declaredType, symbolId = symbolId
)

fun fieldDeclaration(
    id: DeclarationId,
    name: String,
    origin: DeclarationOrigin = DeclarationOrigin.DOC_COMMENT,
    owner: DeclarationOwner,
    anchorNode: BaseASTNode? = null,
    range: Range? = anchorNode?.range,
    documentation: DeclarationDocumentation? = null,
    declaredTypeSyntax: TypeSyntax? = null,
    declaredType: Type? = null,
    symbolId: SymbolId? = null
): BinderDeclaration = BinderDeclaration(
    id = id, name = name, kind = DeclarationKind.FIELD, origin = origin, owner = owner,
    anchorNode = anchorNode, range = range, documentation = documentation,
    declaredTypeSyntax = declaredTypeSyntax, declaredType = declaredType, symbolId = symbolId
)

fun methodDeclaration(
    id: DeclarationId,
    name: String,
    origin: DeclarationOrigin = DeclarationOrigin.DOC_COMMENT,
    owner: DeclarationOwner,
    anchorNode: BaseASTNode? = null,
    range: Range? = anchorNode?.range,
    documentation: DeclarationDocumentation? = null,
    declaredTypeSyntax: TypeSyntax? = null,
    declaredType: Type? = null,
    symbolId: SymbolId? = null
): BinderDeclaration = BinderDeclaration(
    id = id, name = name, kind = DeclarationKind.METHOD, origin = origin, owner = owner,
    anchorNode = anchorNode, range = range, documentation = documentation,
    declaredTypeSyntax = declaredTypeSyntax, declaredType = declaredType, symbolId = symbolId
)

fun typeParameterDeclaration(
    id: DeclarationId,
    name: String,
    origin: DeclarationOrigin = DeclarationOrigin.DOC_COMMENT,
    owner: DeclarationOwner,
    anchorNode: BaseASTNode? = null,
    range: Range? = anchorNode?.range,
    documentation: DeclarationDocumentation? = null,
    declaredTypeSyntax: TypeSyntax? = null,
    declaredType: Type? = null,
    symbolId: SymbolId? = null
): BinderDeclaration = BinderDeclaration(
    id = id, name = name, kind = DeclarationKind.TYPE_PARAMETER, origin = origin, owner = owner,
    anchorNode = anchorNode, range = range, documentation = documentation,
    declaredTypeSyntax = declaredTypeSyntax, declaredType = declaredType, symbolId = symbolId
)
