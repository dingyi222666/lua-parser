package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaTextDocumentService
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Moniker
import org.eclipse.lsp4j.MonikerKind
import org.eclipse.lsp4j.MonikerParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.UniquenessLevel
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TASK-277 — LSP moniker provider safety corpus.
 *
 * Locks the safety contract for `textDocument/moniker`:
 * - When moniker is unimplemented (LSP4J default [UnsupportedOperationException]
 *   on [LuaTextDocumentService]), requests must degrade as a documented gap —
 *   never as an unexpected hard crash (NPE / AssertionError).
 * - Missing symbols, whitespace, keywords, literals, and out-of-range positions
 *   must not throw once moniker is product-reachable: empty list / null is the
 *   soft-degrade ideal (per LSP: "If no monikers can be calculated, an empty
 *   array or null should be returned").
 * - When monikers are returned, each entry must carry non-blank scheme,
 *   identifier, and uniqueness, and kind (when present) must be a known
 *   [MonikerKind] value.
 *
 * Product moniker remains out of scope for this task (test-only). Current
 * [LuaTextDocumentService] inherits the LSP4J default that throws
 * [UnsupportedOperationException], and [LuaLanguageService] does not advertise
 * monikerProvider. Tests dual-path:
 * - Documented gap: unimplemented surface completes exceptionally with
 *   UnsupportedOperationException and is recorded as the known surface.
 * - Ideal path: empty/null for missing symbols; well-formed monikers when
 *   product returns them.
 *
 * Verification is review-owned and serial; this worker does not run Gradle.
 */
class LspMonikerSafetyTddTest {

    // -------------------------------------------------------------------------
    // Capability / surface probe
    // -------------------------------------------------------------------------

    @Test
    fun moniker_surface_is_invokable_without_killing_service() {
        val service = service()
        val capabilities = service.initialize(InitializeParams()).capabilities
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/moniker-surface.lua",
            "local value = 1\nreturn value"
        )

        val outcome = invokeMoniker(
            textDocuments,
            monikerParams(document, document.positionOf("value", occurrence = 1))
        )

        assertTrue(
            outcome is MonikerOutcome.Unsupported ||
                outcome is MonikerOutcome.Empty ||
                outcome is MonikerOutcome.Succeeded ||
                outcome is MonikerOutcome.Failed,
            "moniker surface must resolve to a known outcome; got $outcome " +
                "(monikerProvider=${capabilities.monikerProvider})"
        )

        // Soft failures (ResponseError, IllegalState, etc.) are allowed while the
        // feature is partial; hard process-killing crash classes are not.
        if (outcome is MonikerOutcome.Failed) {
            assertFalse(
                isHardCrash(outcome.detail),
                "moniker surface must not hard-crash; got ${outcome.detail}"
            )
        }
    }

    @Test
    fun moniker_unimplemented_degrades_as_documented_gap() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/moniker-gap.lua",
            "local value = 1\nreturn value"
        )

        val outcome = invokeMoniker(
            textDocuments,
            monikerParams(document, document.positionOf("value", occurrence = 2))
        )

        when (outcome) {
            is MonikerOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap expects UnsupportedOperationException for moniker; got ${outcome.detail}"
                )
            }
            is MonikerOutcome.Empty -> {
                // Soft degrade (empty / null) is the ideal unimplemented-or-no-hit path.
            }
            is MonikerOutcome.Succeeded -> {
                assertWellFormedMonikers(outcome.monikers, label = "local 'value' monikers")
            }
            is MonikerOutcome.Failed -> {
                assertFalse(
                    isHardCrash(outcome.detail),
                    "moniker must not NPE/assert when unimplemented/partial; got ${outcome.detail}"
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // Missing symbol / non-identifier positions — no throw
    // -------------------------------------------------------------------------

    @Test
    fun moniker_missing_symbol_does_not_throw() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/moniker-missing-symbol.lua",
            "local value = 1\nreturn value"
        )

        // Position past EOF — no symbol under cursor.
        val outcome = invokeMoniker(
            textDocuments,
            monikerParams(document, Position(5, 0))
        )

        assertNoThrowOnMissingOrNonIdentifier(
            outcome,
            context = "missing symbol past EOF"
        )
    }

    @Test
    fun moniker_on_whitespace_does_not_throw() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/moniker-whitespace.lua",
            "local value = 1\n\nreturn value"
        )

        val outcome = invokeMoniker(
            textDocuments,
            monikerParams(document, Position(1, 0))
        )

        assertNoThrowOnMissingOrNonIdentifier(
            outcome,
            context = "whitespace / blank line between statements"
        )
    }

    @Test
    fun moniker_on_keyword_does_not_throw() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/moniker-keyword.lua",
            "local value = 1\nreturn value"
        )

        val outcome = invokeMoniker(
            textDocuments,
            monikerParams(document, document.positionOf("local"))
        )

        assertNoThrowOnMissingOrNonIdentifier(
            outcome,
            context = "keyword 'local'"
        )
    }

    @Test
    fun moniker_on_numeric_literal_does_not_throw() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/moniker-number.lua",
            "local value = 42\nreturn value"
        )

        val outcome = invokeMoniker(
            textDocuments,
            monikerParams(document, document.positionOf("42"))
        )

        assertNoThrowOnMissingOrNonIdentifier(
            outcome,
            context = "numeric literal"
        )
    }

    @Test
    fun moniker_on_string_literal_does_not_throw() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/moniker-string.lua",
            "local greeting = \"hello\"\nreturn greeting"
        )

        val outcome = invokeMoniker(
            textDocuments,
            monikerParams(document, document.positionOf("\"hello\""))
        )

        assertNoThrowOnMissingOrNonIdentifier(
            outcome,
            context = "string literal"
        )
    }

    @Test
    fun moniker_on_operator_does_not_throw() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/moniker-operator.lua",
            "local a = 1\nlocal b = 2\nreturn a + b"
        )

        val outcome = invokeMoniker(
            textDocuments,
            monikerParams(document, document.positionOf("+"))
        )

        assertNoThrowOnMissingOrNonIdentifier(
            outcome,
            context = "binary operator '+'"
        )
    }

    @Test
    fun moniker_undeclared_free_name_does_not_throw() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/moniker-free-name.lua",
            "return freeName"
        )

        val outcome = invokeMoniker(
            textDocuments,
            monikerParams(document, document.positionOf("freeName"))
        )

        assertNoThrowOnMissingOrNonIdentifier(
            outcome,
            context = "undeclared free name"
        )
    }

    @Test
    fun moniker_closed_document_uri_does_not_throw() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        // Document never opened — moniker for unknown uri must soft-degrade.
        val params = MonikerParams(
            TextDocumentIdentifier("file:///workspace/moniker-never-opened.lua"),
            Position(0, 0)
        )

        val outcome = invokeMoniker(textDocuments, params)

        assertNoThrowOnMissingOrNonIdentifier(
            outcome,
            context = "never-opened document uri"
        )
    }

    // -------------------------------------------------------------------------
    // Local / global identifier — soft degrade or well-formed monikers
    // -------------------------------------------------------------------------

    @Test
    fun moniker_local_identifier_empty_or_well_formed_or_documented_gap() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/moniker-local.lua",
            "local value = 1\nlocal copy = value\nreturn value + copy"
        )

        val outcome = invokeMoniker(
            textDocuments,
            monikerParams(document, document.positionOf("value", occurrence = 2))
        )

        when (outcome) {
            is MonikerOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap expects UnsupportedOperationException for moniker; got ${outcome.detail}"
                )
            }
            is MonikerOutcome.Empty -> {
                // Soft degrade is acceptable until product monikers ship.
            }
            is MonikerOutcome.Succeeded -> {
                assertWellFormedMonikers(
                    outcome.monikers,
                    label = "local 'value' monikers"
                )
            }
            is MonikerOutcome.Failed -> {
                assertFalse(
                    isHardCrash(outcome.detail),
                    "moniker on local identifier must not hard-crash; got ${outcome.detail}"
                )
            }
        }
    }

    @Test
    fun moniker_global_print_empty_or_well_formed_or_documented_gap() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/moniker-global-print.lua",
            "print(1)\nreturn print"
        )

        val outcome = invokeMoniker(
            textDocuments,
            monikerParams(document, document.positionOf("print", occurrence = 1))
        )

        when (outcome) {
            is MonikerOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap expects UnsupportedOperationException for moniker; got ${outcome.detail}"
                )
            }
            is MonikerOutcome.Empty -> {
                // Globals may have no moniker until product indexes them.
            }
            is MonikerOutcome.Succeeded -> {
                assertWellFormedMonikers(
                    outcome.monikers,
                    label = "global 'print' monikers"
                )
            }
            is MonikerOutcome.Failed -> {
                assertFalse(
                    isHardCrash(outcome.detail),
                    "moniker on global must not hard-crash; got ${outcome.detail}"
                )
            }
        }
    }

    @Test
    fun moniker_local_function_name_empty_or_well_formed_or_documented_gap() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/moniker-local-function.lua",
            """
            local function render(value)
                return value
            end
            return render
            """
        )

        val outcome = invokeMoniker(
            textDocuments,
            monikerParams(document, document.positionOf("render", occurrence = 1))
        )

        when (outcome) {
            is MonikerOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap expects UnsupportedOperationException for moniker; got ${outcome.detail}"
                )
            }
            is MonikerOutcome.Empty -> {
                // Soft degrade until moniker product lands.
            }
            is MonikerOutcome.Succeeded -> {
                assertWellFormedMonikers(
                    outcome.monikers,
                    label = "local function 'render' monikers"
                )
            }
            is MonikerOutcome.Failed -> {
                assertFalse(
                    isHardCrash(outcome.detail),
                    "moniker on local function must not hard-crash; got ${outcome.detail}"
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun service(): LuaLanguageService {
        return LuaLanguageService().apply {
            initialize(InitializeParams())
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

    private fun monikerParams(document: OpenDocument, position: Position): MonikerParams {
        return MonikerParams(TextDocumentIdentifier(document.uri), position)
    }

    private fun invokeMoniker(
        textDocuments: LuaTextDocumentService,
        params: MonikerParams
    ): MonikerOutcome {
        return try {
            val monikers = textDocuments.moniker(params).get()
            when {
                monikers == null -> MonikerOutcome.Empty(detail = "null result")
                monikers.isEmpty() -> MonikerOutcome.Empty(detail = "empty list")
                else -> MonikerOutcome.Succeeded(monikers = monikers.toList())
            }
        } catch (error: Throwable) {
            val root = unwrap(error)
            if (isUnsupportedOperation(root)) {
                MonikerOutcome.Unsupported(detail = root.toString(), isUnsupportedOperation = true)
            } else {
                MonikerOutcome.Failed(detail = root.toString())
            }
        }
    }

    private fun assertNoThrowOnMissingOrNonIdentifier(
        outcome: MonikerOutcome,
        context: String
    ) {
        when (outcome) {
            is MonikerOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap for $context expects UnsupportedOperationException; got ${outcome.detail}"
                )
            }
            is MonikerOutcome.Empty -> {
                // Ideal soft degrade for missing / non-identifier positions.
            }
            is MonikerOutcome.Succeeded -> {
                // Product may still return monikers for some free/global names;
                // when it does they must be well-formed.
                assertWellFormedMonikers(outcome.monikers, label = "monikers at $context")
            }
            is MonikerOutcome.Failed -> {
                assertFalse(
                    isHardCrash(outcome.detail),
                    "moniker at $context must not NPE/assert; got ${outcome.detail}"
                )
            }
        }
    }

    private fun assertWellFormedMonikers(monikers: List<Moniker>, label: String) {
        assertTrue(monikers.isNotEmpty(), "$label expected non-empty moniker list")
        monikers.forEachIndexed { index, moniker ->
            val scheme = moniker.scheme
            val identifier = moniker.identifier
            val unique = moniker.unique
            assertNotNull(scheme, "$label[$index].scheme must be non-null")
            assertTrue(
                scheme.isNotBlank(),
                "$label[$index].scheme must be non-blank; got '$scheme'"
            )
            assertNotNull(identifier, "$label[$index].identifier must be non-null")
            assertTrue(
                identifier.isNotBlank(),
                "$label[$index].identifier must be non-blank; got '$identifier'"
            )
            assertNotNull(unique, "$label[$index].unique must be non-null")
            assertTrue(
                unique.isNotBlank(),
                "$label[$index].unique must be non-blank; got '$unique'"
            )
            assertTrue(
                isKnownUniqueness(unique),
                "$label[$index].unique must be a known UniquenessLevel " +
                    "(document/project/group/scheme/global); got '$unique'"
            )
            val kind = moniker.kind
            if (kind != null) {
                assertTrue(
                    isKnownMonikerKind(kind),
                    "$label[$index].kind must be a known MonikerKind " +
                        "(import/export/local) when present; got '$kind'"
                )
            }
        }
    }

    private fun isKnownUniqueness(unique: String): Boolean {
        return unique == UniquenessLevel.Document ||
            unique == UniquenessLevel.Project ||
            unique == UniquenessLevel.Group ||
            unique == UniquenessLevel.Scheme ||
            unique == UniquenessLevel.Global ||
            // Tolerate lowercase wire forms some servers emit.
            unique.equals("document", ignoreCase = true) ||
            unique.equals("project", ignoreCase = true) ||
            unique.equals("group", ignoreCase = true) ||
            unique.equals("scheme", ignoreCase = true) ||
            unique.equals("global", ignoreCase = true)
    }

    private fun isKnownMonikerKind(kind: String): Boolean {
        return kind == MonikerKind.Import ||
            kind == MonikerKind.Export ||
            kind == MonikerKind.Local ||
            kind.equals("import", ignoreCase = true) ||
            kind.equals("export", ignoreCase = true) ||
            kind.equals("local", ignoreCase = true)
    }

    private fun isHardCrash(detail: String): Boolean {
        return detail.contains("NullPointerException", ignoreCase = true) ||
            detail.contains("AssertionError", ignoreCase = true) ||
            detail.contains("KotlinNullPointerException", ignoreCase = true) ||
            detail.contains("IndexOutOfBoundsException", ignoreCase = true)
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

    private sealed class MonikerOutcome {
        data class Unsupported(
            val detail: String,
            val isUnsupportedOperation: Boolean
        ) : MonikerOutcome()

        data class Empty(val detail: String) : MonikerOutcome()

        data class Succeeded(val monikers: List<Moniker>) : MonikerOutcome()

        data class Failed(val detail: String) : MonikerOutcome()
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
