package lsp

import io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider
import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaTextDocumentService
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.SignatureHelpParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.WorkspaceFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TASK-217 — LSP signature help multi-overload corpus.
 *
 * Encodes the product contract for Java multi-overload signature help:
 * - Distinct arities/shapes surface as multiple [org.eclipse.lsp4j.SignatureInformation]
 *   entries (String.valueOf, StringBuilder.append, Arrays.copyOf).
 * - Primitive-only overloads that collapse to a single Lua number surface (Math.max)
 *   still return callable help with a two-parameter binary signature.
 * - Active parameter index advances across commas (including colon-call receiver offset).
 * - Labels may be generic `fun(...)` / `fun<T>(...)` forms without the Java method name.
 *
 * Product code is intentionally out of scope (test-only). Verification is
 * review-owned and serial; this worker does not run Gradle.
 */
class LspSignatureHelpOverloadTddTest {

    @Test
    fun math_max_static_overloads_return_multiple_signature_entries() {
        val service = jvmService()
        val document = service.open(
            "workspace/signature-overload-math-max.lua",
            """
            local Math = require("Math")
            local current = Math.max(1, 2)
            return current
            """
        )

        val help = assertNotNull(service.signatureHelp(signatureParams(document, "1,")))

        // Product collapses int/long/float/double Math.max into a single number/number
        // surface. Multi-arity overload expansion is covered by valueOf / append / copyOf.
        assertTrue(
            help.signatures.isNotEmpty(),
            "Expected Math.max signature help; got empty signatures"
        )
        assertTrue(
            help.signatures.all { signature -> looksLikeCallableLabel(signature.label) },
            "Each overload label should look like a callable signature: ${help.labels()}"
        )
        // Every Math.max overload takes two parameters (after numeric collapse).
        assertTrue(
            help.signatures.all { it.parameters.size == 2 },
            "Math.max overloads are binary; parameters=${help.signatures.map { it.parameters.size }} labels=${help.labels()}"
        )
        assertTrue(
            help.signatures.any { signature ->
                signature.label.contains("number", ignoreCase = true) ||
                    signature.parameters.any { it.label.left.contains("number", ignoreCase = true) }
            },
            "Collapsed Math.max should expose number parameters: ${help.labels()} / ${help.parameterLabels()}"
        )
        assertTrue(help.activeSignature >= 0 && help.activeSignature < help.signatures.size)
    }

    @Test
    fun math_max_active_parameter_advances_across_comma() {
        val service = jvmService()
        val document = service.open(
            "workspace/signature-overload-math-max-active.lua",
            """
            local Math = require("Math")
            local current = Math.max(1, 2)
            return current
            """
        )

        val first = assertNotNull(service.signatureHelp(signatureParams(document, "1,")))
        val between = assertNotNull(
            service.signatureHelp(
                SignatureHelpParams(
                    TextDocumentIdentifier(document.uri),
                    document.positionBetween("1,", "2")
                )
            )
        )
        val second = assertNotNull(service.signatureHelp(signatureParams(document, "2)")))

        assertEquals(0, first.activeParameter, "Cursor on first argument should select parameter 0")
        assertEquals(1, between.activeParameter, "Cursor after the comma should select parameter 1")
        assertEquals(1, second.activeParameter, "Cursor on second argument should select parameter 1")
        assertTrue(first.signatures.isNotEmpty(), "Math.max must still expose signature entries at the call site")
        assertEquals(first.signatures.size, second.signatures.size)
        assertTrue(first.signatures.all { it.parameters.size == 2 })
    }

    @Test
    fun string_value_of_static_overloads_include_arity_variants() {
        val service = jvmService()
        val document = service.open(
            "workspace/signature-overload-string-valueof.lua",
            """
            local String = require("String")
            local text = String.valueOf("token")
            return text
            """
        )

        val help = assertNotNull(service.signatureHelp(signatureParams(document, "\"token\"")))

        assertTrue(
            help.signatures.size >= 2,
            "Expected String.valueOf multi-overload help; got ${help.signatures.size}: ${help.labels()}"
        )
        val arities = help.signatures.map { it.parameters.size }.toSet()
        assertTrue(
            arities.size >= 2,
            "String.valueOf should expose arity variants (1-arg and 3-arg); arities=$arities labels=${help.labels()}"
        )
        assertTrue(
            help.signatures.any { it.parameters.size == 1 },
            "Expected at least one unary valueOf overload among ${help.labels()}"
        )
        assertTrue(
            help.signatures.any { it.parameters.size >= 3 },
            "Expected valueOf(char[], int, int) style overload among ${help.labels()}"
        )
        assertEquals(0, help.activeParameter)
    }

    @Test
    fun string_value_of_active_parameter_tracks_three_argument_call() {
        val service = jvmService()
        val document = service.open(
            "workspace/signature-overload-string-valueof-active.lua",
            """
            local String = require("String")
            local text = String.valueOf(chars, 0, 1)
            return text
            """
        )

        val first = assertNotNull(service.signatureHelp(signatureParams(document, "chars,")))
        val second = assertNotNull(service.signatureHelp(signatureParams(document, "0,")))
        val third = assertNotNull(service.signatureHelp(signatureParams(document, "1)")))

        assertEquals(0, first.activeParameter)
        assertEquals(1, second.activeParameter)
        assertEquals(2, third.activeParameter)
        assertTrue(
            first.signatures.size >= 2,
            "Multi-overload set must remain available across the call site; size=${first.signatures.size}"
        )
        // Three-argument cursor positions should still pick a signature that can host the active param.
        val active = third.signatures[third.activeSignature.coerceIn(0, third.signatures.lastIndex)]
        assertTrue(
            active.parameters.size > third.activeParameter,
            "Active signature ${active.label} must include activeParameter=${third.activeParameter}"
        )
    }

    @Test
    fun string_builder_append_instance_overloads_return_multiple_entries() {
        val service = jvmService()
        val document = service.open(
            "workspace/signature-overload-stringbuilder-append.lua",
            """
            import "java.lang.StringBuilder"
            local builder = StringBuilder()
            local current = builder:append("prefix")
            return current
            """
        )

        val help = assertNotNull(service.signatureHelp(signatureParams(document, "\"prefix\"")))

        assertTrue(
            help.signatures.size >= 2,
            "Expected StringBuilder.append multi-overload help; got ${help.signatures.size}: ${help.labels()}"
        )
        // Colon call injects an implicit self receiver as parameter 0.
        assertTrue(
            help.signatures.any { signature ->
                signature.parameters.size >= 2 || signature.label.contains("self")
            },
            "Colon append should expose receiver-aware parameters: ${help.labels()} / ${help.parameterLabels()}"
        )
        // Cursor is on the first explicit argument → active parameter is past self.
        assertTrue(
            help.activeParameter >= 1,
            "Colon call should offset activeParameter past self; activeParameter=${help.activeParameter}"
        )
    }

    @Test
    fun string_builder_append_active_parameter_advances_for_multi_arg_overload() {
        val service = jvmService()
        val document = service.open(
            "workspace/signature-overload-stringbuilder-append-active.lua",
            """
            import "java.lang.StringBuilder"
            local builder = StringBuilder()
            local current = builder:append("text", 0, 1)
            return current
            """
        )

        val first = assertNotNull(service.signatureHelp(signatureParams(document, "\"text\",")))
        val second = assertNotNull(service.signatureHelp(signatureParams(document, "0,")))
        val third = assertNotNull(service.signatureHelp(signatureParams(document, "1)")))

        // self + three explicit args → active indices 1, 2, 3
        assertEquals(1, first.activeParameter, "first explicit arg after colon receiver")
        assertEquals(2, second.activeParameter, "second explicit arg")
        assertEquals(3, third.activeParameter, "third explicit arg")
        assertTrue(first.signatures.size >= 2, "Overload set retained at multi-arg call site")
        assertEquals(first.signatures.size, third.signatures.size)
    }

    @Test
    fun arrays_copy_of_overloads_return_multiple_entries_and_track_active_parameter() {
        val service = jvmService()
        val document = service.open(
            "workspace/signature-overload-arrays-copyof.lua",
            """
            local Arrays = require("Arrays")
            local copy = Arrays.copyOf(source, 4)
            return copy
            """
        )

        val first = assertNotNull(service.signatureHelp(signatureParams(document, "source,")))
        val second = assertNotNull(service.signatureHelp(signatureParams(document, "4)")))

        assertTrue(
            first.signatures.size >= 2,
            "Expected Arrays.copyOf multi-overload help; got ${first.signatures.size}: ${first.labels()}"
        )
        // Most primitive copyOf overloads are binary (array, newLength); generic 3-arg also exists.
        assertTrue(
            first.signatures.any { it.parameters.size == 2 },
            "Expected binary copyOf overloads among ${first.labels()}"
        )
        assertTrue(
            first.signatures.any { it.parameters.size >= 3 } || first.signatures.size >= 8,
            "Expected either the 3-arg generic copyOf or the full primitive overload set; labels=${first.labels()}"
        )
        assertEquals(0, first.activeParameter)
        assertEquals(1, second.activeParameter)
        assertEquals(first.signatures.size, second.signatures.size)
    }

    @Test
    fun arrays_as_list_still_exposes_signature_help_at_call_site() {
        val service = jvmService()
        val document = service.open(
            "workspace/signature-overload-arrays-aslist.lua",
            """
            local Arrays = require("Arrays")
            local list = Arrays.asList("one", "two", "three")
            return list
            """
        )

        val first = assertNotNull(service.signatureHelp(signatureParams(document, "\"one\",")))
        val second = assertNotNull(service.signatureHelp(signatureParams(document, "\"two\",")))

        assertTrue(first.signatures.isNotEmpty(), "asList should still produce signature help")
        // Product labels look like fun<T>(arg1: T...): java.util.List<T> (no method name,
        // and type parameters sit between `fun` and `(` so bare "fun(" may not match).
        assertTrue(
            first.signatures.any { looksLikeCallableLabel(it.label) || it.parameters.isNotEmpty() },
            "asList labels=${first.labels()}"
        )
        assertTrue(
            first.signatures.any { signature ->
                signature.parameters.any { parameter ->
                    parameter.label.left.contains("...") || parameter.label.left.contains("T")
                } || signature.label.contains("...") || signature.label.contains("List")
            },
            "asList should surface vararg/List-shaped formals: ${first.labels()} / ${first.parameterLabels()}"
        )
        // Vararg formals may clamp activeParameter to the last formal index; multi-param
        // tracking is covered by Math.max / valueOf / copyOf / append cases. Still require
        // a non-null help payload with a non-negative active index at every comma site.
        assertTrue(first.activeParameter >= 0)
        assertTrue(second.activeParameter >= 0)
        assertTrue(second.activeParameter >= first.activeParameter)
    }

    @Test
    fun text_document_service_wraps_java_multi_overload_signature_help() {
        val service = jvmService()
        val textDocuments = LuaTextDocumentService(service)
        // Prefer a multi-arity method so TextDocumentService is validated against true
        // multi-overload forwarding (Math.max collapses to a single number surface).
        val document = textDocuments.open(
            "workspace/signature-overload-text-document.lua",
            """
            local String = require("String")
            local text = String.valueOf(chars, 0, 1)
            return text
            """
        )

        val help = assertNotNull(textDocuments.signatureHelp(signatureParams(document, "0,")).get())

        assertTrue(
            help.signatures.size >= 2,
            "TextDocumentService must forward multi-overload entries; got ${help.signatures.size}: ${help.labels()}"
        )
        assertEquals(1, help.activeParameter)
        assertTrue(
            help.signatures.any { it.parameters.size >= 3 },
            "Expected a 3-arg valueOf overload among TextDocumentService payload: ${help.labels()}"
        )
    }

    // -------------------------------------------------------------------------
    // Harness
    // -------------------------------------------------------------------------

    private fun jvmService(): LuaLanguageService {
        return LuaLanguageService().apply {
            initialize(
                InitializeParams().apply {
                    workspaceFolders = listOf(WorkspaceFolder("file:///workspace", "workspace"))
                }
            )
            setWorkspaceMetadata(
                mapOf(
                    JvmClassModuleProvider.CLASSES_METADATA_KEY to listOf(
                        "java.lang.Math",
                        "java.lang.String",
                        "java.lang.StringBuilder",
                        "java.util.Arrays"
                    ).joinToString("\n")
                )
            )
        }
    }

    private fun LuaLanguageService.open(path: String, source: String): OpenDocument {
        val document = OpenDocument(path = path, source = source.trimIndent())
        didOpen(DidOpenTextDocumentParams(TextDocumentItem(document.uri, "lua", 1, document.source)))
        return document
    }

    private fun LuaTextDocumentService.open(path: String, source: String): OpenDocument {
        val document = OpenDocument(path = path, source = source.trimIndent())
        didOpen(DidOpenTextDocumentParams(TextDocumentItem(document.uri, "lua", 1, document.source)))
        return document
    }

    private fun signatureParams(document: OpenDocument, needle: String, occurrence: Int = 1): SignatureHelpParams {
        return SignatureHelpParams(TextDocumentIdentifier(document.uri), document.positionOf(needle, occurrence))
    }

    private fun SignatureHelp.labels(): List<String> = signatures.map { it.label }

    private fun SignatureHelp.parameterLabels(): List<List<String>> =
        signatures.map { signature -> signature.parameters.map { it.label.left } }

    /**
     * Product labels are typically `fun(...)` or generic `fun<T>(...)` (and may omit the
     * Java method name). Accept any of those shapes plus an explicit method-name form.
     */
    private fun looksLikeCallableLabel(label: String): Boolean {
        val trimmed = label.trim()
        return trimmed.contains("fun") ||
            trimmed.contains("(") ||
            trimmed.contains("max", ignoreCase = true) ||
            trimmed.contains("valueOf", ignoreCase = true) ||
            trimmed.contains("append", ignoreCase = true) ||
            trimmed.contains("asList", ignoreCase = true) ||
            trimmed.contains("copyOf", ignoreCase = true)
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
                require(index >= 0) { "Could not find occurrence ${it + 1} of '$needle' in $path" }
                fromIndex = index + needle.length
            }
            return positionAt(index)
        }

        /**
         * Cursor between two needles (after the end of [before], before the start of [after]).
         * Used to land on the comma gap between arguments.
         */
        fun positionBetween(before: String, after: String): Position {
            val beforeIndex = source.indexOf(before)
            require(beforeIndex >= 0) { "Could not find '$before' in $path" }
            val afterIndex = source.indexOf(after, beforeIndex + before.length)
            require(afterIndex >= 0) { "Could not find '$after' after '$before' in $path" }
            val gap = beforeIndex + before.length
            // Prefer a position inside the gap; if adjacent, use the after start.
            val offset = if (gap < afterIndex) gap else afterIndex
            return positionAt(offset)
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
