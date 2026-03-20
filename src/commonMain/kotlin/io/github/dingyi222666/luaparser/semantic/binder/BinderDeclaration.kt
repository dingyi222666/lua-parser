package io.github.dingyi222666.luaparser.semantic.binder

import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode
import io.github.dingyi222666.luaparser.parser.ast.node.Range
import io.github.dingyi222666.luaparser.semantic.types.model.Type
import io.github.dingyi222666.luaparser.semantic.types.syntax.TypeSyntax

data class BinderDeclaration(
    val id: DeclarationId,
    val name: String,
    val kind: DeclarationKind,
    val origin: DeclarationOrigin,
    val owner: DeclarationOwner = DeclarationOwner.Root,
    val anchorNode: BaseASTNode? = null,
    val range: Range? = anchorNode?.range,
    val documentation: DeclarationDocumentation? = null,
    val declaredTypeSyntax: TypeSyntax? = null,
    val declaredType: Type? = null,
    val symbolId: SymbolId? = null
)
