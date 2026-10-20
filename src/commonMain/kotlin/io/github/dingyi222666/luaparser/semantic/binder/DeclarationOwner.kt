package io.github.dingyi222666.luaparser.semantic.binder

import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode

sealed interface DeclarationOwner {
    data object Root : DeclarationOwner

    data class Lexical(val node: BaseASTNode) : DeclarationOwner

    data class Declaration(val declarationId: DeclarationId) : DeclarationOwner
}
