package parser.lua54

import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.AttributeIdentifier
import io.github.dingyi222666.luaparser.parser.ast.node.BlockNode
import io.github.dingyi222666.luaparser.parser.ast.node.CallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.ConstantNode
import io.github.dingyi222666.luaparser.parser.ast.node.DoStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ForGenericStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ForNumericStatement
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.IfStatement
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.parser.ast.node.RepeatStatement
import io.github.dingyi222666.luaparser.parser.ast.node.VarargLiteral
import io.github.dingyi222666.luaparser.parser.ast.node.WhileStatement
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import parser.assertParseFails
import parser.firstStatement
import parser.parse
import parser.renderShape

/**
 * Focused Lua 5.4 local attribute (`<const>` / `<close>`) corpus at AST level.
 *
 * Acceptance:
 * - Lua 5.4 policy accepts attribute locals and preserves AttrId shapes.
 * - Non-5.4 policies reject the same sources without recovery.
 * - Test-only; does not assert runtime const/close semantics.
 */
class Lua54LocalAttributeConstTddTest {

    @Test
    fun parsesSingleConstAndCloseLocals() {
        assertCaseShapes(
            "const local with initializer" to (
                "local x<const> = 1" to
                    "Chunk(Block[Local(AttrId(x<const>)=Const(1))])"
            ),
            "const local with spaces around attribute" to (
                "local pinned <const> = 1" to
                    "Chunk(Block[Local(AttrId(pinned<const>)=Const(1))])"
            ),
            "close local with call initializer" to (
                "local file<close> = open()" to
                    "Chunk(Block[Local(AttrId(file<close>)=Call(Id(open):))])"
            ),
            "close local with spaces around attribute" to (
                "local handle <close> = open()" to
                    "Chunk(Block[Local(AttrId(handle<close>)=Call(Id(open):))])"
            ),
            "const local without initializer" to (
                "local frozen<const>" to
                    "Chunk(Block[Local(AttrId(frozen<const>)=)])"
            ),
            "close local without initializer" to (
                "local resource<close>" to
                    "Chunk(Block[Local(AttrId(resource<close>)=)])"
            )
        )

        val constChunk = parse(LuaVersion.LUA_5_4, "local x<const> = 1")
        val constLocal = constChunk.firstStatement<LocalStatement>()
        val constIdentifier = assertIs<AttributeIdentifier>(constLocal.init.single())
        assertEquals("x", constIdentifier.name)
        assertEquals("const", constIdentifier.attributeName)
        assertTrue(constIdentifier.isLocal)
        assertEquals(constLocal, constIdentifier.parent)
        assertEquals(1, constLocal.variables.size)
        assertIs<ConstantNode>(constLocal.variables.single())

        val closeChunk = parse(LuaVersion.LUA_5_4, "local file<close> = open()")
        val closeLocal = closeChunk.firstStatement<LocalStatement>()
        val closeIdentifier = assertIs<AttributeIdentifier>(closeLocal.init.single())
        assertEquals("file", closeIdentifier.name)
        assertEquals("close", closeIdentifier.attributeName)
        assertTrue(closeIdentifier.isLocal)
        assertEquals(closeLocal, closeIdentifier.parent)
        assertIs<CallExpression>(closeLocal.variables.single())
    }

    @Test
    fun parsesMixedAttributeListsAndOptionalAttributes() {
        assertCaseShapes(
            "mixed const close and bare name with vararg" to (
                "local a<const>, b<close>, c = ..." to
                    "Chunk(Block[Local(AttrId(a<const>),AttrId(b<close>),AttrId(c)=Vararg)])"
            ),
            "mixed with spaces around attributes" to (
                "local pinned <const>, handle <close> = 1, open()" to
                    "Chunk(Block[Local(AttrId(pinned<const>),AttrId(handle<close>)=Const(1),Call(Id(open):))])"
            ),
            "bare name then trailing const" to (
                "local only, tagged <const> = 1, 2" to
                    "Chunk(Block[Local(AttrId(only),AttrId(tagged<const>)=Const(1),Const(2))])"
            ),
            "close then bare name" to (
                "local file <close>, name = open(), 'x'" to
                    "Chunk(Block[Local(AttrId(file<close>),AttrId(name)=Call(Id(open):),Const('x'))])"
            ),
            "all three without initializer" to (
                "local a<const>, b<close>, c" to
                    "Chunk(Block[Local(AttrId(a<const>),AttrId(b<close>),AttrId(c)=)])"
            )
        )

        val chunk = parse(LuaVersion.LUA_5_4, "local a<const>, b<close>, c = ...")
        val localStatement = chunk.firstStatement<LocalStatement>()
        assertEquals(3, localStatement.init.size)
        localStatement.init.forEach {
            assertIs<AttributeIdentifier>(it)
            assertTrue(it.isLocal)
            assertEquals(localStatement, it.parent)
        }
        assertContentEquals(
            listOf("a", "b", "c"),
            localStatement.init.map { (it as AttributeIdentifier).name }
        )
        assertContentEquals(
            listOf("const", "close", null),
            localStatement.init.map { (it as AttributeIdentifier).attributeName }
        )
        assertEquals(1, localStatement.variables.size)
        assertIs<VarargLiteral>(localStatement.variables.single())
    }

    @Test
    fun parsesAttributedLocalsInsideBlocksLoopsAndFunctions() {
        assertCaseShapes(
            "const inside do" to (
                "do local value<const> = 1 end" to
                    "Chunk(Block[Do(Block[Local(AttrId(value<const>)=Const(1))])])"
            ),
            "close inside while" to (
                "while ready do local step <const> = 1 end" to
                    "Chunk(Block[While(Id(ready):Block[Local(AttrId(step<const>)=Const(1))])])"
            ),
            "close inside repeat" to (
                """
                repeat
                    local handle<close> = open()
                until done
                """.trimIndent() to
                    "Chunk(Block[Repeat(Block[Local(AttrId(handle<close>)=Call(Id(open):))]:Id(done))])"
            ),
            "const inside numeric for" to (
                """
                for i = 1, 3 do
                    local step<const> = i
                end
                """.trimIndent() to
                    "Chunk(Block[ForNumeric(Id(i)=Const(1),Const(3),null:Block[Local(AttrId(step<const>)=Id(i))])])"
            ),
            "close inside generic for" to (
                """
                for key, value in pairs(items) do
                    local current<close> = value
                end
                """.trimIndent() to
                    "Chunk(Block[ForGeneric(Id(key),Id(value) in Call(Id(pairs):Id(items)):Block[Local(AttrId(current<close>)=Id(value))])])"
            ),
            "const inside if then" to (
                "if ok then local pinned<const> = 1 end" to
                    "Chunk(Block[If(Clause(Id(ok):Block[Local(AttrId(pinned<const>)=Const(1))]))])"
            ),
            "close inside function body" to (
                """
                function openHandle()
                    local file <close> = open()
                end
                """.trimIndent() to
                    "Chunk(Block[Function(Id(openHandle),Block[Local(AttrId(file<close>)=Call(Id(open):))])])"
            ),
            "const inside local function body" to (
                """
                local function build()
                    local limit <const> = 10
                    return limit
                end
                """.trimIndent() to
                    "Chunk(Block[Function(Id(build),Block[Local(AttrId(limit<const>)=Const(10));Return(Id(limit))])])"
            )
        )

        val doChunk = parse(LuaVersion.LUA_5_4, "do local value<const> = 1 end")
        val doStatement = doChunk.firstStatement<DoStatement>()
        val doLocal = assertIs<LocalStatement>(doStatement.body.statements.single())
        val doIdentifier = assertIs<AttributeIdentifier>(doLocal.init.single())
        assertEquals("const", doIdentifier.attributeName)
        assertEquals(doStatement.body, doLocal.parent)
        assertEquals(doLocal, doIdentifier.parent)

        val functionChunk = parse(
            LuaVersion.LUA_5_4,
            """
            function openHandle()
                local file <close> = open()
            end
            """.trimIndent()
        )
        val function = functionChunk.firstStatement<FunctionDeclaration>()
        val functionLocal = assertIs<LocalStatement>(requireNotNull(function.body).statements.single())
        assertEquals("close", assertIs<AttributeIdentifier>(functionLocal.init.single()).attributeName)
        assertEquals(function.body, functionLocal.parent)
    }

    @Test
    fun resourceFixtureAttributesInBlocksMatchesShape() {
        val source = """
            do
                local config<const> = defaults
                local handle<close> = open()
            end
            for i = 1, 2 do
                local step<const> = i
            end
            repeat
                local resource<close> = open()
            until done
        """.trimIndent()

        val expectedShape =
            "Chunk(Block[Do(Block[Local(AttrId(config<const>)=Id(defaults));Local(AttrId(handle<close>)=Call(Id(open):))]);" +
                "ForNumeric(Id(i)=Const(1),Const(2),null:Block[Local(AttrId(step<const>)=Id(i))]);" +
                "Repeat(Block[Local(AttrId(resource<close>)=Call(Id(open):))]:Id(done))])"

        val chunk = parse(LuaVersion.LUA_5_4, source)
        assertEquals(expectedShape, renderShape(chunk))
        assertEquals(3, chunk.body.statements.size)

        val doStatement = assertIs<DoStatement>(chunk.body.statements[0])
        assertContentEquals(
            listOf("const", "close"),
            doStatement.body.statements.map {
                assertIs<AttributeIdentifier>(assertIs<LocalStatement>(it).init.single()).attributeName
            }
        )

        val numericStatement = assertIs<ForNumericStatement>(chunk.body.statements[1])
        assertEquals(
            "const",
            assertIs<AttributeIdentifier>(
                assertIs<LocalStatement>(numericStatement.body.statements.single()).init.single()
            ).attributeName
        )

        val repeatStatement = assertIs<RepeatStatement>(chunk.body.statements[2])
        assertEquals(
            "close",
            assertIs<AttributeIdentifier>(
                assertIs<LocalStatement>(repeatStatement.body.statements.single()).init.single()
            ).attributeName
        )
    }

    @Test
    fun lua54AcceptsAttributedLocalsAcrossPolicySurface() {
        val successCases = listOf(
            "local x<const> = 1" to "Chunk(Block[Local(AttrId(x<const>)=Const(1))])",
            "local file<close> = open()" to "Chunk(Block[Local(AttrId(file<close>)=Call(Id(open):))])",
            "local a<const>, b<close>, c = ..." to
                "Chunk(Block[Local(AttrId(a<const>),AttrId(b<close>),AttrId(c)=Vararg)])",
            "do local value<const> = 1 end" to
                "Chunk(Block[Do(Block[Local(AttrId(value<const>)=Const(1))])])",
            "local pinned <const>, handle <close> = 1, open()" to
                "Chunk(Block[Local(AttrId(pinned<const>),AttrId(handle<close>)=Const(1),Call(Id(open):))])",
            "for i = 1, 2 do local limit <const> = i end" to
                "Chunk(Block[ForNumeric(Id(i)=Const(1),Const(2),null:Block[Local(AttrId(limit<const>)=Id(i))])])"
        )

        successCases.forEach { (source, expectedShape) ->
            assertEquals(expectedShape, renderShape(parse(LuaVersion.LUA_5_4, source)), source)
        }
    }

    @Test
    fun rejectsLocalAttributesOutsideLua54Policy() {
        val sources = listOf(
            "local x<const> = 1",
            "local file<close> = open()",
            "local a<const>, b<close>, c = ...",
            "do local value<const> = 1 end",
            "local pinned <const> = 1",
            "local handle <close> = open()",
            "for i = 1, 2 do local limit <const> = i end",
            "local only, tagged <const> = 1, 2"
        )

        val rejectedVersions = listOf(LuaVersion.LUA_5_3, LuaVersion.ANDROLUA_5_3)

        rejectedVersions.forEach { version ->
            sources.forEach { source ->
                val failure = assertParseFails(version, source)
                assertTrue(
                    failure.message?.isNotBlank() == true || failure is Throwable,
                    "expected rejection for $version source=$source"
                )
            }
        }
    }

    @Test
    fun collectsAttributeIdentifiersFromNestedControlFlow() {
        val chunk = parse(
            LuaVersion.LUA_5_4,
            """
            local root <const> = 0
            do
                local file <close> = open()
                while ready do
                    local step <const> = 1
                end
            end
            for i = 1, 2 do
                local limit <const> = i
            end
            """.trimIndent()
        )

        val attributes = collectAttributeIdentifiers(chunk)
        assertContentEquals(
            listOf(
                "root" to "const",
                "file" to "close",
                "step" to "const",
                "limit" to "const"
            ),
            attributes.map { it.name to it.attributeName }
        )
        assertTrue(attributes.all { it.isLocal })
        assertTrue(attributes.all { it.parent is LocalStatement })
    }

    @Test
    fun bareLocalNamesStillParseAsAttributeIdentifiersUnderLua54() {
        // Lua 5.4 path always builds AttributeIdentifier nodes even when attrib is absent.
        val chunk = parse(LuaVersion.LUA_5_4, "local bare, also = 1, 2")
        val local = chunk.firstStatement<LocalStatement>()
        assertEquals(2, local.init.size)
        local.init.forEach {
            val identifier = assertIs<AttributeIdentifier>(it)
            assertNull(identifier.attributeName)
            assertTrue(identifier.isLocal)
            assertEquals(local, identifier.parent)
        }
        assertContentEquals(listOf("bare", "also"), local.init.map { (it as AttributeIdentifier).name })
        assertEquals(
            "Chunk(Block[Local(AttrId(bare),AttrId(also)=Const(1),Const(2))])",
            renderShape(chunk)
        )
    }

    private fun assertCaseShapes(vararg cases: Pair<String, Pair<String, String>>) {
        val failures = cases.mapNotNull { (name, case) ->
            val (source, expectedShape) = case
            runCatching {
                assertEquals(expectedShape, renderShape(parse(LuaVersion.LUA_5_4, source)), name)
            }.exceptionOrNull()?.let { failure ->
                "$name\nsource: $source\n${failure.message}"
            }
        }

        if (failures.isNotEmpty()) {
            fail(failures.joinToString(separator = "\n\n"))
        }
    }

    private fun collectAttributeIdentifiers(chunk: ChunkNode): List<AttributeIdentifier> {
        val out = mutableListOf<AttributeIdentifier>()

        fun walkBlock(block: BlockNode) {
            val statements = buildList {
                addAll(block.statements)
                block.returnStatement?.let { add(it) }
            }
            for (statement in statements) {
                if (statement is LocalStatement) {
                    statement.init.forEach { init ->
                        if (init is AttributeIdentifier) {
                            out += init
                        }
                    }
                }
                when (statement) {
                    is DoStatement -> walkBlock(statement.body)
                    is WhileStatement -> walkBlock(statement.body)
                    is RepeatStatement -> walkBlock(statement.body)
                    is ForNumericStatement -> walkBlock(statement.body)
                    is ForGenericStatement -> walkBlock(statement.body)
                    is IfStatement -> statement.causes.forEach { walkBlock(it.body) }
                    is FunctionDeclaration -> statement.body?.let(::walkBlock)
                    else -> Unit
                }
            }
        }

        walkBlock(chunk.body)
        return out
    }
}
