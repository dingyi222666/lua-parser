package semantic

import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode
import io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.parser.ast.node.Range
import io.github.dingyi222666.luaparser.semantic.api.CompletionItem
import io.github.dingyi222666.luaparser.semantic.api.CompletionItemKind
import io.github.dingyi222666.luaparser.semantic.api.Diagnostic
import io.github.dingyi222666.luaparser.semantic.api.DiagnosticSeverity
import io.github.dingyi222666.luaparser.semantic.api.Scope
import io.github.dingyi222666.luaparser.semantic.api.ScopeKind
import io.github.dingyi222666.luaparser.semantic.api.Symbol
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import io.github.dingyi222666.luaparser.semantic.api.TypeInfo
import io.github.dingyi222666.luaparser.semantic.model.SemanticAnalysisResult
import io.github.dingyi222666.luaparser.semantic.model.SemanticAnalysisSummary
import io.github.dingyi222666.luaparser.semantic.model.SemanticModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SemanticPublicApiTest {

    @Test
    fun semanticPublicTypesAreUsableWithoutAnalyzerIntegration() {
        val position = Position(1, 5)
        val range = Range(Position(1, 1), Position(1, 10))
        val parentNode = object : BaseASTNode {
            override var parent: BaseASTNode = this
            override var range: Range = range
            override var bad: Boolean = false
        }
        val node = object : ExpressionNode {
            override var parent: BaseASTNode = parentNode
            override var range: Range = range
            override var bad: Boolean = false
            override fun clone(): ExpressionNode = this
        }
        val symbol = Symbol(
            name = "value",
            kind = SymbolKind.LOCAL,
            range = range,
            type = TypeInfo("number"),
            declaredType = TypeInfo("number"),
            symbolId = "symbol:value"
        )
        val scope = Scope(
            kind = ScopeKind.BLOCK,
            range = range,
            symbols = listOf(symbol),
            name = "test"
        )
        val diagnostics = listOf(
            Diagnostic(
                message = "placeholder",
                range = range,
                severity = DiagnosticSeverity.INFO,
                code = "semantic.placeholder"
            )
        )
        val completions = listOf(
            CompletionItem(
                label = "value",
                kind = CompletionItemKind.VARIABLE,
                detail = "number"
            )
        )

        val model = object : SemanticModel {
            override fun getSymbolAt(position: Position): Symbol = symbol

            override fun getTypeAt(node: BaseASTNode): TypeInfo = TypeInfo("number")

            override fun getDeclaredType(symbol: Symbol): TypeInfo = TypeInfo("number")

            override fun getInferredType(symbol: Symbol): TypeInfo = TypeInfo("integer")

            override fun getMembers(type: TypeInfo): List<Symbol> = listOf(symbol)

            override fun getCompletionsAt(position: Position): List<CompletionItem> = completions

            override fun getDiagnostics(): List<Diagnostic> = diagnostics

            override fun getScopeAt(position: Position): Scope = scope
        }

        val result = SemanticAnalysisResult(
            model = model,
            summary = SemanticAnalysisSummary.from(model.getDiagnostics())
        )

        assertEquals("value", result.model.getSymbolAt(position)?.name)
        assertEquals("number", result.model.getTypeAt(node)?.displayName)
        assertEquals("number", result.model.getDeclaredType(symbol)?.displayName)
        assertEquals("integer", result.model.getInferredType(symbol)?.displayName)
        assertEquals("value", result.model.getMembers(TypeInfo("number")).single().name)
        assertEquals("value", result.model.getCompletionsAt(position).single().label)
        assertEquals("placeholder", result.model.getDiagnostics().single().message)
        assertEquals(1, result.summary.diagnosticCount)
        assertEquals(0, result.summary.errorCount)
        assertEquals(1, result.summary.infoCount)
        assertFalse(result.summary.hasErrors)
    }
}
