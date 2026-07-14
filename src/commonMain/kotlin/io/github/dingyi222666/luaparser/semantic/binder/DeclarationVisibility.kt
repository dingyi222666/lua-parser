package io.github.dingyi222666.luaparser.semantic.binder

import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration

internal fun BinderPassResult.isChunkGlobalFunctionDeclaration(declaration: BinderDeclaration): Boolean {
    if (declaration.kind != DeclarationKind.GLOBAL || declaration.origin != DeclarationOrigin.AST) {
        return false
    }
    val function = declaration.anchorNode?.parent as? FunctionDeclaration ?: return false
    return !function.isLocal && declaration.id in scopeGraph.rootScope.declarationIds
}
