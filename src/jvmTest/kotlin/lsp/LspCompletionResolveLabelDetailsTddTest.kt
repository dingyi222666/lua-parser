package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionItemLabelDetails
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InsertTextFormat
import org.eclipse.lsp4j.InsertTextMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Adversarial-audit fix: `completionItem/resolve` builds a fresh [CompletionItem] copy and
 * used to drop `labelDetails` and `insertTextMode`. Both must travel with the resolved
 * item (null-safe: absent stays absent), alongside the identity fields that were
 * already preserved.
 *
 * Test-only. Verification is review-owned (TASK-043). Workers must not run Gradle.
 */
class LspCompletionResolveLabelDetailsTddTest {

    @Test
    fun resolve_carries_label_details_and_insert_text_mode() {
        val service = service()
        val unresolved = CompletionItem("greet").apply {
            kind = CompletionItemKind.Function
            labelDetails = CompletionItemLabelDetails().apply {
                detail = "(name)"
                description = "module.greet"
            }
            insertTextMode = InsertTextMode.AdjustIndentation
            insertText = "greet"
            insertTextFormat = InsertTextFormat.PlainText
            sortText = "2:0000:greet"
        }

        val resolved = service.resolveCompletionItem(unresolved)

        assertEquals("greet", resolved.label)
        assertEquals(CompletionItemKind.Function, resolved.kind)
        val details = assertNotNull(resolved.labelDetails, "labelDetails must survive resolve")
        assertEquals("(name)", details.detail)
        assertEquals("module.greet", details.description)
        assertEquals(InsertTextMode.AdjustIndentation, resolved.insertTextMode, "insertTextMode must survive resolve")
        assertEquals("greet", resolved.insertText)
        assertEquals(InsertTextFormat.PlainText, resolved.insertTextFormat)
        assertEquals("2:0000:greet", resolved.sortText)
    }

    @Test
    fun resolve_keeps_absent_label_details_and_insert_text_mode_absent() {
        val service = service()
        val unresolved = CompletionItem("plain").apply {
            kind = CompletionItemKind.Variable
        }

        val resolved = service.resolveCompletionItem(unresolved)

        assertEquals("plain", resolved.label)
        assertNull(resolved.labelDetails, "resolve must not invent labelDetails")
        assertNull(resolved.insertTextMode, "resolve must not invent insertTextMode")
    }

    @Test
    fun resolve_preserves_label_details_even_when_it_enriches_detail_and_documentation() {
        val service = service()
        val unresolved = CompletionItem("helper").apply {
            kind = CompletionItemKind.Method
            labelDetails = CompletionItemLabelDetails().apply { description = "table.helper" }
            insertTextMode = InsertTextMode.AsIs
        }

        val resolved = service.resolveCompletionItem(unresolved)

        // Enrichment happens (detail from kind, documentation synthesized) ...
        assertNotNull(resolved.detail)
        assertNotNull(resolved.documentation)
        // ... without losing the presentation fields.
        assertEquals("table.helper", assertNotNull(resolved.labelDetails).description)
        assertEquals(InsertTextMode.AsIs, resolved.insertTextMode)
    }

    private fun service(): LuaLanguageService {
        return LuaLanguageService().also { it.initialize(InitializeParams()) }
    }
}
