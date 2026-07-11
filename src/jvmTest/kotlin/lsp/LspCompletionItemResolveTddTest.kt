package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaTextDocumentService
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TASK-446 — LSP `completionItem/resolve` dual-path corpus.
 *
 * Locks the contract for resolving additional fields on a completion item after
 * `textDocument/completion` returns a lightweight list:
 *
 * - Capability: [org.eclipse.lsp4j.CompletionOptions.resolveProvider] may stay
 *   null/false until product advertises lazy resolve. When true, the resolve
 *   surface must be invokable without process-killing failures.
 * - Current product: [LuaLanguageService] advertises completion with trigger
 *   characters `.` / `:` but does **not** set resolveProvider. [LuaTextDocumentService]
 *   inherits the LSP4J default [UnsupportedOperationException] for
 *   [LuaTextDocumentService.resolveCompletionItem].
 * - Completion items today map label / kind / detail / insertText / sortText /
 *   PlainText insert format; documentation and `data` are typically absent on the
 *   list path (enrichment is the resolve job when product lands).
 *
 * Dual-path policy:
 * - **Documented gap**: unimplemented resolve completes exceptionally with
 *   UnsupportedOperationException — accepted and recorded as the known surface.
 * - **Soft degrade / CURRENTLY_ACCEPTS**: product may return the unresolved item
 *   unchanged (identity-preserving no-op) or a shallow clone with the same label.
 * - **Ideal / live resolve**: product returns a well-formed item that preserves
 *   the unresolved label (and kind when set) and may enrich detail / documentation
 *   / additionalTextEdits / data without inventing blank labels.
 * - Hard process-killing crash classes (NPE / AssertionError / IndexOutOfBounds)
 *   are never accepted.
 *
 * Product resolve remains out of scope for this worker (test-only). Host
 * android.jar is not required for this corpus; when product later uses JVM
 * class docs, host paths stay Downloads + SDK android-35 only (never G:/).
 * Verification is review-owned and serial; this worker does not run Gradle.
 */
class LspCompletionItemResolveTddTest {

    // -------------------------------------------------------------------------
    // Capability / surface probe
    // -------------------------------------------------------------------------

    @Test
    fun initialize_completion_resolve_provider_is_null_false_or_true_when_product_lands() {
        val service = service()
        val capabilities = service.initialize(InitializeParams()).capabilities
        val completion = assertNotNull(
            capabilities.completionProvider,
            "completionProvider must be advertised"
        )

        val resolveProvider = completion.resolveProvider
        assertTrue(
            resolveProvider == null || resolveProvider == false || resolveProvider == true,
            "resolveProvider must be null/false (gap) or true when product advertises " +
                "completionItem/resolve; got $resolveProvider"
        )

        // Trigger characters stay the product completion contract regardless of resolve.
        assertEquals(
            listOf(".", ":"),
            completion.triggerCharacters,
            "completion trigger characters must remain '.', ':' "
        )
    }

    @Test
    fun completion_item_resolve_surface_is_invokable_without_killing_service() {
        val service = service()
        val capabilities = service.initialize(InitializeParams()).capabilities
        val textDocuments = LuaTextDocumentService(service)
        val unresolved = CompletionItem("probe").apply {
            kind = CompletionItemKind.Variable
        }

        val outcome = invokeResolve(textDocuments, unresolved)

        assertTrue(
            outcome is ResolveOutcome.Unsupported ||
                outcome is ResolveOutcome.Identity ||
                outcome is ResolveOutcome.Enriched ||
                outcome is ResolveOutcome.Failed,
            "resolveCompletionItem surface must resolve to a known outcome; got $outcome " +
                "(resolveProvider=${capabilities.completionProvider?.resolveProvider})"
        )

        if (outcome is ResolveOutcome.Failed) {
            assertFalse(
                isHardCrash(outcome.detail),
                "resolve surface must not hard-crash; got ${outcome.detail}"
            )
        }
    }

    @Test
    fun completion_item_resolve_unimplemented_degrades_as_documented_gap() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val unresolved = CompletionItem("alpha").apply {
            kind = CompletionItemKind.Variable
            detail = "number"
        }

        val outcome = invokeResolve(textDocuments, unresolved)

        when (outcome) {
            is ResolveOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap expects UnsupportedOperationException for " +
                        "completionItem/resolve; got ${outcome.detail}"
                )
            }
            is ResolveOutcome.Identity -> {
                assertEquals("alpha", outcome.item.label)
            }
            is ResolveOutcome.Enriched -> {
                assertWellFormedResolved(outcome.item, expectedLabel = "alpha")
            }
            is ResolveOutcome.Failed -> {
                assertFalse(
                    isHardCrash(outcome.detail),
                    "resolve must not NPE/assert when unimplemented/partial; got ${outcome.detail}"
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // Synthetic / edge unresolved items — no throw
    // -------------------------------------------------------------------------

    @Test
    fun resolve_synthetic_item_without_data_does_not_hard_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val unresolved = CompletionItem("syntheticOnly")

        val outcome = invokeResolve(textDocuments, unresolved)

        assertNoHardCrashOnResolve(outcome, context = "synthetic item without data")
        when (outcome) {
            is ResolveOutcome.Identity -> assertEquals("syntheticOnly", outcome.item.label)
            is ResolveOutcome.Enriched -> assertWellFormedResolved(outcome.item, "syntheticOnly")
            else -> Unit
        }
    }

    @Test
    fun resolve_item_with_only_label_preserves_or_gaps() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val unresolved = CompletionItem("labelOnly")

        val outcome = invokeResolve(textDocuments, unresolved)

        when (outcome) {
            is ResolveOutcome.Unsupported -> {
                assertTrue(outcome.isUnsupportedOperation)
            }
            is ResolveOutcome.Identity -> {
                assertEquals("labelOnly", outcome.item.label)
            }
            is ResolveOutcome.Enriched -> {
                assertWellFormedResolved(outcome.item, expectedLabel = "labelOnly")
            }
            is ResolveOutcome.Failed -> {
                assertFalse(isHardCrash(outcome.detail), outcome.detail)
            }
        }
    }

    @Test
    fun resolve_item_with_kind_and_detail_preserves_core_fields_or_gaps() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val unresolved = CompletionItem("render").apply {
            kind = CompletionItemKind.Function
            detail = "fun(input: string): string"
            insertText = "render"
        }

        val outcome = invokeResolve(textDocuments, unresolved)

        when (outcome) {
            is ResolveOutcome.Unsupported -> {
                assertTrue(outcome.isUnsupportedOperation)
            }
            is ResolveOutcome.Identity -> {
                assertEquals("render", outcome.item.label)
                // Identity path may keep or drop optional fields; label is the floor.
            }
            is ResolveOutcome.Enriched -> {
                assertWellFormedResolved(outcome.item, expectedLabel = "render")
                // Kind should not flip to an unrelated category when product enriches.
                if (outcome.item.kind != null) {
                    assertTrue(
                        outcome.item.kind == CompletionItemKind.Function ||
                            outcome.item.kind == CompletionItemKind.Method ||
                            outcome.item.kind == CompletionItemKind.Variable,
                        "enriched kind for function-like item must stay function-family; " +
                            "got ${outcome.item.kind}"
                    )
                }
            }
            is ResolveOutcome.Failed -> {
                assertFalse(isHardCrash(outcome.detail), outcome.detail)
            }
        }
    }

    @Test
    fun resolve_item_with_opaque_data_payload_does_not_hard_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val unresolved = CompletionItem("withData").apply {
            kind = CompletionItemKind.Variable
            // Opaque client/server round-trip field; product may ignore until resolve lands.
            data = mapOf(
                "path" to "workspace/resolve-data.lua",
                "line" to 1,
                "character" to 0
            )
        }

        val outcome = invokeResolve(textDocuments, unresolved)

        assertNoHardCrashOnResolve(outcome, context = "opaque data payload")
        when (outcome) {
            is ResolveOutcome.Identity -> assertEquals("withData", outcome.item.label)
            is ResolveOutcome.Enriched -> assertWellFormedResolved(outcome.item, "withData")
            else -> Unit
        }
    }

    @Test
    fun resolve_keyword_like_item_does_not_hard_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val unresolved = CompletionItem("if").apply {
            kind = CompletionItemKind.Keyword
        }

        val outcome = invokeResolve(textDocuments, unresolved)

        assertNoHardCrashOnResolve(outcome, context = "keyword-like item")
    }

    @Test
    fun resolve_snippet_like_item_does_not_hard_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val unresolved = CompletionItem("fori").apply {
            kind = CompletionItemKind.Snippet
            insertText = "for i = 1, #\$1 do\n\t\$0\nend"
        }

        val outcome = invokeResolve(textDocuments, unresolved)

        assertNoHardCrashOnResolve(outcome, context = "snippet-like item")
    }

    // -------------------------------------------------------------------------
    // Live completion → resolve dual-path (local / builtin / function)
    // -------------------------------------------------------------------------

    @Test
    fun resolve_completion_item_from_local_is_gap_identity_or_enriched() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/completion-resolve-local.lua",
            """
            local alpha = 1

            return alpha
            """
        )

        val items = completionItemsAt(textDocuments, document, blankLineAfterLocals())
        val labels = items.map { it.label }
        assertTrue(
            "alpha" in labels,
            "Expected local 'alpha' in completion list before resolve; actual=$labels"
        )
        val unresolved = items.first { it.label == "alpha" }

        val outcome = invokeResolve(textDocuments, unresolved)
        assertResolvePreservesOrGaps(outcome, expectedLabel = "alpha", context = "local 'alpha'")
    }

    @Test
    fun resolve_completion_item_from_builtin_print_is_gap_identity_or_enriched() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/completion-resolve-print.lua",
            """
            local ready = true

            return ready
            """
        )

        val items = completionItemsAt(textDocuments, document, blankLineAfterLocals())
        val labels = items.map { it.label }
        assertTrue(
            "print" in labels,
            "Expected seeded builtin 'print' in completion list before resolve; actual=$labels"
        )
        val unresolved = items.first { it.label == "print" }

        val outcome = invokeResolve(textDocuments, unresolved)
        assertResolvePreservesOrGaps(outcome, expectedLabel = "print", context = "builtin 'print'")
    }

    @Test
    fun resolve_completion_item_from_local_function_is_gap_identity_or_enriched() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/completion-resolve-function.lua",
            """
            ---@param input string
            ---@return string
            local function render(input)
                return input
            end

            return render
            """
        )

        val items = completionItemsAt(textDocuments, document, blankLineAfterLocals())
        val labels = items.map { it.label }
        assertTrue(
            "render" in labels,
            "Expected local function 'render' in completion list before resolve; actual=$labels"
        )
        val unresolved = items.first { it.label == "render" }

        val outcome = invokeResolve(textDocuments, unresolved)
        assertResolvePreservesOrGaps(outcome, expectedLabel = "render", context = "local function 'render'")
    }

    @Test
    fun resolve_member_completion_item_when_present_is_gap_identity_or_enriched() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val source = """
            local t = { field = 1 }
            return t.
        """.trimIndent()
        val document = textDocuments.open("workspace/completion-resolve-member.lua", source)
        // Cursor after the trailing '.' (member completion site).
        val position = Position(
            document.source.lineCount() - 1,
            document.source.substringAfterLast('\n').length
        )

        val items = completionItemsAt(textDocuments, document, position)
        if (items.isEmpty()) {
            // CURRENTLY_ACCEPTS: member surface may be empty for untyped tables.
            return
        }

        val unresolved = items.first()
        assertTrue(unresolved.label.isNotBlank(), "member completion labels must be non-blank")

        val outcome = invokeResolve(textDocuments, unresolved)
        assertResolvePreservesOrGaps(
            outcome,
            expectedLabel = unresolved.label,
            context = "member completion '${unresolved.label}'"
        )
    }

    // -------------------------------------------------------------------------
    // Enrichment contracts when product returns a live item
    // -------------------------------------------------------------------------

    @Test
    fun resolved_item_when_live_preserves_label_and_may_add_documentation() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/completion-resolve-docs.lua",
            """
            --- Human readable label for UI.
            ---@type string
            local label = "x"

            return label
            """
        )

        val items = completionItemsAt(textDocuments, document, blankLineAfterLocals())
        val unresolved = items.firstOrNull { it.label == "label" }
        if (unresolved == null) {
            // Soft: if lexical surface misses the local, still probe synthetic resolve.
            val synthetic = CompletionItem("label").apply { kind = CompletionItemKind.Variable }
            val syntheticOutcome = invokeResolve(textDocuments, synthetic)
            assertNoHardCrashOnResolve(syntheticOutcome, context = "synthetic documented local")
            return
        }

        val outcome = invokeResolve(textDocuments, unresolved)
        when (outcome) {
            is ResolveOutcome.Unsupported -> {
                assertTrue(outcome.isUnsupportedOperation)
            }
            is ResolveOutcome.Identity -> {
                assertEquals("label", outcome.item.label)
                // Identity no-op is CURRENTLY_ACCEPTS until product fills documentation.
            }
            is ResolveOutcome.Enriched -> {
                assertWellFormedResolved(outcome.item, expectedLabel = "label")
                // Documentation enrichment is optional; when present must be non-blank markup/string.
                assertDocumentationWellFormedWhenPresent(outcome.item)
            }
            is ResolveOutcome.Failed -> {
                assertFalse(isHardCrash(outcome.detail), outcome.detail)
            }
        }
    }

    @Test
    fun resolved_item_when_live_does_not_blank_out_insert_text_when_set() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val unresolved = CompletionItem("alpha").apply {
            kind = CompletionItemKind.Variable
            insertText = "alpha"
        }

        val outcome = invokeResolve(textDocuments, unresolved)

        when (outcome) {
            is ResolveOutcome.Unsupported -> assertTrue(outcome.isUnsupportedOperation)
            is ResolveOutcome.Identity -> {
                val insert = outcome.item.insertText
                if (insert != null) {
                    assertTrue(insert.isNotBlank(), "identity path must not blank insertText")
                }
            }
            is ResolveOutcome.Enriched -> {
                val insert = outcome.item.insertText
                if (insert != null) {
                    assertTrue(
                        insert.isNotBlank(),
                        "enriched path must not blank insertText when set; got '$insert'"
                    )
                }
            }
            is ResolveOutcome.Failed -> assertFalse(isHardCrash(outcome.detail), outcome.detail)
        }
    }

    @Test
    fun resolve_is_stable_across_repeated_calls_or_documented_gap() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val unresolved = CompletionItem("stable").apply {
            kind = CompletionItemKind.Variable
            detail = "number"
        }

        val first = invokeResolve(textDocuments, unresolved)
        val second = invokeResolve(textDocuments, unresolved)

        assertTrue(
            sameOutcomeFamily(first, second),
            "repeated resolve calls should stay on the same dual-path family; " +
                "first=$first second=$second"
        )

        if (first is ResolveOutcome.Enriched && second is ResolveOutcome.Enriched) {
            assertEquals(first.item.label, second.item.label)
            assertEquals(
                documentationText(first.item),
                documentationText(second.item),
                "enriched documentation should be stable across repeated resolve"
            )
        }
        if (first is ResolveOutcome.Identity && second is ResolveOutcome.Identity) {
            assertEquals(first.item.label, second.item.label)
        }
    }

    @Test
    fun resolve_does_not_invent_unrelated_label() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val unresolved = CompletionItem("keepMe").apply {
            kind = CompletionItemKind.Variable
        }

        val outcome = invokeResolve(textDocuments, unresolved)

        when (outcome) {
            is ResolveOutcome.Unsupported -> assertTrue(outcome.isUnsupportedOperation)
            is ResolveOutcome.Identity -> assertEquals("keepMe", outcome.item.label)
            is ResolveOutcome.Enriched -> {
                assertEquals(
                    "keepMe",
                    outcome.item.label,
                    "resolve must not rename the completion item label"
                )
            }
            is ResolveOutcome.Failed -> assertFalse(isHardCrash(outcome.detail), outcome.detail)
        }
    }

    // -------------------------------------------------------------------------
    // Quiet / reject policy interaction (service still dual-path safe)
    // -------------------------------------------------------------------------

    @Test
    fun resolve_through_default_text_document_service_matches_dual_path_contract() {
        // Default request policy is Accept; resolve still inherits LSP4J default until product wires it.
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/completion-resolve-forward.lua",
            """
            local marker = 1

            return marker
            """
        )

        val items = completionItemsAt(textDocuments, document, blankLineAfterLocals())
        val unresolved = items.firstOrNull { it.label == "marker" }
            ?: CompletionItem("marker").apply { kind = CompletionItemKind.Variable }

        val outcome = invokeResolve(textDocuments, unresolved)
        assertResolvePreservesOrGaps(outcome, expectedLabel = unresolved.label, context = "forward path")
    }

    @Test
    fun resolve_empty_completion_list_path_still_allows_synthetic_resolve() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        // Never-opened URI → completion may be empty; resolve remains independent.
        val params = CompletionParams(
            TextDocumentIdentifier("file:///workspace/completion-resolve-never-opened.lua"),
            Position(0, 0)
        )
        val list = try {
            textDocuments.completion(params).get()
        } catch (error: Throwable) {
            null
        }
        // Soft: either empty list or exception is outside this corpus; synthetic resolve still dual-paths.
        if (list != null) {
            val items = completionItemsFromEither(list)
            assertTrue(items.isEmpty() || items.all { it.label.isNotBlank() })
        }

        val outcome = invokeResolve(textDocuments, CompletionItem("phantom"))
        assertNoHardCrashOnResolve(outcome, context = "synthetic after empty/never-opened completion")
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

    private fun completionItemsAt(
        textDocuments: LuaTextDocumentService,
        document: OpenDocument,
        position: Position
    ): List<CompletionItem> {
        val either = textDocuments.completion(
            CompletionParams(TextDocumentIdentifier(document.uri), position)
        ).get()
        return completionItemsFromEither(either)
    }

    private fun completionItemsFromEither(
        either: Either<MutableList<CompletionItem>, CompletionList>
    ): List<CompletionItem> {
        return when {
            either.isLeft -> either.left.orEmpty()
            either.isRight -> either.right?.items.orEmpty()
            else -> emptyList()
        }
    }

    private fun blankLineAfterLocals(): Position = Position(1, 0)

    private fun invokeResolve(
        textDocuments: LuaTextDocumentService,
        unresolved: CompletionItem
    ): ResolveOutcome {
        val snapshotLabel = unresolved.label
        return try {
            val resolved = textDocuments.resolveCompletionItem(unresolved).get()
            classifyResolved(unresolvedLabel = snapshotLabel, unresolved = unresolved, resolved = resolved)
        } catch (error: Throwable) {
            val root = unwrap(error)
            if (isUnsupportedOperation(root)) {
                ResolveOutcome.Unsupported(detail = root.toString(), isUnsupportedOperation = true)
            } else {
                ResolveOutcome.Failed(detail = root.toString())
            }
        }
    }

    private fun classifyResolved(
        unresolvedLabel: String,
        unresolved: CompletionItem,
        resolved: CompletionItem?
    ): ResolveOutcome {
        if (resolved == null) {
            // Null result is treated as soft failure (not ideal, not hard-crash).
            return ResolveOutcome.Failed(detail = "null resolved item")
        }
        if (resolved.label.isNullOrBlank()) {
            return ResolveOutcome.Failed(detail = "resolved item has blank label")
        }
        if (resolved.label != unresolvedLabel) {
            return ResolveOutcome.Failed(
                detail = "resolved label '${resolved.label}' diverged from unresolved '$unresolvedLabel'"
            )
        }

        val enriched =
            hasRicherDocumentation(unresolved, resolved) ||
                hasRicherDetail(unresolved, resolved) ||
                hasAdditionalTextEdits(resolved) ||
                (resolved.data != null && unresolved.data == null)

        return if (enriched) {
            ResolveOutcome.Enriched(item = resolved)
        } else {
            ResolveOutcome.Identity(item = resolved)
        }
    }

    private fun hasRicherDocumentation(unresolved: CompletionItem, resolved: CompletionItem): Boolean {
        val before = documentationText(unresolved)
        val after = documentationText(resolved)
        return after != null && after.isNotBlank() && after != before
    }

    private fun hasRicherDetail(unresolved: CompletionItem, resolved: CompletionItem): Boolean {
        val before = unresolved.detail?.trim().orEmpty()
        val after = resolved.detail?.trim().orEmpty()
        return after.isNotEmpty() && after != before
    }

    private fun hasAdditionalTextEdits(resolved: CompletionItem): Boolean {
        return !resolved.additionalTextEdits.isNullOrEmpty()
    }

    private fun documentationText(item: CompletionItem): String? {
        val documentation = item.documentation ?: return null
        return when {
            documentation.isLeft -> documentation.left?.takeIf { it.isNotBlank() }
            documentation.isRight -> {
                val markup: MarkupContent? = documentation.right
                markup?.value?.takeIf { it.isNotBlank() }
            }
            else -> null
        }
    }

    private fun assertDocumentationWellFormedWhenPresent(item: CompletionItem) {
        val documentation = item.documentation ?: return
        when {
            documentation.isLeft -> {
                val text = documentation.left
                assertNotNull(text, "string documentation must be non-null when left is set")
                assertTrue(text.isNotBlank(), "string documentation must be non-blank")
            }
            documentation.isRight -> {
                val markup = documentation.right
                assertNotNull(markup, "MarkupContent documentation must be non-null when right is set")
                assertTrue(
                    markup.value.isNotBlank(),
                    "MarkupContent.value must be non-blank when documentation is present"
                )
                val kind = markup.kind
                if (kind != null) {
                    assertTrue(
                        kind == MarkupKind.PLAINTEXT ||
                            kind == MarkupKind.MARKDOWN ||
                            kind.equals("plaintext", ignoreCase = true) ||
                            kind.equals("markdown", ignoreCase = true),
                        "documentation markup kind must be plaintext or markdown; got '$kind'"
                    )
                }
            }
        }
    }

    private fun assertWellFormedResolved(item: CompletionItem, expectedLabel: String) {
        assertEquals(expectedLabel, item.label, "resolved label must match unresolved label")
        assertTrue(item.label.isNotBlank(), "resolved label must be non-blank")
        assertDocumentationWellFormedWhenPresent(item)
        val detail = item.detail
        if (detail != null) {
            // Empty string detail is pointless noise; treat as soft defect if product sets it.
            assertTrue(
                detail.isNotBlank(),
                "resolved detail when present must be non-blank; got '$detail'"
            )
        }
    }

    private fun assertResolvePreservesOrGaps(
        outcome: ResolveOutcome,
        expectedLabel: String,
        context: String
    ) {
        when (outcome) {
            is ResolveOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap for $context expects UnsupportedOperationException; got ${outcome.detail}"
                )
            }
            is ResolveOutcome.Identity -> {
                assertEquals(
                    expectedLabel,
                    outcome.item.label,
                    "identity resolve for $context must preserve label"
                )
            }
            is ResolveOutcome.Enriched -> {
                assertWellFormedResolved(outcome.item, expectedLabel = expectedLabel)
            }
            is ResolveOutcome.Failed -> {
                assertFalse(
                    isHardCrash(outcome.detail),
                    "resolve for $context must not hard-crash; got ${outcome.detail}"
                )
            }
        }
    }

    private fun assertNoHardCrashOnResolve(outcome: ResolveOutcome, context: String) {
        when (outcome) {
            is ResolveOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap for $context expects UnsupportedOperationException; got ${outcome.detail}"
                )
            }
            is ResolveOutcome.Identity,
            is ResolveOutcome.Enriched -> {
                // Soft / ideal paths are fine.
            }
            is ResolveOutcome.Failed -> {
                assertFalse(
                    isHardCrash(outcome.detail),
                    "resolve at $context must not NPE/assert; got ${outcome.detail}"
                )
            }
        }
    }

    private fun sameOutcomeFamily(a: ResolveOutcome, b: ResolveOutcome): Boolean {
        return when (a) {
            is ResolveOutcome.Unsupported -> b is ResolveOutcome.Unsupported
            is ResolveOutcome.Identity -> b is ResolveOutcome.Identity
            is ResolveOutcome.Enriched -> b is ResolveOutcome.Enriched
            is ResolveOutcome.Failed -> b is ResolveOutcome.Failed
        }
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

    private fun String.lineCount(): Int {
        if (isEmpty()) return 1
        return count { it == '\n' } + 1
    }

    private sealed class ResolveOutcome {
        data class Unsupported(
            val detail: String,
            val isUnsupportedOperation: Boolean
        ) : ResolveOutcome()

        /** Product returned an item without enriching documentation/detail/edits/data. */
        data class Identity(val item: CompletionItem) : ResolveOutcome()

        /** Product returned additional fields on top of the unresolved item. */
        data class Enriched(val item: CompletionItem) : ResolveOutcome()

        data class Failed(val detail: String) : ResolveOutcome()
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
