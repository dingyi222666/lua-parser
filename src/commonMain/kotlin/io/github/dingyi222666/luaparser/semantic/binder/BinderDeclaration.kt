package io.github.dingyi222666.luaparser.semantic.binder

import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode
import io.github.dingyi222666.luaparser.parser.ast.node.Position
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
    val symbolId: SymbolId? = null,
    /**
     * Position from which this declaration is lexically visible, when it differs from
     * the declaration site itself. `null` means "default", i.e. visibility starts at
     * [range].[Range.start]. Locals declared by a LocalStatement set this to the end of
     * the whole statement: in Lua a local's scope begins at the first statement after
     * its declaration, so the RHS of `local x = x + 1` must still resolve the outer `x`.
     */
    val visibleFrom: Position? = null
)
