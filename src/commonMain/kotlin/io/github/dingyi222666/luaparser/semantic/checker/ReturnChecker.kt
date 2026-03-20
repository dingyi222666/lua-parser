package io.github.dingyi222666.luaparser.semantic.checker

import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.IfStatement
import io.github.dingyi222666.luaparser.parser.ast.node.DoStatement
import io.github.dingyi222666.luaparser.parser.ast.node.Range
import io.github.dingyi222666.luaparser.parser.ast.node.StatementNode
import io.github.dingyi222666.luaparser.parser.ast.node.BlockNode
import io.github.dingyi222666.luaparser.parser.ast.node.ElseClause
import io.github.dingyi222666.luaparser.parser.ast.node.WhenStatement
import io.github.dingyi222666.luaparser.semantic.api.Diagnostic
import io.github.dingyi222666.luaparser.semantic.binder.BinderDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.model.UnknownType
import io.github.dingyi222666.luaparser.semantic.types.resolve.isAssignableFrom

class ReturnChecker(
    private val binder: BinderPassResult,
    private val evaluator: ExpressionTypeEvaluator = ExpressionTypeEvaluator(binder)
) {

    fun checkDeclaration(declaration: BinderDeclaration): List<Diagnostic> {
        if (declaration.kind != DeclarationKind.FUNCTION && declaration.kind != DeclarationKind.GLOBAL) {
            return emptyList()
        }

        val function = resolveOwningFunctionDeclaration(binder, declaration) ?: return emptyList()
        val functionType = declaration.declaredType as? FunctionType ?: return emptyList()
        val body = function.body ?: return emptyList()
        val fallbackScopeId = binder.scopeGraph.getScope(body)?.id ?: binder.scopeGraph.rootScope.id
        val context = evaluator.buildFunctionBodyContext(function, functionType.parameters, fallbackScopeId)
        val expected = ValueSequence.of(functionType.returnType)
        val returnSites = evaluator.collectReturnSites(body, context)
        val hasImplicitFallthrough = mayFallThrough(body)

        val diagnostics = returnSites.flatMap { site ->
            compareReturnSite(
                expected = expected,
                actual = site.values,
                range = site.statement?.range ?: body.range,
                functionType = functionType
            )
        }

        if (returnSites.isEmpty() || hasImplicitFallthrough) {
            return diagnostics + compareReturnSite(
                expected = expected,
                actual = ValueSequence(fixed = listOf(PrimitiveType.NIL)),
                range = body.range,
                functionType = functionType
            )
        }

        return diagnostics
    }

    private fun mayFallThrough(block: BlockNode): Boolean {
        if (block.returnStatement != null) {
            return false
        }
        val lastStatement = block.statements.lastOrNull() ?: return true
        return statementMayFallThrough(lastStatement)
    }

    private fun statementMayFallThrough(statement: StatementNode): Boolean {
        return when (statement) {
            is DoStatement -> mayFallThrough(statement.body)
            is IfStatement -> ifStatementMayFallThrough(statement)
            is WhenStatement -> whenStatementMayFallThrough(statement)
            is FunctionDeclaration -> true
            else -> true
        }
    }

    private fun ifStatementMayFallThrough(statement: IfStatement): Boolean {
        if (statement.causes.isEmpty()) {
            return true
        }
        if (statement.causes.none { it is ElseClause }) {
            return true
        }
        return statement.causes.any { mayFallThrough(it.body) }
    }

    private fun whenStatementMayFallThrough(statement: WhenStatement): Boolean {
        val elseClause = statement.elseCause ?: return true
        return statementMayFallThrough(statement.ifCause) || statementMayFallThrough(elseClause)
    }

    private fun compareReturnSite(
        expected: ValueSequence,
        actual: ValueSequence,
        range: Range?,
        functionType: FunctionType
    ): List<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()
        val comparisonLimit = if (expected.isOpenEnded) {
            maxOf(expected.fixed.size, actual.fixed.size)
        } else {
            expected.fixed.size
        }

        repeat(comparisonLimit) { index ->
            val expectedType = expected.typeAt(index)
            val actualType = actual.typeAt(index)
            if (expectedType == UnknownType || actualType == UnknownType) {
                return@repeat
            }
            if (!expectedType.isAssignableFrom(actualType)) {
                diagnostics += Diagnostic(
                    message = "Return value ${index + 1} has type ${actualType.displayName}, expected ${expectedType.displayName}.",
                    range = range,
                    code = "checker.function.return.typeMismatch"
                )
            }
        }

        if (expected.isOpenEnded && actual.isOpenEnded) {
            val index = comparisonLimit
            val expectedType = expected.typeAt(index)
            val actualType = actual.typeAt(index)
            if (expectedType != UnknownType && actualType != UnknownType && !expectedType.isAssignableFrom(actualType)) {
                diagnostics += Diagnostic(
                    message = "Return value ${index + 1} has type ${actualType.displayName}, expected ${expectedType.displayName}.",
                    range = range,
                    code = "checker.function.return.typeMismatch"
                )
            }
        }

        if (!expected.isOpenEnded && actual.hasValueAt(expected.fixed.size)) {
            val extraType = actual.typeAt(expected.fixed.size)
            if (extraType != UnknownType) {
                diagnostics += Diagnostic(
                    message = "Return statement provides extra values beyond declared return type ${functionType.returnType.displayName}.",
                    range = range,
                    code = "checker.function.return.extraValues"
                )
            }
        }

        return diagnostics
    }
}
