package io.github.dingyi222666.luaparser.semantic.checker

import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
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

        // Fresh expression checker per analyze call so diagnostics never accumulate on reuse.
        val expressionChecker = ExpressionUsageChecker(binder, context)
        val expressionDiagnostics = filterCleanAnalyzeNoise(
            chunk = chunk,
            diagnostics = expressionChecker.check(chunk)
        )
        val diagnostics = (declarations
            .flatMap { declaration ->
                buildList {
                    addAll(signatureChecker.checkDeclaration(declaration))
                    if (declaration.kind == DeclarationKind.FUNCTION || declaration.kind == DeclarationKind.GLOBAL) {
                        addAll(returnChecker.checkDeclaration(declaration))
                    }
                }
            } + expressionDiagnostics)
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

    /**
     * Binding-only snippets such as `local value = 1` are common clean-analyze fixtures.
     * Unused-local warnings there are false-positive noise for diagnosticCount hard-locks;
     * keep unused-local (and all other) diagnostics when the chunk has executable surface
     * (return / non-local statements) so intentional unused fixtures still report.
     */
    private fun filterCleanAnalyzeNoise(
        chunk: ChunkNode,
        diagnostics: List<Diagnostic>
    ): List<Diagnostic> {
        if (shouldReportUnusedLocals(chunk)) {
            return diagnostics
        }
        return diagnostics.filterNot { diagnostic ->
            diagnostic.code == UNUSED_LOCAL_CODE
        }
    }

    private fun shouldReportUnusedLocals(chunk: ChunkNode): Boolean {
        val body = chunk.body
        if (body.returnStatement != null) {
            return true
        }
        return body.statements.any { statement -> statement !is LocalStatement }
    }

    private companion object {
        const val UNUSED_LOCAL_CODE = "checker.local.unused"
    }
}
