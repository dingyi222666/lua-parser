package lsp

import io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider
import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.WorkspaceFolder
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Adversarial-audit fix: `obj:` member completion used to return the identical member
 * surface as `obj.` (only the sort order differed), so non-callable fields such as the
 * conservative JavaBean property aliases (`path` / `name` on `java.io.File`) were offered
 * at a call site where they cannot be invoked.
 *
 * Contract:
 * - `f:` offers `getName`-style methods but no field-only labels (`path`, `name`).
 * - `f.` still offers the bean aliases next to the methods (unchanged surface).
 * - Lua functions stored in table fields remain reachable through `:` because their
 *   `fun(` display marks them callable; fields with a KNOWN non-callable type (plain
 *   data fields) are dropped.
 * - A field whose type is Unknown / absent is NOT dropped: unknown does not mean
 *   non-callable, so it stays in the `:` surface (regression follow-up).
 *
 * Test-only. Verification is review-owned (TASK-043). Workers must not run Gradle.
 */
class LspColonMemberCompletionCallableOnlyTddTest {

    @Test
    fun colon_completion_on_java_instance_drops_bean_aliases_but_keeps_methods() {
        val service = jvmService()
        val uri = "file:///workspace/colon-file.lua"
        service.didOpen(
            DidOpenTextDocumentParams(TextDocumentItem(uri, "lua", 1, FILE_SOURCE))
        )

        // Caret on `getName` in `local n = f:getName()` / on `name` in `local p = f.name`.
        val colonLabels = service.completion(uri, 2, 12).items.map { it.label }
        val dotLabels = service.completion(uri, 3, 12).items.map { it.label }

        assertTrue("getName" in colonLabels, "colon surface must keep instance methods; got $colonLabels")
        assertTrue("getPath" in colonLabels, "colon surface must keep instance methods; got $colonLabels")
        assertFalse("name" in colonLabels, "colon surface must not offer the `name` bean alias; got $colonLabels")
        assertFalse("path" in colonLabels, "colon surface must not offer the `path` bean alias; got $colonLabels")

        assertTrue("getName" in dotLabels, "dot surface keeps instance methods; got $dotLabels")
        assertTrue("name" in dotLabels, "dot surface keeps the `name` bean alias; got $dotLabels")
    }

    @Test
    fun colon_completion_on_java_instance_offers_no_field_kind_items() {
        val service = jvmService()
        val uri = "file:///workspace/colon-file-kinds.lua"
        service.didOpen(
            DidOpenTextDocumentParams(TextDocumentItem(uri, "lua", 1, FILE_SOURCE))
        )

        val colonItems = service.completion(uri, 2, 12).items
        assertTrue(colonItems.isNotEmpty(), "colon completion on a File instance must not be empty")
        val fieldKinds = colonItems.filter {
            it.kind == CompletionItemKind.Field || it.kind == CompletionItemKind.Property
        }
        assertTrue(
            fieldKinds.isEmpty(),
            "colon surface must not contain field-kind items; got ${fieldKinds.map { it.label }}"
        )
    }

    @Test
    fun colon_completion_on_lua_table_keeps_callable_fields_and_drops_data_fields() {
        val service = plainService()
        val uri = "file:///workspace/colon-table.lua"
        service.didOpen(
            DidOpenTextDocumentParams(TextDocumentItem(uri, "lua", 1, TABLE_SOURCE))
        )

        // Caret on `reset` in `local r = widget:reset()` / on `count` in `local c = widget.count`.
        val colonLabels = service.completion(uri, 9, 17).items.map { it.label }
        val dotLabels = service.completion(uri, 10, 17).items.map { it.label }

        assertTrue("reset" in colonLabels, "colon surface keeps methods; got $colonLabels")
        assertTrue("describe" in colonLabels, "colon surface keeps callable function fields; got $colonLabels")
        assertFalse("count" in colonLabels, "colon surface drops non-callable data fields; got $colonLabels")
        assertFalse("label" in colonLabels, "colon surface drops non-callable data fields; got $colonLabels")

        assertTrue("count" in dotLabels, "dot surface keeps data fields; got $dotLabels")
        assertTrue("describe" in dotLabels, "dot surface keeps function fields; got $dotLabels")
    }

    @Test
    fun colon_completion_keeps_unknown_typed_fields() {
        val service = plainService()
        val uri = "file:///workspace/colon-unknown-field.lua"
        service.didOpen(
            DidOpenTextDocumentParams(TextDocumentItem(uri, "lua", 1, UNKNOWN_FIELD_SOURCE))
        )

        // `handler` is assigned an unknown-typed value, so its member surface display is
        // "unknown" — no callability signal. Caret on `handler` in `local r = t:handler()`
        // (character 12) and on `count` in `local c = t.count` (character 12).
        val colonLabels = service.completion(uri, 1, 12).items.map { it.label }
        val dotLabels = service.completion(uri, 2, 12).items.map { it.label }

        assertTrue(
            "handler" in colonLabels,
            "unknown-typed field must NOT be filtered from the colon surface; got $colonLabels"
        )
        assertTrue("handler" in dotLabels, "dot surface keeps every member; got $dotLabels")
        assertFalse(
            "count" in colonLabels,
            "a field with a KNOWN non-callable type stays dropped; got $colonLabels"
        )
        assertTrue("count" in dotLabels, "dot surface keeps data fields; got $dotLabels")
    }

    // --- helpers -----------------------------------------------------------------

    private fun jvmService(): LuaLanguageService {
        return plainService().apply {
            setWorkspaceMetadata(
                mapOf(JvmClassModuleProvider.CLASSES_METADATA_KEY to "java.io.File")
            )
        }
    }

    private fun plainService(): LuaLanguageService {
        return LuaLanguageService().also { service ->
            service.initialize(
                InitializeParams().apply {
                    workspaceFolders = listOf(WorkspaceFolder("file:///workspace", "workspace"))
                }
            )
            check(service.awaitWorkspaceReady()) { "background build did not finish" }
        }
    }

    companion object {
        /** Line 2 caret target `getName` starts at character 12; line 3 `name` at 12. */
        private const val FILE_SOURCE =
            "local File = luajava.bindClass(\"java.io.File\")\n" +
                "local f = File(\"x\")\n" +
                "local n = f:getName()\n" +
                "local p = f.name"

        /** Line 9 caret target `reset` starts at character 17; line 10 `count` at 17. */
        private const val TABLE_SOURCE =
            "local widget = {}\n" +
                "widget.count = 1\n" +
                "widget.label = \"x\"\n" +
                "function widget.describe(self)\n" +
                "    return self.label\n" +
                "end\n" +
                "function widget:reset()\n" +
                "    self.count = 0\n" +
                "end\n" +
                "local r = widget:reset()\n" +
                "local c = widget.count"

        /**
         * `handler` is assigned an unknown-typed value (unresolvable global) so its field
         * surface display is "unknown"; `count` is a known number data field.
         * Line 2 caret target `handler` starts at character 12; line 3 `count` at 12.
         */
        private const val UNKNOWN_FIELD_SOURCE =
            "local t = { handler = missingGlobal, count = 1 }\n" +
                "local r = t:handler()\n" +
                "local c = t.count"
    }
}
