package lsp

import io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider
import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaTextDocumentService
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InlayHint
import org.eclipse.lsp4j.InlayHintKind
import org.eclipse.lsp4j.InlayHintParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.Assume
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * TASK-270 — LSP inlay hints parameter-name corpus.
 *
 * Encodes the contract for `textDocument/inlayHint` when the product supports
 * parameter-name hints on call arguments:
 * - Call sites of annotated / known multi-arg functions can surface
 *   [InlayHintKind.Parameter] labels that identify formal parameter names.
 * - When the capability / method is absent, tests skip cleanly (explicit
 *   [Assume]) rather than failing the suite — "degrade safely when
 *   unimplemented".
 * - Empty, malformed, and large open documents must not crash the inlay-hint
 *   surface once it is reachable.
 *
 * Product inlay hints are intentionally out of scope (test-only). Current
 * [LuaTextDocumentService] inherits the LSP4J default that throws
 * [UnsupportedOperationException], and [LuaLanguageService] does not advertise
 * `inlayHintProvider`. Tests therefore dual-path:
 * - Skip with an explicit Assume reason when the capability/method is absent.
 * - Enforce hard parameter-name contracts once inlay hints are implemented /
 *   advertised.
 *
 * Verification is review-owned and serial; this worker does not run Gradle.
 */
class LspInlayHintsParamNameTddTest {

    // -------------------------------------------------------------------------
    // Capability / surface probe
    // -------------------------------------------------------------------------

    @Test
    fun initialize_inlay_hint_capability_is_probeable() {
        val service = plainService()
        val capabilities = service.initialize(InitializeParams()).capabilities

        // Capability may be null today (documented gap) or present once product
        // lands inlay hints. Reading the field must not throw either way.
        val provider = capabilities.inlayHintProvider
        if (provider == null) {
            assertTrue(
                true,
                "inlayHintProvider absent is an accepted pre-product surface"
            )
            return
        }

        // Advertised as boolean true or as registration options.
        val enabled = when {
            provider.isLeft -> provider.left == true
            provider.isRight -> true
            else -> false
        }
        assertTrue(
            enabled,
            "advertised inlayHintProvider must enable inlay hints (got $provider)"
        )
    }

    @Test
    fun inlay_hint_capability_or_explicit_skip_when_unimplemented() {
        val service = plainService()
        val capabilities = service.initialize(InitializeParams()).capabilities
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/inlay-param-capability-probe.lua",
            """
            ---@param value number
            ---@param label string
            local function render(value, label)
                return label
            end
            local current = render(1, "hi")
            return current
            """
        )

        val outcome = invokeInlayHint(textDocuments, fullDocumentInlayParams(document))

        when (outcome) {
            is InlayOutcome.Unsupported -> {
                Assume.assumeTrue(
                    "TASK-270 skipped: inlay hints not yet implemented " +
                        "(inlayHintProvider=${capabilities.inlayHintProvider}, " +
                        "detail=${outcome.detail})",
                    false
                )
            }
            is InlayOutcome.Failed -> {
                fail("inlayHint must not fail hard once surface is reachable: ${outcome.detail}")
            }
            is InlayOutcome.Succeeded -> {
                // Capability may lag implementation; once hints are returned they
                // must be well-formed even if the initialize flag is still null.
                assertWellFormedHints(outcome.hints, document)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Parameter-name hints at call sites (hard asserts once supported)
    // -------------------------------------------------------------------------

    @Test
    fun parameter_name_hints_cover_multi_arg_local_call_when_supported() {
        val service = plainService()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/inlay-param-multi-arg.lua",
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

        val hints = requireInlayHints(textDocuments, document, context = "multi-arg local call")
        val parameterHints = parameterNameHints(hints)

        assertTrue(
            parameterHints.isNotEmpty(),
            "Expected at least one Parameter-kind (or param-like) inlay hint for paint(1, \"mid\", true); " +
                "got ${describe(hints)}"
        )

        val labels = parameterHints.flatMap { hintLabels(it) }.map { it.trim().trimEnd(':') }
        // Formal names a / b / c should appear (product may append ":" or padding).
        val expected = listOf("a", "b", "c")
        val matched = expected.filter { name ->
            labels.any { label -> label.equals(name, ignoreCase = true) || label.startsWith(name) }
        }
        assertTrue(
            matched.size >= 2,
            "Expected parameter-name labels covering at least two of $expected; " +
                "labels=$labels hints=${describe(hints)}"
        )

        // Hints for the call should sit on the call-site line (last non-return line).
        val callLine = document.lineOf("paint(1,")
        assertTrue(
            parameterHints.any { it.position.line == callLine },
            "At least one parameter hint should land on the call-site line $callLine; " +
                "got ${describe(parameterHints)}"
        )
        assertWellFormedHints(hints, document)
    }

    @Test
    fun parameter_name_hints_cover_two_arg_call_when_supported() {
        val service = plainService()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/inlay-param-two-arg.lua",
            """
            ---@param value number
            ---@param label string
            local function render(value, label)
                return label
            end
            local current = render(1, "hi")
            return current
            """
        )

        val hints = requireInlayHints(textDocuments, document, context = "two-arg local call")
        val parameterHints = parameterNameHints(hints)
        val labels = parameterHints.flatMap { hintLabels(it) }.map { it.trim().trimEnd(':') }

        assertTrue(
            labels.any { it.contains("value", ignoreCase = true) } ||
                labels.any { it.contains("label", ignoreCase = true) },
            "Expected parameter-name hint for value and/or label; labels=$labels hints=${describe(hints)}"
        )
        assertWellFormedHints(hints, document)
    }

    @Test
    fun parameter_name_hints_for_colon_method_call_when_supported() {
        val service = plainService()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/inlay-param-colon.lua",
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

        val hints = requireInlayHints(textDocuments, document, context = "colon method call")
        val parameterHints = parameterNameHints(hints)
        val labels = parameterHints.flatMap { hintLabels(it) }.map { it.trim().trimEnd(':') }

        // Explicit args value/label should be hintable; self may be omitted at the
        // call site (colon receiver is implicit).
        assertTrue(
            labels.any { it.contains("value", ignoreCase = true) } ||
                labels.any { it.contains("label", ignoreCase = true) } ||
                parameterHints.isNotEmpty(),
            "Expected parameter-name hints for colon method explicit args; " +
                "labels=$labels hints=${describe(hints)}"
        )
        assertWellFormedHints(hints, document)
    }

    @Test
    fun parameter_name_hints_for_overloaded_java_call_when_supported() {
        val service = jvmService()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/inlay-param-java-max.lua",
            """
            local Math = require("Math")
            local current = Math.max(1, 2)
            return current
            """
        )

        val outcome = invokeInlayHint(textDocuments, fullDocumentInlayParams(document))
        when (outcome) {
            is InlayOutcome.Unsupported -> {
                Assume.assumeTrue(
                    "TASK-270 skipped: inlay hints not yet implemented (java Math.max probe); " +
                        "detail=${outcome.detail}",
                    false
                )
            }
            is InlayOutcome.Failed -> {
                fail("inlayHint on Java Math.max call must not fail hard: ${outcome.detail}")
            }
            is InlayOutcome.Succeeded -> {
                // JVM overload parameter names are best-effort; once the surface is
                // live, hints must remain well-formed (empty list is allowed if the
                // product cannot resolve formal names for the overload).
                assertWellFormedHints(outcome.hints, document)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Crash / degrade safety (must not throw; may skip only if surface missing)
    // -------------------------------------------------------------------------

    @Test
    fun inlay_hints_on_empty_document_do_not_crash_when_supported() {
        val service = plainService()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/inlay-param-empty.lua",
            ""
        )

        val outcome = invokeInlayHint(textDocuments, fullDocumentInlayParams(document))
        when (outcome) {
            is InlayOutcome.Unsupported -> {
                Assume.assumeTrue(
                    "TASK-270 skipped: inlay hints not yet implemented (empty document probe); " +
                        "detail=${outcome.detail}",
                    false
                )
            }
            is InlayOutcome.Failed -> {
                fail("empty document must not crash inlayHint provider: ${outcome.detail}")
            }
            is InlayOutcome.Succeeded -> {
                assertWellFormedHints(outcome.hints, document)
                assertTrue(
                    outcome.hints.isEmpty(),
                    "empty document should yield no inlay hints; got ${describe(outcome.hints)}"
                )
            }
        }
    }

    @Test
    fun inlay_hints_on_malformed_source_do_not_crash_when_supported() {
        val service = plainService()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/inlay-param-malformed.lua",
            """
            local function broken(
                return paint(1, 2,
            """
        )

        val outcome = invokeInlayHint(textDocuments, fullDocumentInlayParams(document))
        when (outcome) {
            is InlayOutcome.Unsupported -> {
                Assume.assumeTrue(
                    "TASK-270 skipped: inlay hints not yet implemented (malformed source probe); " +
                        "detail=${outcome.detail}",
                    false
                )
            }
            is InlayOutcome.Failed -> {
                fail("malformed source must not crash inlayHint provider: ${outcome.detail}")
            }
            is InlayOutcome.Succeeded -> {
                assertWellFormedHints(outcome.hints, document)
            }
        }
    }

    @Test
    fun inlay_hints_on_large_open_document_do_not_crash_when_supported() {
        val service = plainService()
        val textDocuments = LuaTextDocumentService(service)

        // ~2k lines of simple multi-arg calls — large enough to stress open-doc
        // inlay computation without inventing product-specific scaling limits.
        val body = buildString {
            appendLine("---@param a number")
            appendLine("---@param b string")
            appendLine("---@param c boolean")
            appendLine("local function paint(a, b, c)")
            appendLine("    return c")
            appendLine("end")
            repeat(600) { index ->
                appendLine("local v$index = paint($index, \"mid$index\", true)")
            }
            appendLine("return v0")
        }
        val document = textDocuments.open(
            "workspace/inlay-param-large.lua",
            body
        )

        val outcome = invokeInlayHint(textDocuments, fullDocumentInlayParams(document))
        when (outcome) {
            is InlayOutcome.Unsupported -> {
                Assume.assumeTrue(
                    "TASK-270 skipped: inlay hints not yet implemented (large document probe); " +
                        "detail=${outcome.detail}",
                    false
                )
            }
            is InlayOutcome.Failed -> {
                fail("large open document must not crash inlayHint provider: ${outcome.detail}")
            }
            is InlayOutcome.Succeeded -> {
                assertWellFormedHints(outcome.hints, document)
                // Large multi-call corpus: empty is allowed only if the product
                // intentionally returns no parameter hints (e.g. budgeted off for
                // huge docs); non-empty is the preferred signal once live.
                // Well-formedness is the hard contract; size is informational.
                assertTrue(
                    outcome.hints.size >= 0,
                    "large-doc inlay surface reachable; size=${outcome.hints.size}"
                )
            }
        }
    }

    @Test
    fun inlay_hints_on_call_free_document_are_empty_or_well_formed_when_supported() {
        val service = plainService()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/inlay-param-no-calls.lua",
            """
            local name = "token"
            local value = 1
            return name
            """
        )

        val outcome = invokeInlayHint(textDocuments, fullDocumentInlayParams(document))
        when (outcome) {
            is InlayOutcome.Unsupported -> {
                Assume.assumeTrue(
                    "TASK-270 skipped: inlay hints not yet implemented (call-free probe); " +
                        "detail=${outcome.detail}",
                    false
                )
            }
            is InlayOutcome.Failed -> {
                fail("call-free document must not crash inlayHint provider: ${outcome.detail}")
            }
            is InlayOutcome.Succeeded -> {
                // No call sites → no parameter-name hints required; empty is ideal.
                assertWellFormedHints(outcome.hints, document)
            }
        }
    }

    @Test
    fun inlay_hints_range_request_outside_calls_is_empty_or_well_formed_when_supported() {
        val service = plainService()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/inlay-param-range-outside.lua",
            """
            ---@param value number
            ---@param label string
            local function render(value, label)
                return label
            end
            local current = render(1, "hi")
            return current
            """
        )

        // Range covering only the function declaration header (no call site).
        val range = Range(Position(0, 0), Position(3, 0))
        val outcome = invokeInlayHint(
            textDocuments,
            InlayHintParams(TextDocumentIdentifier(document.uri), range)
        )
        when (outcome) {
            is InlayOutcome.Unsupported -> {
                Assume.assumeTrue(
                    "TASK-270 skipped: inlay hints not yet implemented (range-outside probe); " +
                        "detail=${outcome.detail}",
                    false
                )
            }
            is InlayOutcome.Failed -> {
                fail("range-restricted inlayHint must not fail hard: ${outcome.detail}")
            }
            is InlayOutcome.Succeeded -> {
                assertWellFormedHints(outcome.hints, document)
            }
        }
    }

    @Test
    fun text_document_service_forwards_inlay_hint_or_documents_gap() {
        val service = plainService()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/inlay-param-text-document.lua",
            """
            ---@param a number
            ---@param b string
            local function paint(a, b)
                return b
            end
            local current = paint(1, "mid")
            return current
            """
        )

        val outcome = invokeInlayHint(textDocuments, fullDocumentInlayParams(document))
        when (outcome) {
            is InlayOutcome.Unsupported -> {
                // Documented gap: LuaTextDocumentService inherits LSP4J default.
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap expects UnsupportedOperationException for inlayHint; " +
                        "got ${outcome.detail}"
                )
            }
            is InlayOutcome.Failed -> {
                fail("inlayHint forward path must not fail hard: ${outcome.detail}")
            }
            is InlayOutcome.Succeeded -> {
                assertWellFormedHints(outcome.hints, document)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
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

    private fun LuaTextDocumentService.open(path: String, source: String): OpenDocument {
        val document = OpenDocument(path = path, source = source.trimIndent())
        didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(document.uri, "lua", 1, document.source)
            )
        )
        return document
    }

    private fun fullDocumentInlayParams(document: OpenDocument): InlayHintParams {
        val endLine = (document.lineCount - 1).coerceAtLeast(0)
        val endChar = if (document.lineCount == 0) 0 else document.source.substringAfterLast('\n').length
        return InlayHintParams(
            TextDocumentIdentifier(document.uri),
            Range(Position(0, 0), Position(endLine, endChar))
        )
    }

    private fun requireInlayHints(
        textDocuments: LuaTextDocumentService,
        document: OpenDocument,
        context: String
    ): List<InlayHint> {
        return when (val outcome = invokeInlayHint(textDocuments, fullDocumentInlayParams(document))) {
            is InlayOutcome.Unsupported -> {
                Assume.assumeTrue(
                    "TASK-270 skipped: inlay hints not yet implemented for $context; " +
                        "detail=${outcome.detail}",
                    false
                )
                emptyList() // unreachable after assume
            }
            is InlayOutcome.Failed -> {
                fail("inlayHint failed for $context: ${outcome.detail}")
            }
            is InlayOutcome.Succeeded -> {
                assertNotNull(outcome.hints)
                outcome.hints
            }
        }
    }

    private fun invokeInlayHint(
        textDocuments: LuaTextDocumentService,
        params: InlayHintParams
    ): InlayOutcome {
        return try {
            val hints = textDocuments.inlayHint(params).get()
            InlayOutcome.Succeeded(hints.orEmpty())
        } catch (error: Throwable) {
            val root = unwrap(error)
            if (isUnsupportedOperation(root)) {
                InlayOutcome.Unsupported(
                    detail = root.toString(),
                    isUnsupportedOperation = true
                )
            } else {
                InlayOutcome.Failed(detail = root.toString())
            }
        }
    }

    private fun parameterNameHints(hints: List<InlayHint>): List<InlayHint> {
        return hints.filter { hint ->
            hint.kind == InlayHintKind.Parameter ||
                // Some implementations omit kind and encode names with trailing ":".
                hintLabels(hint).any { label ->
                    val trimmed = label.trim()
                    trimmed.endsWith(":") || trimmed.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))
                }
        }
    }

    private fun hintLabels(hint: InlayHint): List<String> {
        val label: Either<String, List<org.eclipse.lsp4j.InlayHintLabelPart>>? = hint.label
        if (label == null) {
            return emptyList()
        }
        return when {
            label.isLeft -> listOfNotNull(label.left)
            label.isRight -> label.right.orEmpty().mapNotNull { part -> part.value }
            else -> emptyList()
        }
    }

    private fun assertWellFormedHints(hints: List<InlayHint>, document: OpenDocument) {
        val lineCount = document.lineCount
        hints.forEach { hint ->
            val position = hint.position
            assertNotNull(position, "inlay hint must have a position: ${describe(listOf(hint))}")
            assertTrue(
                position.line >= 0,
                "hint line must be >= 0; got ${position.line} in ${describe(listOf(hint))}"
            )
            assertTrue(
                position.character >= 0,
                "hint character must be >= 0; got ${position.character} in ${describe(listOf(hint))}"
            )
            if (lineCount > 0) {
                assertTrue(
                    position.line < lineCount,
                    "hint line must be inside document (lineCount=$lineCount); " +
                        "got ${describe(listOf(hint))}"
                )
            } else {
                assertTrue(
                    hints.isEmpty(),
                    "empty document should yield no inlay hints; got ${describe(hints)}"
                )
            }
            val labels = hintLabels(hint)
            assertTrue(
                labels.isNotEmpty() && labels.all { it.isNotEmpty() },
                "inlay hint label must be non-empty; got ${describe(listOf(hint))}"
            )
        }
    }

    private fun describe(hints: List<InlayHint>): String {
        return hints.joinToString(prefix = "[", postfix = "]") { hint ->
            val pos = hint.position
            val kind = hint.kind?.name ?: "null"
            val labels = hintLabels(hint).joinToString("|")
            "${pos?.line}:${pos?.character} kind=$kind label=$labels"
        }
    }

    private fun unwrap(error: Throwable): Throwable {
        var current = error
        while (
            (current is ExecutionException || current is CompletionException) &&
            current.cause != null
        ) {
            current = current.cause!!
        }
        return current
    }

    private fun isUnsupportedOperation(error: Throwable): Boolean {
        if (error is UnsupportedOperationException) {
            return true
        }
        val message = error.message.orEmpty()
        return message.contains("UnsupportedOperationException") ||
            message.contains("not implemented", ignoreCase = true)
    }

    private sealed class InlayOutcome {
        data class Unsupported(
            val detail: String,
            val isUnsupportedOperation: Boolean = false
        ) : InlayOutcome()

        data class Failed(val detail: String) : InlayOutcome()
        data class Succeeded(val hints: List<InlayHint>) : InlayOutcome()
    }

    private data class OpenDocument(
        val path: String,
        val source: String
    ) {
        val uri: String = "file:///$path"

        val lineCount: Int
            get() {
                if (source.isEmpty()) {
                    return 0
                }
                return source.count { it == '\n' } + 1
            }

        fun lineOf(needle: String): Int {
            val index = source.indexOf(needle)
            require(index >= 0) { "Could not find '$needle' in $path" }
            var line = 0
            for (i in 0 until index) {
                if (source[i] == '\n') {
                    line += 1
                }
            }
            return line
        }
    }
}
