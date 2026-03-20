package semantic.model

import io.github.dingyi222666.luaparser.semantic.api.CompletionItemKind
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
