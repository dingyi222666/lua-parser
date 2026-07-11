package semantic.comments

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.ast.node.AssignmentStatement
import io.github.dingyi222666.luaparser.parser.ast.node.DoStatement
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.semantic.comments.AliasTagSyntax
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachPass
import io.github.dingyi222666.luaparser.semantic.comments.TypeTagSyntax
import io.github.dingyi222666.luaparser.semantic.comments.UnknownTagSyntax
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * REVIEW22B / TASK-258 corpus: EmmyLua @type / @alias doc tags must attach to the
 * correct local/assignment targets (or remain parseable orphans for free-standing
 * aliases). Unknown type names and malformed tags must not crash the attach pass.
 *
 * Test-only; verification is review-owned (no Gradle in worker waves).
 */
class DocTypeAliasAttachTddTest {

    private val luaParser = LuaParser()
    private val attachPass = CommentAttachPass()

    @Test
    fun attachesTypeTagToNextLocalStatement() {
        val chunk = parse(
            """
            --- widget table
            ---@type table<string, number>
            local value = {}
            """.trimIndent()
        )

        val local = chunk.body.statements.filterIsInstance<LocalStatement>().single()
        val index = attachPass.attach(chunk)
        val doc = assertNotNull(index.getDocComment(local))

        assertEquals("widget table", doc.description)
        val typeTag = assertIs<TypeTagSyntax>(doc.tags.single())
        assertEquals("table<string, number>", typeTag.typeText)
        assertEquals("table<string, number>", index.getInlineTypeText(local))
        assertEquals(0, index.orphanAttachments.size)
    }

    @Test
    fun attachesTypeTagToNextAssignmentStatement() {
        val chunk = parse(
            """
            value = nil

            ---@type string|nil
            value = "ready"
            """.trimIndent()
        )

        val assignments = chunk.body.statements.filterIsInstance<AssignmentStatement>()
        assertEquals(2, assignments.size)
        val typedAssign = assignments[1]
        assertEquals("value", assertIs<Identifier>(typedAssign.variables.single()).name)

        val index = attachPass.attach(chunk)
        val doc = assertNotNull(index.getDocComment(typedAssign))
        val typeTag = assertIs<TypeTagSyntax>(doc.tags.single())

        assertEquals("string|nil", typeTag.typeText)
        assertEquals("string|nil", index.getInlineTypeText(typedAssign))
        assertNull(index.getAttachment(assignments[0]))
        assertEquals(0, index.orphanAttachments.size)
    }

    @Test
    fun attachesTypeTagToNestedLocalInsideDoBlock() {
        val chunk = parse(
            """
            do
                ---@type integer
                local inner = 1
            end
            """.trimIndent()
        )

        val doStatement = chunk.body.statements.filterIsInstance<DoStatement>().single()
        val inner = doStatement.body.statements.filterIsInstance<LocalStatement>().single()
        val index = attachPass.attach(chunk)

        assertEquals("integer", index.getInlineTypeText(inner))
        val typeTag = assertIs<TypeTagSyntax>(assertNotNull(index.getDocComment(inner)).tags.single())
        assertEquals("integer", typeTag.typeText)
        assertNull(index.getAttachment(doStatement))
        assertEquals(0, index.orphanAttachments.size)
    }

    @Test
    fun attachesDistinctTypeBlocksToAdjacentLocalsWithoutCrossTalk() {
        val chunk = parse(
            """
            ---@type string
            local first = "a"

            ---@type number
            local second = 2
            """.trimIndent()
        )

        val locals = chunk.body.statements.filterIsInstance<LocalStatement>()
        assertEquals(2, locals.size)
        assertEquals("first", locals[0].init.single().name)
        assertEquals("second", locals[1].init.single().name)

        val index = attachPass.attach(chunk)

        assertEquals("string", index.getInlineTypeText(locals[0]))
        assertEquals("number", index.getInlineTypeText(locals[1]))
        assertEquals(
            "string",
            assertIs<TypeTagSyntax>(assertNotNull(index.getDocComment(locals[0])).tags.single()).typeText
        )
        assertEquals(
            "number",
            assertIs<TypeTagSyntax>(assertNotNull(index.getDocComment(locals[1])).tags.single()).typeText
        )
        assertEquals(0, index.orphanAttachments.size)
        assertTrue(index.getAttachment(locals[0]) !== index.getAttachment(locals[1]))
    }

    @Test
    fun blankLineSeparatedTypeBlockDoesNotAttachAcrossGap() {
        val chunk = parse(
            """
            ---@type string

            ---@type number
            local value = 1
            """.trimIndent()
        )

        val local = chunk.body.statements.filterIsInstance<LocalStatement>().single()
        val index = attachPass.attach(chunk)

        assertEquals("number", index.getInlineTypeText(local))
        assertEquals(
            "number",
            assertIs<TypeTagSyntax>(assertNotNull(index.getDocComment(local)).tags.single()).typeText
        )

        val orphan = index.orphanAttachments.single()
        val orphanType = assertIs<TypeTagSyntax>(assertNotNull(orphan.docComment).tags.single())
        assertEquals("string", orphanType.typeText)
    }

    @Test
    fun freeStandingAliasRegistersNamedAliasAsOrphanWithoutCrash() {
        val chunk = parse(
            """
            ---@alias Identifier string | number identifier alias

            local value = 1
            """.trimIndent()
        )

        val local = chunk.body.statements.filterIsInstance<LocalStatement>().single()
        val index = attachPass.attach(chunk)

        assertNull(index.getAttachment(local))
        val orphan = index.orphanAttachments.single()
        val alias = assertIs<AliasTagSyntax>(assertNotNull(orphan.docComment).tags.single())
        assertEquals("Identifier", alias.name)
        assertEquals("string | number", alias.targetTypeText)
        assertEquals("identifier alias", alias.description)
        assertEquals(listOf("Identifier"), index.orphanDocComments.flatMap { doc ->
            doc.tags.filterIsInstance<AliasTagSyntax>().map { it.name }
        })
    }

    @Test
    fun adjacentAliasWithoutBlankLineAttachesWithNextLocalButStillParsesAlias() {
        // When @alias is immediately above a local (no blank line), attach pass binds
        // the block to that local; alias tag remains available on the bound doc comment.
        val chunk = parse(
            """
            ---@alias Name string
            local name = "x"
            """.trimIndent()
        )

        val local = chunk.body.statements.filterIsInstance<LocalStatement>().single()
        val index = attachPass.attach(chunk)
        val doc = assertNotNull(index.getDocComment(local))
        val alias = assertIs<AliasTagSyntax>(doc.tags.single())

        assertEquals("Name", alias.name)
        assertEquals("string", alias.targetTypeText)
        assertEquals(0, index.orphanAttachments.size)
    }

    @Test
    fun aliasWithTypeParametersParsesWithoutCrash() {
        val chunk = parse(
            """
            ---@alias Mapper<T> fun(value: T): T
            ---@alias Pair<L, R> { left: L, right: R }

            local unused = 0
            """.trimIndent()
        )

        val index = attachPass.attach(chunk)
        val aliases = index.orphanAttachments
            .mapNotNull { it.docComment }
            .flatMap { it.tags }
            .filterIsInstance<AliasTagSyntax>()

        // Free-standing aliases (blank line before next statement) land as orphans.
        // Consecutive alias lines form one block with both named aliases.
        assertTrue(aliases.any { it.name == "Mapper" })
        assertTrue(aliases.any { it.name == "Pair" })

        val mapper = aliases.first { it.name == "Mapper" }
        assertEquals(listOf("T"), mapper.declaredTypeParameters)
        assertEquals("fun(value: T): T", mapper.targetTypeText)

        val pair = aliases.first { it.name == "Pair" }
        assertEquals(listOf("L", "R"), pair.declaredTypeParameters)
        assertEquals("{ left: L, right: R }", pair.targetTypeText)
    }

    @Test
    fun unknownTypeNamesStillAttachAndExposeInlineTypeText() {
        // Unknown / forward type names must degrade safely: attach + keep raw text.
        val chunk = parse(
            """
            ---@type NotARealType
            local mystery = nil

            ---@type package.UnknownWidget
            mystery = mystery
            """.trimIndent()
        )

        val local = chunk.body.statements.filterIsInstance<LocalStatement>().single()
        val assign = chunk.body.statements.filterIsInstance<AssignmentStatement>().single()
        val index = attachPass.attach(chunk)

        assertEquals("NotARealType", index.getInlineTypeText(local))
        assertEquals(
            "NotARealType",
            assertIs<TypeTagSyntax>(assertNotNull(index.getDocComment(local)).tags.single()).typeText
        )

        assertEquals("package.UnknownWidget", index.getInlineTypeText(assign))
        assertEquals(
            "package.UnknownWidget",
            assertIs<TypeTagSyntax>(assertNotNull(index.getDocComment(assign)).tags.single()).typeText
        )
        assertEquals(0, index.orphanAttachments.size)
    }

    @Test
    fun typeTagWithTrailingProseKeepsTypeTextAndDescription() {
        val chunk = parse(
            """
            ---@type table<string, { value: number }> trailing prose that is not type syntax
            local value = {}
            """.trimIndent()
        )

        val local = chunk.body.statements.filterIsInstance<LocalStatement>().single()
        val index = attachPass.attach(chunk)
        val typeTag = assertIs<TypeTagSyntax>(assertNotNull(index.getDocComment(local)).tags.single())

        assertEquals("table<string, { value: number }>", typeTag.typeText)
        assertEquals("trailing prose that is not type syntax", typeTag.description)
        assertEquals("table<string, { value: number }>", index.getInlineTypeText(local))
        assertEquals(0, index.orphanAttachments.size)
    }

    @Test
    fun malformedTypeAndAliasTagsDoNotCrashAttachPass() {
        val chunk = parse(
            """
            ---@type
            ---@type table<string, { ok: boolean
            ---@alias
            ---@alias Result<T
            ---@alias Broken fun(value: string
            ---@mystery still here
            local value = {}
            """.trimIndent()
        )

        val local = chunk.body.statements.filterIsInstance<LocalStatement>().single()

        // Must not throw.
        val index = attachPass.attach(chunk)
        val attachment = assertNotNull(index.getAttachment(local))
        val doc = assertNotNull(index.getDocComment(local))

        assertEquals(0, index.orphanAttachments.size)
        assertTrue(doc.tags.isNotEmpty())
        assertTrue(doc.tags.any { it is TypeTagSyntax })
        assertTrue(doc.tags.any { it is AliasTagSyntax })
        assertTrue(doc.tags.any { it is UnknownTagSyntax })
        assertNotNull(attachment.comments)
        assertTrue(attachment.comments.isNotEmpty())

        // Bare ---@type degrades to a type tag (content may default to "any").
        val typeTags = doc.tags.filterIsInstance<TypeTagSyntax>()
        assertTrue(typeTags.isNotEmpty())
        assertTrue(typeTags.any { it.typeText.isNotBlank() })

        // Malformed alias still yields AliasTagSyntax without crash.
        val aliasTags = doc.tags.filterIsInstance<AliasTagSyntax>()
        assertTrue(aliasTags.isNotEmpty())
        assertTrue(aliasTags.any { it.name == "Result<T" || it.name.startsWith("Result") })
    }

    @Test
    fun mixedTypeAliasAndUnknownStillAttachToLocal() {
        val chunk = parse(
            """
            ---@alias Id string
            ---@type Id
            ---@type NeverHeardOfThis
            local id = "x"
            """.trimIndent()
        )

        val local = chunk.body.statements.filterIsInstance<LocalStatement>().single()
        val index = attachPass.attach(chunk)
        val doc = assertNotNull(index.getDocComment(local))

        assertEquals(0, index.orphanAttachments.size)

        val alias = doc.tags.filterIsInstance<AliasTagSyntax>().single()
        assertEquals("Id", alias.name)
        assertEquals("string", alias.targetTypeText)

        val types = doc.tags.filterIsInstance<TypeTagSyntax>()
        assertEquals(2, types.size)
        assertEquals("Id", types[0].typeText)
        assertEquals("NeverHeardOfThis", types[1].typeText)

        // findInlineTypeText walks comments/lines in reverse → last @type wins.
        assertEquals("NeverHeardOfThis", index.getInlineTypeText(local))
    }

    @Test
    fun doesNotAttachTypeDocsToFollowingUnrelatedFunction() {
        val chunk = parse(
            """
            ---@type number
            local count = 0

            local function next()
                return count
            end
            """.trimIndent()
        )

        val local = chunk.body.statements.filterIsInstance<LocalStatement>().single()
        val function = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        val index = attachPass.attach(chunk)

        assertEquals("number", index.getInlineTypeText(local))
        assertNotNull(index.getDocComment(local))
        assertNull(index.getAttachment(function))
        assertEquals(0, index.orphanAttachments.size)
    }

    @Test
    fun multiLocalDeclarationReceivesLeadingTypeAttachment() {
        val chunk = parse(
            """
            ---@type string, number
            local a, b = "x", 1
            """.trimIndent()
        )

        val local = chunk.body.statements.filterIsInstance<LocalStatement>().single()
        assertEquals(listOf("a", "b"), local.init.map { it.name })

        val index = attachPass.attach(chunk)
        val typeTag = assertIs<TypeTagSyntax>(assertNotNull(index.getDocComment(local)).tags.single())

        // Type text may be the full multi-type annotation or first segment depending on
        // type-prefix parsing; either way attachment must succeed without crash.
        assertTrue(typeTag.typeText.isNotBlank())
        assertNotNull(index.getInlineTypeText(local))
        assertEquals(0, index.orphanAttachments.size)
    }

    private fun parse(source: String) = luaParser.parse(source)
}
