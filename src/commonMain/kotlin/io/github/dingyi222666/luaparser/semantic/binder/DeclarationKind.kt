package io.github.dingyi222666.luaparser.semantic.binder

enum class DeclarationNamespace {
    VALUE,
    TYPE,
    MEMBER
}

enum class DeclarationOrigin {
    AST,
    DOC_COMMENT,
    SYNTHETIC,
    BUILTIN
}

enum class DeclarationKind(val namespace: DeclarationNamespace) {
    LOCAL(DeclarationNamespace.VALUE),
    GLOBAL(DeclarationNamespace.VALUE),
    FUNCTION(DeclarationNamespace.VALUE),
    MODULE(DeclarationNamespace.VALUE),
    PARAMETER(DeclarationNamespace.VALUE),
    CLASS(DeclarationNamespace.TYPE),
    TYPE_ALIAS(DeclarationNamespace.TYPE),
    TYPE_PARAMETER(DeclarationNamespace.TYPE),
    FIELD(DeclarationNamespace.MEMBER),
    METHOD(DeclarationNamespace.MEMBER)
}
