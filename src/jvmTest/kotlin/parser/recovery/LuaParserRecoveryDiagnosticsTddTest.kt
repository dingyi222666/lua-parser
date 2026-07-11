package parser.recovery

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.LuaParserRecoveryDiagnostic
import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.parser.ast.node.Range
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import parser.renderShape

class LuaParserRecoveryDiagnosticsTddTest {

    @Test
    fun parserRecoveryCollectsStructuredDiagnosticsWithoutStdoutNoise() {
        val (result, stdout) = captureStdout {
            LuaParser(luaVersion = LuaVersion.ANDROLUA_5_3, errorRecovery = true)
                .parseWithDiagnostics("if ready print('x') end")
        }

        assertEquals("", stdout)
        assertEquals(
            listOf(
                RecoveryDiagnosticExpectation(
                    message = "The <then> expected near print",
                    range = Range(Position(1, 10), Position(1, 15))
                )
            ),
            result.recoveryDiagnostics.map(::toExpectation)
        )
        val recoveredShape = renderShape(result.chunk)
        assertTrue(recoveredShape.contains("CallStmt(Call(Id(print):Const('x')))"), recoveredShape)
    }

    @Test
    fun parserRecoveryDiagnosticsAreDeterministicAndResetPerParse() {
        val parser = LuaParser(luaVersion = LuaVersion.ANDROLUA_5_3, errorRecovery = true)
        val malformedSource = "while ready print('x') end"

        val first = parser.parseWithDiagnostics(malformedSource)
        val second = parser.parseWithDiagnostics(malformedSource)
        val valid = parser.parseWithDiagnostics("print('ok')")

        assertEquals(first.recoveryDiagnostics, second.recoveryDiagnostics)
        assertEquals(
            listOf(
                RecoveryDiagnosticExpectation(
                    message = "The <do> expected near print",
                    range = Range(Position(1, 13), Position(1, 18))
                )
            ),
            first.recoveryDiagnostics.map(::toExpectation)
        )
        assertEquals(emptyList(), valid.recoveryDiagnostics)
        assertEquals(emptyList(), parser.recoveryDiagnostics)
    }

    @Test
    fun localDeclarationMissingInitializerExpressionReportsPreciseDiagnostic() {
        val result = LuaParser(luaVersion = LuaVersion.ANDROLUA_5_3, errorRecovery = true)
            .parseWithDiagnostics("local value =\nreturn value")

        assertEquals(
            listOf(
                RecoveryDiagnosticExpectation(
                    message = "<expression> expected near =",
                    range = Range(Position(1, 14), Position(1, 15))
                )
            ),
            result.recoveryDiagnostics.map(::toExpectation)
        )
        val recoveredShape = renderShape(result.chunk)
        assertTrue(
            recoveredShape.contains("Local(Id(value)=ExpressionNodeSupport)"),
            recoveredShape
        )
        assertTrue(
            recoveredShape.contains("Return(Id(value))"),
            recoveredShape
        )
    }

    private fun toExpectation(diagnostic: LuaParserRecoveryDiagnostic): RecoveryDiagnosticExpectation {
        return RecoveryDiagnosticExpectation(
            message = diagnostic.message,
            range = diagnostic.range
        )
    }

    private fun <T> captureStdout(block: () -> T): Captured<T> {
        val originalOut = System.out
        val output = ByteArrayOutputStream()
        System.setOut(PrintStream(output))
        try {
            return Captured(block(), output.toString())
        } finally {
            System.setOut(originalOut)
        }
    }

    private data class Captured<T>(
        val result: T,
        val stdout: String
    )

    private data class RecoveryDiagnosticExpectation(
        val message: String,
        val range: Range
    )
}
