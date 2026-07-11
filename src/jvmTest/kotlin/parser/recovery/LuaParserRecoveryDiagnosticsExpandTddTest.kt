package parser.recovery

import io.github.dingyi222666.luaparser.parser.LuaParseResult
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

/**
 * Sparse recovery-diagnostics golden expand (TASK-424).
 *
 * Complements the thin [LuaParserRecoveryDiagnosticsTddTest] suite (3 cases) and the
 * per-construct recovery corpora (if-chain / while-do / for-in / numeric-for / repeat /
 * goto-label / local-assign / attribute / lambda / switch-when / multi-rhs / tables),
 * which mostly assert message *fragments* only.
 *
 * This expand pins:
 * - exact message + range goldens for representative sparse cases across recovery suites
 * - multi-diagnostic sources (ordered, deterministic)
 * - dual-path honesty for placeholder-only recovery that currently emits no warning
 * - stdout silence, per-parse reset, well-formed emptiness
 * - positive range structure + inventory coverage across Lua 5.3 / 5.4 / AndroLua
 *
 * Test-only; no production edits. Verification deferred to TASK-043 serial review.
 *
 * Product notes (aligned with [LuaParser.warning] / [LuaParser.currentRecoveryRange]):
 * - recovery diagnostics are collected only when errorRecovery=true and
 *   ignoreWarningMessage=true (defaults for recovery parses);
 * - ranges anchor on the current significant token (line/column/length);
 * - ExpressionNodeSupport placeholders often recover *without* a structured diagnostic
 *   (documented dual-path CURRENTLY_ACCEPTS_MISSING_DIAGNOSTIC);
 * - unary missing operands only insert ExpressionNodeSupport when the next token is an
 *   expression terminator (`end` / `)` / `,` / …); a following NAME/`print(...)` is
 *   absorbed as the unary operand (no unary statement-start-after-line-break path —
 *   CURRENTLY_ACCEPTS absorb residual, same family as multi-rhs / local-assign corpora);
 * - call missing `)` uses recoverToken RPAREN (`')' expected near …`). Prefer line-break
 *   + statement-start residual (`use(k\nprint(t)`) rather than top-level bare `end`, which
 *   is a block terminator and can trip chunk EOF expectation after recovery.
 */
class LuaParserRecoveryDiagnosticsExpandTddTest {

    // -------------------------------------------------------------------------
    // Exact sparse goldens (message + range) — control-flow delimiters
    // -------------------------------------------------------------------------

    @Test
    fun expandsIfMissingThenDiagnosticGoldenWithExactRange() {
        // Mirrors LuaParserRecoveryDiagnosticsTddTest baseline; keep exact pin.
        assertExactDiagnostics(
            source = "if ready print('x') end",
            version = LuaVersion.ANDROLUA_5_3,
            expected = listOf(
                Diag(
                    message = "The <then> expected near print",
                    range = Range(Position(1, 10), Position(1, 15))
                )
            ),
            requiredShapeFragments = listOf("CallStmt(Call(Id(print):Const('x')))")
        )
    }

    @Test
    fun expandsWhileMissingDoDiagnosticGoldenWithExactRange() {
        assertExactDiagnostics(
            source = "while ready print('x') end",
            version = LuaVersion.ANDROLUA_5_3,
            expected = listOf(
                Diag(
                    message = "The <do> expected near print",
                    range = Range(Position(1, 13), Position(1, 18))
                )
            ),
            requiredShapeFragments = listOf("While(", "CallStmt(Call(Id(print):Const('x')))")
        )
    }

    @Test
    fun expandsElseIfMissingThenDiagnosticGoldenWithExactRange() {
        // "if first then one() elseif second two() else three() end"
        // missing then before second body: near token `two`
        val source = "if first then one() elseif second two() else three() end"
        val twoStart = source.indexOf("two") + 1 // 1-based
        val twoEnd = twoStart + "two".length
        assertExactDiagnostics(
            source = source,
            version = LuaVersion.LUA_5_3,
            expected = listOf(
                Diag(
                    message = "The <then> expected near two",
                    range = Range(Position(1, twoStart), Position(1, twoEnd))
                )
            ),
            requiredShapeFragments = listOf(
                "ElseIf(Id(second):",
                "CallStmt(Call(Id(two):))"
            )
        )
    }

    @Test
    fun expandsNumericForMissingDoDiagnosticGoldenWithExactRange() {
        val source = "for i=1,10 print(i) end"
        val printStart = source.indexOf("print") + 1
        val printEnd = printStart + "print".length
        assertExactDiagnostics(
            source = source,
            version = LuaVersion.LUA_5_3,
            expected = listOf(
                Diag(
                    message = "The <do> expected near print",
                    range = Range(Position(1, printStart), Position(1, printEnd))
                )
            ),
            requiredShapeFragments = listOf(
                "ForNumeric(",
                "CallStmt(Call(Id(print):Id(i)))"
            )
        )
    }

    @Test
    fun expandsGenericForMissingDoDiagnosticGoldenWithExactRange() {
        val source = "for k, v in pairs(t) use(k, v) end"
        val useStart = source.indexOf("use") + 1
        val useEnd = useStart + "use".length
        assertExactDiagnostics(
            source = source,
            version = LuaVersion.LUA_5_3,
            expected = listOf(
                Diag(
                    message = "The <do> expected near use",
                    range = Range(Position(1, useStart), Position(1, useEnd))
                )
            ),
            requiredShapeFragments = listOf(
                "ForGeneric(",
                "CallStmt(Call(Id(use):Id(k),Id(v)))"
            )
        )
    }

    @Test
    fun expandsGenericForMissingInDiagnosticFamily() {
        // Product: parseForGenericStatement emits "The <in> expected near '${lexerText()}'"
        val source = "for k, v pairs(t) do use(k, v) end"
        val result = parseWithDiagnosticsWithoutThrow(source, version = LuaVersion.LUA_5_3)
        assertTrue(
            result.recoveryDiagnostics.any { it.message.contains("The <in> expected") },
            "expected <in> diagnostic; got ${result.recoveryDiagnostics.map { it.message }}"
        )
        assertPositiveRanges(result.recoveryDiagnostics)
        val shape = renderShape(result.chunk)
        assertTrue(shape.contains("ForGeneric("), shape)
        assertTrue(shape.contains("CallStmt(Call(Id(use):Id(k),Id(v)))"), shape)
        assertDeterministic(source, version = LuaVersion.LUA_5_3)
    }

    @Test
    fun expandsRepeatMissingUntilDiagnosticGoldenWithExactRange() {
        // Product: after parseBlockNode, missing until emits
        // "'until' expected near '${lexerText()}'". Body absorbs following statements
        // until EOF/END/UNTIL, so the near-token is typically EOF — pin message family +
        // residual shape rather than brittle EOF columns.
        val source = "repeat work() print(1)"
        val result = parseWithDiagnosticsWithoutThrow(source, version = LuaVersion.LUA_5_3)
        assertTrue(
            result.recoveryDiagnostics.any { it.message.contains("'until' expected") },
            "expected until diagnostic; got ${result.recoveryDiagnostics.map { it.message }}"
        )
        assertPositiveRanges(result.recoveryDiagnostics)
        val shape = renderShape(result.chunk)
        assertTrue(shape.contains("Repeat("), shape)
        assertTrue(shape.contains("CallStmt(Call(Id(work):))"), shape)
        assertDeterministic(source, version = LuaVersion.LUA_5_3)
    }

    @Test
    fun expandsIfMissingEndAtEofDiagnosticContainsEndExpected() {
        // Missing end at EOF: product emits "<end> expected (to close 'if' at line 1) near ..."
        // Exact near-token/range depends on EOF token presentation; pin message family +
        // positive range structure rather than brittle EOF columns.
        val source = "if ready then work()"
        val result = parseWithDiagnosticsWithoutThrow(source, version = LuaVersion.LUA_5_3)
        assertTrue(
            result.recoveryDiagnostics.any { it.message.contains("<end> expected") },
            "expected <end> diagnostic; got ${result.recoveryDiagnostics.map { it.message }}"
        )
        assertTrue(
            result.recoveryDiagnostics.any {
                it.message.contains("to close 'if'") || it.message.contains("<end> expected")
            },
            "expected if-close end diagnostic family; got ${result.recoveryDiagnostics.map { it.message }}"
        )
        assertPositiveRanges(result.recoveryDiagnostics)
        assertTrue(
            renderShape(result.chunk).contains("CallStmt(Call(Id(work):))"),
            renderShape(result.chunk)
        )
        assertDeterministic(source, version = LuaVersion.LUA_5_3)
    }

    @Test
    fun expandsWhileMissingEndAtEofDiagnosticFamily() {
        val source = "while ready do work()"
        val result = parseWithDiagnosticsWithoutThrow(source, version = LuaVersion.LUA_5_3)
        assertTrue(
            result.recoveryDiagnostics.any { it.message.contains("<end> expected") },
            "expected <end> diagnostic for while; got ${result.recoveryDiagnostics.map { it.message }}"
        )
        assertPositiveRanges(result.recoveryDiagnostics)
        assertTrue(renderShape(result.chunk).contains("While("), renderShape(result.chunk))
        assertTrue(renderShape(result.chunk).contains("CallStmt(Call(Id(work):))"), renderShape(result.chunk))
        assertDeterministic(source, version = LuaVersion.LUA_5_3)
    }

    @Test
    fun expandsDoMissingEndAtEofDiagnosticFamily() {
        val source = "do work() print(1)"
        val result = parseWithDiagnosticsWithoutThrow(source, version = LuaVersion.LUA_5_3)
        assertTrue(
            result.recoveryDiagnostics.any { it.message.contains("<end> expected") },
            "expected <end> diagnostic for do; got ${result.recoveryDiagnostics.map { it.message }}"
        )
        assertPositiveRanges(result.recoveryDiagnostics)
        val shape = renderShape(result.chunk)
        assertTrue(shape.contains("Do("), shape)
        assertTrue(shape.contains("CallStmt(Call(Id(work):))"), shape)
        assertDeterministic(source, version = LuaVersion.LUA_5_3)
    }

    // -------------------------------------------------------------------------
    // Goto / label / function / assignment sparse goldens
    // -------------------------------------------------------------------------

    @Test
    fun expandsGotoMissingNameDiagnosticGolden() {
        val source = "goto"
        val result = parseWithDiagnosticsWithoutThrow(source, version = LuaVersion.LUA_5_3)
        assertTrue(
            result.recoveryDiagnostics.any { it.message.contains("<name> expected") },
            "expected <name> diagnostic for bare goto; got ${result.recoveryDiagnostics.map { it.message }}"
        )
        assertPositiveRanges(result.recoveryDiagnostics)
        assertTrue(renderShape(result.chunk).contains("Goto(Id())"), renderShape(result.chunk))
        assertDeterministic(source, version = LuaVersion.LUA_5_3)
    }

    @Test
    fun expandsLabelMissingClosingColonsDiagnosticGolden() {
        val source = "::again"
        val result = parseWithDiagnosticsWithoutThrow(source, version = LuaVersion.LUA_5_3)
        assertTrue(
            result.recoveryDiagnostics.any { it.message.contains("'::' expected") },
            "expected '::' diagnostic; got ${result.recoveryDiagnostics.map { it.message }}"
        )
        assertPositiveRanges(result.recoveryDiagnostics)
        assertTrue(renderShape(result.chunk).contains("Label(Id(again))"), renderShape(result.chunk))
        assertDeterministic(source, version = LuaVersion.LUA_5_3)
    }

    @Test
    fun expandsAssignmentMissingEqualsDiagnosticGolden() {
        val source = "a, b\nprint(a)"
        val result = parseWithDiagnosticsWithoutThrow(source, version = LuaVersion.LUA_5_3)
        assertTrue(
            result.recoveryDiagnostics.any { it.message.contains("'=' expected") },
            "expected '=' diagnostic; got ${result.recoveryDiagnostics.map { it.message }}"
        )
        assertPositiveRanges(result.recoveryDiagnostics)
        assertTrue(
            renderShape(result.chunk).contains("CallStmt(Call(Id(print):Id(a)))"),
            renderShape(result.chunk)
        )
        assertDeterministic(source, version = LuaVersion.LUA_5_3)
    }

    @Test
    fun expandsCallMissingCloseParenDiagnosticGolden() {
        // Product: parseCallExpression recoverToken RPAREN emits "')' expected near …".
        // Use line-break + following statement rather than bare top-level `end` (block
        // terminator can leave residual END before chunk EOF and trip expectToken).
        // Residual: incomplete call + sibling print.
        val source = "use(k\nprint(t)"
        val result = parseWithDiagnosticsWithoutThrow(source, version = LuaVersion.LUA_5_3)
        assertTrue(
            result.recoveryDiagnostics.any {
                it.message.contains("')' expected") || it.message.contains(") expected")
            },
            "expected ')' diagnostic; got ${result.recoveryDiagnostics.map { it.message }}"
        )
        assertPositiveRanges(result.recoveryDiagnostics)
        val shape = renderShape(result.chunk)
        assertTrue(
            shape.contains("CallStmt(Call(Id(print):Id(t)))") ||
                shape.contains("Call(Id(use):"),
            shape
        )
        assertTrue(
            shape.contains("Call(Id(use):") || shape.contains("CallStmt(Call(Id(use)"),
            "incomplete call should remain as Call residual:\n$shape"
        )
        assertDeterministic(source, version = LuaVersion.LUA_5_3)
    }

    @Test
    fun expandsFunctionBodyMissingCloseParenDiagnosticGolden() {
        // Product: recoverToken RPAREN emits ") expected near ..."
        val source = "function f(a return a end"
        val result = parseWithDiagnosticsWithoutThrow(source, version = LuaVersion.LUA_5_3)
        assertTrue(
            result.recoveryDiagnostics.any {
                it.message.contains(") expected") || it.message.contains("')' expected")
            },
            "expected ')' diagnostic for function body; got ${result.recoveryDiagnostics.map { it.message }}"
        )
        assertPositiveRanges(result.recoveryDiagnostics)
        assertTrue(renderShape(result.chunk).contains("Function("), renderShape(result.chunk))
        assertDeterministic(source, version = LuaVersion.LUA_5_3)
    }

    @Test
    fun expandsLocalMissingNameDiagnosticGolden() {
        val source = "local =\nlocal after = 1"
        val result = parseWithDiagnosticsWithoutThrow(source, version = LuaVersion.LUA_5_3)
        assertTrue(
            result.recoveryDiagnostics.any { it.message.contains("<name> expected") },
            "expected <name> diagnostic; got ${result.recoveryDiagnostics.map { it.message }}"
        )
        assertPositiveRanges(result.recoveryDiagnostics)
        assertTrue(
            renderShape(result.chunk).contains("Local(Id(after)=Const(1))"),
            renderShape(result.chunk)
        )
        assertDeterministic(source, version = LuaVersion.LUA_5_3)
    }

    // -------------------------------------------------------------------------
    // Dual-path: placeholder recovery without structured diagnostics
    // -------------------------------------------------------------------------

    @Test
    fun expandsLocalMissingInitializerDualPathDiagnosticOrPlaceholderOnly() {
        // Base LuaParserRecoveryDiagnosticsTddTest historically expected:
        //   "<expression> expected near =" at Range(1,14)-(1,15)
        // Current product path (parseExpressionOrMissing under recovery) inserts
        // ExpressionNodeSupport *without* always emitting a structured diagnostic.
        // Dual-path: exact historical golden OR empty diagnostics + bad placeholder.
        val source = "local value =\nreturn value"
        val result = parseWithDiagnosticsWithoutThrow(source, version = LuaVersion.ANDROLUA_5_3)
        val shape = renderShape(result.chunk)
        assertTrue(
            shape.contains("Local(Id(value)=ExpressionNodeSupport)"),
            "missing local initializer should recover placeholder:\n$shape"
        )
        assertTrue(
            shape.contains("Return(Id(value))"),
            "later return must stay reachable:\n$shape"
        )

        val historical = listOf(
            Diag(
                message = "<expression> expected near =",
                range = Range(Position(1, 14), Position(1, 15))
            )
        )
        val actual = result.recoveryDiagnostics.map(::toDiag)
        val placeholderOnly = actual.isEmpty()
        val matchesHistorical = actual == historical
        assertTrue(
            placeholderOnly || matchesHistorical,
            "dual-path for local missing initializer: expected empty diagnostics " +
                "(CURRENTLY_ACCEPTS_MISSING_DIAGNOSTIC) OR historical $historical; got $actual"
        )
        assertPositiveRanges(result.recoveryDiagnostics)
        assertDeterministic(source, version = LuaVersion.ANDROLUA_5_3)
    }

    @Test
    fun expandsAssignmentMissingRhsDualPathPlaceholderWithoutRequiredDiagnostic() {
        // Missing RHS after `=` with next-line statement start inserts ExpressionNodeSupport
        // and typically emits no structured diagnostic (strict CURRENTLY_ACCEPTS_MISSING_RHS).
        val source = "a =\nprint(a)"
        val result = parseWithDiagnosticsWithoutThrow(source, version = LuaVersion.LUA_5_3)
        val shape = renderShape(result.chunk)
        assertTrue(shape.contains("Assign(Id(a)=ExpressionNodeSupport)"), shape)
        assertTrue(shape.contains("CallStmt(Call(Id(print):Id(a)))"), shape)
        // Dual-path: empty diagnostics is product residual; any emitted diagnostics must be
        // well-formed and deterministic.
        assertPositiveRanges(result.recoveryDiagnostics)
        assertDeterministic(source, version = LuaVersion.LUA_5_3)
    }

    @Test
    fun expandsBinaryRhsMissingOperandDualPathPlaceholderWithoutRequiredDiagnostic() {
        val source = "a = value +\nprint(a)"
        val result = parseWithDiagnosticsWithoutThrow(source, version = LuaVersion.LUA_5_3)
        val shape = renderShape(result.chunk)
        assertTrue(
            shape.contains("Binary(+,Id(value),ExpressionNodeSupport)"),
            shape
        )
        assertTrue(shape.contains("CallStmt(Call(Id(print):Id(a)))"), shape)
        assertPositiveRanges(result.recoveryDiagnostics)
        assertDeterministic(source, version = LuaVersion.LUA_5_3)
    }

    @Test
    fun expandsUnaryMissingOperandDualPathPlaceholderWithoutRequiredDiagnostic() {
        // Product residual (parseUnaryExpression):
        // - following NAME/`print(...)` is an expression start → absorbed as unary operand
        //   (CURRENTLY_ACCEPTS absorb; no structured diagnostic required)
        // - following expression terminator (`end`) → ExpressionNodeSupport placeholder,
        //   still typically without a structured diagnostic
        // Dual-path honesty: document both residuals; no required diagnostic either way.

        // Path A — absorb next-line call as operand.
        val absorbSource = "a = -\nprint(a)"
        val absorb = parseWithDiagnosticsWithoutThrow(absorbSource, version = LuaVersion.LUA_5_3)
        val absorbShape = renderShape(absorb.chunk)
        assertTrue(
            absorbShape.contains("Unary(-,Call(Id(print):Id(a)))") ||
                absorbShape.contains("Assign(Id(a)=Unary(-,"),
            "unary absorb residual expected:\n$absorbShape"
        )
        assertPositiveRanges(absorb.recoveryDiagnostics)
        assertDeterministic(absorbSource, version = LuaVersion.LUA_5_3)

        // Path B — terminator forces ExpressionNodeSupport placeholder; later print sibling.
        val placeholderSource = "do\n  a = -\nend\nprint(a)"
        val placeholder = parseWithDiagnosticsWithoutThrow(
            placeholderSource,
            version = LuaVersion.LUA_5_3
        )
        val placeholderShape = renderShape(placeholder.chunk)
        assertTrue(
            placeholderShape.contains("Unary(-,ExpressionNodeSupport)"),
            "unary terminator residual should insert placeholder:\n$placeholderShape"
        )
        assertTrue(
            placeholderShape.contains("CallStmt(Call(Id(print):Id(a)))"),
            "later print must stay reachable after do/end:\n$placeholderShape"
        )
        assertPositiveRanges(placeholder.recoveryDiagnostics)
        assertDeterministic(placeholderSource, version = LuaVersion.LUA_5_3)
    }

    // -------------------------------------------------------------------------
    // Android-Lua / attribute / switch / multi-diagnostic expand
    // -------------------------------------------------------------------------

    @Test
    fun expandsAndroluaLambdaMissingArrowDiagnosticFamily() {
        val source = "local f = lambda x 1"
        val result = parseWithDiagnosticsWithoutThrow(source, version = LuaVersion.ANDROLUA_5_3)
        // Product may emit '->' / '=>' / ':' expected depending on how far the arrow parse
        // advances; any of those families is acceptable residual.
        assertTrue(
            result.recoveryDiagnostics.any { d ->
                d.message.contains("'->' expected") ||
                    d.message.contains("'=>' expected") ||
                    d.message.contains("':' expected")
            },
            "expected lambda arrow-family diagnostic; got ${result.recoveryDiagnostics.map { it.message }}"
        )
        assertPositiveRanges(result.recoveryDiagnostics)
        assertTrue(renderShape(result.chunk).contains("Lambda("), renderShape(result.chunk))
        assertDeterministic(source, version = LuaVersion.ANDROLUA_5_3)
    }

    @Test
    fun expandsLua54AttributeMissingGtDiagnosticFamily() {
        val source = "local x <const = 1"
        val result = parseWithDiagnosticsWithoutThrow(source, version = LuaVersion.LUA_5_4)
        assertTrue(
            result.recoveryDiagnostics.any { it.message.contains("'>' expected") },
            "expected '>' diagnostic for open attribute; got ${result.recoveryDiagnostics.map { it.message }}"
        )
        assertPositiveRanges(result.recoveryDiagnostics)
        assertTrue(renderShape(result.chunk).contains("Local("), renderShape(result.chunk))
        assertDeterministic(source, version = LuaVersion.LUA_5_4)
    }

    @Test
    fun expandsAndroluaSwitchMissingDoDiagnosticFamily() {
        val source = "switch mode case 1 one() default fallback() end"
        val result = parseWithDiagnosticsWithoutThrow(source, version = LuaVersion.ANDROLUA_5_3)
        assertTrue(
            result.recoveryDiagnostics.any { it.message.contains("The <do> expected") },
            "expected switch <do> diagnostic; got ${result.recoveryDiagnostics.map { it.message }}"
        )
        assertPositiveRanges(result.recoveryDiagnostics)
        val shape = renderShape(result.chunk)
        assertTrue(shape.contains("Switch(Id(mode):"), shape)
        assertTrue(shape.contains("Case(Const(1):"), shape)
        assertDeterministic(source, version = LuaVersion.ANDROLUA_5_3)
    }

    @Test
    fun expandsAndroluaSwitchMissingEndDiagnosticFamily() {
        val source = "switch mode do case 1 one() default fallback()"
        val result = parseWithDiagnosticsWithoutThrow(source, version = LuaVersion.ANDROLUA_5_3)
        assertTrue(
            result.recoveryDiagnostics.any { it.message.contains("<end> expected") },
            "expected switch <end> diagnostic; got ${result.recoveryDiagnostics.map { it.message }}"
        )
        assertPositiveRanges(result.recoveryDiagnostics)
        val shape = renderShape(result.chunk)
        assertTrue(shape.contains("Switch(Id(mode):"), shape)
        assertTrue(shape.contains("Default("), shape)
        assertDeterministic(source, version = LuaVersion.ANDROLUA_5_3)
    }

    @Test
    fun expandsAndroluaSwitchMissingDoAndEndOrderedDiagnostics() {
        val source = "switch mode case 1 one()"
        val first = parseWithDiagnosticsWithoutThrow(source, version = LuaVersion.ANDROLUA_5_3)
        val second = parseWithDiagnosticsWithoutThrow(source, version = LuaVersion.ANDROLUA_5_3)
        val messages = first.recoveryDiagnostics.map { it.message }
        assertTrue(
            messages.any { it.contains("The <do> expected") },
            "expected do diagnostic; got $messages"
        )
        assertTrue(
            messages.any { it.contains("<end> expected") },
            "expected end diagnostic; got $messages"
        )
        val doIdx = messages.indexOfFirst { it.contains("The <do> expected") }
        val endIdx = messages.indexOfFirst { it.contains("<end> expected") }
        assertTrue(doIdx in 0 until endIdx, "do should precede end; messages=$messages")
        assertEquals(first.recoveryDiagnostics, second.recoveryDiagnostics)
        assertEquals(renderShape(first.chunk), renderShape(second.chunk))
        assertPositiveRanges(first.recoveryDiagnostics)
    }

    @Test
    fun expandsMultiDiagnosticIfMissingThenAndEndOrderedAndDeterministic() {
        val source = "if ready work() print(\"tail\")"
        val first = parseWithDiagnosticsWithoutThrow(source, version = LuaVersion.LUA_5_3)
        val second = parseWithDiagnosticsWithoutThrow(source, version = LuaVersion.LUA_5_3)

        val messages = first.recoveryDiagnostics.map { it.message }
        assertTrue(
            messages.any { it.contains("The <then> expected") },
            "expected then diagnostic; got $messages"
        )
        assertTrue(
            messages.any { it.contains("<end> expected") },
            "expected end diagnostic; got $messages"
        )
        // Order: then is discovered before end during parse.
        val thenIdx = messages.indexOfFirst { it.contains("The <then> expected") }
        val endIdx = messages.indexOfFirst { it.contains("<end> expected") }
        assertTrue(thenIdx in 0 until endIdx, "then should precede end; messages=$messages")

        assertEquals(first.recoveryDiagnostics, second.recoveryDiagnostics)
        assertEquals(renderShape(first.chunk), renderShape(second.chunk))
        assertPositiveRanges(first.recoveryDiagnostics)
    }

    @Test
    fun expandsTableMissingCloseBraceDiagnosticFamily() {
        val source = "local t = { a = 1"
        val result = parseWithDiagnosticsWithoutThrow(source, version = LuaVersion.LUA_5_3)
        assertTrue(
            result.recoveryDiagnostics.any { it.message.contains("'}' expected") },
            "expected '}' diagnostic; got ${result.recoveryDiagnostics.map { it.message }}"
        )
        assertPositiveRanges(result.recoveryDiagnostics)
        assertTrue(
            renderShape(result.chunk).contains("Table(") || renderShape(result.chunk).contains("Local("),
            renderShape(result.chunk)
        )
        assertDeterministic(source, version = LuaVersion.LUA_5_3)
    }

    @Test
    fun expandsIndexExpressionMissingCloseBracketDiagnosticFamily() {
        val source = "local v = t[1\nprint(v)"
        val result = parseWithDiagnosticsWithoutThrow(source, version = LuaVersion.LUA_5_3)
        assertTrue(
            result.recoveryDiagnostics.any { it.message.contains("']' expected") },
            "expected ']' diagnostic; got ${result.recoveryDiagnostics.map { it.message }}"
        )
        assertPositiveRanges(result.recoveryDiagnostics)
        assertTrue(
            renderShape(result.chunk).contains("CallStmt(Call(Id(print):Id(v)))") ||
                renderShape(result.chunk).contains("Index("),
            renderShape(result.chunk)
        )
        assertDeterministic(source, version = LuaVersion.LUA_5_3)
    }

    @Test
    fun expandsAndroluaArrayMissingCloseBracketDiagnosticFamily() {
        val source = "local a = [1, 2\nprint(a)"
        val result = parseWithDiagnosticsWithoutThrow(source, version = LuaVersion.ANDROLUA_5_3)
        assertTrue(
            result.recoveryDiagnostics.any { it.message.contains("']' expected") },
            "expected ']' diagnostic for array constructor; got ${result.recoveryDiagnostics.map { it.message }}"
        )
        assertPositiveRanges(result.recoveryDiagnostics)
        val shape = renderShape(result.chunk)
        assertTrue(shape.contains("Array(") || shape.contains("Local("), shape)
        assertDeterministic(source, version = LuaVersion.ANDROLUA_5_3)
    }

    @Test
    fun expandsMultiLineMissingThenDiagnosticRangeAnchorsOnBodyTokenLine() {
        // Multi-line control-flow: missing then near body token on line 2.
        val source = "if ready\nprint('x') end"
        val result = parseWithDiagnosticsWithoutThrow(source, version = LuaVersion.LUA_5_3)
        val thenDiag = result.recoveryDiagnostics.firstOrNull {
            it.message.contains("The <then> expected")
        }
        assertTrue(thenDiag != null, "expected then diagnostic; got ${result.recoveryDiagnostics.map { it.message }}")
        assertTrue(
            thenDiag!!.range.start.line == 2,
            "then range should anchor on body line 2; range=${thenDiag.range}"
        )
        assertTrue(
            thenDiag.message.contains("print"),
            "near-token should be print; message=${thenDiag.message}"
        )
        assertPositiveRanges(result.recoveryDiagnostics)
        assertTrue(
            renderShape(result.chunk).contains("CallStmt(Call(Id(print):Const('x')))"),
            renderShape(result.chunk)
        )
        assertDeterministic(source, version = LuaVersion.LUA_5_3)
    }

    // -------------------------------------------------------------------------
    // Cross-cutting contracts
    // -------------------------------------------------------------------------

    @Test
    fun expandsSparseDiagnosticsDoNotEmitStdoutNoise() {
        val noisySources = listOf(
            "if ready print('x') end",
            "while ready print('x') end",
            "for i=1,10 print(i) end",
            "repeat work() print(1)",
            "goto",
            "a, b\nprint(a)",
            "switch mode case 1 one() end",
            "local t = { a = 1",
            "use(k\nprint(t)"
        )
        noisySources.forEach { source ->
            val (result, stdout) = captureStdout {
                LuaParser(luaVersion = LuaVersion.ANDROLUA_5_3, errorRecovery = true)
                    .parseWithDiagnostics(source)
            }
            assertEquals("", stdout, "recovery diagnostics must stay structured (no stdout) for: $source")
            assertTrue(
                result.recoveryDiagnostics.isNotEmpty() ||
                    renderShape(result.chunk).contains("ExpressionNodeSupport") ||
                    renderShape(result.chunk).contains("Goto(") ||
                    renderShape(result.chunk).contains("Assign(") ||
                    renderShape(result.chunk).contains("Switch(") ||
                    renderShape(result.chunk).contains("Call("),
                "expected residual recovery surface for: $source"
            )
        }
    }

    @Test
    fun expandsDiagnosticsAreDeterministicAndResetAcrossParses() {
        val parser = LuaParser(luaVersion = LuaVersion.ANDROLUA_5_3, errorRecovery = true)
        val malformed = "if ready print('x') end"

        val first = parser.parseWithDiagnostics(malformed)
        val second = parser.parseWithDiagnostics(malformed)
        val valid = parser.parseWithDiagnostics("print('ok')")

        assertEquals(first.recoveryDiagnostics, second.recoveryDiagnostics)
        assertEquals(
            listOf(
                Diag(
                    message = "The <then> expected near print",
                    range = Range(Position(1, 10), Position(1, 15))
                )
            ),
            first.recoveryDiagnostics.map(::toDiag)
        )
        assertEquals(emptyList(), valid.recoveryDiagnostics)
        assertEquals(emptyList(), parser.recoveryDiagnostics)
    }

    @Test
    fun expandsWellFormedSourcesEmitNoRecoveryDiagnosticsAcrossSuites() {
        val wellFormed = listOf(
            WellFormed("if ready then work() end", LuaVersion.LUA_5_3, "If("),
            WellFormed("while ready do print(ready) end", LuaVersion.LUA_5_3, "While("),
            WellFormed("for i=1,10 do print(i) end", LuaVersion.LUA_5_3, "ForNumeric("),
            WellFormed("for k,v in pairs(t) do use(k,v) end", LuaVersion.LUA_5_3, "ForGeneric("),
            WellFormed("repeat work() until done", LuaVersion.LUA_5_3, "Repeat("),
            WellFormed("do work() end", LuaVersion.LUA_5_3, "Do("),
            WellFormed("::again:: goto again", LuaVersion.LUA_5_3, "Goto("),
            WellFormed("local value = 1", LuaVersion.LUA_5_3, "Local("),
            WellFormed("a, b = 1, 2", LuaVersion.LUA_5_3, "Assign("),
            WellFormed("local t = { a = 1 }", LuaVersion.LUA_5_3, "Table("),
            WellFormed("local f = lambda x -> x + 1", LuaVersion.ANDROLUA_5_3, "Lambda("),
            WellFormed(
                "switch mode do case 1 then one() default two() end",
                LuaVersion.ANDROLUA_5_3,
                "Switch("
            ),
            WellFormed("when ready print(1) else print(2)", LuaVersion.ANDROLUA_5_3, "When("),
            WellFormed("local x <const> = 1", LuaVersion.LUA_5_4, "Local(")
        )

        wellFormed.forEach { case ->
            val result = parseWithDiagnosticsWithoutThrow(case.source, version = case.version)
            assertEquals(
                emptyList(),
                result.recoveryDiagnostics.map { it.message },
                "well-formed source must not emit recovery diagnostics: ${case.source}"
            )
            assertTrue(
                renderShape(result.chunk).contains(case.shapeFragment),
                "well-formed shape for ${case.source}:\n${renderShape(result.chunk)}"
            )
        }
    }

    @Test
    fun expandsSparseGoldenInventoryCoversRequiredRecoveryFamilies() {
        val inventory = sparseGoldenInventory()
        assertEquals(27, inventory.size)

        val names = inventory.map { it.name }.toSet()
        assertTrue(names.any { it.contains("if") && it.contains("then") })
        assertTrue(names.any { it.contains("while") && it.contains("do") })
        assertTrue(names.any { it.contains("for") && it.contains("do") })
        assertTrue(names.any { it.contains("for") && it.contains("in") })
        assertTrue(names.any { it.contains("repeat") || it.contains("until") })
        assertTrue(names.any { it.contains("goto") || it.contains("label") })
        assertTrue(names.any { it.contains("assign") || it.contains("local") })
        assertTrue(names.any { it.contains("call") || it.contains("paren") })
        assertTrue(names.any { it.contains("dual-path") || it.contains("placeholder") })
        assertTrue(names.any { it.contains("multi") })
        assertTrue(names.any { it.contains("attribute") || it.contains("lambda") || it.contains("table") })
        assertTrue(names.any { it.contains("switch") || it.contains("array") || it.contains("index") })
        assertTrue(names.any { it.contains("do missing") || it.contains("while missing end") })
        assertTrue(names.any { it.contains("unary") })

        // Every inventory case must recover without throw and stay deterministic.
        inventory.forEach { case ->
            val first = parseWithDiagnosticsWithoutThrow(case.source, version = case.version)
            val second = parseWithDiagnosticsWithoutThrow(case.source, version = case.version)
            assertEquals(
                first.recoveryDiagnostics.map(::toDiag),
                second.recoveryDiagnostics.map(::toDiag),
                "${case.name} diagnostics must be deterministic"
            )
            assertEquals(
                renderShape(first.chunk),
                renderShape(second.chunk),
                "${case.name} shape must be deterministic"
            )
            assertPositiveRanges(first.recoveryDiagnostics)
            case.requiredMessageFragments.forEach { fragment ->
                if (case.allowEmptyDiagnostics) {
                    // dual-path: empty OR fragment present
                    val messages = first.recoveryDiagnostics.map { it.message }
                    assertTrue(
                        messages.isEmpty() || messages.any { it.contains(fragment) },
                        "${case.name} dual-path fragment '$fragment'; messages=$messages"
                    )
                } else {
                    assertTrue(
                        first.recoveryDiagnostics.any { it.message.contains(fragment) },
                        "${case.name} should emit message containing '$fragment'; " +
                            "actual=${first.recoveryDiagnostics.map { it.message }}"
                    )
                }
            }
            case.requiredShapeFragments.forEach { fragment ->
                assertTrue(
                    renderShape(first.chunk).contains(fragment),
                    "${case.name} shape should contain '$fragment':\n${renderShape(first.chunk)}"
                )
            }
        }
    }

    @Test
    fun expandsParserReuseDoesNotLeakDiagnosticsAcrossVersions() {
        // Sequential parseWithDiagnostics on distinct parsers must stay isolated.
        val andro = LuaParser(luaVersion = LuaVersion.ANDROLUA_5_3, errorRecovery = true)
            .parseWithDiagnostics("switch mode case 1 one() end")
        val lua53 = LuaParser(luaVersion = LuaVersion.LUA_5_3, errorRecovery = true)
            .parseWithDiagnostics("if ready print('x') end")
        val lua54 = LuaParser(luaVersion = LuaVersion.LUA_5_4, errorRecovery = true)
            .parseWithDiagnostics("local x <const = 1")

        assertTrue(andro.recoveryDiagnostics.any { it.message.contains("The <do> expected") })
        assertTrue(lua53.recoveryDiagnostics.any { it.message.contains("The <then> expected") })
        assertTrue(lua54.recoveryDiagnostics.any { it.message.contains("'>' expected") })
        assertTrue(andro.recoveryDiagnostics.size >= 1)
        assertTrue(lua53.recoveryDiagnostics.size >= 1)
        assertTrue(lua54.recoveryDiagnostics.size >= 1)
        assertPositiveRanges(andro.recoveryDiagnostics + lua53.recoveryDiagnostics + lua54.recoveryDiagnostics)
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private fun sparseGoldenInventory(): List<SparseCase> {
        return listOf(
            SparseCase(
                name = "if missing then exact",
                source = "if ready print('x') end",
                version = LuaVersion.ANDROLUA_5_3,
                requiredMessageFragments = listOf("The <then> expected"),
                requiredShapeFragments = listOf("CallStmt(Call(Id(print):Const('x')))")
            ),
            SparseCase(
                name = "while missing do exact",
                source = "while ready print('x') end",
                version = LuaVersion.ANDROLUA_5_3,
                requiredMessageFragments = listOf("The <do> expected"),
                requiredShapeFragments = listOf("While(")
            ),
            SparseCase(
                name = "elseif missing then",
                source = "if first then one() elseif second two() else three() end",
                version = LuaVersion.LUA_5_3,
                requiredMessageFragments = listOf("The <then> expected"),
                requiredShapeFragments = listOf("ElseIf(")
            ),
            SparseCase(
                name = "numeric for missing do",
                source = "for i=1,10 print(i) end",
                version = LuaVersion.LUA_5_3,
                requiredMessageFragments = listOf("The <do> expected"),
                requiredShapeFragments = listOf("ForNumeric(")
            ),
            SparseCase(
                name = "generic for missing do",
                source = "for k, v in pairs(t) use(k, v) end",
                version = LuaVersion.LUA_5_3,
                requiredMessageFragments = listOf("The <do> expected"),
                requiredShapeFragments = listOf("ForGeneric(")
            ),
            SparseCase(
                name = "generic for missing in",
                source = "for k, v pairs(t) do use(k, v) end",
                version = LuaVersion.LUA_5_3,
                requiredMessageFragments = listOf("The <in> expected"),
                requiredShapeFragments = listOf("ForGeneric(")
            ),
            SparseCase(
                name = "repeat missing until",
                source = "repeat work() print(1)",
                version = LuaVersion.LUA_5_3,
                requiredMessageFragments = listOf("'until' expected"),
                requiredShapeFragments = listOf("Repeat(")
            ),
            SparseCase(
                name = "if missing end at eof",
                source = "if ready then work()",
                version = LuaVersion.LUA_5_3,
                requiredMessageFragments = listOf("<end> expected"),
                requiredShapeFragments = listOf("If(")
            ),
            SparseCase(
                name = "while missing end at eof",
                source = "while ready do work()",
                version = LuaVersion.LUA_5_3,
                requiredMessageFragments = listOf("<end> expected"),
                requiredShapeFragments = listOf("While(")
            ),
            SparseCase(
                name = "do missing end at eof",
                source = "do work() print(1)",
                version = LuaVersion.LUA_5_3,
                requiredMessageFragments = listOf("<end> expected"),
                requiredShapeFragments = listOf("Do(")
            ),
            SparseCase(
                name = "goto missing name",
                source = "goto",
                version = LuaVersion.LUA_5_3,
                requiredMessageFragments = listOf("<name> expected"),
                requiredShapeFragments = listOf("Goto(Id())")
            ),
            SparseCase(
                name = "label missing closing colons",
                source = "::again",
                version = LuaVersion.LUA_5_3,
                requiredMessageFragments = listOf("'::' expected"),
                requiredShapeFragments = listOf("Label(Id(again))")
            ),
            SparseCase(
                name = "assignment missing equals",
                source = "a, b\nprint(a)",
                version = LuaVersion.LUA_5_3,
                requiredMessageFragments = listOf("'=' expected"),
                requiredShapeFragments = listOf("CallStmt(Call(Id(print):Id(a)))")
            ),
            SparseCase(
                // Line-break residual (not bare top-level `end`) keeps print sibling and
                // avoids chunk EOF mismatch after END block terminator recovery.
                name = "call missing close paren",
                source = "use(k\nprint(t)",
                version = LuaVersion.LUA_5_3,
                requiredMessageFragments = listOf("')' expected"),
                requiredShapeFragments = listOf("Call(Id(use):")
            ),
            SparseCase(
                name = "local missing name",
                source = "local =\nlocal after = 1",
                version = LuaVersion.LUA_5_3,
                requiredMessageFragments = listOf("<name> expected"),
                requiredShapeFragments = listOf("Local(Id(after)=Const(1))")
            ),
            SparseCase(
                name = "dual-path local missing initializer placeholder",
                source = "local value =\nreturn value",
                version = LuaVersion.ANDROLUA_5_3,
                requiredMessageFragments = listOf("<expression> expected"),
                requiredShapeFragments = listOf("ExpressionNodeSupport"),
                allowEmptyDiagnostics = true
            ),
            SparseCase(
                name = "dual-path assignment missing rhs placeholder",
                source = "a =\nprint(a)",
                version = LuaVersion.LUA_5_3,
                requiredMessageFragments = emptyList(),
                requiredShapeFragments = listOf("Assign(Id(a)=ExpressionNodeSupport)"),
                allowEmptyDiagnostics = true
            ),
            SparseCase(
                name = "dual-path binary rhs placeholder",
                source = "a = value +\nprint(a)",
                version = LuaVersion.LUA_5_3,
                requiredMessageFragments = emptyList(),
                requiredShapeFragments = listOf("Binary(+,Id(value),ExpressionNodeSupport)"),
                allowEmptyDiagnostics = true
            ),
            SparseCase(
                // Unary absorb residual: following print is operand (CURRENTLY_ACCEPTS).
                // No required diagnostic; shape pins absorb honesty.
                name = "dual-path unary missing operand absorb placeholder",
                source = "a = -\nprint(a)",
                version = LuaVersion.LUA_5_3,
                requiredMessageFragments = emptyList(),
                requiredShapeFragments = listOf("Unary(-,Call(Id(print):Id(a)))"),
                allowEmptyDiagnostics = true
            ),
            SparseCase(
                name = "multi then+end",
                source = "if ready work() print(\"tail\")",
                version = LuaVersion.LUA_5_3,
                requiredMessageFragments = listOf("The <then> expected", "<end> expected"),
                requiredShapeFragments = listOf("If(")
            ),
            SparseCase(
                name = "table missing close brace",
                source = "local t = { a = 1",
                version = LuaVersion.LUA_5_3,
                requiredMessageFragments = listOf("'}' expected"),
                requiredShapeFragments = listOf("Local(")
            ),
            SparseCase(
                name = "index missing close bracket",
                source = "local v = t[1\nprint(v)",
                version = LuaVersion.LUA_5_3,
                requiredMessageFragments = listOf("']' expected"),
                requiredShapeFragments = listOf("Local(")
            ),
            SparseCase(
                name = "lambda missing arrow family",
                source = "local f = lambda x 1",
                version = LuaVersion.ANDROLUA_5_3,
                requiredMessageFragments = listOf("expected"),
                requiredShapeFragments = listOf("Lambda(")
            ),
            SparseCase(
                name = "attribute missing gt",
                source = "local x <const = 1",
                version = LuaVersion.LUA_5_4,
                requiredMessageFragments = listOf("'>' expected"),
                requiredShapeFragments = listOf("Local(")
            ),
            SparseCase(
                name = "switch missing do",
                source = "switch mode case 1 one() default fallback() end",
                version = LuaVersion.ANDROLUA_5_3,
                requiredMessageFragments = listOf("The <do> expected"),
                requiredShapeFragments = listOf("Switch(")
            ),
            SparseCase(
                name = "switch missing end",
                source = "switch mode do case 1 one() default fallback()",
                version = LuaVersion.ANDROLUA_5_3,
                requiredMessageFragments = listOf("<end> expected"),
                requiredShapeFragments = listOf("Switch(")
            ),
            SparseCase(
                name = "array missing close bracket",
                source = "local a = [1, 2\nprint(a)",
                version = LuaVersion.ANDROLUA_5_3,
                requiredMessageFragments = listOf("']' expected"),
                requiredShapeFragments = listOf("Local(")
            )
        )
    }

    private fun assertExactDiagnostics(
        source: String,
        version: LuaVersion,
        expected: List<Diag>,
        requiredShapeFragments: List<String> = emptyList(),
    ) {
        val (result, stdout) = captureStdout {
            LuaParser(luaVersion = version, errorRecovery = true).parseWithDiagnostics(source)
        }
        assertEquals("", stdout, "stdout must stay empty for: $source")
        assertEquals(
            expected,
            result.recoveryDiagnostics.map(::toDiag),
            "exact diagnostic goldens for: $source"
        )
        val shape = renderShape(result.chunk)
        requiredShapeFragments.forEach { fragment ->
            assertTrue(shape.contains(fragment), "shape should contain '$fragment':\n$shape")
        }
        assertDeterministic(source, version)
    }

    private fun assertDeterministic(source: String, version: LuaVersion) {
        val first = parseWithDiagnosticsWithoutThrow(source, version = version)
        val second = parseWithDiagnosticsWithoutThrow(source, version = version)
        assertEquals(
            first.recoveryDiagnostics.map(::toDiag),
            second.recoveryDiagnostics.map(::toDiag),
            "diagnostics must be deterministic for: $source"
        )
        assertEquals(
            renderShape(first.chunk),
            renderShape(second.chunk),
            "shape must be deterministic for: $source"
        )
    }

    private fun assertPositiveRanges(diagnostics: List<LuaParserRecoveryDiagnostic>) {
        diagnostics.forEach { diagnostic ->
            assertTrue(diagnostic.message.isNotBlank(), "diagnostic message must be non-blank")
            assertTrue(
                diagnostic.range.start.line >= 1 && diagnostic.range.start.column >= 1,
                "range start must be positive: ${diagnostic.range}"
            )
            assertTrue(
                diagnostic.range.end.line >= diagnostic.range.start.line,
                "range end line must be >= start: ${diagnostic.range}"
            )
            if (diagnostic.range.end.line == diagnostic.range.start.line) {
                assertTrue(
                    diagnostic.range.end.column >= diagnostic.range.start.column,
                    "same-line end column must be >= start: ${diagnostic.range}"
                )
            }
        }
    }

    private fun parseWithDiagnosticsWithoutThrow(
        source: String,
        version: LuaVersion = LuaVersion.LUA_5_3,
    ): LuaParseResult {
        return try {
            LuaParser(luaVersion = version, errorRecovery = true).parseWithDiagnostics(source)
        } catch (failure: Throwable) {
            throw AssertionError("recovery parseWithDiagnostics must not throw for: $source", failure)
        }
    }

    private fun toDiag(diagnostic: LuaParserRecoveryDiagnostic): Diag {
        return Diag(message = diagnostic.message, range = diagnostic.range)
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
        val stdout: String,
    )

    private data class Diag(
        val message: String,
        val range: Range,
    )

    private data class WellFormed(
        val source: String,
        val version: LuaVersion,
        val shapeFragment: String,
    )

    private data class SparseCase(
        val name: String,
        val source: String,
        val version: LuaVersion = LuaVersion.LUA_5_3,
        val requiredMessageFragments: List<String> = emptyList(),
        val requiredShapeFragments: List<String> = emptyList(),
        val allowEmptyDiagnostics: Boolean = false,
    )
}
