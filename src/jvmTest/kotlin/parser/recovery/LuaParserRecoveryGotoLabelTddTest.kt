package parser.recovery

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.LuaParseResult
import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.CallStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.GotoStatement
import io.github.dingyi222666.luaparser.parser.ast.node.LabelStatement
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import parser.assertParseFails
import parser.parse
import parser.renderShape

/**
 * Focused recovery corpus for malformed Lua 5.3 `goto` / label (`::name::`) forms
 * (TASK-321 base + TASK-431 dual-path expansion).
 *
 * Acceptance:
 * - Malformed goto/label recovers without throw when recovery is enabled.
 * - Residual label/goto shapes are deterministic across re-parses.
 * - Dual-path strict expectations document honest CURRENTLY_ACCEPTS footguns
 *   where recovery=false still accepts a form that recovery mode treats as
 *   residual/malformed (or both paths absorb the same NAME target).
 * - Test-only; no production LuaParser edits unless review re-scopes.
 *
 * Complements the well-formed [parser.lua53.Lua53GotoLabelCorpusTddTest] inventory with
 * recovery edges: missing names, missing closing `::`, trailing statements after broken
 * jumps, nested incomplete labels, and strict-mode rejection.
 *
 * Goldens track current product behaviour in [LuaParser.parseGotoStatement] /
 * [LuaParser.parseLabelStatement] / [LuaParser.parseStatementNameOrMissing]:
 * - missing name (non-NAME next token) inserts a bad empty [Identifier] (`Id()` in
 *   renderShape) and emits `"<name> expected"`;
 * - `goto` followed by a NAME is accepted as the target even across a line break
 *   (no statement-start-after-line-break guard on statement names), so
 *   `goto\nprint(1)` residual is `Goto(Id(print))` rather than empty Id + CallStmt;
 * - bare `goto\nprint` (no leftover call args) is well-formed on both recovery and
 *   strict paths — honest CURRENTLY_ACCEPTS dual-path (same shape both modes);
 * - missing closing `::` emits `"'::' expected"` and still keeps a LabelStatement;
 * - later statements remain reachable siblings after recovery when the next token is
 *   not absorbed as a NAME.
 */
class LuaParserRecoveryGotoLabelTddTest {

    @Test
    fun recoversMissingGotoNameWithoutThrowing() {
        assertSupportedCases(
            GotoLabelCase(
                name = "goto alone at eof inserts empty name",
                source = "goto",
                requiredShapeFragments = listOf("Goto(Id())"),
                warningFragments = listOf("<name> expected")
            ),
            GotoLabelCase(
                name = "goto before number inserts empty name and keeps number statement reachable",
                source = "goto 1\nprint(1)",
                requiredShapeFragments = listOf(
                    "Goto(Id())",
                    "CallStmt(Call(Id(print):Const(1)))"
                ),
                warningFragments = listOf("<name> expected")
            ),
            GotoLabelCase(
                name = "goto before keyword inserts empty name and keeps later local",
                source = "goto\nlocal after = 1",
                requiredShapeFragments = listOf(
                    "Goto(Id())",
                    "Local(Id(after)=Const(1))"
                ),
                warningFragments = listOf("<name> expected")
            ),
            GotoLabelCase(
                name = "goto before another goto recovers both empty targets",
                source = "goto\ngoto",
                requiredShapeFragments = listOf("Goto(Id())"),
                warningFragments = listOf("<name> expected")
            )
        )
    }

    @Test
    fun recoversMissingLabelNameAndClosingColonsWithoutThrowing() {
        assertSupportedCases(
            GotoLabelCase(
                name = "open double colon at eof inserts empty label name",
                source = "::",
                requiredShapeFragments = listOf("Label(Id())"),
                warningFragments = listOf("<name> expected", "'::' expected")
            ),
            GotoLabelCase(
                name = "label missing closing colons keeps identifier",
                source = "::again",
                requiredShapeFragments = listOf("Label(Id(again))"),
                warningFragments = listOf("'::' expected")
            ),
            GotoLabelCase(
                name = "label missing name but has closing colons keeps empty Id",
                source = "::::",
                requiredShapeFragments = listOf("Label(Id())"),
                warningFragments = listOf("<name> expected")
            ),
            GotoLabelCase(
                name = "label missing closing colons keeps later print reachable",
                source = "::again\nprint(again)",
                requiredShapeFragments = listOf(
                    "Label(Id(again))",
                    "CallStmt(Call(Id(print):Id(again)))"
                ),
                warningFragments = listOf("'::' expected")
            ),
            GotoLabelCase(
                name = "label missing name keeps later goto sibling",
                source = "::\ngoto again\n::again::",
                requiredShapeFragments = listOf(
                    "Label(Id())",
                    "Goto(Id(again))",
                    "Label(Id(again))"
                ),
                warningFragments = listOf("<name> expected")
            )
        )
    }

    @Test
    fun recoversMixedMalformedGotoAndLabelChainsDeterministically() {
        assertSupportedCases(
            GotoLabelCase(
                name = "goto missing name then well-formed label keeps both",
                source = "goto\n::done::",
                requiredShapeFragments = listOf(
                    "Goto(Id())",
                    "Label(Id(done))"
                ),
                warningFragments = listOf("<name> expected")
            ),
            GotoLabelCase(
                name = "incomplete label then incomplete goto keeps residual shapes",
                source = "::start\ngoto",
                requiredShapeFragments = listOf(
                    "Label(Id(start))",
                    "Goto(Id())"
                ),
                warningFragments = listOf("'::' expected", "<name> expected")
            ),
            GotoLabelCase(
                name = "nested do with incomplete goto keeps outer trailing print",
                source = """
                    do
                      goto
                    end
                    print(after)
                """.trimIndent(),
                requiredShapeFragments = listOf(
                    "Do(Block[Goto(Id())])",
                    "CallStmt(Call(Id(print):Id(after)))"
                ),
                warningFragments = listOf("<name> expected")
            ),
            GotoLabelCase(
                name = "while body incomplete label keeps trailing local after end",
                source = """
                    while keep do
                      ::loop
                    end
                    local after = 1
                """.trimIndent(),
                requiredShapeFragments = listOf(
                    "While(Id(keep):Block[Label(Id(loop))])",
                    "Local(Id(after)=Const(1))"
                ),
                warningFragments = listOf("'::' expected")
            ),
            GotoLabelCase(
                name = "function body incomplete goto keeps outer label sibling",
                source = """
                    function hop()
                      goto
                    end
                    ::outer::
                """.trimIndent(),
                requiredShapeFragments = listOf(
                    "Function(Id(hop),Block[Goto(Id())])",
                    "Label(Id(outer))"
                ),
                warningFragments = listOf("<name> expected")
            )
        )
    }

    @Test
    fun residualGotoAndLabelNodesExposeEmptyOrNamedIdentifiers() {
        // Empty-name residual requires a non-NAME next token (keyword / number / eof).
        // `goto\nlocal …` is the stable empty-Id path; `goto\nprint(1)` is NOT empty
        // under current product (NAME is absorbed across the line break).
        val missingGoto = parseRecoveringWithoutThrow("goto\nlocal after = 1")
        val gotoStmt = assertIs<GotoStatement>(missingGoto.body.statements[0])
        assertEquals("", gotoStmt.identifier.name)
        assertTrue(gotoStmt.identifier.bad, "missing goto name should mark identifier bad")
        assertIs<LocalStatement>(missingGoto.body.statements[1])

        // Current product: parseStatementNameOrMissing accepts NAME even after a line
        // break, so the following identifier becomes the goto target (not a CallStmt).
        val absorbedName = parseRecoveringWithoutThrow("goto\nprint(1)")
        val absorbedGoto = assertIs<GotoStatement>(absorbedName.body.statements[0])
        assertEquals("print", absorbedGoto.identifier.name)
        assertFalse(
            absorbedGoto.identifier.bad,
            "NAME after goto is a real target under current product, not a bad empty Id"
        )
        assertTrue(
            absorbedName.body.statements.none { it is CallStatement },
            "print should not remain a sibling CallStmt when absorbed as goto target; shape=${renderShape(absorbedName)}"
        )
        // Trailing `(1)` is not a statement start; recovery should not throw.
        assertTrue(
            renderShape(absorbedName).contains("Goto(Id(print))"),
            "residual shape should keep absorbed goto target: ${renderShape(absorbedName)}"
        )

        val incompleteLabel = parseRecoveringWithoutThrow("::again\nlocal x = 1")
        val labelStmt = assertIs<LabelStatement>(incompleteLabel.body.statements[0])
        assertEquals("again", labelStmt.identifier.name)
        assertIs<LocalStatement>(incompleteLabel.body.statements[1])

        val emptyLabel = parseRecoveringWithoutThrow("::\ngoto target")
        val empty = assertIs<LabelStatement>(emptyLabel.body.statements[0])
        assertEquals("", empty.identifier.name)
        assertTrue(empty.identifier.bad, "missing label name should mark identifier bad")
        assertIs<GotoStatement>(emptyLabel.body.statements[1])
        assertEquals("target", (emptyLabel.body.statements[1] as GotoStatement).identifier.name)

        // Number after goto also forces empty residual name (not a NAME token).
        val gotoBeforeNumber = parseRecoveringWithoutThrow("goto 1\nprint(1)")
        val emptyBeforeNumber = assertIs<GotoStatement>(gotoBeforeNumber.body.statements[0])
        assertEquals("", emptyBeforeNumber.identifier.name)
        assertTrue(emptyBeforeNumber.identifier.bad)
        assertIs<CallStatement>(gotoBeforeNumber.body.statements[1])
    }

    @Test
    fun recoveryDiagnosticsAndShapesAreDeterministicForGotoLabelEdges() {
        val sources = listOf(
            "goto",
            "goto 1",
            "::",
            "::again",
            "::::",
            "goto\n::done::",
            "::start\ngoto",
            "do goto end\nprint(after)",
            "while keep do ::loop end\nlocal after = 1",
            "function hop() goto end\n::outer::",
            // Documented product: NAME after goto is absorbed; still deterministic.
            "goto\nprint(1)"
        )

        sources.forEach { source ->
            val first = parseWithDiagnosticsWithoutThrow(source, attempt = "first")
            val second = parseWithDiagnosticsWithoutThrow(source, attempt = "second")

            assertEquals(
                renderShape(first.chunk),
                renderShape(second.chunk),
                "recovered shape should be deterministic for: $source"
            )
            assertEquals(
                first.recoveryDiagnostics.map { it.message },
                second.recoveryDiagnostics.map { it.message },
                "diagnostic messages should be deterministic for: $source"
            )
            // Absorbed-NAME `goto\nprint(1)` is syntactically complete under current
            // product and may emit no recovery diagnostics; other edges must warn.
            if (source != "goto\nprint(1)") {
                assertTrue(
                    first.recoveryDiagnostics.isNotEmpty(),
                    "expected recovery diagnostics for malformed goto/label: $source"
                )
            }
            first.recoveryDiagnostics.forEach { diagnostic ->
                assertTrue(diagnostic.message.isNotBlank(), "diagnostic message must be non-blank")
                assertTrue(
                    diagnostic.range.start.line >= 1 && diagnostic.range.start.column >= 1,
                    "diagnostic range start must be positive: ${diagnostic.range}"
                )
            }
            val shape = renderShape(first.chunk)
            assertTrue(
                shape.contains("Goto(") || shape.contains("Label("),
                "residual shape should keep Goto/Label nodes for: $source\n$shape"
            )
        }
    }

    @Test
    fun strictParseRejectsMalformedGotoLabelWhileRecoveryDoesNotThrow() {
        val incompleteSources = listOf(
            "goto",
            "goto 1",
            "::",
            "::again",
            "::::",
            "goto\nlocal after = 1",
            "::start\nprint(1)",
            "do goto end",
            "function hop() goto end"
        )

        incompleteSources.forEach { source ->
            parseRecoveringWithoutThrow(source)

            val first = assertParseFails(LuaVersion.LUA_5_3, source, recovery = false)
            val second = assertParseFails(LuaVersion.LUA_5_3, source, recovery = false)
            assertEquals(first::class, second::class, "strict failure type for: $source")
            assertEquals(first.message, second.message, "strict failure message for: $source")
        }

        // Current product absorbs NAME after goto (`goto\nprint` is a normal jump).
        // With leftover tokens (`goto\nprint(1)`), recovery still does not throw and keeps
        // Goto(Id(print)); strict mode fails on the unexpected call args after the target.
        val absorbedWithLeftover = "goto\nprint(1)"
        val recovered = parseRecoveringWithoutThrow(absorbedWithLeftover)
        assertTrue(
            renderShape(recovered).contains("Goto(Id(print))"),
            "recovery residual should absorb NAME as goto target: ${renderShape(recovered)}"
        )
        val fail1 = assertParseFails(LuaVersion.LUA_5_3, absorbedWithLeftover, recovery = false)
        val fail2 = assertParseFails(LuaVersion.LUA_5_3, absorbedWithLeftover, recovery = false)
        assertEquals(fail1::class, fail2::class, "strict leftover after absorbed NAME failure type")
        assertEquals(fail1.message, fail2.message, "strict leftover after absorbed NAME failure message")

        // Bare absorbed NAME (no leftover) is well-formed in both modes.
        val bareAbsorbed = "goto\nprint"
        val bareRecovery = parseRecoveringWithoutThrow(bareAbsorbed)
        val bareStrict = parse(LuaVersion.LUA_5_3, bareAbsorbed, recovery = false)
        assertEquals(renderShape(bareRecovery), renderShape(bareStrict))
        assertTrue(renderShape(bareStrict).contains("Goto(Id(print))"), renderShape(bareStrict))
    }

    @Test
    fun wellFormedGotoLabelStillParseCleanlyUnderRecovery() {
        val wellFormed = listOf(
            "::again:: goto again",
            "goto again ::again::",
            "do ::inner:: goto inner end",
            "while keep do ::loop:: goto loop end",
            "function hop() ::start:: goto start end",
            "::a:: ::b:: goto a goto b"
        )

        wellFormed.forEach { source ->
            val result = parseWithDiagnosticsWithoutThrow(source)
            assertEquals(
                emptyList(),
                result.recoveryDiagnostics.map { it.message },
                "well-formed goto/label should not emit recovery diagnostics: $source"
            )
            val shape = renderShape(result.chunk)
            assertTrue(shape.contains("Goto(") || shape.contains("Label("), shape)
            val strict = parse(LuaVersion.LUA_5_3, source, recovery = false)
            assertEquals(renderShape(result.chunk), renderShape(strict))
        }
    }

    @Test
    fun dualPathStrictAcceptsAbsorbedGotoNameAcrossLineBreak() {
        // Dual-path footgun family (TASK-431): parseStatementNameOrMissing has no
        // statement-start-after-line-break guard, so a NAME after `goto` is always
        // the target. Bare absorbed names are well-formed on both paths; leftover
        // call args after the absorbed name remain REJECTS under strict.
        assertSupportedCases(
            GotoLabelCase(
                name = "goto absorbs next-line bare NAME on both paths",
                source = "goto\nprint",
                requiredShapeFragments = listOf("Goto(Id(print))"),
                strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
            ),
            GotoLabelCase(
                name = "goto absorbs next-line target then well-formed label dual-path",
                source = "goto\ntarget\n::target::",
                requiredShapeFragments = listOf(
                    "Goto(Id(target))",
                    "Label(Id(target))"
                ),
                strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
            ),
            GotoLabelCase(
                name = "goto same-line bare NAME remains clean dual-path",
                source = "goto again",
                requiredShapeFragments = listOf("Goto(Id(again))"),
                strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
            ),
            GotoLabelCase(
                // With leftover `(1)`, recovery still keeps Goto(Id(print)) without throw
                // while strict fails on unexpected call args — REJECTS dual-path contrast
                // against the bare absorbed CURRENTLY_ACCEPTS cases above.
                name = "goto absorbs next-line NAME then leftover call args: recovery keeps target, strict rejects",
                source = "goto\nprint(1)",
                requiredShapeFragments = listOf("Goto(Id(print))"),
                strictParseExpectation = StrictParseExpectation.REJECTS
            )
        )
    }

    @Test
    fun dualPathStrictRejectsMissingGotoLabelWhileRecordingAcceptGaps() {
        val rejects = requiredGotoLabelCases().filter {
            it.strictParseExpectation == StrictParseExpectation.REJECTS
        }
        assertTrue(rejects.isNotEmpty(), "inventory must retain REJECTS missing-goto/label cases")
        rejects.forEach { case ->
            parseRecoveringWithoutThrow(case.source, case.version)
            val fail1 = assertParseFails(case.version, case.source, recovery = false)
            val fail2 = assertParseFails(case.version, case.source, recovery = false)
            assertEquals(fail1::class, fail2::class, "${case.name} strict failure type")
            assertEquals(fail1.message, fail2.message, "${case.name} strict failure message")
        }

        val accepts = requiredGotoLabelCases().filter {
            it.strictParseExpectation == StrictParseExpectation.CURRENTLY_ACCEPTS
        }
        assertTrue(
            accepts.map { it.name }.any {
                it.contains("both paths") || it.contains("dual-path") || it.contains("bare NAME")
            },
            "inventory should document at least one CURRENTLY_ACCEPTS dual-path footgun"
        )
        accepts.forEach { case ->
            val first = parse(case.version, case.source, recovery = false)
            val second = parse(case.version, case.source, recovery = false)
            assertEquals(renderShape(first), renderShape(second), "${case.name} strict accepted shape")
            val recovered = parseRecoveringWithoutThrow(case.source, case.version)
            assertEquals(
                renderShape(first),
                renderShape(recovered),
                "${case.name} recovery and strict shapes should match for CURRENTLY_ACCEPTS"
            )
        }
    }

    @Test
    fun corpusInventoryCoversRequiredGotoLabelRecoveryFamilies() {
        val inventory = requiredGotoLabelCases()
        assertEquals(18, inventory.size)

        val names = inventory.map { it.name }.toSet()
        assertTrue(names.any { it.contains("goto alone") || it.contains("goto before") })
        assertTrue(names.any { it.contains("label missing") || it.contains("open double colon") })
        assertTrue(names.any { it.contains("nested") || it.contains("while") || it.contains("function") })
        assertTrue(names.any { it.contains("mixed") || it.contains("incomplete label then") })
        assertTrue(
            inventory.any { it.strictParseExpectation == StrictParseExpectation.CURRENTLY_ACCEPTS },
            "inventory must include dual-path CURRENTLY_ACCEPTS cases"
        )
        assertTrue(
            inventory.count { it.strictParseExpectation == StrictParseExpectation.CURRENTLY_ACCEPTS } >= 3,
            "expected at least three dual-path CURRENTLY_ACCEPTS footguns"
        )
        assertTrue(
            inventory.any { it.strictParseExpectation == StrictParseExpectation.REJECTS },
            "inventory must retain REJECTS missing-goto/label cases"
        )
        assertTrue(
            names.any { it.contains("absorbs next-line") || it.contains("bare NAME") },
            "inventory must document absorbed-NAME dual-path footguns"
        )
    }

    // --- helpers -----------------------------------------------------------------

    private fun requiredGotoLabelCases(): List<GotoLabelCase> {
        return listOf(
            GotoLabelCase("goto alone at eof inserts empty name", "goto", listOf("Goto(Id())"), warningFragments = listOf("<name> expected")),
            GotoLabelCase("goto before number inserts empty name and keeps number statement reachable", "goto 1\nprint(1)", listOf("Goto(Id())"), warningFragments = listOf("<name> expected")),
            GotoLabelCase("goto before keyword inserts empty name and keeps later local", "goto\nlocal after = 1", listOf("Local(Id(after)=Const(1))"), warningFragments = listOf("<name> expected")),
            GotoLabelCase("goto before another goto recovers both empty targets", "goto\ngoto", listOf("Goto(Id())"), warningFragments = listOf("<name> expected")),
            GotoLabelCase("open double colon at eof inserts empty label name", "::", listOf("Label(Id())"), warningFragments = listOf("<name> expected", "'::' expected")),
            GotoLabelCase("label missing closing colons keeps identifier", "::again", listOf("Label(Id(again))"), warningFragments = listOf("'::' expected")),
            GotoLabelCase("label missing name but has closing colons keeps empty Id", "::::", listOf("Label(Id())"), warningFragments = listOf("<name> expected")),
            GotoLabelCase("label missing closing colons keeps later print reachable", "::again\nprint(again)", listOf("CallStmt(Call(Id(print):Id(again)))"), warningFragments = listOf("'::' expected")),
            GotoLabelCase("label missing name keeps later goto sibling", "::\ngoto again\n::again::", listOf("Goto(Id(again))"), warningFragments = listOf("<name> expected")),
            GotoLabelCase("goto missing name then well-formed label keeps both", "goto\n::done::", listOf("Label(Id(done))"), warningFragments = listOf("<name> expected")),
            GotoLabelCase("incomplete label then incomplete goto keeps residual shapes", "::start\ngoto", listOf("Label(Id(start))", "Goto(Id())"), warningFragments = listOf("'::' expected", "<name> expected")),
            GotoLabelCase("nested do with incomplete goto keeps outer trailing print", "do\n  goto\nend\nprint(after)", listOf("Do(Block[Goto(Id())])"), warningFragments = listOf("<name> expected")),
            GotoLabelCase("while body incomplete label keeps trailing local after end", "while keep do\n  ::loop\nend\nlocal after = 1", listOf("Label(Id(loop))"), warningFragments = listOf("'::' expected")),
            GotoLabelCase("function body incomplete goto keeps outer label sibling", "function hop()\n  goto\nend\n::outer::", listOf("Function(Id(hop),Block[Goto(Id())])"), warningFragments = listOf("<name> expected")),
            // Dual-path CURRENTLY_ACCEPTS footguns (TASK-431): NAME after goto is absorbed
            // on both recovery and strict; bare forms are well-formed (no diagnostics).
            GotoLabelCase(
                name = "goto absorbs next-line bare NAME on both paths",
                source = "goto\nprint",
                requiredShapeFragments = listOf("Goto(Id(print))"),
                strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
            ),
            GotoLabelCase(
                name = "goto absorbs next-line target then well-formed label dual-path",
                source = "goto\ntarget\n::target::",
                requiredShapeFragments = listOf("Goto(Id(target))", "Label(Id(target))"),
                strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
            ),
            GotoLabelCase(
                name = "goto same-line bare NAME remains clean dual-path",
                source = "goto again",
                requiredShapeFragments = listOf("Goto(Id(again))"),
                strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
            ),
            GotoLabelCase(
                name = "goto absorbs next-line NAME then leftover call args: recovery keeps target, strict rejects",
                source = "goto\nprint(1)",
                requiredShapeFragments = listOf("Goto(Id(print))"),
                strictParseExpectation = StrictParseExpectation.REJECTS
            )
        )
    }

    private fun assertSupportedCases(vararg cases: GotoLabelCase) {
        cases.forEach(::assertSupportedCase)
    }

    private fun assertSupportedCase(case: GotoLabelCase) {
        val first = recoverTwiceAndAssertDeterministic(case)
        assertShapeFragments(case, first.chunk)
        assertWarningFragments(case, first.warnings)

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

    private fun recoverTwiceAndAssertDeterministic(case: GotoLabelCase): RecoveryRun {
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
            throw AssertionError("recovery parseWithDiagnostics must not throw during $attempt for: $source", failure)
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

    private fun assertShapeFragments(case: GotoLabelCase, chunk: ChunkNode) {
        val shape = renderShape(chunk)
        case.requiredShapeFragments.forEach { expected ->
            assertTrue(
                shape.contains(expected),
                "${case.name} should keep '$expected' reachable in recovered shape:\n$shape"
            )
        }
    }

    private fun assertWarningFragments(case: GotoLabelCase, warnings: List<String>) {
        case.warningFragments.forEach { expected ->
            assertTrue(
                warnings.any { it.contains(expected) },
                "${case.name} should emit warning containing '$expected'; actual warnings: $warnings"
            )
        }
    }

    private enum class StrictParseExpectation {
        REJECTS,
        CURRENTLY_ACCEPTS,
    }

    private data class GotoLabelCase(
        val name: String,
        val source: String,
        val requiredShapeFragments: List<String>,
        val version: LuaVersion = LuaVersion.LUA_5_3,
        val warningFragments: List<String> = emptyList(),
        val strictParseExpectation: StrictParseExpectation = StrictParseExpectation.REJECTS,
    )

    private data class RecoveryRun(
        val chunk: ChunkNode,
        val warnings: List<String>,
    )
}
