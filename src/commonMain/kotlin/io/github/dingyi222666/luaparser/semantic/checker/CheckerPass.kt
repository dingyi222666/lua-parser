package io.github.dingyi222666.luaparser.semantic.checker

import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.semantic.SemanticWorkspaceContext
import io.github.dingyi222666.luaparser.semantic.api.Diagnostic
import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind

class CheckerPass {
    internal fun check(
        chunk: ChunkNode,
        binder: BinderPassResult,
        context: SemanticWorkspaceContext = SemanticWorkspaceContext()
    ): CheckerPassResult {
        val signatureChecker = FunctionSignatureChecker(binder)
        val returnChecker = ReturnChecker(binder, context)
        val declarations = binder.declarationIndex.declarations.filter {
            it.kind in setOf(
                DeclarationKind.FUNCTION,
                DeclarationKind.GLOBAL,
                DeclarationKind.METHOD
            )
        }

        val diagnostics = declarations
            .flatMap { declaration ->
                buildList {
                    addAll(signatureChecker.checkDeclaration(declaration))
                    if (declaration.kind == DeclarationKind.FUNCTION || declaration.kind == DeclarationKind.GLOBAL) {
                        addAll(returnChecker.checkDeclaration(declaration))
                    }
                }
            }
            .distinctBy { diagnostic ->
                listOf(
                    diagnostic.range?.start?.line,
                    diagnostic.range?.start?.column,
                    diagnostic.range?.end?.line,
                    diagnostic.range?.end?.column,
                    diagnostic.severity,
                    diagnostic.code,
                    diagnostic.message
                )
            }
            .sortedWith(
                compareBy<Diagnostic>(
                    { it.range?.start?.line ?: Int.MAX_VALUE },
                    { it.range?.start?.column ?: Int.MAX_VALUE },
                    { it.severity.ordinal },
                    { it.code ?: "" },
                    { it.message }
                )
            )

        return CheckerPassResult(
            binder = binder,
            diagnostics = diagnostics
        )
    }
}
