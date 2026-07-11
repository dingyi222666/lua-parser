package semantic.binder

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.AttributeIdentifier
import io.github.dingyi222666.luaparser.parser.ast.node.CallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.ConstantNode
import io.github.dingyi222666.luaparser.parser.ast.node.DoStatement
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.parser.ast.node.Range
import io.github.dingyi222666.luaparser.parser.ast.node.TableConstructorExpression
import io.github.dingyi222666.luaparser.parser.ast.node.VarargLiteral
import io.github.dingyi222666.luaparser.semantic.binder.BinderDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.BinderPass
import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOwner
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachPass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Binder multi-local extra shape corpus (TASK-361).
 *
 * Complements [BinderMultiLocalSameLineRangeTddTest] (TASK-285) and
 * [BinderMultiAssignRangeTddTest] (TASK-195) with additional product-surface
 * shapes that those corpora do not lock:
 * - AST quirk: [LocalStatement.init] = declared names, [LocalStatement.variables] = RHS
 * - multi-value RHS forms (call / table / function / vararg) still bind only names
 * - shared Lexical owner + same scope for co-declared names
 * - shadowing outer locals with same-line multi-local rebinds
 * - attribute multi-locals (Lua 5.4) keep per-name anchors
 * - if / while / function-body multi-locals stay block-local
 * - underscore / digit-dense packing and half-open query boundaries
 *
 * Test-only; production defects surface as assertion failures (review-owned
 * verification via `jvmTest --tests semantic.binder.BinderMultiLocalExtraShapeTddTest`).
 */
class BinderMultiLocalExtraShapeTddTest {

    private val parser = LuaParser()
    private val lua54Parser = LuaParser(LuaVersion.LUA_5_4)

    // --- AST quirk: init = names, variables = RHS --------------------------------

    @Test
    fun localStatementInitAreNamesAndVariablesAreRhs_binderAnchorsOnlyInit() {
        val source = "local first, second = 1, true"
        val chunk = parser.parse(source)
        val statement = chunk.body.statements.filterIsInstance<LocalStatement>().single()
        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))

        // Legacy mapping (do not invert): init = names, variables = RHS values.
        assertEquals(2, statement.init.size)
        assertEquals(listOf("first", "second"), statement.init.map { it.name })
        assertTrue(statement.init.all { it.isLocal })
        assertEquals(2, statement.variables.size)
        assertIs<ConstantNode>(statement.variables[0])
        assertIs<ConstantNode>(statement.variables[1])

        val first = localOf(result, "first")
        val second = localOf(result, "second")
        assertSame(statement.init[0], first.anchorNode)
        assertSame(statement.init[1], second.anchorNode)
        assertEquals(statement.init[0].range, first.range)
        assertEquals(statement.init[1].range, second.range)

        // RHS constants are not declaration anchors.
        assertNull(result.positionQueries.getDeclarationAt(positionOf(source, "1")))
        assertNull(result.positionQueries.getDeclarationAt(positionOf(source, "true")))
    }

    @Test
    fun multiLocalParentsPointAtLocalStatementForInitAndVariables() {
        val source = "local left, right = pair(), extra"
        val chunk = parser.parse(source)
        val statement = chunk.body.statements.filterIsInstance<LocalStatement>().single()

        statement.init.forEach { name ->
            assertSame(statement, name.parent, "init name parent must be LocalStatement")
        }
        statement.variables.forEach { value ->
            assertSame(statement, value.parent, "variables RHS parent must be LocalStatement")
        }

        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        assertEquals(2, nonBuiltinLocals(result).size)
        assertEquals(listOf("left", "right"), nonBuiltinLocals(result).map { it.name })
        assertIs<CallExpression>(statement.variables[0])
        assertIs<Identifier>(statement.variables[1])
    }

    @Test
    fun bareMultiLocalKeepsEmptyVariablesListAndStillBindsInitNames() {
        val source = "local bareLeft, bareRight"
        val chunk = parser.parse(source)
        val statement = chunk.body.statements.filterIsInstance<LocalStatement>().single()
        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))

        assertEquals(2, statement.init.size)
        assertTrue(statement.variables.isEmpty())
        assertEquals(listOf("bareLeft", "bareRight"), nonBuiltinLocals(result).map { it.name })
        assertSame(statement.init[0], localOf(result, "bareLeft").anchorNode)
        assertSame(statement.init[1], localOf(result, "bareRight").anchorNode)
    }

    // --- multi-value RHS forms still bind only LHS names -------------------------

    @Test
    fun multiLocalFromCallRhs_bindsOnlyDeclaredNames() {
        val source = "local left, right = pair()"
        val chunk = parser.parse(source)
        val statement = chunk.body.statements.filterIsInstance<LocalStatement>().single()
        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))

        assertEquals(1, statement.variables.size)
        assertIs<CallExpression>(statement.variables.single())
        assertEquals(listOf("left", "right"), nonBuiltinLocals(result).map { it.name })

        listOf("left", "right").forEach { name ->
            val declaration = localOf(result, name)
            assertPerNameRange(source, declaration, name)
            assertEquals(declaration, result.positionQueries.getDeclarationAt(positionOf(source, name)))
        }
        assertRangesDisjoint(localOf(result, "left").range!!, localOf(result, "right").range!!)
    }

    @Test
    fun multiLocalFromTableAndFunctionRhs_bindsOnlyNamesNotRhsForms() {
        val source = "local cfg, handler = { enabled = true }, function(v) return v end"
        val chunk = parser.parse(source)
        val statement = chunk.body.statements.filterIsInstance<LocalStatement>().single()
        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))

        assertEquals(2, statement.variables.size)
        assertIs<TableConstructorExpression>(statement.variables[0])
        assertIs<FunctionDeclaration>(statement.variables[1])

        assertEquals(listOf("cfg", "handler"), nonBuiltinLocals(result).map { it.name })
        // Function param `v` is a parameter, not a multi-local sibling.
        assertEquals(1, result.declarationIndex.declarations.count {
            it.name == "v" && it.kind == DeclarationKind.PARAMETER
        })
        assertNull(result.positionQueries.getDeclarationAt(positionOf(source, "enabled")))
    }

    @Test
    fun multiLocalFromVarargRhs_bindsLhsNamesOnly() {
        val source = """
            local function wrap(...)
                local a, b = ...
            end
            """.trimIndent()
        val chunk = parser.parse(source)
        val localInBody = chunk.body.statements
            .filterIsInstance<FunctionDeclaration>()
            .single()
            .body!!
            .statements
            .filterIsInstance<LocalStatement>()
            .single()
        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))

        assertEquals(1, localInBody.variables.size)
        assertIs<VarargLiteral>(localInBody.variables.single())
        assertEquals(listOf("a", "b"), listOf(localOf(result, "a"), localOf(result, "b")).map { it.name })
        assertPerNameRange(source, localOf(result, "a"), "a")
        assertPerNameRange(source, localOf(result, "b"), "b")
    }

    @Test
    fun moreRhsThanLhs_extraValuesDoNotBecomeDeclarations() {
        val source = "local onlyLeft, onlyRight = 1, 2, 3, orphan"
        val result = bind(source)
        val locals = nonBuiltinLocals(result)

        assertEquals(listOf("onlyLeft", "onlyRight"), locals.map { it.name })
        assertTrue(locals.none { it.name == "orphan" || it.name == "1" || it.name == "2" || it.name == "3" })
        assertNull(result.positionQueries.getDeclarationAt(positionOf(source, "orphan")))
    }

    @Test
    fun fewerRhsThanLhs_stillBindsEveryInitName() {
        val source = "local one, two, three = single()"
        val chunk = parser.parse(source)
        val statement = chunk.body.statements.filterIsInstance<LocalStatement>().single()
        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))

        assertEquals(3, statement.init.size)
        assertEquals(1, statement.variables.size)
        assertEquals(listOf("one", "two", "three"), nonBuiltinLocals(result).map { it.name })
        listOf("one", "two", "three").forEach { name ->
            assertPerNameRange(source, localOf(result, name), name)
        }
    }

    // --- shared owner / same scope for co-declared names -------------------------

    @Test
    fun coDeclaredSameLineLocalsShareLexicalOwnerAndRootScope() {
        val source = "local alpha, beta, gamma = 1, 2, 3"
        val chunk = parser.parse(source)
        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        val locals = listOf("alpha", "beta", "gamma").map { localOf(result, it) }
        val owner = DeclarationOwner.Lexical(chunk.body)

        locals.forEach { declaration ->
            assertEquals(owner, declaration.owner)
            assertTrue(result.scopeGraph.getDeclarations(result.scopeGraph.rootScope.id).contains(declaration.id))
        }
        // Distinct symbols even under the same owner/scope.
        assertEquals(3, locals.map { it.symbolId }.toSet().size)
        assertEquals(3, locals.map { it.id }.toSet().size)
    }

    @Test
    fun nestedBlockMultiLocalsShareBlockOwnerNotRoot() {
        val source = """
            local outerA, outerB = 1, 2
            do
                local innerA, innerB = 3, 4
            end
            """.trimIndent()
        val chunk = parser.parse(source)
        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        val doBody = chunk.body.statements
            .filterIsInstance<DoStatement>()
            .single()
            .body

        assertEquals(DeclarationOwner.Lexical(chunk.body), localOf(result, "outerA").owner)
        assertEquals(DeclarationOwner.Lexical(chunk.body), localOf(result, "outerB").owner)
        assertEquals(DeclarationOwner.Lexical(doBody), localOf(result, "innerA").owner)
        assertEquals(DeclarationOwner.Lexical(doBody), localOf(result, "innerB").owner)

        val blockScope = assertNotNull(result.scopeGraph.getScope(doBody))
        assertTrue(result.scopeGraph.getDeclarations(blockScope.id).contains(localOf(result, "innerA").id))
        assertTrue(result.scopeGraph.getDeclarations(blockScope.id).contains(localOf(result, "innerB").id))
        assertTrue(result.scopeGraph.getDeclarations(result.scopeGraph.rootScope.id).contains(localOf(result, "outerA").id))
    }

    // --- shadowing / rebind shapes -----------------------------------------------

    @Test
    fun sameLineMultiLocalShadowsOuterSameNamesWithDistinctDeclarations() {
        val source = """
            local left, right = 1, 2
            do
                local left, right = 3, 4
            end
            """.trimIndent()
        val result = bind(source)
        val lefts = nonBuiltinLocals(result).filter { it.name == "left" }
        val rights = nonBuiltinLocals(result).filter { it.name == "right" }

        assertEquals(2, lefts.size)
        assertEquals(2, rights.size)
        assertNotEquals(lefts[0].id, lefts[1].id)
        assertNotEquals(rights[0].id, rights[1].id)
        assertNotEquals(lefts[0].symbolId, lefts[1].symbolId)

        // Position queries hit declaration-site ranges only; both sites remain queryable.
        assertEquals(lefts[0], result.positionQueries.getDeclarationAt(positionOf(source, "left", occurrence = 1)))
        assertEquals(lefts[1], result.positionQueries.getDeclarationAt(positionOf(source, "left", occurrence = 2)))
        assertEquals(rights[0], result.positionQueries.getDeclarationAt(positionOf(source, "right", occurrence = 1)))
        assertEquals(rights[1], result.positionQueries.getDeclarationAt(positionOf(source, "right", occurrence = 2)))
    }

    @Test
    fun multiLocalRhsIdentifierUsesOuterBindingNotSiblingBeingDeclared() {
        // Lua semantics: RHS of `local a, b = a, 1` sees outer `a`, not the new local.
        // Binder surface: only the two new locals are declared; outer remains separate.
        val source = """
            local a = 10
            local a, b = a, 1
            """.trimIndent()
        val result = bind(source)
        val aDecls = nonBuiltinLocals(result).filter { it.name == "a" }

        assertEquals(2, aDecls.size)
        assertEquals(1, nonBuiltinLocals(result).count { it.name == "b" })
        assertEquals(aDecls[0], result.positionQueries.getDeclarationAt(positionOf(source, "a", occurrence = 1)))
        assertEquals(aDecls[1], result.positionQueries.getDeclarationAt(positionOf(source, "a", occurrence = 2)))
        // Third `a` is a usage (RHS), not a declaration site.
        assertNull(result.positionQueries.getDeclarationAt(positionOf(source, "a", occurrence = 3)))
    }

    // --- control-flow / function-body multi-locals -------------------------------

    @Test
    fun ifThenMultiLocalsAreConditionalBlockLocals() {
        val source = """
            if ok then
                local yesA, yesB = 1, 2
            else
                local noA, noB = 3, 4
            end
            """.trimIndent()
        val result = bind(source)

        listOf("yesA", "yesB", "noA", "noB").forEach { name ->
            val declaration = localOf(result, name)
            assertPerNameRange(source, declaration, name)
            assertEquals(declaration, result.positionQueries.getDeclarationAt(positionOf(source, name)))
        }
        assertRangesDisjoint(localOf(result, "yesA").range!!, localOf(result, "yesB").range!!)
        assertRangesDisjoint(localOf(result, "noA").range!!, localOf(result, "noB").range!!)
    }

    @Test
    fun whileBodyMultiLocalsStayInsideLoopBodyScope() {
        val source = """
            while true do
                local wLeft, wRight = 1, 2
            end
            """.trimIndent()
        val result = bind(source)

        val wLeft = localOf(result, "wLeft")
        val wRight = localOf(result, "wRight")
        assertPerNameRange(source, wLeft, "wLeft")
        assertPerNameRange(source, wRight, "wRight")
        assertEquals(wLeft, result.positionQueries.getDeclarationAt(positionOf(source, "wLeft")))
        assertEquals(wRight, result.positionQueries.getDeclarationAt(positionOf(source, "wRight")))
        assertRangesDisjoint(wLeft.range!!, wRight.range!!)
        assertNotEquals(DeclarationOwner.Root, wLeft.owner)
        assertEquals(wLeft.owner, wRight.owner)
    }

    @Test
    fun localFunctionBodyMultiLocalsDoNotEscapeToChunkRoot() {
        val source = """
            local function pack()
                local p1, p2 = 1, 2
            end
            """.trimIndent()
        val chunk = parser.parse(source)
        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        val function = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        val bodyScope = assertNotNull(result.scopeGraph.getScope(function.body!!))

        val p1 = localOf(result, "p1")
        val p2 = localOf(result, "p2")
        assertTrue(result.scopeGraph.getDeclarations(bodyScope.id).contains(p1.id))
        assertTrue(result.scopeGraph.getDeclarations(bodyScope.id).contains(p2.id))
        assertTrue(
            result.scopeGraph.getDeclarations(result.scopeGraph.rootScope.id).none {
                it == p1.id || it == p2.id
            }
        )
        assertRangesDisjoint(p1.range!!, p2.range!!)
    }

    // --- attribute multi-locals (Lua 5.4) ----------------------------------------
    //
    // Product surface today (do not invent ranges or plain Identifier AST):
    // - Lua 5.4 local attnamelist always builds [AttributeIdentifier] nodes
    //   (even when attrib is absent; attributeName stays null).
    // - Binder still declares each init name as LOCAL with that node as anchor.
    // - parseAttribute currently does not finishNode the AttributeIdentifier, so
    //   AttributeIdentifier.range remains Range.EMPTY. PositionRangeIndex drops
    //   empty ranges, so attribute multi-locals are not a stable position-query
    //   surface. Locked product surface: AttributeIdentifier anchors, attributeName,
    //   isLocal, parent, LOCAL kind, AST origin, distinct ids/symbolIds, shared
    //   Lexical owner, RHS not declaration sites, EMPTY range mirrored on decl.
    //   Mirrors BinderPassDeclarationTest.bindsAttributeLocalsAsLocalDeclarations.

    @Test
    fun multiAttributeLocalsKeepPerNameAnchors() {
        val source = "local x<const>, y<close> = 1, io.open('f')"
        val chunk = lua54Parser.parse(source)
        val statement = chunk.body.statements.filterIsInstance<LocalStatement>().single()
        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))

        assertEquals(2, statement.init.size)
        val xId = assertIs<AttributeIdentifier>(statement.init[0])
        val yId = assertIs<AttributeIdentifier>(statement.init[1])
        assertEquals("x", xId.name)
        assertEquals("y", yId.name)
        assertEquals("const", xId.attributeName)
        assertEquals("close", yId.attributeName)
        assertTrue(xId.isLocal)
        assertTrue(yId.isLocal)
        assertSame(statement, xId.parent)
        assertSame(statement, yId.parent)
        // Product gap locked: attribute anchors keep Range.EMPTY (no finishNode).
        assertEquals(Range.EMPTY, xId.range)
        assertEquals(Range.EMPTY, yId.range)

        val x = localOf(result, "x")
        val y = localOf(result, "y")
        assertSame(xId, x.anchorNode)
        assertSame(yId, y.anchorNode)
        // Declaration range mirrors the anchor node range (EMPTY today).
        assertEquals(xId.range, x.range)
        assertEquals(yId.range, y.range)
        assertEquals(Range.EMPTY, x.range)
        assertEquals(Range.EMPTY, y.range)
        assertEquals(DeclarationKind.LOCAL, x.kind)
        assertEquals(DeclarationKind.LOCAL, y.kind)
        assertEquals(DeclarationOrigin.AST, x.origin)
        assertEquals(DeclarationOrigin.AST, y.origin)
        assertNotEquals(x.id, y.id)
        assertNotEquals(x.symbolId, y.symbolId)
        assertEquals(DeclarationOwner.Lexical(chunk.body), x.owner)
        assertEquals(x.owner, y.owner)
        assertTrue(result.scopeGraph.getDeclarations(result.scopeGraph.rootScope.id).contains(x.id))
        assertTrue(result.scopeGraph.getDeclarations(result.scopeGraph.rootScope.id).contains(y.id))
        // EMPTY ranges are not indexed for position queries (product surface today).
        assertNull(result.positionQueries.getDeclarationAt(positionOf(source, "x")))
        assertNull(result.positionQueries.getDeclarationAt(positionOf(source, "y")))
        // RHS forms are not declaration anchors.
        assertNull(result.positionQueries.getDeclarationAt(positionOf(source, "1")))
        assertNull(result.positionQueries.getDeclarationAt(positionOf(source, "open")))
    }

    @Test
    fun mixedAttributeAndPlainMultiLocalStillDisjoint() {
        val source = "local plain, marked<const> = 0, 1"
        val chunk = lua54Parser.parse(source)
        val statement = chunk.body.statements.filterIsInstance<LocalStatement>().single()
        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))

        // Lua 5.4 path: every local name is AttributeIdentifier; bare names keep null attribute.
        val plainId = assertIs<AttributeIdentifier>(statement.init[0])
        val markedId = assertIs<AttributeIdentifier>(statement.init[1])
        assertEquals("plain", plainId.name)
        assertEquals("marked", markedId.name)
        assertNull(plainId.attributeName)
        assertEquals("const", markedId.attributeName)
        assertTrue(plainId.isLocal)
        assertTrue(markedId.isLocal)
        assertSame(statement, plainId.parent)
        assertSame(statement, markedId.parent)
        assertEquals(Range.EMPTY, plainId.range)
        assertEquals(Range.EMPTY, markedId.range)

        val plain = localOf(result, "plain")
        val marked = localOf(result, "marked")
        assertSame(plainId, plain.anchorNode)
        assertSame(markedId, marked.anchorNode)
        assertEquals(plainId.range, plain.range)
        assertEquals(markedId.range, marked.range)
        assertEquals(Range.EMPTY, plain.range)
        assertEquals(Range.EMPTY, marked.range)
        assertEquals(DeclarationKind.LOCAL, plain.kind)
        assertEquals(DeclarationKind.LOCAL, marked.kind)
        assertEquals(DeclarationOrigin.AST, plain.origin)
        assertEquals(DeclarationOrigin.AST, marked.origin)
        assertNotEquals(plain.id, marked.id)
        assertNotEquals(plain.symbolId, marked.symbolId)
        assertEquals(plain.owner, marked.owner)
        assertEquals(DeclarationOwner.Lexical(chunk.body), plain.owner)
        assertEquals(listOf("plain", "marked"), nonBuiltinLocals(result).map { it.name })
        // EMPTY ranges are not a position-query surface for either slot.
        assertNull(result.positionQueries.getDeclarationAt(positionOf(source, "plain")))
        assertNull(result.positionQueries.getDeclarationAt(positionOf(source, "marked")))
        // RHS constants still never become declaration sites.
        assertNull(result.positionQueries.getDeclarationAt(positionOf(source, "0")))
        assertNull(result.positionQueries.getDeclarationAt(positionOf(source, "1")))
    }

    // --- dense packing / underscore / digit shapes -------------------------------

    @Test
    fun underscoreAndDigitDenseSameLineMultiLocalStaysQueryable() {
        // Dense packing stresses half-open ends next to commas with no spaces.
        val source = "local _a,_b2,c3_=1,2,3"
        val result = bind(source)
        val names = listOf("_a", "_b2", "c3_")

        names.forEach { name ->
            val declaration = localOf(result, name)
            assertPerNameRange(source, declaration, name)
            assertEveryColumnHits(source, result, declaration, name)
        }
        for (i in names.indices) {
            for (j in i + 1 until names.size) {
                assertRangesDisjoint(localOf(result, names[i]).range!!, localOf(result, names[j]).range!!)
            }
        }
        // Commas never resolve.
        var from = 0
        repeat(2) {
            val idx = source.indexOf(',', from)
            require(idx >= 0)
            assertNull(result.positionQueries.getDeclarationAt(indexToPosition(source, idx)))
            from = idx + 1
        }
    }

    @Test
    fun fiveNameSameLineMultiLocalPreservesSourceOrderAndDisjointRanges() {
        val source = "local n1, n2, n3, n4, n5 = 1, 2, 3, 4, 5"
        val result = bind(source)
        val names = listOf("n1", "n2", "n3", "n4", "n5")
        val declarations = names.map { localOf(result, it) }

        assertEquals(names, nonBuiltinLocals(result).map { it.name })
        names.zip(declarations).forEach { (name, declaration) ->
            assertPerNameRange(source, declaration, name)
            assertEquals(declaration, result.positionQueries.getDeclarationAt(positionOf(source, name)))
        }
        for (i in declarations.indices) {
            for (j in i + 1 until declarations.size) {
                assertRangesDisjoint(declarations[i].range!!, declarations[j].range!!)
                assertSameLine(declarations[i].range!!, declarations[j].range!!)
            }
        }
    }

    @Test
    fun halfOpenEndOfEachDenseNameMissesNextName() {
        val source = "local aa,bb=1,2"
        val result = bind(source)
        val aa = localOf(result, "aa")
        val bb = localOf(result, "bb")

        assertEquals(aa, result.positionQueries.getDeclarationAt(aa.range!!.start))
        assertNull(result.positionQueries.getDeclarationAt(aa.range!!.end))
        assertEquals(bb, result.positionQueries.getDeclarationAt(bb.range!!.start))
        assertNull(result.positionQueries.getDeclarationAt(bb.range!!.end))
    }

    // --- doc multi-slot stays unresolved; documentation may still attach ---------

    @Test
    fun multiLocalDocTypeMappingStaysUnresolvedButDocsAttach() {
        val source = """
            ---@type string, number
            local s, n = "a", 1
            """.trimIndent()
        val result = bind(source)
        val s = localOf(result, "s")
        val n = localOf(result, "n")

        // Multi-name locals must not invent per-slot declaredTypeSyntax.
        assertNull(s.declaredTypeSyntax)
        assertNull(n.declaredTypeSyntax)
        assertNotNull(s.documentation)
        assertNotNull(n.documentation)
        assertPerNameRange(source, s, "s")
        assertPerNameRange(source, n, "n")
        assertRangesDisjoint(s.range!!, n.range!!)
    }

    @Test
    fun multiLocalSingleTypeTagStillLeavesDeclaredTypeSyntaxNull() {
        // Contrast with single-name locals: multi-name never fabricates per-slot types,
        // even when the doc comment carries only one type slot.
        val result = bind(
            """
            ---@type string
            local a, b = "x", "y"
            """.trimIndent()
        )
        assertTrue(listOf(localOf(result, "a"), localOf(result, "b")).all { it.declaredTypeSyntax == null })
        assertEquals(listOf("a", "b"), nonBuiltinLocals(result).map { it.name })
    }

    // --- helpers -----------------------------------------------------------------

    private fun bind(source: String): BinderPassResult {
        val chunk = parser.parse(source)
        return BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
    }

    private fun nonBuiltinLocals(result: BinderPassResult): List<BinderDeclaration> {
        return result.declarationIndex.declarations.filter {
            it.kind == DeclarationKind.LOCAL && it.origin != DeclarationOrigin.BUILTIN
        }
    }

    private fun localOf(result: BinderPassResult, name: String): BinderDeclaration {
        return nonBuiltinLocals(result).single { it.name == name }
    }

    private fun assertPerNameRange(source: String, declaration: BinderDeclaration, name: String) {
        val range = assertNotNull(declaration.range, "Declaration '$name' must expose a range")
        val anchor = assertNotNull(declaration.anchorNode, "Declaration '$name' must keep an anchor node")
        val identifier = assertIs<Identifier>(anchor)
        assertEquals(name, identifier.name)
        assertEquals(name, declaration.name)
        assertEquals(DeclarationKind.LOCAL, declaration.kind)
        assertEquals(identifier.range, range)

        val expectedStart = positionOf(source, name)
        assertEquals(expectedStart, range.start, "Range start for '$name' should match identifier start")
        assertEquals(expectedStart.line, range.end.line)
        assertEquals(expectedStart.column + name.length, range.end.column)
        assertTrue(range.end.column > range.start.column)
    }

    private fun assertEveryColumnHits(
        source: String,
        result: BinderPassResult,
        declaration: BinderDeclaration,
        name: String
    ) {
        val start = positionOf(source, name)
        for (offset in 0 until name.length) {
            val pos = Position(start.line, start.column + offset)
            assertEquals(
                declaration,
                result.positionQueries.getDeclarationAt(pos),
                "expected '$name' at column offset $offset ($pos)"
            )
            assertEquals(listOf(declaration), result.positionQueries.getDeclarationsAt(pos))
            assertEquals(declaration.symbolId, result.positionQueries.getSymbolAt(pos)?.id)
        }
    }

    private fun assertRangesDisjoint(a: Range, b: Range) {
        val aEndsBeforeB = comparePositions(a.end, b.start) <= 0
        val bEndsBeforeA = comparePositions(b.end, a.start) <= 0
        assertTrue(
            aEndsBeforeB || bEndsBeforeA,
            "Expected disjoint ranges but got $a and $b"
        )
    }

    private fun assertSameLine(a: Range, b: Range) {
        assertEquals(a.start.line, b.start.line, "Expected same-line declarations")
        assertEquals(a.end.line, b.end.line)
    }

    private fun comparePositions(left: Position, right: Position): Int {
        val line = left.line.compareTo(right.line)
        return if (line != 0) line else left.column.compareTo(right.column)
    }

    /**
     * Locate the start Position of [needle] in [source].
     *
     * Identifier needles are matched as whole words so short names do not hit
     * substrings inside `local` / longer identifiers. Punctuation needles keep
     * plain substring match.
     */
    private fun positionOf(source: String, needle: String, occurrence: Int = 1): Position {
        var fromIndex = 0
        var found = 0
        val requireWordBoundary = needle.all { isIdentChar(it) }

        while (true) {
            val index = source.indexOf(needle, fromIndex)
            require(index >= 0) { "Missing '$needle' occurrence $occurrence in:\n$source" }

            val match = if (!requireWordBoundary) {
                true
            } else {
                val beforeOk = index == 0 || !isIdentChar(source[index - 1])
                val afterIndex = index + needle.length
                val afterOk = afterIndex >= source.length || !isIdentChar(source[afterIndex])
                beforeOk && afterOk
            }

            if (match) {
                found++
                if (found == occurrence) {
                    return indexToPosition(source, index)
                }
            }
            fromIndex = index + 1
        }
    }

    private fun indexToPosition(source: String, index: Int): Position {
        var line = 1
        var column = 1
        for (i in 0 until index) {
            if (source[i] == '\n') {
                line++
                column = 1
            } else {
                column++
            }
        }
        return Position(line, column)
    }

    private fun isIdentChar(ch: Char): Boolean {
        return ch == '_' || ch.isLetterOrDigit()
    }
}
