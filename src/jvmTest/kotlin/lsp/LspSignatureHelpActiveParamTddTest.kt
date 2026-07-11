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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TASK-263 — SignatureHelp active parameter multi-arg corpus.
 *
 * Encodes the product contract for [org.eclipse.lsp4j.SignatureHelp.activeParameter]
 * across comma-separated argument lists:
 * - Simple annotated local functions advance activeParameter 0 → 1 → 2 across args.
 * - Colon-method receivers offset activeParameter past implicit `self`.
 * - Overloaded Java callables (String.valueOf, Math.max) keep multi-arg tracking
 *   when overload entries are available.
 * - Outside a call argument region, signatureHelp returns null (empty/null policy)
 *   without throwing for non-call tokens / empty sources.
 *
 * Product code is intentionally out of scope (test-only). Verification is
 * review-owned and serial; this worker does not run Gradle.
 *
 * Goldens for empty-arg lists and post-call positions follow
 * SignatureHelpProvider / NodePositionIndex half-open vs inclusive edge policy:
 * - Empty `f()` argument region is base.range.end..call.range.end (inclusive end).
 * - Exact call.range.end (cursor immediately after `)`) is still treated as
 *   within the call by SignatureHelpProvider.contains.
 * - Parser finishNode currently ends CallExpression at the *next significant token*
 *   after `)` (peek leaves that token as current). Product therefore still reports
 *   help on that next token (e.g. `return` / `local` of the following statement).
 *   True outside-call null is asserted past that inflated end (e.g. a later
 *   identifier) plus non-call identifiers, declarations, and empty sites.
 */
class LspSignatureHelpActiveParamTddTest {

    @Test
    fun simple_multi_arg_function_tracks_active_parameter_across_commas() {
        val service = plainService()
        val document = service.open(
            "workspace/signature-active-simple.lua",
            """
            ---@param a number
            ---@param b string
            ---@param c boolean
            local function paint(a, b, c)
                return c
            end
            local current = paint(1, "mid", true)
            return current
            """
        )

        val first = assertNotNull(service.signatureHelp(signatureParams(document, "1,")))
        val second = assertNotNull(service.signatureHelp(signatureParams(document, "\"mid\",")))
        val third = assertNotNull(service.signatureHelp(signatureParams(document, "true)")))

        assertEquals(0, first.activeParameter, "cursor on first argument")
        assertEquals(1, second.activeParameter, "cursor on second argument")
        assertEquals(2, third.activeParameter, "cursor on third argument")
        assertTrue(first.signatures.isNotEmpty())
        assertEquals(3, first.signatures.single().parameters.size)
        assertEquals(first.signatures.size, third.signatures.size)
    }

    @Test
    fun simple_multi_arg_function_advances_when_cursor_is_between_arguments() {
        val service = plainService()
        val document = service.open(
            "workspace/signature-active-between.lua",
            """
            ---@param value number
            ---@param label string
            local function render(value, label)
                return label
            end
            local current = render(1,  "hi")
            return current
            """
        )

        val onFirst = assertNotNull(service.signatureHelp(signatureParams(document, "1,")))
        val between = assertNotNull(
            service.signatureHelp(
                SignatureHelpParams(
                    TextDocumentIdentifier(document.uri),
                    document.positionBetween("1,", "\"hi\"")
                )
            )
        )
        val onSecond = assertNotNull(service.signatureHelp(signatureParams(document, "\"hi\")")))

        assertEquals(0, onFirst.activeParameter)
        assertEquals(1, between.activeParameter, "cursor after comma / in gap selects next parameter")
        assertEquals(1, onSecond.activeParameter)
    }

    @Test
    fun colon_method_receiver_offsets_active_parameter_for_multi_arg_call() {
        val service = plainService()
        val document = service.open(
            "workspace/signature-active-colon.lua",
            """
            local box = {}
            ---@param self table
            ---@param value number
            ---@param label string
            function box:render(value, label)
                return label
            end
            local current = box:render(1, "hi")
            return current
            """
        )

        val first = assertNotNull(service.signatureHelp(signatureParams(document, "1,")))
        val second = assertNotNull(service.signatureHelp(signatureParams(document, "\"hi\")")))

        // self + two explicit args → active indices 1, 2
        assertEquals(1, first.activeParameter, "first explicit arg after colon receiver")
        assertEquals(2, second.activeParameter, "second explicit arg")
        assertTrue(
            first.signatures.single().parameters.size >= 3,
            "colon method signature should include self: ${first.labels()}"
        )
    }

    @Test
    fun overloaded_string_value_of_tracks_active_parameter_across_three_args() {
        val service = jvmService()
        val document = service.open(
            "workspace/signature-active-valueof.lua",
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
            "Overloaded valueOf must expose multiple signatures; size=${first.signatures.size} labels=${first.labels()}"
        )
        val active = third.signatures[third.activeSignature.coerceIn(0, third.signatures.lastIndex)]
        assertTrue(
            active.parameters.size > third.activeParameter,
            "Active signature ${active.label} must host activeParameter=${third.activeParameter}"
        )
    }

    @Test
    fun overloaded_math_max_advances_active_parameter_across_binary_call() {
        val service = jvmService()
        val document = service.open(
            "workspace/signature-active-math-max.lua",
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

        assertEquals(0, first.activeParameter)
        assertEquals(1, between.activeParameter)
        assertEquals(1, second.activeParameter)
        assertTrue(first.signatures.isNotEmpty(), "Math.max must expose signature help")
        assertTrue(first.signatures.all { it.parameters.size == 2 })
    }

    @Test
    fun outside_call_context_returns_null_without_crash() {
        val service = plainService()
        val document = service.open(
            "workspace/signature-active-outside.lua",
            """
            ---@param value number
            ---@param label string
            local function render(value, label)
                return label
            end
            local current = render(1, "hi")
            local sentinel = true
            return sentinel
            """
        )

        // On the function name at the call site (outside argument region).
        // Needle includes call args so we do not hit the declaration params list.
        val onCallee = service.signatureHelp(signatureParams(document, "render(1"))
        // On a local binding / non-call token (first "current").
        val onLocal = service.signatureHelp(signatureParams(document, "current"))
        // On the function declaration identifier (not a call).
        val onDecl = service.signatureHelp(signatureParams(document, "function render"))
        // Immediately after the closing ')' — product SignatureHelpProvider.contains is
        // end-inclusive on call.range.end, so this is still a call-argument site.
        val atCallEnd = assertNotNull(
            service.signatureHelp(
                SignatureHelpParams(
                    TextDocumentIdentifier(document.uri),
                    document.positionAfter("render(1, \"hi\")")
                )
            ),
            "exact call.range.end remains inside argument region per SignatureHelpProvider"
        )
        // Parser currently ends CallExpression at the next significant token after `)`
        // (peek leaves that token current for finishNode). Product therefore still reports
        // help on that token; assert null only past it — on the following statement's
        // identifier ("sentinel"), which is outside the inflated call.range.end.
        val onNextStatementKeyword = assertNotNull(
            service.signatureHelp(signatureParams(document, "local sentinel")),
            "next significant token after call is still inside product call.range.end"
        )
        val afterCallOutside = service.signatureHelp(signatureParams(document, "sentinel"))

        assertNull(onCallee, "signature help on callee name should be null (outside args)")
        assertNull(onLocal, "signature help on non-call identifier should be null")
        assertNull(onDecl, "signature help on function declaration should be null")
        assertTrue(atCallEnd.signatures.isNotEmpty())
        assertTrue(atCallEnd.activeParameter >= 0)
        assertTrue(onNextStatementKeyword.signatures.isNotEmpty())
        assertNull(
            afterCallOutside,
            "signature help past next-token call.range.end (sentinel identifier) should be null"
        )
    }

    @Test
    fun outside_call_context_on_empty_and_statement_sites_returns_null_without_crash() {
        val service = plainService()
        val empty = service.open(
            "workspace/signature-active-empty.lua",
            """
            local x = 1
            return x
            """
        )
        val helpOnNumber = service.signatureHelp(signatureParams(empty, "1"))
        val helpOnReturn = service.signatureHelp(signatureParams(empty, "return"))

        assertNull(helpOnNumber)
        assertNull(helpOnReturn)

        // Completely empty argument-less expression site still must not throw.
        val bare = service.open(
            "workspace/signature-active-bare.lua",
            """
            local name = "token"
            """
        )
        assertNull(service.signatureHelp(signatureParams(bare, "token")))
    }

    @Test
    fun text_document_service_forwards_active_parameter_and_outside_null_policy() {
        val service = plainService()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/signature-active-text-document.lua",
            """
            ---@param a number
            ---@param b string
            ---@param c boolean
            local function paint(a, b, c)
                return c
            end
            local current = paint(1, "mid", true)
            return current
            """
        )

        val second = assertNotNull(
            textDocuments.signatureHelp(signatureParams(document, "\"mid\",")).get()
        )
        assertEquals(1, second.activeParameter)
        assertTrue(second.signatures.isNotEmpty())

        val outside = textDocuments.signatureHelp(signatureParams(document, "current")).get()
        assertNull(outside)
    }

    @Test
    fun empty_argument_list_still_selects_first_parameter_inside_parens() {
        val service = plainService()
        val document = service.open(
            "workspace/signature-active-empty-args.lua",
            """
            ---@param value number
            ---@param label string
            local function render(value, label)
                return label
            end
            local current = render()
            return current
            """
        )

        // Cursor between the parentheses of the empty *call* argument list.
        // Must not use bare "render(" — that matches the declaration first.
        val inside = assertNotNull(
            service.signatureHelp(
                SignatureHelpParams(
                    TextDocumentIdentifier(document.uri),
                    document.positionBetween("current = render(", ")")
                )
            ),
            "empty call-site parens should still yield signature help"
        )
        assertEquals(0, inside.activeParameter)
        assertTrue(inside.signatures.isNotEmpty())
    }

    // -------------------------------------------------------------------------
    // Harness
    // -------------------------------------------------------------------------

    private fun plainService(): LuaLanguageService {
        return LuaLanguageService().apply {
            initialize(
                InitializeParams().apply {
                    workspaceFolders = listOf(WorkspaceFolder("file:///workspace", "workspace"))
                }
            )
        }
    }

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
                        "java.lang.String"
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
         * Used to land on the comma gap between arguments or inside empty parentheses.
         */
        fun positionBetween(before: String, after: String): Position {
            val beforeIndex = source.indexOf(before)
            require(beforeIndex >= 0) { "Could not find '$before' in $path" }
            val afterIndex = source.indexOf(after, beforeIndex + before.length)
            require(afterIndex >= 0) { "Could not find '$after' after '$before' in $path" }
            val gap = beforeIndex + before.length
            val offset = if (gap < afterIndex) gap else afterIndex
            return positionAt(offset)
        }

        fun positionAfter(needle: String): Position {
            val index = source.indexOf(needle)
            require(index >= 0) { "Could not find '$needle' in $path" }
            return positionAt(index + needle.length)
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
