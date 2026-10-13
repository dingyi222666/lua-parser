package io.github.dingyi222666.luaparser.semantic.binder

import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode
import io.github.dingyi222666.luaparser.parser.ast.node.Range
import io.github.dingyi222666.luaparser.semantic.types.model.Type
import io.github.dingyi222666.luaparser.semantic.types.syntax.TypeSyntax

/**
 * Kind-specialized factories over the single [BinderDeclaration] constructor. They exist so
 * binding sites read as `localDeclaration(...)` / `functionDeclaration(...)` instead of
 * `BinderDeclaration(kind = DeclarationKind.LOCAL, ...)`; every parameter list mirrors the
 * constructor's parameter order and defaults positionally, so the two layers cannot drift.
 */
fun localDeclaration(
    id: DeclarationId, name: String, origin: DeclarationOrigin = DeclarationOrigin.AST, owner: DeclarationOwner = DeclarationOwner.Root,
    anchorNode: BaseASTNode? = null, range: Range? = anchorNode?.range, documentation: DeclarationDocumentation? = null,
    declaredTypeSyntax: TypeSyntax? = null, declaredType: Type? = null, symbolId: SymbolId? = null
): BinderDeclaration = BinderDeclaration(
    id, name, DeclarationKind.LOCAL, origin, owner, anchorNode, range,
    documentation, declaredTypeSyntax, declaredType, symbolId
)

fun functionDeclaration(
    id: DeclarationId, name: String, origin: DeclarationOrigin = DeclarationOrigin.AST, owner: DeclarationOwner = DeclarationOwner.Root,
    anchorNode: BaseASTNode? = null, range: Range? = anchorNode?.range, documentation: DeclarationDocumentation? = null,
    declaredTypeSyntax: TypeSyntax? = null, declaredType: Type? = null, symbolId: SymbolId? = null
): BinderDeclaration = BinderDeclaration(
    id, name, DeclarationKind.FUNCTION, origin, owner, anchorNode, range,
    documentation, declaredTypeSyntax, declaredType, symbolId
)

fun moduleDeclaration(
    id: DeclarationId, name: String, origin: DeclarationOrigin = DeclarationOrigin.AST, owner: DeclarationOwner = DeclarationOwner.Root,
    anchorNode: BaseASTNode? = null, range: Range? = anchorNode?.range, documentation: DeclarationDocumentation? = null,
    declaredTypeSyntax: TypeSyntax? = null, declaredType: Type? = null, symbolId: SymbolId? = null
): BinderDeclaration = BinderDeclaration(
    id, name, DeclarationKind.MODULE, origin, owner, anchorNode, range,
    documentation, declaredTypeSyntax, declaredType, symbolId
)

fun globalDeclaration(
    id: DeclarationId, name: String, origin: DeclarationOrigin = DeclarationOrigin.AST, owner: DeclarationOwner = DeclarationOwner.Root,
    anchorNode: BaseASTNode? = null, range: Range? = anchorNode?.range, documentation: DeclarationDocumentation? = null,
    declaredTypeSyntax: TypeSyntax? = null, declaredType: Type? = null, symbolId: SymbolId? = null
): BinderDeclaration = BinderDeclaration(
    id, name, DeclarationKind.GLOBAL, origin, owner, anchorNode, range,
    documentation, declaredTypeSyntax, declaredType, symbolId
)

fun parameterDeclaration(
    id: DeclarationId, name: String, origin: DeclarationOrigin = DeclarationOrigin.AST, owner: DeclarationOwner,
    anchorNode: BaseASTNode? = null, range: Range? = anchorNode?.range, documentation: DeclarationDocumentation? = null,
    declaredTypeSyntax: TypeSyntax? = null, declaredType: Type? = null, symbolId: SymbolId? = null
): BinderDeclaration = BinderDeclaration(
    id, name, DeclarationKind.PARAMETER, origin, owner, anchorNode, range,
    documentation, declaredTypeSyntax, declaredType, symbolId
)

fun classDeclaration(
    id: DeclarationId, name: String, origin: DeclarationOrigin = DeclarationOrigin.DOC_COMMENT, owner: DeclarationOwner = DeclarationOwner.Root,
    anchorNode: BaseASTNode? = null, range: Range? = anchorNode?.range, documentation: DeclarationDocumentation? = null,
    declaredTypeSyntax: TypeSyntax? = null, declaredType: Type? = null, symbolId: SymbolId? = null
): BinderDeclaration = BinderDeclaration(
    id, name, DeclarationKind.CLASS, origin, owner, anchorNode, range,
    documentation, declaredTypeSyntax, declaredType, symbolId
)

fun aliasDeclaration(
    id: DeclarationId, name: String, origin: DeclarationOrigin = DeclarationOrigin.DOC_COMMENT, owner: DeclarationOwner = DeclarationOwner.Root,
    anchorNode: BaseASTNode? = null, range: Range? = anchorNode?.range, documentation: DeclarationDocumentation? = null,
    declaredTypeSyntax: TypeSyntax? = null, declaredType: Type? = null, symbolId: SymbolId? = null
): BinderDeclaration = BinderDeclaration(
    id, name, DeclarationKind.TYPE_ALIAS, origin, owner, anchorNode, range,
    documentation, declaredTypeSyntax, declaredType, symbolId
)

fun fieldDeclaration(
    id: DeclarationId, name: String, origin: DeclarationOrigin = DeclarationOrigin.DOC_COMMENT, owner: DeclarationOwner,
    anchorNode: BaseASTNode? = null, range: Range? = anchorNode?.range, documentation: DeclarationDocumentation? = null,
    declaredTypeSyntax: TypeSyntax? = null, declaredType: Type? = null, symbolId: SymbolId? = null
): BinderDeclaration = BinderDeclaration(
    id, name, DeclarationKind.FIELD, origin, owner, anchorNode, range,
    documentation, declaredTypeSyntax, declaredType, symbolId
)

fun methodDeclaration(
    id: DeclarationId, name: String, origin: DeclarationOrigin = DeclarationOrigin.DOC_COMMENT, owner: DeclarationOwner,
    anchorNode: BaseASTNode? = null, range: Range? = anchorNode?.range, documentation: DeclarationDocumentation? = null,
    declaredTypeSyntax: TypeSyntax? = null, declaredType: Type? = null, symbolId: SymbolId? = null
): BinderDeclaration = BinderDeclaration(
    id, name, DeclarationKind.METHOD, origin, owner, anchorNode, range,
    documentation, declaredTypeSyntax, declaredType, symbolId
)

fun typeParameterDeclaration(
    id: DeclarationId, name: String, origin: DeclarationOrigin = DeclarationOrigin.DOC_COMMENT, owner: DeclarationOwner,
    anchorNode: BaseASTNode? = null, range: Range? = anchorNode?.range, documentation: DeclarationDocumentation? = null,
    declaredTypeSyntax: TypeSyntax? = null, declaredType: Type? = null, symbolId: SymbolId? = null
): BinderDeclaration = BinderDeclaration(
    id, name, DeclarationKind.TYPE_PARAMETER, origin, owner, anchorNode, range,
    documentation, declaredTypeSyntax, declaredType, symbolId
)
