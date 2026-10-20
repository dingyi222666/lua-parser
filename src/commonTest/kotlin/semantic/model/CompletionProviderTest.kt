package semantic.model

import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.semantic.api.CompletionItemKind
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompletionProviderTest {

    @Test
    fun lexicalCompletionsIncludeVisibleSymbolsAndBuiltins() {
        val harness = semanticModelHarness(
            """
            local outer = 1
            local function render(param)
                do
                    local inner = param
                    local result = inner
                end
            end
            """.trimIndent()
        )

        val labels = harness.model.getCompletionsAt(harness.positionOf("result")).map { it.label }

        assertTrue("inner" in labels)
        assertTrue("param" in labels)
        assertTrue("render" in labels)
        assertTrue("outer" in labels)
        assertTrue("print" in labels)
    }

    @Test
    fun shadowedOuterNamesAreNotDuplicatedAndLaterDeclarationsAreExcluded() {
        val harness = semanticModelHarness(
            """
            local value = 1
            do
                local value = 2
                local current = value
                local later = 3
            end
            """.trimIndent()
        )

        val completionsAtCurrent = harness.model.getCompletionsAt(harness.positionOf("current"))
        val valueItems = completionsAtCurrent.filter { it.label == "value" }

        assertEquals(1, valueItems.size)
        assertTrue(completionsAtCurrent.none { it.label == "later" })
    }

    @Test
    fun memberCompletionsFavorFieldsForDotAndMethodsForColon() {
        val harness = semanticModelHarness(
            """
            ---@class User
            ---@field name string
            ---@method User:getName(): string
            ---@type User
            local user = {}
            local fieldValue = user.name
            local methodValue = user:getName()
            """.trimIndent()
        )

        val dotItems = harness.model.getCompletionsAt(harness.positionOf("name", occurrence = 2))
        val colonItems = harness.model.getCompletionsAt(harness.positionOf("getName", occurrence = 2))

        assertEquals("name", dotItems.first().label)
        assertEquals("getName", colonItems.first().label)
    }

    @Test
    fun memberCompletionsIncludeInheritedAndAppliedMembers() {
        val harness = semanticModelHarness(
            """
            ---@class Base
            ---@field id integer
            ---@class Box<T>: Base
            ---@field value T
            ---@method Box:getValue(): T
            ---@alias StringBox Box<string>
            ---@type StringBox
            local box = {}
            local first = box.id
            local second = box:getValue()
            """.trimIndent()
        )

        val dotLabels = harness.model.getCompletionsAt(harness.positionOf("id", occurrence = 2)).map { it.label }
        val colonItems = harness.model.getCompletionsAt(harness.positionOf("getValue", occurrence = 2))

        assertTrue("id" in dotLabels)
        assertTrue("value" in dotLabels)
        assertEquals("getValue", colonItems.first().label)
        assertEquals("string", colonItems.first().detail?.substringAfterLast(": "))
    }

    @Test
    fun memberCompletionsIncludeMembersAssignedToBuiltinTables() {
        val harness = semanticModelHarness(
            """
            table.addObserver = function(old, listener)
                return {}
            end
            table.customValue = 42
            local value = table.
            """.trimIndent()
        )
        val start = harness.positionOf("table.", occurrence = 3)
        val position = Position(start.line, start.column + "table.".length)

        val completions = harness.model.getCompletionsAt(position)
        val labels = completions.map { it.label }

        assertTrue("addObserver" in labels, labels.toString())
        assertTrue("customValue" in labels, labels.toString())
        assertTrue("insert" in labels, labels.toString())
        assertEquals(CompletionItemKind.METHOD, completions.single { it.label == "addObserver" }.kind)
        assertEquals(CompletionItemKind.FIELD, completions.single { it.label == "customValue" }.kind)
    }

    @Test
    fun globalTableCompletionsIncludeAssignedFunctionsAndFields() {
        val harness = semanticModelHarness(
            """
            ViewUtil = {}
            ViewUtil.createView = function(t, n)
                return {}
            end
            ViewUtil.MODE = { ROUND = 0x1f, SQUARE = 0x2f }
            ViewUtil.CONST = { ripple = 1, ripples = 2 }
            ViewUtil.dp2px = function(dpValue)
                return dpValue + 0.5
            end
            local convert = ViewUtil.dp2px
            local target = ViewUtil.
            """.trimIndent()
        )
        val start = harness.positionOf("ViewUtil.", occurrence = 6)
        val position = Position(start.line, start.column + "ViewUtil.".length)

        val completions = harness.model.getCompletionsAt(position)
        val byLabel = completions.associateBy { it.label }
        val dp2px = harness.model.getSymbolAt(harness.positionOf("dp2px", occurrence = 2))

        assertEquals(CompletionItemKind.METHOD, byLabel.getValue("createView").kind)
        assertEquals(CompletionItemKind.METHOD, byLabel.getValue("dp2px").kind)
        assertEquals(CompletionItemKind.FIELD, byLabel.getValue("MODE").kind)
        assertEquals(CompletionItemKind.FIELD, byLabel.getValue("CONST").kind)
        assertEquals(SymbolKind.METHOD, dp2px?.kind)
        assertTrue(dp2px?.type?.displayName?.startsWith("fun(") == true, dp2px?.type?.displayName)
    }

    @Test
    fun completionItemsCarryExpectedKindDetailAndSortOrdering() {
        val harness = semanticModelHarness(
            """
            local function render(param)
                local value = param
                return value
            end
            """.trimIndent()
        )

        val completions = harness.model.getCompletionsAt(harness.positionOf("return"))

        assertEquals(CompletionItemKind.PARAMETER, completions.first().kind)
        assertTrue(completions.first().sortText!! <= completions[1].sortText!!)
        assertTrue(completions.any { it.label == "render" && it.kind == CompletionItemKind.FUNCTION })
    }
}
