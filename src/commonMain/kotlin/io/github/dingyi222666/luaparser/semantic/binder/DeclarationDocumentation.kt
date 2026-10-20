package io.github.dingyi222666.luaparser.semantic.binder

import io.github.dingyi222666.luaparser.parser.ast.node.CommentStatement
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachment
import io.github.dingyi222666.luaparser.semantic.comments.DocCommentSyntax
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.Type

data class DeclarationDocumentation(
    val comments: List<CommentStatement> = emptyList(),
    val docComment: DocCommentSyntax? = null,
    val inlineTypeText: String? = null,
    val resolvedInlineType: Type? = null,
    val resolvedParameterTypes: Map<String, Type> = emptyMap(),
    val resolvedReturnTypes: List<Type> = emptyList(),
    val resolvedParentType: Type? = null,
    val resolvedOverloadTypes: List<FunctionType> = emptyList()
)

fun CommentAttachment.toDeclarationDocumentation(): DeclarationDocumentation {
    return DeclarationDocumentation(
        comments = comments,
        docComment = docComment,
        inlineTypeText = inlineTypeText
    )
}
