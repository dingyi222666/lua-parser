package parser.recovery

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.AttributeIdentifier
import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode
import io.github.dingyi222666.luaparser.parser.ast.node.BinaryExpression
import io.github.dingyi222666.luaparser.parser.ast.node.BlockNode
import io.github.dingyi222666.luaparser.parser.ast.node.CallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.CallStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.DoStatement
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.IfClause
import io.github.dingyi222666.luaparser.parser.ast.node.IfStatement
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ReturnStatement
import io.github.dingyi222666.luaparser.parser.ast.node.WhileStatement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import parser.assertParseFails
import parser.parse
import parser.renderShape

/**
 * Focused recovery corpus for incomplete Lua 5.4 local attributes (`<const>` / `<close>`).
 *
 * Acceptance (TASK-432):
 * - Malformed attribute locals recover without throw under Lua 5.4 recovery.
 * - Residual AttrId shapes are deterministic across re-parses (dual-path with strict).
 * - Later statements remain reachable siblings when product recovery does not absorb them.
 * - Test-only; no production LuaParser edits unless review re-scopes.
 *
 * Complements well-formed [parser.lua54.Lua54LocalAttributeConstTddTest] and the plain
 * local recovery family in [LuaParserRecoveryLocalAssignTddTest] with attribute edges.
 *
 * Goldens track current product behaviour in [LuaParser.parseAttribute] /
 * [LuaParser.parseAttrNameList] / [LuaParser.parseLocalVarList]:
 * - Lua 5.4 locals always build [AttributeIdentifier] (renderShape `AttrId(...)`),
 *   including bare names with null attribute (`AttrId(x)`);
 * - open `<` without a NAME inserts empty attribute text → `AttrId(x<>)` (empty string is
 *   not null, so renderShape always wraps `<>` when attributeName was assigned);
 * - missing `>` emits `'>' expected` and keeps the attribute name already parsed;
 * - missing local name before `<` yields empty AttrId name (`AttrId(<const>)`);
 * - missing initializer after attributed names reuses local explist recovery
 *   (`recoverFirstStatementLineBreak = false`), so a following `local`/`return` stays a
 *   sibling while an expression-start NAME may be absorbed as the initializer under both
 *   recovery and strict (CURRENTLY_ACCEPTS_MISSING_RHS dual-path);
 * - binary right-hand recovery still uses statement-start-after-line-break, so
 *   `local total<const> = value +\nlocal after` inserts ExpressionNodeSupport and keeps
 *   the later local as a sibling (strict rejects the non-expression `local`);
 * - non-5.4 policies do not enter parseAttrNameList; strict rejects attribute sources.
 */
class LuaParserRecoveryAttributeLocalTddTest {

    @Test
    fun recoversMissingAttributeCloserAndEmptyAttributeNameWithoutThrowing() {
        assertEquals(7, missingAttributeCloserCases.size)
        missingAttributeCloserCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun recoversMissingLocalNameAroundAttributesWithoutThrowing() {
        assertEquals(5, missingNameAroundAttributeCases.size)
        missingNameAroundAttributeCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun recoversIncompleteAttributedInitializersAndKeepsLaterStatements() {
        assertEquals(6, incompleteInitializerCases.size)
        incompleteInitializerCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun recoversNestedAttributedLocalsInsideBlocks() {
        assertEquals(4, nestedBlockCases.size)
        nestedBlockCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun residualAttributeIdentifiersExposeEmptyOrNamedFieldsAfterRecovery() {
        // Missing `>` keeps attributeName already parsed.
        val missingCloser = parseRecoveringWithoutThrow("local pinned<const\nlocal after = 1")
        val missingCloserLocal = assertIs<LocalStatement>(missingCloser.body.statements[0])
        val missingCloserId = assertIs<AttributeIdentifier>(missingCloserLocal.init.single())
        assertEquals("pinned", missingCloserId.name)
        assertEquals("const", missingCloserId.attributeName)
        assertTrue(missingCloserId.isLocal)
        assertIs<LocalStatement>(missingCloser.body.statements[1])

        // Open `<` without attribute NAME → empty attribute string (not null).
        val emptyAttr = parseRecoveringWithoutThrow("local x<>\nlocal after = 1")
        val emptyAttrLocal = assertIs<LocalStatement>(emptyAttr.body.statements[0])
        val emptyAttrId = assertIs<AttributeIdentifier>(emptyAttrLocal.init.single())
        assertEquals("x", emptyAttrId.name)
        assertEquals("", emptyAttrId.attributeName)
        assertTrue(emptyAttrId.bad, "empty attribute NAME should mark AttrId bad")
        assertIs<LocalStatement>(emptyAttr.body.statements[1])

        // Missing local name before `<const>` still yields AttributeIdentifier.
        val missingName = parseRecoveringWithoutThrow("local <const> = 1\nreturn after")
        val missingNameLocal = assertIs<LocalStatement>(missingName.body.statements[0])
        val missingNameId = assertIs<AttributeIdentifier>(missingNameLocal.init.single())
        assertEquals("", missingNameId.name)
        assertEquals("const", missingNameId.attributeName)
        assertTrue(missingNameId.bad)
        assertTrue(missingNameLocal.bad)
        assertIs<ReturnStatement>(missingName.body.returnStatement)

        // Multi-name trailing incomplete attribute.
        val multi = parseRecoveringWithoutThrow("local a<const>, b<\nlocal after = 1")
        val multiLocal = assertIs<LocalStatement>(multi.body.statements[0])
        assertEquals(2, multiLocal.init.size)
        val first = assertIs<AttributeIdentifier>(multiLocal.init[0])
        val second = assertIs<AttributeIdentifier>(multiLocal.init[1])
        assertEquals("a" to "const", first.name to first.attributeName)
        assertEquals("b" to "", second.name to second.attributeName)
        assertTrue(second.bad)
        assertIs<LocalStatement>(multi.body.statements[1])
    }

    @Test
    fun recoveryDiagnosticsAndShapesAreDeterministicForAttributeEdges() {
        val sources = listOf(
            "local x<const",
            "local x<>",
            "local x<",
            "local <const> = 1",
            "local a<const>, b<",
            "local pinned<const> =\nlocal after = 1",
            "local a<const>, b<close> = 1,\nlocal after = 2",
            "do\n  local file<close\nend\nprint(after)"
        )

        sources.forEach { source ->
            val first = parseRecoveringWithWarnings(LuaVersion.LUA_5_4, source)
            val second = parseRecoveringWithWarnings(LuaVersion.LUA_5_4, source)

            assertEquals(renderShape(first.chunk), renderShape(second.chunk), "shape deterministic: $source")
            assertEquals(first.warnings, second.warnings, "warnings deterministic: $source")
        }
    }

    @Test
    fun strictParseRejectsMalformedAttributeLocalsWhileAcceptingWellFormed() {
        val rejects = allCases().filter {
            it.strictParseExpectation == StrictParseExpectation.REJECTS
        }
        assertTrue(rejects.isNotEmpty())
        rejects.forEach { case ->
            parseRecoveringWithoutThrow(case, attempt = "strict-reject-recovery-guard")
            assertStrictParseProducesDeterministicFailure(case)
        }

        val accepts = allCases().filter {
            it.strictParseExpectation == StrictParseExpectation.CURRENTLY_ACCEPTS ||
                it.strictParseExpectation == StrictParseExpectation.CURRENTLY_ACCEPTS_MISSING_RHS
        }
        assertTrue(accepts.isNotEmpty())
        accepts.forEach(::assertStrictParseCurrentlyAccepts)
    }

    @Test
    fun wellFormedAttributedLocalsStillParseCleanlyUnderRecovery() {
        val wellFormed = listOf(
            "local x<const> = 1",
            "local file<close> = open()",
            "local pinned <const> = 1",
            "local a<const>, b<close>, c = ...",
            "local bare, tagged <const> = 1, 2",
            "do local value<const> = 1 end",
            "local frozen<const>\nprint(frozen)"
        )

        wellFormed.forEach { source ->
            val recovered = parseRecoveringWithWarnings(LuaVersion.LUA_5_4, source)
            assertEquals(
                emptyList(),
                recovered.warnings,
                "well-formed attributed local should not emit recovery diagnostics: $source"
            )
            val strict = parse(LuaVersion.LUA_5_4, source, recovery = false)
            assertEquals(renderShape(recovered.chunk), renderShape(strict), source)
            assertTrue(
                renderShape(recovered.chunk).contains("AttrId("),
                "Lua 5.4 local names should render as AttrId: ${renderShape(recovered.chunk)}"
            )
        }
    }

    @Test
    fun nonLua54PoliciesStillRejectAttributedLocalsInStrictMode() {
        val sources = listOf(
            "local x<const> = 1",
            "local file<close> = open()",
            "local a<const>, b<close>, c = ..."
        )
        val versions = listOf(LuaVersion.LUA_5_3, LuaVersion.ANDROLUA_5_3)

        versions.forEach { version ->
            sources.forEach { source ->
                val failure = assertParseFails(version, source, recovery = false)
                assertTrue(
                    failure.message?.isNotBlank() == true || failure is Throwable,
                    "expected strict rejection for $version source=$source"
                )
            }
        }
    }

    @Test
    fun documentsAttributeLocalRecoveryInventorySizeAndCoverage() {
        assertEquals(
            missingAttributeCloserCases.size +
                missingNameAroundAttributeCases.size +
                incompleteInitializerCases.size +
                nestedBlockCases.size,
            allCases().size
        )
        assertEquals(22, allCases().size)

        val names = allCases().map { it.name }
        assertTrue(names.any { it.contains("missing") && it.contains(">") })
        assertTrue(names.any { it.contains("empty attribute") || it.contains("open <") })
        assertTrue(names.any { it.contains("missing name") || it.contains("missing local name") })
        assertTrue(names.any { it.contains("initializer") || it.contains("rhs") })
        assertTrue(names.any { it.contains("do") || it.contains("while") || it.contains("function") })
        assertTrue(
            allCases().any {
                it.strictParseExpectation == StrictParseExpectation.CURRENTLY_ACCEPTS
            }
        )
        assertTrue(
            allCases().any {
                it.strictParseExpectation == StrictParseExpectation.CURRENTLY_ACCEPTS_MISSING_RHS
            },
            "inventory should document dual-path missing-RHS absorb cases"
        )
        assertTrue(
            allCases().all { it.version == LuaVersion.LUA_5_4 },
            "attribute recovery corpus is Lua 5.4-scoped"
        )
    }

    // --- helpers -----------------------------------------------------------------

    private fun assertSupportedRecoveryCase(case: RecoveryCase) {
        val first = recoverTwiceAndAssertDeterministic(case)
        assertShapeFragments(case, first.chunk)
        assertBadShapeFragments(case, first.chunk)
        assertWarningFragments(case, first.warnings)
        when (case.strictParseExpectation) {
            StrictParseExpectation.REJECTS -> assertStrictParseProducesDeterministicFailure(case)
            StrictParseExpectation.CURRENTLY_ACCEPTS,
            StrictParseExpectation.CURRENTLY_ACCEPTS_MISSING_RHS -> assertStrictParseCurrentlyAccepts(case)
        }
    }

    private fun recoverTwiceAndAssertDeterministic(case: RecoveryCase): RecoveryRun {
        val first = parseRecoveringWithoutThrow(case, attempt = "first")
        val second = parseRecoveringWithoutThrow(case, attempt = "second")

        assertEquals(renderShape(first.chunk), renderShape(second.chunk), "${case.name} recovered shape")
        assertEquals(first.warnings, second.warnings, "${case.name} warning stream")

        return first
    }

    private fun parseRecoveringWithoutThrow(case: RecoveryCase, attempt: String): RecoveryRun {
        try {
            return parseRecoveringWithWarnings(case.version, case.source)
        } catch (failure: Throwable) {
            throw AssertionError("${case.name} should recover without throwing during $attempt parse", failure)
        }
    }

    private fun parseRecoveringWithoutThrow(
        source: String,
        version: LuaVersion = LuaVersion.LUA_5_4,
    ): ChunkNode {
        return try {
            LuaParser(luaVersion = version, errorRecovery = true).parse(source)
        } catch (failure: Throwable) {
            throw AssertionError("recovery parse must not throw for: $source", failure)
        }
    }

    private fun parseRecoveringWithWarnings(version: LuaVersion, source: String): RecoveryRun {
        val result = LuaParser(luaVersion = version, errorRecovery = true)
            .parseWithDiagnostics(source)

        return RecoveryRun(
            chunk = result.chunk,
            warnings = result.recoveryDiagnostics.map { it.message }
        )
    }

    private fun assertStrictParseProducesDeterministicFailure(case: RecoveryCase) {
        val first = assertParseFails(case.version, case.source, recovery = false)
        val second = assertParseFails(case.version, case.source, recovery = false)

        assertEquals(first::class, second::class, "${case.name} strict failure type")
        assertEquals(first.message, second.message, "${case.name} strict failure message")
    }

    private fun assertStrictParseCurrentlyAccepts(case: RecoveryCase) {
        val first = parse(case.version, case.source, recovery = false)
        val second = parse(case.version, case.source, recovery = false)

        assertEquals(renderShape(first), renderShape(second), "${case.name} strict accepted shape")
    }

    private fun assertShapeFragments(case: RecoveryCase, chunk: ChunkNode) {
        val shape = renderShape(chunk)
        case.requiredShapeFragments.forEach { expected ->
            assertTrue(
                shape.contains(expected),
                "${case.name} should keep '$expected' reachable in recovered shape:\n$shape"
            )
        }
    }

    private fun assertBadShapeFragments(case: RecoveryCase, chunk: ChunkNode) {
        if (case.badShapeFragments.isEmpty()) {
            return
        }
        val badShapes = collectBadNodes(chunk).map(::renderShape)
        case.badShapeFragments.forEach { expected ->
            assertTrue(
                badShapes.any { it.contains(expected) },
                "${case.name} should mark a recovered node containing '$expected' as bad; actual bad nodes: $badShapes"
            )
        }
    }

    private fun assertWarningFragments(case: RecoveryCase, warnings: List<String>) {
        case.warningFragments.forEach { expected ->
            assertTrue(
                warnings.any { it.contains(expected) },
                "${case.name} should emit warning containing '$expected'; actual warnings: $warnings"
            )
        }
    }

    private fun collectBadNodes(root: BaseASTNode): List<BaseASTNode> {
        val result = mutableListOf<BaseASTNode>()

        fun visit(node: BaseASTNode) {
            if (node.bad) {
                result += node
            }
            childrenOf(node).forEach(::visit)
        }

        visit(root)
        return result
    }

    private fun childrenOf(node: BaseASTNode): List<BaseASTNode> {
        // Must walk nested expression trees so bad ExpressionNodeSupport placeholders
        // under Binary/Call RHS are visible to assertBadShapeFragments (same family as
        // LuaParserRecoveryLocalAssignTddTest). Incomplete walk was the REVIEW40 failure
        // for recoversIncompleteAttributedInitializersAndKeepsLaterStatements.
        return when (node) {
            is ChunkNode -> listOf(node.body)
            is BlockNode -> node.statements + listOfNotNull(node.returnStatement)
            is ReturnStatement -> node.arguments
            is LocalStatement -> node.init + node.variables
            is CallStatement -> listOf(node.expression)
            is DoStatement -> listOf(node.body)
            is WhileStatement -> listOf(node.condition, node.body)
            is IfStatement -> node.causes
            is IfClause -> listOf(node.condition, node.body)
            is FunctionDeclaration -> listOfNotNull(node.identifier, node.body) + node.params
            is BinaryExpression -> listOfNotNull(node.left, node.right)
            is CallExpression -> listOf(node.base) + node.arguments
            is AttributeIdentifier -> emptyList()
            else -> emptyList()
        }
    }

    private fun allCases(): List<RecoveryCase> {
        return missingAttributeCloserCases +
            missingNameAroundAttributeCases +
            incompleteInitializerCases +
            nestedBlockCases
    }

    private enum class StrictParseExpectation {
        REJECTS,
        CURRENTLY_ACCEPTS,
        /**
         * Strict mode currently accepts a missing local RHS by absorbing the next
         * expression-start statement (e.g. next-line `print(...)`) as the initializer.
         * Recovery with `recoverFirstStatementLineBreak = false` does the same for NAME
         * starts; sibling reachability is documented via `local`/`return` residual cases.
         */
        CURRENTLY_ACCEPTS_MISSING_RHS,
    }

    private data class RecoveryCase(
        val name: String,
        val source: String,
        val version: LuaVersion = LuaVersion.LUA_5_4,
        val requiredShapeFragments: List<String>,
        val badShapeFragments: List<String> = emptyList(),
        val warningFragments: List<String> = emptyList(),
        val strictParseExpectation: StrictParseExpectation = StrictParseExpectation.REJECTS,
    )

    private data class RecoveryRun(
        val chunk: ChunkNode,
        val warnings: List<String>,
    )

    // Missing `>` / empty attribute NAME after `<`.
    private val missingAttributeCloserCases = listOf(
        RecoveryCase(
            name = "const attribute missing > keeps later local",
            source = "local pinned<const\nlocal after = 1\nreturn after",
            requiredShapeFragments = listOf(
                "Local(AttrId(pinned<const>)=)",
                "Local(AttrId(after)=Const(1))",
                "Return(Id(after))"
            ),
            warningFragments = listOf("'>' expected")
        ),
        RecoveryCase(
            name = "close attribute missing > keeps later print",
            source = "local file<close\nprint(file)",
            requiredShapeFragments = listOf(
                "Local(AttrId(file<close>)=)",
                "CallStmt(Call(Id(print):Id(file)))"
            ),
            warningFragments = listOf("'>' expected")
        ),
        RecoveryCase(
            name = "empty attribute name with both brackets keeps later local",
            source = "local x<>\nlocal after = 1",
            requiredShapeFragments = listOf(
                "Local(AttrId(x<>)=)",
                "Local(AttrId(after)=Const(1))"
            ),
            badShapeFragments = listOf("AttrId(x<>)"),
            warningFragments = listOf("<name> expected")
        ),
        RecoveryCase(
            name = "open < without attribute name at eof still recovers",
            source = "local x<",
            requiredShapeFragments = listOf("Local(AttrId(x<>)=)"),
            badShapeFragments = listOf("AttrId(x<>)"),
            warningFragments = listOf("<name> expected", "'>' expected")
        ),
        RecoveryCase(
            name = "open < without attribute name keeps later local",
            source = "local x<\nlocal after = 1",
            requiredShapeFragments = listOf(
                "Local(AttrId(x<>)=)",
                "Local(AttrId(after)=Const(1))"
            ),
            badShapeFragments = listOf("AttrId(x<>)"),
            warningFragments = listOf("<name> expected", "'>' expected")
        ),
        RecoveryCase(
            name = "multi-name second attribute missing > keeps later local",
            source = "local a<const>, b<close\nlocal after = 1",
            requiredShapeFragments = listOf(
                "Local(AttrId(a<const>),AttrId(b<close>)=)",
                "Local(AttrId(after)=Const(1))"
            ),
            warningFragments = listOf("'>' expected")
        ),
        RecoveryCase(
            name = "multi-name second attribute open < keeps later local",
            source = "local a<const>, b<\nlocal after = 1",
            requiredShapeFragments = listOf(
                "Local(AttrId(a<const>),AttrId(b<>)=)",
                "Local(AttrId(after)=Const(1))"
            ),
            badShapeFragments = listOf("AttrId(b<>)"),
            warningFragments = listOf("<name> expected", "'>' expected")
        )
    )

    // Missing local NAME around / before attribute syntax.
    private val missingNameAroundAttributeCases = listOf(
        RecoveryCase(
            name = "missing local name before <const> keeps later return",
            source = "local <const> = 1\nreturn after",
            requiredShapeFragments = listOf(
                "Local(AttrId(<const>)=Const(1))",
                "Return(Id(after))"
            ),
            badShapeFragments = listOf("AttrId(<const>)"),
            warningFragments = listOf("<name> expected")
        ),
        RecoveryCase(
            name = "missing local name before <close> keeps later local",
            source = "local <close> = open()\nlocal after = 1",
            requiredShapeFragments = listOf(
                "Local(AttrId(<close>)=Call(Id(open):))",
                "Local(AttrId(after)=Const(1))"
            ),
            badShapeFragments = listOf("AttrId(<close>)"),
            warningFragments = listOf("<name> expected")
        ),
        RecoveryCase(
            name = "local alone at eof still recovers empty AttrId",
            source = "local",
            requiredShapeFragments = listOf("Local(AttrId()=)"),
            badShapeFragments = listOf("Local(AttrId()=)"),
            warningFragments = listOf("<name> expected")
        ),
        RecoveryCase(
            name = "local multi-name missing second name after attributed first",
            source = "local a<const>, =\nlocal after = 1",
            requiredShapeFragments = listOf(
                "Local(AttrId(a<const>),AttrId()=ExpressionNodeSupport)",
                "Local(AttrId(after)=Const(1))"
            ),
            badShapeFragments = listOf("AttrId()"),
            warningFragments = listOf("<name> expected")
        ),
        RecoveryCase(
            // Bare namelist without `=` is valid Lua; recovery must keep later print
            // and still render Lua 5.4 bare names as AttrId with null attribute.
            name = "bare attributed-less namelist without equals keeps following print",
            source = "local value\nprint(value)",
            requiredShapeFragments = listOf(
                "Local(AttrId(value)=)",
                "CallStmt(Call(Id(print):Id(value)))"
            ),
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
        )
    )

    // Incomplete initializers after attributed names (local explist recovery).
    // Dual-path notes (REVIEW40 fix):
    // - sibling `local`/`return` after `=` → ExpressionNodeSupport + later stmt; strict REJECTS
    // - next-line expression-start NAME (`print`) → absorbed as RHS under recovery and strict
    //   (CURRENTLY_ACCEPTS_MISSING_RHS); product local explist uses recoverFirstStatementLineBreak=false
    // - binary RHS still recovers on statement-start-after-line-break even for locals
    private val incompleteInitializerCases = listOf(
        RecoveryCase(
            name = "const local missing initializer keeps later local",
            source = "local pinned<const> =\nlocal after = 1\nreturn after",
            requiredShapeFragments = listOf(
                "Local(AttrId(pinned<const>)=ExpressionNodeSupport)",
                "Local(AttrId(after)=Const(1))",
                "Return(Id(after))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "close local missing initializer keeps following return",
            source = "local file<close> =\nreturn file",
            requiredShapeFragments = listOf(
                "Local(AttrId(file<close>)=ExpressionNodeSupport)",
                "Return(Id(file))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "mixed attributes missing trailing rhs keeps later local",
            source = "local a<const>, b<close> = 1,\nlocal after = 2",
            requiredShapeFragments = listOf(
                "Local(AttrId(a<const>),AttrId(b<close>)=Const(1),ExpressionNodeSupport)",
                "Local(AttrId(after)=Const(2))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            // Local first-RHS does not recover on statement-start-after-line-break for
            // expression-start tokens, so next-line `print` is absorbed as initializer
            // under both recovery and strict (same family as plain local unary/RHS).
            name = "const local missing initializer absorbs following print as rhs",
            source = "local pinned<const> =\nprint(pinned)",
            requiredShapeFragments = listOf(
                "Local(AttrId(pinned<const>)=Call(Id(print):Id(pinned)))"
            ),
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS_MISSING_RHS
        ),
        RecoveryCase(
            // Binary right uses statement-start-after-line-break recovery, so next-line
            // `local` is not absorbed; ExpressionNodeSupport is nested under Binary and
            // must be reachable via childrenOf(BinaryExpression).
            name = "attributed local binary initializer missing operand keeps later local",
            source = "local total<const> = value +\nlocal after = 1",
            requiredShapeFragments = listOf(
                "Local(AttrId(total<const>)=Binary(+,Id(value),ExpressionNodeSupport))",
                "Local(AttrId(after)=Const(1))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "well-formed const local with later print remains clean dual-path",
            source = "local pinned<const> = 1\nprint(pinned)",
            requiredShapeFragments = listOf(
                "Local(AttrId(pinned<const>)=Const(1))",
                "CallStmt(Call(Id(print):Id(pinned)))"
            ),
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
        )
    )

    // Nested / blocked contexts still keep later statements reachable.
    private val nestedBlockCases = listOf(
        RecoveryCase(
            name = "missing > on close attribute inside do keeps trailing print after end",
            source = """
                do
                  local file<close
                end
                print(after)
            """.trimIndent(),
            requiredShapeFragments = listOf(
                "Do(Block[Local(AttrId(file<close>)=)])",
                "CallStmt(Call(Id(print):Id(after)))"
            ),
            warningFragments = listOf("'>' expected")
        ),
        RecoveryCase(
            name = "missing attributed initializer inside if then keeps trailing print after end",
            source = """
                if ready then
                  local step<const> =
                end
                print(ready)
            """.trimIndent(),
            requiredShapeFragments = listOf(
                "Local(AttrId(step<const>)=ExpressionNodeSupport)",
                "CallStmt(Call(Id(print):Id(ready)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "empty attribute name inside while keeps trailing local after end",
            source = """
                while ready do
                  local x<>
                end
                local after = 1
            """.trimIndent(),
            requiredShapeFragments = listOf(
                "Local(AttrId(x<>)=)",
                "Local(AttrId(after)=Const(1))"
            ),
            badShapeFragments = listOf("AttrId(x<>)"),
            warningFragments = listOf("<name> expected")
        ),
        RecoveryCase(
            name = "missing > on const attribute inside function keeps trailing print after end",
            source = """
                function openHandle()
                  local limit<const
                end
                print(done)
            """.trimIndent(),
            requiredShapeFragments = listOf(
                "Function(Id(openHandle),Block[Local(AttrId(limit<const>)=)])",
                "CallStmt(Call(Id(print):Id(done)))"
            ),
            warningFragments = listOf("'>' expected")
        )
    )
}
