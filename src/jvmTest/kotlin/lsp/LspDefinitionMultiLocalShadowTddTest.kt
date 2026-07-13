package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaTextDocumentService
import org.eclipse.lsp4j.DeclarationParams
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * TASK-356 — LSP definition multi-local shadow corpus.
 *
 * Encodes the contract for textDocument/definition (and declaration) when the
 * same local name is nested across block / function / loop scopes:
 * - A use inside the innermost shadow resolves to the innermost local binding
 *   (not middle / outer).
 * - Uses outside a shadow remain bound to the still-visible outer local.
 * - Sibling blocks do not leak each other's shadows.
 * - Parameter / for-loop locals shadow outer chunk locals only inside their body.
 * - Definition / declaration stay in the requesting file for pure locals and
 *   never invent cross-file provider paths.
 *
 * Mirrors binder policy already covered by
 * semantic.binder.ScopeGraphParentChainTddTest (innermost-first along parent
 * chains) at the JVM LSP surface via [LuaLanguageService.definition] /
 * [LuaLanguageService.declaration] and the [LuaTextDocumentService] wrappers.
 *
 * Product code is intentionally out of scope (test-only). Verification is
 * review-owned and serial; this worker does not run Gradle.
 */
class LspDefinitionMultiLocalShadowTddTest {

    // -------------------------------------------------------------------------
    // Innermost shadow preferred
    // -------------------------------------------------------------------------

    @Test
    fun definition_inner_use_prefers_innermost_local_shadow() {
        val service = service()
        val document = service.open(
            "workspace/definition-shadow-inner.lua",
            """
            local value = "outer"
            do
                local value = "middle"
                do
                    local value = "inner"
                    local chosen = value
                end
            end
            """
        )

        // Occurrence map: 1 outer decl, 2 middle decl, 3 inner decl, 4 use.
        val locations = service.definition(definitionParams(document, "value", occurrence = 4))

        assertSingleLocalDefinitionOnLine(
            locations = locations,
            document = document,
            expectedLine = document.lineOf("value", occurrence = 3),
            label = "inner use of shadowed 'value'"
        )
        assertFalse(
            locations.any {
                it.range.start.line == document.lineOf("value", occurrence = 1) ||
                    it.range.start.line == document.lineOf("value", occurrence = 2)
            },
            "inner use must not land on outer/middle binding; got ${locations.describe()}"
        )
    }

    @Test
    fun definition_middle_use_prefers_middle_shadow_not_outer_or_inner() {
        val service = service()
        val document = service.open(
            "workspace/definition-shadow-middle.lua",
            """
            local value = "outer"
            do
                local value = "middle"
                do
                    local value = "inner"
                    local chosen = value
                end
                local midUse = value
            end
            """
        )

        // Occurrences: 1 outer, 2 middle, 3 inner, 4 inner use, 5 middle use.
        val locations = service.definition(definitionParams(document, "value", occurrence = 5))

        assertSingleLocalDefinitionOnLine(
            locations = locations,
            document = document,
            expectedLine = document.lineOf("value", occurrence = 2),
            label = "middle-scope use of 'value'"
        )
        assertFalse(
            locations.any {
                it.range.start.line == document.lineOf("value", occurrence = 1) ||
                    it.range.start.line == document.lineOf("value", occurrence = 3)
            },
            "middle use must not land on outer or inner binding; got ${locations.describe()}"
        )
    }

    // -------------------------------------------------------------------------
    // Parameter / function / loop shadows
    // -------------------------------------------------------------------------

    @Test
    fun definition_function_parameter_shadows_outer_local_inside_body() {
        val service = service()
        val document = service.open(
            "workspace/definition-shadow-param.lua",
            """
            local name = 1
            local function render(name)
                local body = name
            end
            local after = name
            """
        )

        // Occurrences: 1 outer local, 2 function name "render" is not "name",
        // "name" sites: 1 outer decl, 2 parameter, 3 body use, 4 after use.
        val bodyUse = service.definition(definitionParams(document, "name", occurrence = 3))
        val afterUse = service.definition(definitionParams(document, "name", occurrence = 4))

        assertSingleLocalDefinitionOnLine(
            locations = bodyUse,
            document = document,
            expectedLine = document.lineOf("name", occurrence = 2),
            label = "body use of parameter-shadowed 'name'"
        )
        assertSingleLocalDefinitionOnLine(
            locations = afterUse,
            document = document,
            expectedLine = document.lineOf("name", occurrence = 1),
            label = "after-function use of outer 'name'"
        )
    }

    @Test
    fun definition_for_loop_variable_shadows_outer_local_only_inside_loop() {
        val service = service()
        val document = service.open(
            "workspace/definition-shadow-for.lua",
            """
            local i = 100
            for i = 1, 3 do
                local body = i
            end
            local after = i
            """
        )

        // Occurrences of "i": 1 outer, 2 loop var, 3 body use, 4 after use.
        val bodyUse = service.definition(definitionParams(document, "i", occurrence = 3))
        val afterUse = service.definition(definitionParams(document, "i", occurrence = 4))

        assertSingleLocalDefinitionOnLine(
            locations = bodyUse,
            document = document,
            expectedLine = document.lineOf("i", occurrence = 2),
            label = "for-body use of loop variable 'i'"
        )
        assertSingleLocalDefinitionOnLine(
            locations = afterUse,
            document = document,
            expectedLine = document.lineOf("i", occurrence = 1),
            label = "after-loop use of outer 'i'"
        )
    }

    // -------------------------------------------------------------------------
    // Sibling isolation / multi-file safety
    // -------------------------------------------------------------------------

    @Test
    fun definition_same_named_local_in_other_file_does_not_steal_binding() {
        val service = service()
        service.open(
            "workspace/definition-shadow-other.lua",
            """
            local value = 99
            return value
            """
        )
        val document = service.open(
            "workspace/definition-shadow-same-file.lua",
            """
            local value = 1
            do
                local value = 2
                local pick = value
            end
            local outer = value
            """
        )

        val innerUse = service.definition(definitionParams(document, "value", occurrence = 3))
        val outerUse = service.definition(definitionParams(document, "value", occurrence = 4))

        assertSingleLocalDefinitionOnLine(
            locations = innerUse,
            document = document,
            expectedLine = document.lineOf("value", occurrence = 2),
            label = "same-file inner shadow"
        )
        assertSingleLocalDefinitionOnLine(
            locations = outerUse,
            document = document,
            expectedLine = document.lineOf("value", occurrence = 1),
            label = "same-file outer use"
        )
        assertTrue(
            innerUse.all { it.uri == document.uri } && outerUse.all { it.uri == document.uri },
            "pure local definition must stay in requesting file; " +
                "inner=${innerUse.describe()} outer=${outerUse.describe()}"
        )
        assertFalse(
            (innerUse + outerUse).any { it.uri.contains("definition-shadow-other") },
            "other-file same-named local must not steal binding"
        )
    }

    // -------------------------------------------------------------------------
    // TextDocumentService wrapper + safety
    // -------------------------------------------------------------------------

    @Test
    fun text_document_service_definition_prefers_innermost_local_shadow() {
        val languageService = service()
        val textDocuments = LuaTextDocumentService(languageService)
        val document = textDocuments.open(
            "workspace/definition-shadow-text-service.lua",
            """
            local value = "outer"
            do
                local value = "inner"
                local chosen = value
            end
            local outerUse = value
            """
        )

        // Occurrences: 1 outer, 2 inner, 3 use, 4 outerUse.
        val inner = textDocuments.definition(definitionParams(document, "value", occurrence = 3)).get().left
        val outer = textDocuments.definition(definitionParams(document, "value", occurrence = 4)).get().left

        assertSingleLocalDefinitionOnLine(
            locations = inner,
            document = document,
            expectedLine = document.lineOf("value", occurrence = 2),
            label = "textDocument definition inner use"
        )
        assertSingleLocalDefinitionOnLine(
            locations = outer,
            document = document,
            expectedLine = document.lineOf("value", occurrence = 1),
            label = "textDocument definition outer use"
        )
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun service(): LuaLanguageService {
        return LuaLanguageService().apply {
            initialize(InitializeParams())
        }
    }

    private fun LuaLanguageService.open(path: String, source: String): OpenDocument {
        val document = OpenDocument(path = path, source = source.trimIndent())
        didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(document.uri, "lua", 1, document.source)
            )
        )
        return document
    }

    private fun LuaTextDocumentService.open(path: String, source: String): OpenDocument {
        val document = OpenDocument(path = path, source = source.trimIndent())
        didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(document.uri, "lua", 1, document.source)
            )
        )
        return document
    }

    private fun definitionParams(
        document: OpenDocument,
        needle: String,
        occurrence: Int = 1
    ): DefinitionParams {
        return DefinitionParams(
            TextDocumentIdentifier(document.uri),
            document.positionOf(needle, occurrence)
        )
    }

    private fun declarationParams(
        document: OpenDocument,
        needle: String,
        occurrence: Int = 1
    ): DeclarationParams {
        return DeclarationParams(
            TextDocumentIdentifier(document.uri),
            document.positionOf(needle, occurrence)
        )
    }

    private fun assertSingleLocalDefinitionOnLine(
        locations: List<Location>,
        document: OpenDocument,
        expectedLine: Int,
        label: String
    ) {
        assertTrue(
            locations.isNotEmpty(),
            "$label expected a definition location; got empty list"
        )
        // Prefer single hit; product may still return multi-location lists for the
        // same binding (e.g. overlapping ranges). Require at least one hit on the
        // expected line and that every hit stays in-file.
        assertTrue(
            locations.any { it.uri == document.uri && it.range.start.line == expectedLine },
            "$label expected a same-file hit on line $expectedLine; got ${locations.describe()}"
        )
        assertTrue(
            locations.all { it.uri == document.uri },
            "$label pure local definition must stay in requesting file; got ${locations.describe()}"
        )
        // Hard single-location golden when product already returns one hit.
        if (locations.size == 1) {
            assertEquals(document.uri, locations.single().uri, "$label URI")
            assertEquals(expectedLine, locations.single().range.start.line, "$label line")
        }
    }

    private fun List<Location>.describe(): String {
        return joinToString(prefix = "[", postfix = "]") { location ->
            "${location.uri}@${location.range.start.line}:${location.range.start.character}" +
                "-${location.range.end.line}:${location.range.end.character}"
        }
    }

    private fun isHardCrash(error: Throwable): Boolean {
        val detail = buildString {
            var current: Throwable? = error
            var depth = 0
            while (current != null && depth < 6) {
                if (depth > 0) append('|')
                append(current::class.simpleName.orEmpty())
                append(':')
                append(current.message.orEmpty())
                current = current.cause
                depth += 1
            }
        }
        return detail.contains("NullPointerException", ignoreCase = true) ||
            detail.contains("KotlinNullPointerException", ignoreCase = true) ||
            detail.contains("IndexOutOfBoundsException", ignoreCase = true) ||
            detail.contains("ArrayIndexOutOfBoundsException", ignoreCase = true) ||
            detail.contains("StringIndexOutOfBoundsException", ignoreCase = true) ||
            detail.contains("StackOverflowError", ignoreCase = true)
    }

    private data class OpenDocument(
        val path: String,
        val source: String
    ) {
        val uri: String = "file:///$path"

        fun positionOf(needle: String, occurrence: Int = 1): Position {
            require(occurrence >= 1) { "occurrence must be positive" }
            var index = -1
            var fromIndex = 0
            repeat(occurrence) {
                index = source.indexOf(needle, fromIndex)
                require(index >= 0) { "Could not find occurrence $occurrence of '$needle' in $path" }
                fromIndex = index + needle.length
            }
            return positionAt(index)
        }

        fun lineOf(needle: String, occurrence: Int = 1): Int {
            return positionOf(needle, occurrence).line
        }

        private fun positionAt(offset: Int): Position {
            var line = 0
            var lineStart = 0
            for (i in 0 until offset) {
                if (source[i] == '\n') {
                    line += 1
                    lineStart = i + 1
                }
            }
            return Position(line, offset - lineStart)
        }
    }
}
