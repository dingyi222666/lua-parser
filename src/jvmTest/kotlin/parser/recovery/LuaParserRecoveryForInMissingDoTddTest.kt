package parser.recovery

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.LuaParseResult
import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.CallStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.ForGenericStatement
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import parser.assertParseFails
import parser.parse
import parser.renderShape

/**
 * Dual-path recovery corpus for generic for-in loops missing `do`
 * (TASK-461; expands TASK-282 [LuaParserRecoveryForInDoTddTest] with honest
 * CURRENTLY_ACCEPTS dual-path footguns and broader incomplete residual coverage).
 *
 * Complements:
 * - [LuaParserRecoveryForInDoTddTest] missing-`do` REJECTS inventory
 * - [LuaParserRecoveryGenericForTddTest] missing-`in` dual-path suite
 * - sparse "missing generic for do" fixture in [LuaParserRecoveryTddTest]
 *
 * Acceptance:
 * - Missing `do` recovers a ForGeneric, keeps body reachable, emits
 *   `The <do> expected`.
 * - With matching `end`, following statements remain top-level siblings.
 * - Without `end`, residual body statements stay inside the for.
 * - Nested for-in missing-do recovery stays bounded.
 * - Dual-path [StrictParseExpectation] documents CURRENTLY_ACCEPTS footguns
 *   (recovery sibling + ExpressionNodeSupport vs strict absorb of next-line
 *   statement as expression operand) and retained REJECTS incompletes.
 * - Well-formed for-in stays clean under recovery and matches strict shape.
 * - Test-only; no production LuaParser edits.
 *
 * Product goldens (aligned with [LuaParser.parseForGenericStatement] /
 * [LuaParser.parseForBody] / binary-unary recovery):
 * - missing `do` after `in explist` warns `The <do> expected` and still parses body;
 * - missing `end` recovers with `<end> expected` and keeps body inside the for;
 * - incomplete binary RHS after line-break + statement-start inserts
 *   ExpressionNodeSupport under recovery; strict mode currently absorbs the next
 *   call as the binary operand (CURRENTLY_ACCEPTS dual-path);
 * - incomplete unary only placeholders on expression terminators; a following
 *   NAME/call is absorbed as the unary operand on both paths.
 */
class LuaParserRecoveryForInMissingDoTddTest {

    @Test
    fun recoversMissingDoAndKeepsLoopBody() {
        assertSupportedCases(
            ForInMissingDoCase(
                name = "missing do after pairs keeps multi-name body",
                source = "for key, value in pairs(items) use(key, value) end",
                requiredShapeFragments = listOf(
                    "ForGeneric(Id(key),Id(value) in Call(Id(pairs):Id(items)):Block[CallStmt(Call(Id(use):Id(key),Id(value)))])"
                ),
                warningFragments = listOf("The <do> expected")
            ),
            ForInMissingDoCase(
                name = "missing do after ipairs keeps multi-name body",
                source = "for index, value in ipairs(list) consume(value) end",
                requiredShapeFragments = listOf(
                    "ForGeneric(Id(index),Id(value) in Call(Id(ipairs):Id(list)):Block[CallStmt(Call(Id(consume):Id(value)))])"
                ),
                warningFragments = listOf("The <do> expected")
            ),
            ForInMissingDoCase(
                name = "missing do single name keeps body",
                source = "for item in items work(item) end",
                requiredShapeFragments = listOf(
                    "ForGeneric(Id(item) in Id(items):Block[CallStmt(Call(Id(work):Id(item)))])"
                ),
                warningFragments = listOf("The <do> expected")
            ),
            ForInMissingDoCase(
                name = "missing do with multi-iterator explist keeps body",
                source = "for a, b in next, t, nil handle(a, b) end",
                requiredShapeFragments = listOf(
                    "ForGeneric(Id(a),Id(b) in Id(next),Id(t),Const(nil):Block[CallStmt(Call(Id(handle):Id(a),Id(b)))])"
                ),
                warningFragments = listOf("The <do> expected")
            )
        )
    }

    @Test
    fun recoversMissingDoAndKeepsLaterSiblingStatements() {
        assertSupportedCases(
            ForInMissingDoCase(
                name = "missing do keeps following print after pairs loop",
                source = "for key, value in pairs(items) use(key, value) end\nprint(items)",
                requiredShapeFragments = listOf(
                    "ForGeneric(Id(key),Id(value) in Call(Id(pairs):Id(items)):Block[CallStmt(Call(Id(use):Id(key),Id(value)))])",
                    "CallStmt(Call(Id(print):Id(items)))"
                ),
                warningFragments = listOf("The <do> expected")
            ),
            ForInMissingDoCase(
                name = "missing do keeps following local after single name loop",
                source = "for item in items work(item) end\nlocal after = 1",
                requiredShapeFragments = listOf(
                    "ForGeneric(Id(item) in Id(items):Block[CallStmt(Call(Id(work):Id(item)))])",
                    "Local(Id(after)=Const(1))"
                ),
                warningFragments = listOf("The <do> expected")
            ),
            ForInMissingDoCase(
                name = "missing do keeps following return after ipairs loop",
                source = "for i, v in ipairs(list) consume(v) end\nreturn list",
                requiredShapeFragments = listOf(
                    "ForGeneric(Id(i),Id(v) in Call(Id(ipairs):Id(list)):Block[CallStmt(Call(Id(consume):Id(v)))])",
                    "Return(Id(list))"
                ),
                warningFragments = listOf("The <do> expected")
            ),
            ForInMissingDoCase(
                name = "missing do keeps following call after empty body",
                source = "for k, v in pairs(t) end\nprint(t)",
                requiredShapeFragments = listOf(
                    "ForGeneric(Id(k),Id(v) in Call(Id(pairs):Id(t)):Block[])",
                    "CallStmt(Call(Id(print):Id(t)))"
                ),
                warningFragments = listOf("The <do> expected")
            )
        )
    }

    @Test
    fun recoversMissingDoCombinedWithMissingEndAndNested() {
        assertSupportedCases(
            ForInMissingDoCase(
                name = "missing do and end at eof keeps body statements",
                source = "for key, value in pairs(items) use(key, value) print(\"after\")",
                requiredShapeFragments = listOf(
                    "ForGeneric(Id(key),Id(value) in Call(Id(pairs):Id(items)):Block[CallStmt(Call(Id(use):Id(key),Id(value)));CallStmt(Call(Id(print):Const(\"after\")))])"
                ),
                warningFragments = listOf("The <do> expected", "<end> expected")
            ),
            ForInMissingDoCase(
                name = "missing do and end at eof keeps local and call body",
                source = "for item in items local copy = item work(copy)",
                requiredShapeFragments = listOf(
                    "ForGeneric(Id(item) in Id(items):Block[Local(Id(copy)=Id(item));CallStmt(Call(Id(work):Id(copy)))])"
                ),
                warningFragments = listOf("The <do> expected", "<end> expected")
            ),
            ForInMissingDoCase(
                name = "nested for-in missing inner do keeps outer end and following print",
                source = "for k, v in pairs(outer) do for i, x in ipairs(v) use(x) end end\nprint(outer)",
                requiredShapeFragments = listOf(
                    "ForGeneric(Id(k),Id(v) in Call(Id(pairs):Id(outer)):Block[ForGeneric(Id(i),Id(x) in Call(Id(ipairs):Id(v)):Block[CallStmt(Call(Id(use):Id(x)))])])",
                    "CallStmt(Call(Id(print):Id(outer)))"
                ),
                warningFragments = listOf("The <do> expected"),
                minForGenericNodes = 2,
                maxForGenericNodes = 2
            ),
            ForInMissingDoCase(
                name = "nested for-in missing outer do keeps closed inner and following print",
                source = "for k, v in pairs(outer) for i, x in ipairs(v) do use(x) end end\nprint(outer)",
                requiredShapeFragments = listOf(
                    "ForGeneric(Id(k),Id(v) in Call(Id(pairs):Id(outer)):Block[ForGeneric(Id(i),Id(x) in Call(Id(ipairs):Id(v)):Block[CallStmt(Call(Id(use):Id(x)))])])",
                    "CallStmt(Call(Id(print):Id(outer)))"
                ),
                warningFragments = listOf("The <do> expected"),
                minForGenericNodes = 2,
                maxForGenericNodes = 2
            ),
            ForInMissingDoCase(
                name = "nested for-in missing both do stays bounded",
                source = "for k, v in pairs(outer) for i, x in ipairs(v) use(x) end end\nprint(outer)",
                requiredShapeFragments = listOf(
                    "ForGeneric(Id(k),Id(v) in Call(Id(pairs):Id(outer)):Block[ForGeneric(Id(i),Id(x) in Call(Id(ipairs):Id(v)):Block[CallStmt(Call(Id(use):Id(x)))])])",
                    "CallStmt(Call(Id(print):Id(outer)))"
                ),
                warningFragments = listOf("The <do> expected"),
                minForGenericNodes = 2,
                maxForGenericNodes = 2
            )
        )
    }

    @Test
    fun recoversIncompleteIteratorsAndBodiesAfterMissingDoWithoutThrowing() {
        assertSupportedCases(
            ForInMissingDoCase(
                name = "trailing comma after iterator before do inserts placeholder",
                source = "for k, v in pairs(t), do use(k, v) end",
                requiredShapeFragments = listOf(
                    "ForGeneric(Id(k),Id(v) in Call(Id(pairs):Id(t)),ExpressionNodeSupport:Block[CallStmt(Call(Id(use):Id(k),Id(v)))])"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport")
            ),
            ForInMissingDoCase(
                name = "iterator binary missing rhs before do keeps placeholder",
                source = "for k, v in value + do use(k, v) end",
                requiredShapeFragments = listOf(
                    "ForGeneric(Id(k),Id(v) in Binary(+,Id(value),ExpressionNodeSupport):Block[CallStmt(Call(Id(use):Id(k),Id(v)))])"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport")
            ),
            ForInMissingDoCase(
                name = "iterator unary missing operand before do keeps placeholder",
                source = "for k in not do use(k) end",
                requiredShapeFragments = listOf(
                    "ForGeneric(Id(k) in Unary(not,ExpressionNodeSupport):Block[CallStmt(Call(Id(use):Id(k)))])"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport")
            ),
            ForInMissingDoCase(
                name = "missing do with incomplete assignment body keeps following print",
                source = "for k, v in pairs(t) total = total + end\nprint(total)",
                requiredShapeFragments = listOf(
                    "ForGeneric(Id(k),Id(v) in Call(Id(pairs):Id(t)):Block[Assign(Id(total)=Binary(+,Id(total),ExpressionNodeSupport))])",
                    "CallStmt(Call(Id(print):Id(total)))"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                warningFragments = listOf("The <do> expected")
            ),
            ForInMissingDoCase(
                name = "missing do with incomplete call body keeps following print",
                source = "for k, v in pairs(t) use(k end\nprint(t)",
                requiredShapeFragments = listOf(
                    "ForGeneric(",
                    "CallStmt(Call(Id(print):Id(t)))"
                ),
                warningFragments = listOf("The <do> expected", "')' expected")
            ),
            ForInMissingDoCase(
                name = "missing do with incomplete local initializer keeps following print",
                source = "for item in items local copy = end\nprint(items)",
                requiredShapeFragments = listOf(
                    "ForGeneric(Id(item) in Id(items):Block[Local(Id(copy)=ExpressionNodeSupport)])",
                    "CallStmt(Call(Id(print):Id(items)))"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                warningFragments = listOf("The <do> expected")
            )
        )
    }

    @Test
    fun exposesTypedForGenericStructureAfterMissingDoRecovery() {
        val chunk = parseRecoveringWithoutThrow(
            "for key, value in pairs(items) use(key, value) end\nprint(items)"
        )

        val forStmt = assertIs<ForGenericStatement>(chunk.body.statements[0])
        assertEquals(2, forStmt.variables.size)
        assertEquals("Id(key)", renderShape(forStmt.variables[0]))
        assertEquals("Id(value)", renderShape(forStmt.variables[1]))
        assertEquals(1, forStmt.iterators.size)
        assertEquals("Call(Id(pairs):Id(items))", renderShape(forStmt.iterators.single()))
        assertIs<CallStatement>(forStmt.body.statements.single())
        assertIs<CallStatement>(chunk.body.statements[1])
    }

    @Test
    fun recoveryDiagnosticsArePresentDeterministicAndDoNotThrow() {
        val sources = listOf(
            "for key, value in pairs(items) use(key, value) end",
            "for item in items work(item) end\nprint(item)",
            "for k, v in pairs(t) use(k, v)",
            "for i, v in ipairs(list) consume(v) end\nlocal after = 1",
            "for k, v in pairs(outer) for i, x in ipairs(v) use(x) end end\nprint(outer)",
            "for k, v in value + do use(k, v) end"
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
                first.recoveryDiagnostics.isNotEmpty() ||
                    renderShape(first.chunk).contains("ExpressionNodeSupport"),
                "expected recovery signal (diagnostic or bad placeholder) for: $source"
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
    fun dualPathStrictAcceptsIncompleteBinaryInsideForInWithNextLineStatement() {
        // Dual-path footguns: recovery inserts ExpressionNodeSupport for the missing
        // binary right and keeps next-line print as a sibling CallStmt; strict mode
        // currently absorbs print(...) as the binary right operand and accepts.
        assertSupportedCases(
            ForInMissingDoCase(
                name = "body incomplete binary assign next-line print: recovery sibling, strict absorbs",
                source = "for k, v in pairs(t) do total = total +\nprint(total) end",
                requiredShapeFragments = listOf(
                    "Assign(Id(total)=Binary(+,Id(total),ExpressionNodeSupport))",
                    "CallStmt(Call(Id(print):Id(total)))"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
            ),
            ForInMissingDoCase(
                name = "body incomplete comparison assign next-line print: recovery sibling, strict absorbs",
                source = "for k, v in pairs(t) do ok = k ==\nprint(ok) end",
                requiredShapeFragments = listOf(
                    "Assign(Id(ok)=Binary(==,Id(k),ExpressionNodeSupport))",
                    "CallStmt(Call(Id(print):Id(ok)))"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
            ),
            ForInMissingDoCase(
                name = "iterator binary missing rhs next-line print before do: recovery placeholder, strict absorbs",
                source = "for k in value +\nprint(k) do use(k) end",
                requiredShapeFragments = listOf(
                    "Binary(+,Id(value),ExpressionNodeSupport)"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                // Recovery inserts placeholder for iterator RHS then continues; residual
                // body layout depends on whether print is absorbed into body after missing
                // do — require at least the placeholder + ForGeneric residual.
                warningFragments = listOf("The <do> expected"),
                strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
            ),
            ForInMissingDoCase(
                // Unary recovery only placeholders on expression terminators. A following
                // NAME/call is an expression start, so both recovery and strict absorb
                // print as the unary operand — honest CURRENTLY_ACCEPTS dual-path.
                name = "body incomplete unary return absorbs following print on both paths",
                source = "for k in items do return not\nprint(k) end",
                requiredShapeFragments = listOf(
                    "Return(Unary(not,Call(Id(print):Id(k))))"
                ),
                strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
            )
        )
    }

    @Test
    fun dualPathStrictRejectsMissingDoWhileRecordingAcceptGaps() {
        val rejects = requiredCases().filter {
            it.strictParseExpectation == StrictParseExpectation.REJECTS
        }
        assertTrue(rejects.isNotEmpty(), "inventory must retain REJECTS incomplete for-in missing-do cases")
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
    fun wellFormedForInStillParsesCleanlyUnderRecovery() {
        val wellFormed = listOf(
            "for key, value in pairs(items) do use(key, value) end",
            "for item in items do work(item) end\nprint(item)",
            "for i, v in ipairs(list) do consume(v) end\nlocal after = 1",
            "for k, v in pairs(outer) do for i, x in ipairs(v) do use(x) end end\nprint(outer)",
            "for a, b in next, t, nil do handle(a, b) end\nprint(t)"
        )

        wellFormed.forEach { source ->
            val result = parseWithDiagnosticsWithoutThrow(source)
            assertEquals(
                emptyList(),
                result.recoveryDiagnostics.map { it.message },
                "well-formed for-in should not emit recovery diagnostics: $source"
            )
            assertTrue(
                renderShape(result.chunk).contains("ForGeneric("),
                "well-formed source should produce ForGeneric shape: $source"
            )
            val strict = parse(LuaVersion.LUA_5_3, source, recovery = false)
            assertEquals(renderShape(result.chunk), renderShape(strict))
        }
    }

    @Test
    fun corpusInventoryCoversRequiredForInMissingDoFamilies() {
        val inventory = requiredCases()
        assertEquals(24, inventory.size)

        val names = inventory.map { it.name }.toSet()
        assertTrue(names.any { it.contains("missing do") })
        assertTrue(names.any { it.contains("pairs") || it.contains("ipairs") || it.contains("iterator") })
        assertTrue(names.any { it.contains("nested") })
        assertTrue(names.any { it.contains("body") || it.contains("following") })
        assertTrue(names.any { it.contains("following print") || it.contains("later") || it.contains("after") })
        assertTrue(
            inventory.any { it.strictParseExpectation == StrictParseExpectation.CURRENTLY_ACCEPTS },
            "inventory must include dual-path CURRENTLY_ACCEPTS cases"
        )
        assertTrue(
            inventory.count { it.strictParseExpectation == StrictParseExpectation.CURRENTLY_ACCEPTS } >= 4,
            "expected at least four dual-path CURRENTLY_ACCEPTS footguns"
        )
        assertTrue(
            inventory.any { it.strictParseExpectation == StrictParseExpectation.REJECTS },
            "inventory must retain REJECTS incomplete for-in missing-do cases"
        )
        assertTrue(
            inventory.any { case ->
                case.warningFragments.any { it.contains("The <do> expected") }
            },
            "inventory must cover missing-do diagnostics"
        )
    }

    // --- helpers -----------------------------------------------------------------

    private fun requiredCases(): List<ForInMissingDoCase> {
        return listOf(
            ForInMissingDoCase(
                name = "missing do after pairs keeps multi-name body",
                source = "for key, value in pairs(items) use(key, value) end",
                requiredShapeFragments = listOf("ForGeneric(Id(key),Id(value) in Call(Id(pairs):Id(items)):"),
                warningFragments = listOf("The <do> expected")
            ),
            ForInMissingDoCase(
                name = "missing do after ipairs keeps multi-name body",
                source = "for index, value in ipairs(list) consume(value) end",
                requiredShapeFragments = listOf("Call(Id(ipairs):Id(list))"),
                warningFragments = listOf("The <do> expected")
            ),
            ForInMissingDoCase(
                name = "missing do single name keeps body",
                source = "for item in items work(item) end",
                requiredShapeFragments = listOf("ForGeneric(Id(item) in Id(items):"),
                warningFragments = listOf("The <do> expected")
            ),
            ForInMissingDoCase(
                name = "missing do with multi-iterator explist keeps body",
                source = "for a, b in next, t, nil handle(a, b) end",
                requiredShapeFragments = listOf("Id(next),Id(t),Const(nil)"),
                warningFragments = listOf("The <do> expected")
            ),
            ForInMissingDoCase(
                name = "missing do keeps following print after pairs loop",
                source = "for key, value in pairs(items) use(key, value) end\nprint(items)",
                requiredShapeFragments = listOf("CallStmt(Call(Id(print):Id(items)))"),
                warningFragments = listOf("The <do> expected")
            ),
            ForInMissingDoCase(
                name = "missing do keeps following local after single name loop",
                source = "for item in items work(item) end\nlocal after = 1",
                requiredShapeFragments = listOf("Local(Id(after)=Const(1))"),
                warningFragments = listOf("The <do> expected")
            ),
            ForInMissingDoCase(
                name = "missing do keeps following return after ipairs loop",
                source = "for i, v in ipairs(list) consume(v) end\nreturn list",
                requiredShapeFragments = listOf("Return(Id(list))"),
                warningFragments = listOf("The <do> expected")
            ),
            ForInMissingDoCase(
                name = "missing do keeps following call after empty body",
                source = "for k, v in pairs(t) end\nprint(t)",
                requiredShapeFragments = listOf("ForGeneric(Id(k),Id(v) in Call(Id(pairs):Id(t)):Block[])"),
                warningFragments = listOf("The <do> expected")
            ),
            ForInMissingDoCase(
                name = "missing do and end at eof keeps body statements",
                source = "for key, value in pairs(items) use(key, value) print(\"after\")",
                requiredShapeFragments = listOf("CallStmt(Call(Id(print):Const(\"after\")))"),
                warningFragments = listOf("The <do> expected", "<end> expected")
            ),
            ForInMissingDoCase(
                name = "missing do and end at eof keeps local and call body",
                source = "for item in items local copy = item work(copy)",
                requiredShapeFragments = listOf("Local(Id(copy)=Id(item))"),
                warningFragments = listOf("The <do> expected", "<end> expected")
            ),
            ForInMissingDoCase(
                name = "nested for-in missing inner do keeps outer end and following print",
                source = "for k, v in pairs(outer) do for i, x in ipairs(v) use(x) end end\nprint(outer)",
                requiredShapeFragments = listOf("CallStmt(Call(Id(print):Id(outer)))"),
                warningFragments = listOf("The <do> expected"),
                minForGenericNodes = 2,
                maxForGenericNodes = 2
            ),
            ForInMissingDoCase(
                name = "nested for-in missing outer do keeps closed inner and following print",
                source = "for k, v in pairs(outer) for i, x in ipairs(v) do use(x) end end\nprint(outer)",
                requiredShapeFragments = listOf("Call(Id(ipairs):Id(v))"),
                warningFragments = listOf("The <do> expected"),
                minForGenericNodes = 2,
                maxForGenericNodes = 2
            ),
            ForInMissingDoCase(
                name = "nested for-in missing both do stays bounded",
                source = "for k, v in pairs(outer) for i, x in ipairs(v) use(x) end end\nprint(outer)",
                requiredShapeFragments = listOf("CallStmt(Call(Id(print):Id(outer)))"),
                warningFragments = listOf("The <do> expected"),
                minForGenericNodes = 2,
                maxForGenericNodes = 2
            ),
            ForInMissingDoCase(
                name = "iterator binary missing rhs before do keeps placeholder",
                source = "for k, v in value + do use(k, v) end",
                requiredShapeFragments = listOf("Binary(+,Id(value),ExpressionNodeSupport)"),
                badShapeFragments = listOf("ExpressionNodeSupport")
            ),
            ForInMissingDoCase(
                name = "iterator unary missing operand before do keeps placeholder",
                source = "for k in not do use(k) end",
                requiredShapeFragments = listOf("Unary(not,ExpressionNodeSupport)"),
                badShapeFragments = listOf("ExpressionNodeSupport")
            ),
            ForInMissingDoCase(
                name = "missing do with incomplete assignment body keeps following print",
                source = "for k, v in pairs(t) total = total + end\nprint(total)",
                requiredShapeFragments = listOf("Binary(+,Id(total),ExpressionNodeSupport)"),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                warningFragments = listOf("The <do> expected")
            ),
            ForInMissingDoCase(
                name = "missing do with incomplete call body keeps following print",
                source = "for k, v in pairs(t) use(k end\nprint(t)",
                requiredShapeFragments = listOf("CallStmt(Call(Id(print):Id(t)))"),
                warningFragments = listOf("The <do> expected", "')' expected")
            ),
            ForInMissingDoCase(
                name = "missing do with incomplete local initializer keeps following print",
                source = "for item in items local copy = end\nprint(items)",
                requiredShapeFragments = listOf("Local(Id(copy)=ExpressionNodeSupport)"),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                warningFragments = listOf("The <do> expected")
            ),
            ForInMissingDoCase(
                name = "trailing comma after iterator before do inserts placeholder",
                source = "for k, v in pairs(t), do use(k, v) end",
                requiredShapeFragments = listOf("ExpressionNodeSupport"),
                badShapeFragments = listOf("ExpressionNodeSupport")
            ),
            // Dual-path CURRENTLY_ACCEPTS footguns (TASK-461)
            ForInMissingDoCase(
                name = "body incomplete binary assign next-line print: recovery sibling, strict absorbs",
                source = "for k, v in pairs(t) do total = total +\nprint(total) end",
                requiredShapeFragments = listOf(
                    "Assign(Id(total)=Binary(+,Id(total),ExpressionNodeSupport))",
                    "CallStmt(Call(Id(print):Id(total)))"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
            ),
            ForInMissingDoCase(
                name = "body incomplete comparison assign next-line print: recovery sibling, strict absorbs",
                source = "for k, v in pairs(t) do ok = k ==\nprint(ok) end",
                requiredShapeFragments = listOf(
                    "Assign(Id(ok)=Binary(==,Id(k),ExpressionNodeSupport))",
                    "CallStmt(Call(Id(print):Id(ok)))"
                ),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
            ),
            ForInMissingDoCase(
                name = "iterator binary missing rhs next-line print before do: recovery placeholder, strict absorbs",
                source = "for k in value +\nprint(k) do use(k) end",
                requiredShapeFragments = listOf("Binary(+,Id(value),ExpressionNodeSupport)"),
                badShapeFragments = listOf("ExpressionNodeSupport"),
                warningFragments = listOf("The <do> expected"),
                strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
            ),
            ForInMissingDoCase(
                name = "body incomplete unary return absorbs following print on both paths",
                source = "for k in items do return not\nprint(k) end",
                requiredShapeFragments = listOf("Return(Unary(not,Call(Id(print):Id(k))))"),
                strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
            ),
            ForInMissingDoCase(
                name = "missing do keeps multi-statement local/assign body",
                source = "for k, v in pairs(t) local copy = v use(k, copy) end",
                requiredShapeFragments = listOf(
                    "ForGeneric(Id(k),Id(v) in Call(Id(pairs):Id(t)):Block[Local(Id(copy)=Id(v));CallStmt(Call(Id(use):Id(k),Id(copy)))])"
                ),
                warningFragments = listOf("The <do> expected")
            )
        )
    }

    private fun assertSupportedCases(vararg cases: ForInMissingDoCase) {
        cases.forEach(::assertSupportedCase)
    }

    private fun assertSupportedCase(case: ForInMissingDoCase) {
        val first = recoverTwiceAndAssertDeterministic(case)
        assertShapeFragments(case, first.chunk)
        assertBadShapeFragments(case, first.chunk)
        assertWarningFragments(case, first.warnings)
        assertForGenericBounds(case, first.chunk)

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

    private fun recoverTwiceAndAssertDeterministic(case: ForInMissingDoCase): RecoveryRun {
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

    private fun assertShapeFragments(case: ForInMissingDoCase, chunk: ChunkNode) {
        val shape = renderShape(chunk)
        case.requiredShapeFragments.forEach { expected ->
            assertTrue(
                shape.contains(expected),
                "${case.name} should keep '$expected' reachable in recovered shape:\n$shape"
            )
        }
    }

    private fun assertBadShapeFragments(case: ForInMissingDoCase, chunk: ChunkNode) {
        if (case.badShapeFragments.isEmpty()) return
        val shape = renderShape(chunk)
        case.badShapeFragments.forEach { expected ->
            assertTrue(
                shape.contains(expected),
                "${case.name} should include recovered residual containing '$expected'; shape:\n$shape"
            )
        }
    }

    private fun assertWarningFragments(case: ForInMissingDoCase, warnings: List<String>) {
        case.warningFragments.forEach { expected ->
            assertTrue(
                warnings.any { it.contains(expected) },
                "${case.name} should emit warning containing '$expected'; actual warnings: $warnings"
            )
        }
    }

    private fun assertForGenericBounds(case: ForInMissingDoCase, chunk: ChunkNode) {
        if (case.minForGenericNodes == null && case.maxForGenericNodes == null) return
        val count = countForGenericNodes(chunk)
        case.minForGenericNodes?.let {
            assertTrue(count >= it, "${case.name} expected at least $it ForGeneric nodes, got $count")
        }
        case.maxForGenericNodes?.let {
            assertTrue(count <= it, "${case.name} expected at most $it ForGeneric nodes (bounded), got $count")
        }
    }

    private fun countForGenericNodes(chunk: ChunkNode): Int {
        var count = 0
        fun walk(node: Any?) {
            when (node) {
                is ForGenericStatement -> {
                    count++
                    node.variables.forEach(::walk)
                    node.iterators.forEach(::walk)
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
                is io.github.dingyi222666.luaparser.parser.ast.node.WhileStatement -> {
                    walk(node.condition)
                    walk(node.body)
                }
                is io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration -> walk(node.body)
                is LocalStatement -> {
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
                is io.github.dingyi222666.luaparser.parser.ast.node.AssignmentStatement -> {
                    node.init.forEach(::walk)
                    node.variables.forEach(::walk)
                }
                is io.github.dingyi222666.luaparser.parser.ast.node.ReturnStatement -> {
                    node.arguments.forEach(::walk)
                }
                is io.github.dingyi222666.luaparser.parser.ast.node.ForNumericStatement -> walk(node.body)
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

    private data class ForInMissingDoCase(
        val name: String,
        val source: String,
        val requiredShapeFragments: List<String>,
        val version: LuaVersion = LuaVersion.LUA_5_3,
        val badShapeFragments: List<String> = emptyList(),
        val warningFragments: List<String> = emptyList(),
        val strictParseExpectation: StrictParseExpectation = StrictParseExpectation.REJECTS,
        val minForGenericNodes: Int? = null,
        val maxForGenericNodes: Int? = null,
    )

    private data class RecoveryRun(
        val chunk: ChunkNode,
        val warnings: List<String>,
    )
}
