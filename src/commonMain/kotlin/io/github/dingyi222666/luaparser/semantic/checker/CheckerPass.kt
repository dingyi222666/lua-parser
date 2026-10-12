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
        val evaluator = ExpressionTypeEvaluator(binder, context)
        val returnChecker = ReturnChecker(binder, context, evaluator)
        val declarations = binder.declarationIndex.declarations.filter {
            it.kind in setOf(
                DeclarationKind.FUNCTION,
                DeclarationKind.GLOBAL,
                DeclarationKind.METHOD
            )
        }

        // Fresh expression checker per analyze call so diagnostics never accumulate on reuse.
        val expressionChecker = ExpressionUsageChecker(binder, context, evaluator)
        val expressionStart = System.currentTimeMillis()
        val expressionDiagnostics = expressionChecker.check(chunk)
        val expressionMs = System.currentTimeMillis() - expressionStart
        val signatureStart = System.currentTimeMillis()
        val signatureDiagnostics = declarations.flatMap { declaration ->
            buildList {
                addAll(signatureChecker.checkDeclaration(declaration))
                if (declaration.kind == DeclarationKind.FUNCTION || declaration.kind == DeclarationKind.GLOBAL) {
                    addAll(returnChecker.checkDeclaration(declaration))
                }
            }
        }
        val signatureMs = System.currentTimeMillis() - signatureStart
        if (expressionMs > 1_000 || signatureMs > 1_000) {
            System.err.println(
                "CHECKER-PERF: expression=${expressionMs}ms signature=${signatureMs}ms " +
                    "declarations=${declarations.size} path=${context.currentPath?.value ?: "?"}"
            )
        }
        val diagnostics = (signatureDiagnostics + expressionDiagnostics)
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

/** Wire-protocol code for an unused local declaration. */
internal const val UNUSED_LOCAL_CODE = "checker.local.unused"
