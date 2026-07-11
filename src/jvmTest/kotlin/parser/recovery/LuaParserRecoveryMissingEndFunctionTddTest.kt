package parser.recovery

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.LuaParseResult
import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.AssignmentStatement
import io.github.dingyi222666.luaparser.parser.ast.node.CallStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.parser.ast.node.MemberExpression
import io.github.dingyi222666.luaparser.parser.ast.node.ReturnStatement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import parser.assertParseFails
import parser.parse
import parser.renderShape

/**
 * Dual-path recovery corpus for function definitions missing `end`
 * (TASK-482; expands TASK-462 [LuaParserRecoveryFunctionMissingEndTddTest] and the
 * sparse fixtures in [LuaParserRecoveryTddTest] with residual control-flow bodies,
 * vararg/table/call-argument placements, and honest CURRENTLY_ACCEPTS dual-path
 * footguns).
 *
 * Shape notes (from [parser.renderShape]):
 * - IfClause → Clause(...); ElseClause → Else(...); body `...` → Vararg
 *
 * Complements:
 * - [LuaParserRecoveryFunctionMissingEndTddTest] global/local/method/anonymous core
 * - [LuaParserRecoveryTddTest] "function body missing end should return declaration
 *   with body" / "anonymous function missing end should keep function expression body"
 *
 * Coverage families:
 * - residual control-flow bodies (if / while / do / repeat) inside missing-end functions
 * - vararg params and multi-return residual bodies
 * - function expressions in table fields / call arguments missing `end`
 * - nested functions with shared/partial `end` residual layouts
 * - incomplete residual expressions + dual-path CURRENTLY_ACCEPTS footguns
 * - well-formed functions remain clean under recovery
 * - strict-parse REJECTS for incomplete missing-end sources
 *
 * Test-only; no production LuaParser edits unless review re-scopes.
 *
 * Product goldens (aligned with [LuaParser.parseFunctionBody] /
 * [LuaParser.parseGlobalFunctionDeclaration] /
 * [LuaParser.parseLocalFunctionDeclaration] /
 * [LuaParser.parseFunctionExp]):
 * - missing `end` recovers a FunctionDeclaration and emits
 *   `<end> expected (to close 'function' ...)`;
 * - without `end`, residual statements stay inside the function body (block stops
 *   only at terminators / EOF) — no top-level sibling after an unclosed function;
 * - nested function with a single shared `end` lets the inner function consume it;
 *   the outer then recovers with its own missing-end warning;
 * - incomplete binary RHS after line-break + statement-start inserts
 *   ExpressionNodeSupport under recovery; strict mode currently absorbs the next
 *   call as the binary operand (CURRENTLY_ACCEPTS dual-path when `end` is present).
 */
class LuaParserRecoveryMissingEndFunctionTddTest {

    @Test
    fun recoversMissingEndWithResidualControlFlowBodies() {
        assertSupportedCases(
            MissingEndFunctionCase(
                name = "global missing end keeps if body residual",
                source = "function guard(x) if x then use(x) end",
                requiredShapeFragments = listOf(
                    "Function(Id(guard),Block[If(Clause(Id(x):Block[CallStmt(Call(Id(use):Id(x)))])])])"
                ),
                warningFragments = listOf("<end> expected")
            ),
            MissingEndFunctionCase(
                name = "global missing end keeps while body residual",
                source = "function spin(n) while n do n = n - 1 end",
                requiredShapeFragments = listOf(
                    "Function(Id(spin),Block[While(Id(n):Block[Assign(Id(n)=Binary(-,Id(n),Const(1)))])])"
                ),
                warningFragments = listOf("<end> expected")
            ),
            MissingEndFunctionCase(
                name = "global missing end keeps do-end residual",
                source = "function boxed() do local t = 1 use(t) end",
                requiredShapeFragments = listOf(
                    "Function(Id(boxed),Block[Do(Block[Local(Id(t)=Const(1));CallStmt(Call(Id(use):Id(t)))])])"
                ),
                warningFragments = listOf("<end> expected")
            ),
            MissingEndFunctionCase(
                name = "global missing end keeps repeat-until residual",
                source = "function untilReady() repeat work() until ready",
                requiredShapeFragments = listOf(
                    "Function(Id(untilReady),Block[Repeat(Block[CallStmt(Call(Id(work):))]:Id(ready))])"
                ),
                warningFragments = listOf("<end> expected")
            ),
            MissingEndFunctionCase(
                name = "local missing end keeps if-else residual",
                source = "local function choose(f) if f then a() else b() end",
                requiredShapeFragments = listOf(
                    "Function(Id(choose),Block[If(Clause(Id(f):Block[CallStmt(Call(Id(a):))]),Else(Block[CallStmt(Call(Id(b):))])])])"
                ),
                warningFragments = listOf("<end> expected")
            )
        )
    }

    @Test
    fun recoversMissingEndWithVarargMultiReturnAndEmptyBodies() {
        assertSupportedCases(
            MissingEndFunctionCase(
                name = "global missing end keeps vararg param and multi-return",
                source = "function pack(...) return ...",
                requiredShapeFragments = listOf(
                    "Function(Id(pack),Block[Return(Vararg)])"
                ),
                warningFragments = listOf("<end> expected")
            ),
            MissingEndFunctionCase(
                name = "local missing end keeps vararg plus named params",
                source = "local function wrap(a, ...) return a, ...",
                requiredShapeFragments = listOf(
                    "Function(Id(wrap),Block[Return(Id(a),Vararg)])"
                ),
                warningFragments = listOf("<end> expected")
            ),
            MissingEndFunctionCase(
                name = "global missing end keeps multi-return residual",
                source = "function multi(a, b) return a, b, a + b",
                requiredShapeFragments = listOf(
                    "Function(Id(multi),Block[Return(Id(a),Id(b),Binary(+,Id(a),Id(b)))])"
                ),
                warningFragments = listOf("<end> expected")
            ),
            MissingEndFunctionCase(
                name = "global missing end keeps empty body at eof",
                source = "function empty()",
                requiredShapeFragments = listOf(
                    "Function(Id(empty),Block[])"
                ),
                warningFragments = listOf("<end> expected")
            ),
            MissingEndFunctionCase(
                name = "colon method missing end keeps self call residual",
                source = "function module:tick() self:work()",
                requiredShapeFragments = listOf(
                    "Function(Member(Id(module):tick),Block[CallStmt(Call(Member(Id(self):work):))])"
                ),
                warningFragments = listOf("<end> expected")
            ),
            MissingEndFunctionCase(
                name = "dot method missing end keeps multi-statement residual",
                source = "function module.run(x) local copy = x use(copy)",
                requiredShapeFragments = listOf(
                    "Function(Member(Id(module).run),Block[Local(Id(copy)=Id(x));CallStmt(Call(Id(use):Id(copy)))])"
                ),
                warningFragments = listOf("<end> expected")
            )
        )
    }

    @Test
    fun recoversMissingEndForFunctionExpressionsInTablesAndCalls() {
        assertSupportedCases(
            MissingEndFunctionCase(
                name = "table field function missing end keeps body",
                source = "local t = { run = function(x) use(x) }",
                requiredShapeFragments = listOf(
                    "TableKeyString(Id(run)=Function(null,Block[CallStmt(Call(Id(use):Id(x)))]))"
                ),
                warningFragments = listOf("<end> expected")
            ),
            MissingEndFunctionCase(
                name = "table indexed function missing end keeps body",
                source = "local t = { [key] = function() return 1 }",
                requiredShapeFragments = listOf(
                    "TableKey(Id(key)=Function(null,Block[Return(Const(1))]))"
                ),
                warningFragments = listOf("<end> expected")
            ),
            MissingEndFunctionCase(
                name = "call argument function missing end keeps body",
                source = "schedule(function(job) work(job)",
                requiredShapeFragments = listOf(
                    "CallStmt(Call(Id(schedule):Function(null,Block[CallStmt(Call(Id(work):Id(job)))])))"
                ),
                warningFragments = listOf("<end> expected", "')' expected")
            ),
            MissingEndFunctionCase(
                name = "anonymous assigned missing end keeps return residual",
                source = "local f = function(a, b) return a + b",
                requiredShapeFragments = listOf(
                    "Local(Id(f)=Function(null,Block[Return(Binary(+,Id(a),Id(b)))]))"
                ),
                warningFragments = listOf("<end> expected")
            ),
            MissingEndFunctionCase(
                name = "return anonymous missing end keeps nested return",
                source = "return function(v) return v",
                requiredShapeFragments = listOf(
                    "Function(null,Block[Return(Id(v))])"
                ),
                warningFragments = listOf("<end> expected")
            )
        )
    }

    @Test
    fun recoversNestedMissingEndFunctionForms() {
        assertSupportedCases(
            MissingEndFunctionCase(
                name = "nested missing outer end keeps closed inner and trailing call in outer body",
                source = "function outer() function inner() use() end print(outer)",
                requiredShapeFragments = listOf(
                    "Function(Id(outer),Block[Function(Id(inner),Block[CallStmt(Call(Id(use):))]);CallStmt(Call(Id(print):Id(outer)))])"
                ),
                warningFragments = listOf("<end> expected"),
                minFunctionNodes = 2,
                maxFunctionNodes = 2
            ),
            MissingEndFunctionCase(
                name = "nested both missing end at eof keeps both bodies",
                source = "function outer() function inner() use() print(done)",
                requiredShapeFragments = listOf(
                    "Function(Id(outer),Block[Function(Id(inner),Block[CallStmt(Call(Id(use):));CallStmt(Call(Id(print):Id(done)))])])"
                ),
                warningFragments = listOf("<end> expected"),
                minFunctionNodes = 2,
                maxFunctionNodes = 2
            ),
            MissingEndFunctionCase(
                name = "nested missing both ends with only one end: end closes inner first",
                source = "function outer() function inner() use() end",
                requiredShapeFragments = listOf(
                    "Function(Id(outer),Block[Function(Id(inner),Block[CallStmt(Call(Id(use):))])])"
                ),
                warningFragments = listOf("<end> expected"),
                minFunctionNodes = 2,
                maxFunctionNodes = 2
            ),
            MissingEndFunctionCase(
                name = "nested local inside global missing outer end keeps closed local",
                source = "function outer() local function inner() return 1 end print(outer)",
                requiredShapeFragments = listOf(
                    "Function(Id(outer),Block[Function(Id(inner),Block[Return(Const(1))]);CallStmt(Call(Id(print):Id(outer)))])"
                ),
                warningFragments = listOf("<end> expected"),
                minFunctionNodes = 2,
                maxFunctionNodes = 2
            ),
            MissingEndFunctionCase(
                name = "nested three-level missing ends keeps partial AST",
                source = "function a() function b() function c() tick()",
                requiredShapeFragments = listOf(
                    "Function(Id(a),Block[Function(Id(b),Block[Function(Id(c),Block[CallStmt(Call(Id(tick):))])])])"
                ),
                warningFragments = listOf("<end> expected"),
                minFunctionNodes = 3,
                maxFunctionNodes = 3
            )
        )
    }

    @Test
    fun recoversIncompleteBodiesAfterMissingEnd() {
        assertSupportedCases(
            MissingEndFunctionCase(
                name = "missing end with incomplete assignment body keeps placeholder",
                source = "function bump() total = total +",
                requiredShapeFragments = listOf(
                    "Function(Id(bump),Block[Assign(Id(total)=Binary(+,Id(total),ExpressionNodeSupport))])"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                warningFragments = listOf("<end> expected")
            ),
            MissingEndFunctionCase(
                name = "missing end with incomplete call body keeps partial call",
                source = "function run() use(x",
                requiredShapeFragments = listOf(
                    "Function(Id(run),"
                ),
                warningFragments = listOf("<end> expected", "')' expected")
            ),
            MissingEndFunctionCase(
                name = "missing end with incomplete local initializer keeps placeholder",
                source = "function run() local copy =",
                requiredShapeFragments = listOf(
                    "Function(Id(run),Block[Local(Id(copy)=ExpressionNodeSupport)])"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                warningFragments = listOf("<end> expected")
            ),
            MissingEndFunctionCase(
                name = "missing end with incomplete return body keeps placeholder",
                source = "function run() return x +",
                requiredShapeFragments = listOf(
                    "Function(Id(run),Block[Return(Binary(+,Id(x),ExpressionNodeSupport))])"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                warningFragments = listOf("<end> expected")
            ),
            MissingEndFunctionCase(
                name = "missing end with incomplete concat return keeps placeholder",
                source = "function run() return a ..",
                requiredShapeFragments = listOf(
                    "Function(Id(run),Block[Return(Binary(..,Id(a),ExpressionNodeSupport))])"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                warningFragments = listOf("<end> expected")
            ),
            MissingEndFunctionCase(
                name = "anonymous missing end with incomplete binary keeps placeholder",
                source = "return function() return value +",
                requiredShapeFragments = listOf(
                    "Function(null,Block[Return(Binary(+,Id(value),ExpressionNodeSupport))])"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                warningFragments = listOf("<end> expected")
            )
        )
    }

    @Test
    fun exposesTypedFunctionStructureAfterMissingEndRecovery() {
        val chunk = parseRecoveringWithoutThrow(
            "function broken(a, b) local t = a work(t, b)"
        )

        val decl = assertIs<FunctionDeclaration>(chunk.body.statements.single())
        assertFalse(decl.isLocal)
        assertEquals("Id(broken)", renderShape(assertNotNull(decl.identifier)))
        assertEquals(2, decl.params.size)
        assertEquals("a", decl.params[0].name)
        assertEquals("b", decl.params[1].name)
        assertEquals(2, assertNotNull(decl.body).statements.size)
        assertIs<LocalStatement>(decl.body!!.statements[0])
        assertIs<CallStatement>(decl.body!!.statements[1])
    }

    @Test
    fun exposesTypedLocalMethodAndAnonymousStructureAfterMissingEndRecovery() {
        val localChunk = parseRecoveringWithoutThrow(
            "local function helper(v) return v"
        )
        val localDecl = assertIs<FunctionDeclaration>(localChunk.body.statements.single())
        assertTrue(localDecl.isLocal)
        assertEquals("Id(helper)", renderShape(assertNotNull(localDecl.identifier)))
        assertIs<ReturnStatement>(localDecl.body!!.returnStatement)

        val methodChunk = parseRecoveringWithoutThrow(
            "function module:run(x) return self, x"
        )
        val methodDecl = assertIs<FunctionDeclaration>(methodChunk.body.statements.single())
        assertFalse(methodDecl.isLocal)
        val member = assertIs<MemberExpression>(methodDecl.identifier)
        assertEquals(":", member.indexer)
        assertEquals("run", member.identifier.name)
        assertIs<Identifier>(member.base)

        val anonChunk = parseRecoveringWithoutThrow(
            "return function(a) return a"
        )
        val anon = assertIs<FunctionDeclaration>(
            assertNotNull(anonChunk.body.returnStatement).arguments.single()
        )
        assertNull(anon.identifier)
        assertFalse(anon.isLocal)
    }

    @Test
    fun recoveryDiagnosticsArePresentDeterministicAndDoNotThrow() {
        val sources = listOf(
            "function guard(x) if x then use(x) end",
            "local function wrap(a, ...) return a, ...",
            "function module:tick() self:work()",
            "function outer() function inner() use() end",
            "function bump() total = total +",
            "local t = { run = function(x) use(x) }",
            "return function(a) return a"
        )

        sources.forEach { source ->
            val first = parseWithDiagnosticsWithoutThrow(source, attempt = "first")
            val second = parseWithDiagnosticsWithoutThrow(source, attempt = "second")

            assertEquals(
                first.recoveryDiagnostics.map { it.message },
                second.recoveryDiagnostics.map { it.message },
                "diagnostic messages should be deterministic for: $source"
            )
            assertEquals(
                renderShape(first.chunk),
                renderShape(second.chunk),
                "recovered shape should be deterministic for: $source"
            )
            assertTrue(
                first.recoveryDiagnostics.any { it.message.contains("<end> expected") },
                "expected <end> recovery diagnostic for: $source; actual=${first.recoveryDiagnostics.map { it.message }}"
            )
            first.recoveryDiagnostics.forEach { diagnostic ->
                assertTrue(diagnostic.message.isNotBlank(), "diagnostic message must be non-blank")
                assertTrue(
                    diagnostic.range.start.line >= 1 && diagnostic.range.start.column >= 1,
                    "diagnostic range start must be positive: ${diagnostic.range}"
                )
            }
        }
    }

    @Test
    fun dualPathStrictAcceptsIncompleteBinaryInsideFunctionWithNextLineStatement() {
        // Dual-path footguns when `end` is present: recovery inserts ExpressionNodeSupport
        // for the missing binary right and keeps next-line print as a sibling CallStmt in
        // the function body; strict mode currently absorbs print(...) as the binary right
        // operand and accepts the chunk.
        assertSupportedCases(
            MissingEndFunctionCase(
                name = "body incomplete binary assign next-line print: recovery sibling, strict absorbs",
                source = "function bump() total = total +\nprint(total) end",
                requiredShapeFragments = listOf(
                    "Assign(Id(total)=Binary(+,Id(total),ExpressionNodeSupport))",
                    "CallStmt(Call(Id(print):Id(total)))"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
            ),
            MissingEndFunctionCase(
                name = "body incomplete comparison assign next-line print: recovery sibling, strict absorbs",
                source = "function check(a) ok = a ==\nprint(ok) end",
                requiredShapeFragments = listOf(
                    "Assign(Id(ok)=Binary(==,Id(a),ExpressionNodeSupport))",
                    "CallStmt(Call(Id(print):Id(ok)))"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
            ),
            MissingEndFunctionCase(
                name = "body incomplete concat assign next-line print: recovery sibling, strict absorbs",
                source = "function join(a) text = a ..\nprint(text) end",
                requiredShapeFragments = listOf(
                    "Assign(Id(text)=Binary(..,Id(a),ExpressionNodeSupport))",
                    "CallStmt(Call(Id(print):Id(text)))"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
            ),
            MissingEndFunctionCase(
                name = "local body incomplete binary next-line print: recovery sibling, strict absorbs",
                source = "local function bump() total = total +\nprint(total) end",
                requiredShapeFragments = listOf(
                    "Assign(Id(total)=Binary(+,Id(total),ExpressionNodeSupport))",
                    "CallStmt(Call(Id(print):Id(total)))"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
            ),
            MissingEndFunctionCase(
                // Unary recovery only placeholders on expression terminators. A following
                // NAME/call is an expression start, so both recovery and strict absorb
                // print as the unary operand — honest CURRENTLY_ACCEPTS dual-path.
                name = "body incomplete unary return absorbs following print on both paths",
                source = "function run(k) return not\nprint(k) end",
                requiredShapeFragments = listOf(
                    "Return(Unary(not,Call(Id(print):Id(k))))"
                ),
                strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
            )
        )
    }

    @Test
    fun dualPathStrictRejectsMissingEndWhileRecordingAcceptGaps() {
        val rejects = requiredCases().filter {
            it.strictParseExpectation == StrictParseExpectation.REJECTS
        }
        assertTrue(rejects.isNotEmpty(), "inventory must retain REJECTS incomplete missing-end function cases")
        rejects.forEach { case ->
            parseRecoveringWithoutThrow(case.source, case.version)
            val fail1 = assertParseFails(case.version, case.source, recovery = false)
            val fail2 = assertParseFails(case.version, case.source, recovery = false)
            assertEquals(fail1::class, fail2::class, "${case.name} strict failure type")
            assertEquals(fail1.message, fail2.message, "${case.name} strict failure message")
        }

        val accepts = requiredCases().filter {
            it.strictParseExpectation == StrictParseExpectation.CURRENTLY_ACCEPTS
        }
        assertTrue(
            accepts.map { it.name }.any { it.contains("strict absorbs") || it.contains("both paths") },
            "inventory should document at least one CURRENTLY_ACCEPTS dual-path footgun"
        )
        accepts.forEach { case ->
            val first = parse(case.version, case.source, recovery = false)
            val second = parse(case.version, case.source, recovery = false)
            assertEquals(renderShape(first), renderShape(second), "${case.name} strict accepted shape")
        }
    }

    @Test
    fun wellFormedFunctionsStillParseCleanlyUnderRecovery() {
        val wellFormed = listOf(
            "function guard(x) if x then use(x) end end",
            "local function wrap(a, ...) return a, ... end\nprint(wrap)",
            "function module:tick() self:work() end",
            "function outer() function inner() use() end end\nprint(outer)",
            "return function(a) return a end",
            "local f = function(x) use(x) end\nprint(f)",
            "local t = { run = function(x) use(x) end }"
        )

        wellFormed.forEach { source ->
            val result = parseWithDiagnosticsWithoutThrow(source)
            assertEquals(
                emptyList(),
                result.recoveryDiagnostics.map { it.message },
                "well-formed function should not emit recovery diagnostics: $source"
            )
            assertTrue(
                renderShape(result.chunk).contains("Function("),
                "well-formed source should produce Function shape: $source"
            )
            val strict = parse(LuaVersion.LUA_5_3, source, recovery = false)
            assertEquals(renderShape(result.chunk), renderShape(strict))
        }
    }

    @Test
    fun corpusInventoryCoversRequiredMissingEndFunctionFamilies() {
        val inventory = requiredCases()
        assertEquals(31, inventory.size)

        val names = inventory.map { it.name }.toSet()
        assertTrue(names.any { it.contains("if") || it.contains("while") || it.contains("repeat") || it.contains("do-end") })
        assertTrue(names.any { it.contains("vararg") || it.contains("multi-return") })
        assertTrue(names.any { it.contains("table") || it.contains("call argument") || it.contains("anonymous") })
        assertTrue(names.any { it.contains("nested") })
        assertTrue(names.any { it.contains("incomplete") })
        assertTrue(names.any { it.contains("method") || it.contains("colon") || it.contains("dot") })
        assertTrue(
            inventory.any { it.strictParseExpectation == StrictParseExpectation.CURRENTLY_ACCEPTS },
            "inventory must include dual-path CURRENTLY_ACCEPTS cases"
        )
        assertTrue(
            inventory.count { it.strictParseExpectation == StrictParseExpectation.CURRENTLY_ACCEPTS } >= 5,
            "expected at least five dual-path CURRENTLY_ACCEPTS footguns"
        )
        assertTrue(
            inventory.any { it.strictParseExpectation == StrictParseExpectation.REJECTS },
            "inventory must retain REJECTS incomplete missing-end function cases"
        )
        assertTrue(
            inventory.any { case ->
                case.warningFragments.any { it.contains("<end> expected") }
            },
            "inventory must cover missing-end diagnostics"
        )
        assertTrue(
            inventory.filter {
                it.strictParseExpectation == StrictParseExpectation.REJECTS &&
                    it.warningFragments.any { w -> w.contains("<end> expected") }
            }.size >= 18,
            "expected a broad REJECTS missing-end corpus"
        )
    }

    // --- helpers -----------------------------------------------------------------

    private fun requiredCases(): List<MissingEndFunctionCase> {
        return listOf(
            MissingEndFunctionCase(
                name = "global missing end keeps if body residual",
                source = "function guard(x) if x then use(x) end",
                requiredShapeFragments = listOf("If(Clause(Id(x):"),
                warningFragments = listOf("<end> expected")
            ),
            MissingEndFunctionCase(
                name = "global missing end keeps while body residual",
                source = "function spin(n) while n do n = n - 1 end",
                requiredShapeFragments = listOf("While(Id(n):"),
                warningFragments = listOf("<end> expected")
            ),
            MissingEndFunctionCase(
                name = "global missing end keeps do-end residual",
                source = "function boxed() do local t = 1 use(t) end",
                requiredShapeFragments = listOf("Do(Block[Local(Id(t)=Const(1));"),
                warningFragments = listOf("<end> expected")
            ),
            MissingEndFunctionCase(
                name = "global missing end keeps repeat-until residual",
                source = "function untilReady() repeat work() until ready",
                requiredShapeFragments = listOf("Repeat(Block[CallStmt(Call(Id(work):))]:Id(ready))"),
                warningFragments = listOf("<end> expected")
            ),
            MissingEndFunctionCase(
                name = "local missing end keeps if-else residual",
                source = "local function choose(f) if f then a() else b() end",
                requiredShapeFragments = listOf("Else(Block[CallStmt(Call(Id(b):))])"),
                warningFragments = listOf("<end> expected")
            ),
            MissingEndFunctionCase(
                name = "global missing end keeps vararg param and multi-return",
                source = "function pack(...) return ...",
                requiredShapeFragments = listOf("Return(Vararg)"),
                warningFragments = listOf("<end> expected")
            ),
            MissingEndFunctionCase(
                name = "local missing end keeps vararg plus named params",
                source = "local function wrap(a, ...) return a, ...",
                requiredShapeFragments = listOf("Return(Id(a),Vararg)"),
                warningFragments = listOf("<end> expected")
            ),
            MissingEndFunctionCase(
                name = "global missing end keeps multi-return residual",
                source = "function multi(a, b) return a, b, a + b",
                requiredShapeFragments = listOf("Return(Id(a),Id(b),Binary(+,Id(a),Id(b)))"),
                warningFragments = listOf("<end> expected")
            ),
            MissingEndFunctionCase(
                name = "global missing end keeps empty body at eof",
                source = "function empty()",
                requiredShapeFragments = listOf("Function(Id(empty),Block[])"),
                warningFragments = listOf("<end> expected")
            ),
            MissingEndFunctionCase(
                name = "colon method missing end keeps self call residual",
                source = "function module:tick() self:work()",
                requiredShapeFragments = listOf("Function(Member(Id(module):tick),"),
                warningFragments = listOf("<end> expected")
            ),
            MissingEndFunctionCase(
                name = "dot method missing end keeps multi-statement residual",
                source = "function module.run(x) local copy = x use(copy)",
                requiredShapeFragments = listOf("Local(Id(copy)=Id(x))"),
                warningFragments = listOf("<end> expected")
            ),
            MissingEndFunctionCase(
                name = "table field function missing end keeps body",
                source = "local t = { run = function(x) use(x) }",
                requiredShapeFragments = listOf("TableKeyString(Id(run)=Function(null,"),
                warningFragments = listOf("<end> expected")
            ),
            MissingEndFunctionCase(
                name = "table indexed function missing end keeps body",
                source = "local t = { [key] = function() return 1 }",
                requiredShapeFragments = listOf("TableKey(Id(key)=Function(null,"),
                warningFragments = listOf("<end> expected")
            ),
            MissingEndFunctionCase(
                name = "call argument function missing end keeps body",
                source = "schedule(function(job) work(job)",
                requiredShapeFragments = listOf("Call(Id(schedule):Function(null,"),
                warningFragments = listOf("<end> expected", "')' expected")
            ),
            MissingEndFunctionCase(
                name = "anonymous assigned missing end keeps return residual",
                source = "local f = function(a, b) return a + b",
                requiredShapeFragments = listOf("Function(null,Block[Return(Binary(+,Id(a),Id(b)))])"),
                warningFragments = listOf("<end> expected")
            ),
            MissingEndFunctionCase(
                name = "return anonymous missing end keeps nested return",
                source = "return function(v) return v",
                requiredShapeFragments = listOf("Function(null,Block[Return(Id(v))])"),
                warningFragments = listOf("<end> expected")
            ),
            MissingEndFunctionCase(
                name = "nested missing outer end keeps closed inner and trailing call in outer body",
                source = "function outer() function inner() use() end print(outer)",
                requiredShapeFragments = listOf("CallStmt(Call(Id(print):Id(outer)))"),
                warningFragments = listOf("<end> expected"),
                minFunctionNodes = 2,
                maxFunctionNodes = 2
            ),
            MissingEndFunctionCase(
                name = "nested both missing end at eof keeps both bodies",
                source = "function outer() function inner() use() print(done)",
                requiredShapeFragments = listOf("Function(Id(inner),"),
                warningFragments = listOf("<end> expected"),
                minFunctionNodes = 2,
                maxFunctionNodes = 2
            ),
            MissingEndFunctionCase(
                name = "nested missing both ends with only one end: end closes inner first",
                source = "function outer() function inner() use() end",
                requiredShapeFragments = listOf("Function(Id(outer),Block[Function(Id(inner),Block[CallStmt(Call(Id(use):))])])"),
                warningFragments = listOf("<end> expected"),
                minFunctionNodes = 2,
                maxFunctionNodes = 2
            ),
            MissingEndFunctionCase(
                name = "nested local inside global missing outer end keeps closed local",
                source = "function outer() local function inner() return 1 end print(outer)",
                requiredShapeFragments = listOf("Function(Id(inner),Block[Return(Const(1))])"),
                warningFragments = listOf("<end> expected"),
                minFunctionNodes = 2,
                maxFunctionNodes = 2
            ),
            MissingEndFunctionCase(
                name = "nested three-level missing ends keeps partial AST",
                source = "function a() function b() function c() tick()",
                requiredShapeFragments = listOf("Function(Id(c),Block[CallStmt(Call(Id(tick):))])"),
                warningFragments = listOf("<end> expected"),
                minFunctionNodes = 3,
                maxFunctionNodes = 3
            ),
            MissingEndFunctionCase(
                name = "missing end with incomplete assignment body keeps placeholder",
                source = "function bump() total = total +",
                requiredShapeFragments = listOf("Binary(+,Id(total),ExpressionNodeSupport)"),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                warningFragments = listOf("<end> expected")
            ),
            MissingEndFunctionCase(
                name = "missing end with incomplete call body keeps partial call",
                source = "function run() use(x",
                requiredShapeFragments = listOf("Function(Id(run),"),
                warningFragments = listOf("<end> expected", "')' expected")
            ),
            MissingEndFunctionCase(
                name = "missing end with incomplete local initializer keeps placeholder",
                source = "function run() local copy =",
                requiredShapeFragments = listOf("Local(Id(copy)=ExpressionNodeSupport)"),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                warningFragments = listOf("<end> expected")
            ),
            MissingEndFunctionCase(
                name = "missing end with incomplete return body keeps placeholder",
                source = "function run() return x +",
                requiredShapeFragments = listOf("Return(Binary(+,Id(x),ExpressionNodeSupport))"),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                warningFragments = listOf("<end> expected")
            ),
            MissingEndFunctionCase(
                name = "missing end with incomplete concat return keeps placeholder",
                source = "function run() return a ..",
                requiredShapeFragments = listOf("Return(Binary(..,Id(a),ExpressionNodeSupport))"),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                warningFragments = listOf("<end> expected")
            ),
            // Dual-path CURRENTLY_ACCEPTS footguns (TASK-482)
            MissingEndFunctionCase(
                name = "body incomplete binary assign next-line print: recovery sibling, strict absorbs",
                source = "function bump() total = total +\nprint(total) end",
                requiredShapeFragments = listOf(
                    "Assign(Id(total)=Binary(+,Id(total),ExpressionNodeSupport))",
                    "CallStmt(Call(Id(print):Id(total)))"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
            ),
            MissingEndFunctionCase(
                name = "body incomplete comparison assign next-line print: recovery sibling, strict absorbs",
                source = "function check(a) ok = a ==\nprint(ok) end",
                requiredShapeFragments = listOf(
                    "Assign(Id(ok)=Binary(==,Id(a),ExpressionNodeSupport))",
                    "CallStmt(Call(Id(print):Id(ok)))"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
            ),
            MissingEndFunctionCase(
                name = "body incomplete concat assign next-line print: recovery sibling, strict absorbs",
                source = "function join(a) text = a ..\nprint(text) end",
                requiredShapeFragments = listOf(
                    "Assign(Id(text)=Binary(..,Id(a),ExpressionNodeSupport))",
                    "CallStmt(Call(Id(print):Id(text)))"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
            ),
            MissingEndFunctionCase(
                name = "local body incomplete binary next-line print: recovery sibling, strict absorbs",
                source = "local function bump() total = total +\nprint(total) end",
                requiredShapeFragments = listOf(
                    "Assign(Id(total)=Binary(+,Id(total),ExpressionNodeSupport))",
                    "CallStmt(Call(Id(print):Id(total)))"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
            ),
            MissingEndFunctionCase(
                name = "body incomplete unary return absorbs following print on both paths",
                source = "function run(k) return not\nprint(k) end",
                requiredShapeFragments = listOf("Return(Unary(not,Call(Id(print):Id(k))))"),
                strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
            )
        )
    }

    private fun assertSupportedCases(vararg cases: MissingEndFunctionCase) {
        cases.forEach(::assertSupportedCase)
    }

    private fun assertSupportedCase(case: MissingEndFunctionCase) {
        val first = recoverTwiceAndAssertDeterministic(case)
        assertShapeFragments(case, first.chunk)
        assertBadShapeFragments(case, first.chunk)
        assertWarningFragments(case, first.warnings)
        assertFunctionBounds(case, first.chunk)

        when (case.strictParseExpectation) {
            StrictParseExpectation.REJECTS -> {
                val fail1 = assertParseFails(case.version, case.source, recovery = false)
                val fail2 = assertParseFails(case.version, case.source, recovery = false)
                assertEquals(fail1::class, fail2::class, "${case.name} strict failure type")
                assertEquals(fail1.message, fail2.message, "${case.name} strict failure message")
            }
            StrictParseExpectation.CURRENTLY_ACCEPTS -> {
                val firstStrict = parse(case.version, case.source, recovery = false)
                val secondStrict = parse(case.version, case.source, recovery = false)
                assertEquals(
                    renderShape(firstStrict),
                    renderShape(secondStrict),
                    "${case.name} strict accepted shape"
                )
            }
        }
    }

    private fun recoverTwiceAndAssertDeterministic(case: MissingEndFunctionCase): RecoveryRun {
        val first = parseRecoveringWithWarnings(case.version, case.source, case.name, "first")
        val second = parseRecoveringWithWarnings(case.version, case.source, case.name, "second")
        assertEquals(renderShape(first.chunk), renderShape(second.chunk), "${case.name} recovered shape")
        assertEquals(first.warnings, second.warnings, "${case.name} warning stream")
        return first
    }

    private fun parseRecoveringWithoutThrow(source: String, version: LuaVersion = LuaVersion.LUA_5_3): ChunkNode {
        return try {
            LuaParser(luaVersion = version, errorRecovery = true).parse(source)
        } catch (failure: Throwable) {
            throw AssertionError("recovery parse must not throw for: $source", failure)
        }
    }

    private fun parseWithDiagnosticsWithoutThrow(
        source: String,
        attempt: String = "parse",
        version: LuaVersion = LuaVersion.LUA_5_3,
    ): LuaParseResult {
        return try {
            LuaParser(luaVersion = version, errorRecovery = true).parseWithDiagnostics(source)
        } catch (failure: Throwable) {
            throw AssertionError(
                "recovery parseWithDiagnostics must not throw during $attempt for: $source",
                failure
            )
        }
    }

    private fun parseRecoveringWithWarnings(
        version: LuaVersion,
        source: String,
        name: String,
        attempt: String,
    ): RecoveryRun {
        val result = try {
            LuaParser(luaVersion = version, errorRecovery = true).parseWithDiagnostics(source)
        } catch (failure: Throwable) {
            throw AssertionError("$name should recover without throwing during $attempt parse", failure)
        }
        return RecoveryRun(
            chunk = result.chunk,
            warnings = result.recoveryDiagnostics.map { it.message }
        )
    }

    private fun assertShapeFragments(case: MissingEndFunctionCase, chunk: ChunkNode) {
        val shape = renderShape(chunk)
        case.requiredShapeFragments.forEach { expected ->
            assertTrue(
                shape.contains(expected),
                "${case.name} should keep '$expected' reachable in recovered shape:\n$shape"
            )
        }
    }

    private fun assertBadShapeFragments(case: MissingEndFunctionCase, chunk: ChunkNode) {
        if (case.badShapeFragments.isEmpty()) return
        val shape = renderShape(chunk)
        case.badShapeFragments.forEach { expected ->
            assertTrue(
                shape.contains(expected),
                "${case.name} should include recovered residual containing '$expected'; shape:\n$shape"
            )
        }
    }

    private fun assertWarningFragments(case: MissingEndFunctionCase, warnings: List<String>) {
        case.warningFragments.forEach { expected ->
            assertTrue(
                warnings.any { it.contains(expected) },
                "${case.name} should emit warning containing '$expected'; actual warnings: $warnings"
            )
        }
    }

    private fun assertFunctionBounds(case: MissingEndFunctionCase, chunk: ChunkNode) {
        if (case.minFunctionNodes == null && case.maxFunctionNodes == null) return
        val count = countFunctionNodes(chunk)
        case.minFunctionNodes?.let {
            assertTrue(count >= it, "${case.name} expected at least $it Function nodes, got $count")
        }
        case.maxFunctionNodes?.let {
            assertTrue(count <= it, "${case.name} expected at most $it Function nodes (bounded), got $count")
        }
    }

    private fun countFunctionNodes(chunk: ChunkNode): Int {
        var count = 0
        fun walk(node: Any?) {
            when (node) {
                is FunctionDeclaration -> {
                    count++
                    walk(node.identifier)
                    node.params.forEach(::walk)
                    walk(node.body)
                }
                is ChunkNode -> walk(node.body)
                is io.github.dingyi222666.luaparser.parser.ast.node.BlockNode -> {
                    node.statements.forEach(::walk)
                    node.returnStatement?.let(::walk)
                }
                is io.github.dingyi222666.luaparser.parser.ast.node.DoStatement -> walk(node.body)
                is io.github.dingyi222666.luaparser.parser.ast.node.IfStatement -> node.causes.forEach(::walk)
                is io.github.dingyi222666.luaparser.parser.ast.node.IfClause -> {
                    walk(node.condition)
                    walk(node.body)
                }
                is io.github.dingyi222666.luaparser.parser.ast.node.ElseClause -> walk(node.body)
                is io.github.dingyi222666.luaparser.parser.ast.node.WhileStatement -> {
                    walk(node.condition)
                    walk(node.body)
                }
                is LocalStatement -> {
                    node.init.forEach(::walk)
                    node.variables.forEach(::walk)
                }
                is AssignmentStatement -> {
                    node.init.forEach(::walk)
                    node.variables.forEach(::walk)
                }
                is CallStatement -> walk(node.expression)
                is io.github.dingyi222666.luaparser.parser.ast.node.CallExpression -> {
                    walk(node.base)
                    node.arguments.forEach(::walk)
                }
                is io.github.dingyi222666.luaparser.parser.ast.node.BinaryExpression -> {
                    walk(node.left)
                    walk(node.right)
                }
                is io.github.dingyi222666.luaparser.parser.ast.node.UnaryExpression -> walk(node.arg)
                is ReturnStatement -> node.arguments.forEach(::walk)
                is MemberExpression -> {
                    walk(node.base)
                    walk(node.identifier)
                }
                is io.github.dingyi222666.luaparser.parser.ast.node.TableConstructorExpression -> {
                    node.fields.forEach(::walk)
                }
                is io.github.dingyi222666.luaparser.parser.ast.node.TableKey -> {
                    walk(node.key)
                    walk(node.value)
                }
                is io.github.dingyi222666.luaparser.parser.ast.node.TableKeyString -> {
                    walk(node.key)
                    walk(node.value)
                }
                is io.github.dingyi222666.luaparser.parser.ast.node.ForNumericStatement -> walk(node.body)
                is io.github.dingyi222666.luaparser.parser.ast.node.ForGenericStatement -> {
                    node.variables.forEach(::walk)
                    node.iterators.forEach(::walk)
                    walk(node.body)
                }
                is io.github.dingyi222666.luaparser.parser.ast.node.RepeatStatement -> {
                    walk(node.body)
                    walk(node.condition)
                }
            }
        }
        walk(chunk)
        return count
    }

    private enum class StrictParseExpectation {
        REJECTS,
        CURRENTLY_ACCEPTS,
    }

    private data class MissingEndFunctionCase(
        val name: String,
        val source: String,
        val requiredShapeFragments: List<String>,
        val version: LuaVersion = LuaVersion.LUA_5_3,
        val badShapeFragments: List<String> = emptyList(),
        val warningFragments: List<String> = emptyList(),
        val strictParseExpectation: StrictParseExpectation = StrictParseExpectation.REJECTS,
        val minFunctionNodes: Int? = null,
        val maxFunctionNodes: Int? = null,
    )

    private data class RecoveryRun(
        val chunk: ChunkNode,
        val warnings: List<String>,
    )
}
