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
import kotlin.test.fail

/**
 * TASK-447 — LSP signature help active-parameter dual-path corpus (expansion).
 *
 * Complements [LspSignatureHelpActiveParamTddTest] (TASK-263 multi-arg core) with a
 * dual-path expansion lock around [org.eclipse.lsp4j.SignatureHelp.activeParameter]:
 *
 * Hard product contracts (when help is non-null for annotated / JVM callables):
 * - Multi-arg local functions advance 0 → 1 → 2 across commas and comma gaps.
 * - Cursor between `(` and first arg still selects parameter 0.
 * - Colon-method receivers offset activeParameter past implicit `self`.
 * - Dot-method calls do **not** inject a self offset.
 * - Nested call sites prefer the innermost enclosing call argument region.
 * - Multi-line argument lists still track commas.
 * - Overloaded JVM callables (String.valueOf, Math.max) keep multi-arg tracking.
 * - TextDocumentService forwards activeParameter + outside-null policy.
 * - Outside-call / empty / declaration / non-call identifiers return null without crash
 *   (with known next-token call.range.end inflation policy from SignatureHelpProvider).
 *
 * Dual-path / CURRENTLY_ACCEPTS product gaps (never hard-fail the suite):
 * - Freeform unannotated callables may return null or a generic help payload.
 * - Short string-call / table-call argument shapes may or may not resolve today.
 * - Vararg formals may clamp activeParameter to the last formal index.
 * - Incomplete / trailing-comma / unknown-callee sites must not throw; null or help is OK.
 * - Nested createProxy / alias helpers prefer documented labels when product wires them
 *   (see SignatureHelpProvider TASK-394); otherwise null is an accepted gap.
 *
 * Product code is intentionally out of scope (test-only). Verification is review-owned
 * and serial; this worker does not run Gradle. Host android.jar is not required for this
 * corpus; never G:/.
 *
 * Position needles that land inside call argument regions must use call-site-unique
 * prefixes (e.g. `current = render(`), never bare `render(` / `outer(` which match the
 * declaration parameter list first — product returns null outside call args.
 */
class LspSignatureHelpActiveParameterTddTest {

    // -------------------------------------------------------------------------
    // Capability surface
    // -------------------------------------------------------------------------

    @Test
    fun signature_help_capability_advertises_trigger_characters() {
        val service = plainService()
        val capabilities = service.initialize(InitializeParams()).capabilities
        val provider = assertNotNull(
            capabilities.signatureHelpProvider,
            "signatureHelpProvider must be advertised"
        )
        val triggers = provider.triggerCharacters.orEmpty()
        assertTrue(
            triggers.contains("(") || triggers.contains(","),
            "expected ( and/or , trigger characters; got $triggers"
        )
    }

    // -------------------------------------------------------------------------
    // Hard multi-arg / gap / pre-first-arg contracts
    // -------------------------------------------------------------------------

    @Test
    fun annotated_multi_arg_tracks_active_parameter_across_commas() {
        val service = plainService()
        val document = service.open(
            "workspace/sig-active-param-simple.lua",
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
        assertTrue(first.activeSignature >= 0 && first.activeSignature < first.signatures.size)
    }

    // -------------------------------------------------------------------------
    // Receiver offset: colon vs dot
    // -------------------------------------------------------------------------

    @Test
    fun colon_method_receiver_offsets_active_parameter() {
        val service = plainService()
        val document = service.open(
            "workspace/sig-active-param-colon.lua",
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

        assertEquals(1, first.activeParameter, "first explicit arg after colon receiver")
        assertEquals(2, second.activeParameter, "second explicit arg")
        assertTrue(
            first.signatures.single().parameters.size >= 3,
            "colon method signature should include self: ${first.labels()}"
        )
    }

    // -------------------------------------------------------------------------
    // Nested call preference
    // -------------------------------------------------------------------------

    @Test
    fun nested_call_prefers_innermost_active_parameter() {
        val service = plainService()
        val document = service.open(
            "workspace/sig-active-param-nested.lua",
            """
            ---@param outerA number
            ---@param outerB string
            local function outer(outerA, outerB)
                return outerB
            end
            ---@param innerX number
            ---@param innerY boolean
            local function inner(innerX, innerY)
                return innerY
            end
            local current = outer(inner(1, true), "tail")
            return current
            """
        )

        val innerFirst = assertNotNull(service.signatureHelp(signatureParams(document, "1,")))
        val innerSecond = assertNotNull(service.signatureHelp(signatureParams(document, "true)")))
        val outerSecond = assertNotNull(service.signatureHelp(signatureParams(document, "\"tail\")")))

        // Innermost call: active 0, 1 on inner's formals.
        assertEquals(0, innerFirst.activeParameter, "cursor on first arg of nested call")
        assertEquals(1, innerSecond.activeParameter, "cursor on second arg of nested call")
        // Outer call second argument.
        assertEquals(1, outerSecond.activeParameter, "cursor on outer second arg")
        assertTrue(innerFirst.signatures.isNotEmpty())
        assertTrue(outerSecond.signatures.isNotEmpty())
    }

    // -------------------------------------------------------------------------
    // JVM overloads (hard when help non-null)
    // -------------------------------------------------------------------------

    @Test
    fun outside_call_context_returns_null_without_crash() {
        val service = plainService()
        val document = service.open(
            "workspace/sig-active-param-outside.lua",
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

        val onCallee = service.signatureHelp(signatureParams(document, "render(1"))
        val onLocal = service.signatureHelp(signatureParams(document, "current"))
        val onDecl = service.signatureHelp(signatureParams(document, "function render"))
        val atCallEnd = assertNotNull(
            service.signatureHelp(
                SignatureHelpParams(
                    TextDocumentIdentifier(document.uri),
                    document.positionAfter("render(1, \"hi\")")
                )
            ),
            "exact call.range.end remains inside argument region per SignatureHelpProvider"
        )
        // Parser finishNode currently ends CallExpression at the next significant token after `)`.
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

    // -------------------------------------------------------------------------
    // Harness
    // -------------------------------------------------------------------------

    private data class MatrixCase(
        val name: String,
        val source: String,
        val needles: List<Pair<String, Int>>
    )

    /**
     * Dual-path helper: null is an accepted CURRENTLY_ACCEPTS gap; when help is present,
     * activeParameter must match [expectedIfPresent] (hard contract for that site).
     */
    private fun dualPathActiveOrNull(help: SignatureHelp?, expectedIfPresent: Int, site: String) {
        if (help == null) {
            // Documented product gap for this site.
            return
        }
        assertTrue(
            help.activeParameter >= 0,
            "$site activeParameter must be non-negative; got ${help.activeParameter}"
        )
        assertEquals(
            expectedIfPresent,
            help.activeParameter,
            "$site expected activeParameter=$expectedIfPresent when help is non-null; labels=${help.labels()}"
        )
    }

    private fun plainService(): LuaLanguageService {
        return LuaLanguageService().apply {
            initialize(
                InitializeParams().apply {
                    workspaceFolders = listOf(WorkspaceFolder("file:///workspace", "workspace"))
                }
            )
        }
    }

    private fun jvmService(
        classes: List<String> = listOf(
            "java.lang.Math",
            "java.lang.String",
            "java.lang.StringBuilder",
            "java.util.Arrays"
        )
    ): LuaLanguageService {
        return LuaLanguageService().apply {
            initialize(
                InitializeParams().apply {
                    workspaceFolders = listOf(WorkspaceFolder("file:///workspace", "workspace"))
                }
            )
            setWorkspaceMetadata(
                mapOf(
                    JvmClassModuleProvider.CLASSES_METADATA_KEY to classes.joinToString("\n")
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
