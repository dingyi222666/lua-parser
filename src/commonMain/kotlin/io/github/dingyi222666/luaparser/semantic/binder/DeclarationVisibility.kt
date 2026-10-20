package io.github.dingyi222666.luaparser.semantic.binder

import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.Position

internal fun BinderPassResult.isChunkGlobalFunctionDeclaration(declaration: BinderDeclaration): Boolean {
    if (declaration.kind != DeclarationKind.GLOBAL || declaration.origin != DeclarationOrigin.AST) {
        return false
    }
    val function = declaration.anchorNode?.parent as? FunctionDeclaration ?: return false
    return !function.isLocal && declaration.id in scopeGraph.rootScope.declarationIds
}

/**
 * Canonical positional-visibility ladder (the model layer's `ReferenceQueries.isVisibleAt`
 * semantics): builtins file-global iff [builtinIsFileGlobal]; chunk-global functions
 * file-global iff [chunkGlobalIsFileGlobal] (probe via [isChunkGlobalFunction]); AST-invented
 * chunk globals always position-independent (environment-table semantics); missing range or
 * the declared site itself (`start <= pos < end`) always visible; otherwise visible from
 * [visibleFrom] (end of the declaring LocalStatement). Callers that position-gate builtins /
 * chunk-global functions pass `false` flags — the rest of the ladder is identical.
 */
internal fun BinderDeclaration.isVisibleAtPosition(
    position: Position,
    isChunkGlobalFunction: ((BinderDeclaration) -> Boolean)? = null,
    builtinIsFileGlobal: Boolean = false,
    chunkGlobalIsFileGlobal: Boolean = false,
): Boolean {
    if (builtinIsFileGlobal && origin == DeclarationOrigin.BUILTIN) return true
    if (chunkGlobalIsFileGlobal && isChunkGlobalFunction?.invoke(this) == true) return true
    if (kind == DeclarationKind.GLOBAL && origin == DeclarationOrigin.AST) return true
    val range = range ?: return true
    if (comparePositions(range.start, position) <= 0 && comparePositions(position, range.end) < 0) return true
    val visibleFrom = visibleFrom ?: range.start
    return comparePositions(visibleFrom, position) <= 0
}
