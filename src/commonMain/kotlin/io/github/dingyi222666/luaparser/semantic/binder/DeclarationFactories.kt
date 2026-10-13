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
 * Each wrapper forwards verbatim to the single shared builder below.
 */
private fun binderDeclaration(
    kind: DeclarationKind, id: DeclarationId, name: String, origin: DeclarationOrigin, owner: DeclarationOwner,
    anchorNode: BaseASTNode?, range: Range?, documentation: DeclarationDocumentation?,
    declaredTypeSyntax: TypeSyntax?, declaredType: Type?, symbolId: SymbolId?
): BinderDeclaration = BinderDeclaration(
    id, name, kind, origin, owner, anchorNode, range, documentation, declaredTypeSyntax, declaredType, symbolId
)

fun localDeclaration(id: DeclarationId, name: String, origin: DeclarationOrigin = DeclarationOrigin.AST, owner: DeclarationOwner = DeclarationOwner.Root, anchorNode: BaseASTNode? = null, range: Range? = anchorNode?.range, documentation: DeclarationDocumentation? = null, declaredTypeSyntax: TypeSyntax? = null, declaredType: Type? = null, symbolId: SymbolId? = null): BinderDeclaration =
    binderDeclaration(DeclarationKind.LOCAL, id, name, origin, owner, anchorNode, range, documentation, declaredTypeSyntax, declaredType, symbolId)

fun functionDeclaration(id: DeclarationId, name: String, origin: DeclarationOrigin = DeclarationOrigin.AST, owner: DeclarationOwner = DeclarationOwner.Root, anchorNode: BaseASTNode? = null, range: Range? = anchorNode?.range, documentation: DeclarationDocumentation? = null, declaredTypeSyntax: TypeSyntax? = null, declaredType: Type? = null, symbolId: SymbolId? = null): BinderDeclaration =
    binderDeclaration(DeclarationKind.FUNCTION, id, name, origin, owner, anchorNode, range, documentation, declaredTypeSyntax, declaredType, symbolId)

fun moduleDeclaration(id: DeclarationId, name: String, origin: DeclarationOrigin = DeclarationOrigin.AST, owner: DeclarationOwner = DeclarationOwner.Root, anchorNode: BaseASTNode? = null, range: Range? = anchorNode?.range, documentation: DeclarationDocumentation? = null, declaredTypeSyntax: TypeSyntax? = null, declaredType: Type? = null, symbolId: SymbolId? = null): BinderDeclaration =
    binderDeclaration(DeclarationKind.MODULE, id, name, origin, owner, anchorNode, range, documentation, declaredTypeSyntax, declaredType, symbolId)

fun globalDeclaration(id: DeclarationId, name: String, origin: DeclarationOrigin = DeclarationOrigin.AST, owner: DeclarationOwner = DeclarationOwner.Root, anchorNode: BaseASTNode? = null, range: Range? = anchorNode?.range, documentation: DeclarationDocumentation? = null, declaredTypeSyntax: TypeSyntax? = null, declaredType: Type? = null, symbolId: SymbolId? = null): BinderDeclaration =
    binderDeclaration(DeclarationKind.GLOBAL, id, name, origin, owner, anchorNode, range, documentation, declaredTypeSyntax, declaredType, symbolId)

fun parameterDeclaration(id: DeclarationId, name: String, origin: DeclarationOrigin = DeclarationOrigin.AST, owner: DeclarationOwner, anchorNode: BaseASTNode? = null, range: Range? = anchorNode?.range, documentation: DeclarationDocumentation? = null, declaredTypeSyntax: TypeSyntax? = null, declaredType: Type? = null, symbolId: SymbolId? = null): BinderDeclaration =
    binderDeclaration(DeclarationKind.PARAMETER, id, name, origin, owner, anchorNode, range, documentation, declaredTypeSyntax, declaredType, symbolId)

fun classDeclaration(id: DeclarationId, name: String, origin: DeclarationOrigin = DeclarationOrigin.DOC_COMMENT, owner: DeclarationOwner = DeclarationOwner.Root, anchorNode: BaseASTNode? = null, range: Range? = anchorNode?.range, documentation: DeclarationDocumentation? = null, declaredTypeSyntax: TypeSyntax? = null, declaredType: Type? = null, symbolId: SymbolId? = null): BinderDeclaration =
    binderDeclaration(DeclarationKind.CLASS, id, name, origin, owner, anchorNode, range, documentation, declaredTypeSyntax, declaredType, symbolId)

fun aliasDeclaration(id: DeclarationId, name: String, origin: DeclarationOrigin = DeclarationOrigin.DOC_COMMENT, owner: DeclarationOwner = DeclarationOwner.Root, anchorNode: BaseASTNode? = null, range: Range? = anchorNode?.range, documentation: DeclarationDocumentation? = null, declaredTypeSyntax: TypeSyntax? = null, declaredType: Type? = null, symbolId: SymbolId? = null): BinderDeclaration =
    binderDeclaration(DeclarationKind.TYPE_ALIAS, id, name, origin, owner, anchorNode, range, documentation, declaredTypeSyntax, declaredType, symbolId)

fun fieldDeclaration(id: DeclarationId, name: String, origin: DeclarationOrigin = DeclarationOrigin.DOC_COMMENT, owner: DeclarationOwner, anchorNode: BaseASTNode? = null, range: Range? = anchorNode?.range, documentation: DeclarationDocumentation? = null, declaredTypeSyntax: TypeSyntax? = null, declaredType: Type? = null, symbolId: SymbolId? = null): BinderDeclaration =
    binderDeclaration(DeclarationKind.FIELD, id, name, origin, owner, anchorNode, range, documentation, declaredTypeSyntax, declaredType, symbolId)

fun methodDeclaration(id: DeclarationId, name: String, origin: DeclarationOrigin = DeclarationOrigin.DOC_COMMENT, owner: DeclarationOwner, anchorNode: BaseASTNode? = null, range: Range? = anchorNode?.range, documentation: DeclarationDocumentation? = null, declaredTypeSyntax: TypeSyntax? = null, declaredType: Type? = null, symbolId: SymbolId? = null): BinderDeclaration =
    binderDeclaration(DeclarationKind.METHOD, id, name, origin, owner, anchorNode, range, documentation, declaredTypeSyntax, declaredType, symbolId)

fun typeParameterDeclaration(id: DeclarationId, name: String, origin: DeclarationOrigin = DeclarationOrigin.DOC_COMMENT, owner: DeclarationOwner, anchorNode: BaseASTNode? = null, range: Range? = anchorNode?.range, documentation: DeclarationDocumentation? = null, declaredTypeSyntax: TypeSyntax? = null, declaredType: Type? = null, symbolId: SymbolId? = null): BinderDeclaration =
    binderDeclaration(DeclarationKind.TYPE_PARAMETER, id, name, origin, owner, anchorNode, range, documentation, declaredTypeSyntax, declaredType, symbolId)
