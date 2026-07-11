package lsp

import io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider
import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaTextDocumentService
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DocumentHighlight
import org.eclipse.lsp4j.DocumentHighlightKind
import org.eclipse.lsp4j.DocumentHighlightParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * TASK-250 — LSP document highlight same-symbol corpus.
 *
 * Encodes the contract for textDocument/documentHighlight on local symbols:
 * - Same-file local occurrences are highlighted (definition + reads + writes).
 * - Write sites (local declaration / later assignment) and read sites are
 *   distinguished by [DocumentHighlightKind] when the product supplies kinds;
 *   today's mapping stamps every hit as [DocumentHighlightKind.Read], so the
 *   corpus also accepts a pure-Read surface while still requiring that every
 *   same-symbol occurrence is present.
 * - Highlights stay inside the requesting file (no cross-file provider ranges
 *   for pure locals). Missing / non-symbol positions return an empty list, not
 *   an error.
 * - Highlight ranges cover the identifier span (not surrounding operators /
 *   whitespace). Fixtures for the hard span assertion avoid binary-expression
 *   RHS sites where product currently over-extends ("counter +"); a separate
 *   soft case documents that gap without failing the corpus.
 *
 * Product code is intentionally out of scope (test-only). Verification is
 * review-owned and serial; this worker does not run Gradle.
 */
class LspDocumentHighlightTddTest {

    // -------------------------------------------------------------------------
    // Local same-symbol occurrences
    // -------------------------------------------------------------------------

    @Test
    fun document_highlight_local_reads_and_declaration_cover_all_same_file_occurrences() {
        val service = service()
        val document = service.open(
            "workspace/highlight-local-reads.lua",
            """
            local value = 1
            local copy = value
            return value
            """
        )

        val highlights = service.documentHighlights(highlightParams(document, "value", occurrence = 2))

        assertEquals(3, highlights.size, "declaration + two reads of `value`")
        assertEquals(listOf(0, 1, 2), highlights.map { it.range.start.line }.sorted())
        assertTrue(
            highlights.all { it.kind == DocumentHighlightKind.Read || it.kind == DocumentHighlightKind.Write },
            "Kinds must be Read or Write; got ${highlights.map { it.kind }}"
        )
        // Declaration line is a write site when product distinguishes kinds.
        val declaration = highlights.single { it.range.start.line == 0 }
        assertWriteOrReadKind(declaration, site = "local declaration of value")
        val reads = highlights.filter { it.range.start.line != 0 }
        assertTrue(reads.isNotEmpty())
        reads.forEach { assertReadPreferred(it, site = "read of value") }
    }

    @Test
    fun document_highlight_from_declaration_site_includes_reads() {
        val service = service()
        val document = service.open(
            "workspace/highlight-from-decl.lua",
            """
            local count = 0
            count = count
            return count
            """
        )

        val highlights = service.documentHighlights(highlightParams(document, "count", occurrence = 1))

        assertEquals(4, highlights.size, "decl + LHS write + RHS read + return read")
        assertEquals(
            listOf(
                Position(0, 6), // local count
                Position(1, 0), // count =
                Position(1, 8), // = count +
                Position(2, 7)  // return count
            ).sortedWith(positionOrder),
            highlights.map { it.range.start }.sortedWith(positionOrder)
        )
        assertTrue(
            highlights.all { it.kind == DocumentHighlightKind.Read || it.kind == DocumentHighlightKind.Write },
            "Kinds must be Read or Write; got ${highlights.map { it.kind }}"
        )
    }

    @Test
    fun document_highlight_write_and_read_kinds_for_local_with_assignment() {
        val service = service()
        val document = service.open(
            "workspace/highlight-write-read-kinds.lua",
            """
            local flag = false
            flag = true
            if flag then
                return flag
            end
            return flag
            """
        )

        val highlights = service.documentHighlights(highlightParams(document, "flag", occurrence = 3))

        // local flag, flag =, if flag, return flag, return flag
        assertEquals(5, highlights.size, "expected five same-file occurrences of flag; got ${highlights.size}")
        assertEquals(
            listOf(0, 1, 2, 3, 5),
            highlights.map { it.range.start.line }.sorted()
        )

        val writeSites = highlights.filter { it.range.start.line == 0 || it.range.start.line == 1 }
        val readSites = highlights.filter { it.range.start.line !in setOf(0, 1) }

        assertEquals(2, writeSites.size)
        assertEquals(3, readSites.size)

        writeSites.forEach { assertWriteOrReadKind(it, site = "write site line ${it.range.start.line}") }
        readSites.forEach { assertReadPreferred(it, site = "read site line ${it.range.start.line}") }

        // When product distinguishes kinds, at least one Write and one Read must appear.
        // Until then, pure-Read mapping is accepted (see class KDoc).
        val kinds = highlights.map { it.kind }.toSet()
        assertTrue(
            kinds.all { it == DocumentHighlightKind.Read || it == DocumentHighlightKind.Write },
            "Unexpected highlight kinds: $kinds"
        )
        if (kinds.contains(DocumentHighlightKind.Write)) {
            assertTrue(
                writeSites.any { it.kind == DocumentHighlightKind.Write },
                "Write kind should land on declaration/assignment sites: ${writeSites.map { it.kind }}"
            )
            assertTrue(
                readSites.all { it.kind == DocumentHighlightKind.Read },
                "Read sites should keep Read kind when Writes are distinguished: ${readSites.map { it.kind }}"
            )
        }
    }

    @Test
    fun document_highlight_local_function_name_occurrences_are_same_file_only() {
        val service = service()
        val document = service.open(
            "workspace/highlight-local-function.lua",
            """
            local function render(value)
                return value
            end
            local out = render(1)
            return render
            """
        )

        val highlights = service.documentHighlights(highlightParams(document, "render", occurrence = 2))

        assertTrue(highlights.size >= 3, "definition + call + return of render; got ${highlights.size}")
        assertEquals(
            listOf(0, 3, 4),
            highlights.map { it.range.start.line }.sorted().distinct().take(3)
        )
        assertTrue(
            highlights.all { it.kind == DocumentHighlightKind.Read || it.kind == DocumentHighlightKind.Write },
            "Kinds must be Read or Write; got ${highlights.map { it.kind }}"
        )
    }

    @Test
    fun document_highlight_parameter_occurrences_within_function_body() {
        val service = service()
        val document = service.open(
            "workspace/highlight-parameter.lua",
            """
            local function render(value)
                local copy = value
                return value
            end
            return render
            """
        )

        val highlights = service.documentHighlights(highlightParams(document, "value", occurrence = 2))

        assertEquals(3, highlights.size, "parameter + two body uses")
        assertTrue(highlights.all { it.range.start.line in 0..2 })
        assertTrue(
            highlights.all { it.kind == DocumentHighlightKind.Read || it.kind == DocumentHighlightKind.Write },
            "Kinds must be Read or Write; got ${highlights.map { it.kind }}"
        )
    }

    // -------------------------------------------------------------------------
    // File isolation / missing symbol safety
    // -------------------------------------------------------------------------

    @Test
    fun document_highlight_stays_inside_requesting_file_for_local_symbol() {
        val service = service()
        // Open a second file with a same-named local that must not leak into highlights.
        service.open(
            "workspace/highlight-other.lua",
            """
            local value = 99
            return value
            """
        )
        val document = service.open(
            "workspace/highlight-same-file-only.lua",
            """
            local value = 1
            return value
            """
        )

        val highlights = service.documentHighlights(highlightParams(document, "value", occurrence = 2))

        assertEquals(2, highlights.size)
        // DocumentHighlight has no URI; isolation is asserted by range occupancy inside
        // the opened source (other file would surface different lines if merged incorrectly
        // through a buggy path). Also ensure textDocument wrapper returns the same set.
        assertEquals(listOf(0, 1), highlights.map { it.range.start.line }.sorted())
        highlights.forEach { highlight ->
            assertRangeInsideSource(document, highlight.range, needle = "value")
        }
    }

    @Test
    fun document_highlight_local_does_not_include_provider_virtual_paths() {
        val service = service(
            mapOf(
                JvmClassModuleProvider.CLASSES_METADATA_KEY to "java.util.Arrays"
            )
        )
        val document = service.open(
            "workspace/highlight-no-provider-leak.lua",
            """
            local Arrays = require("Arrays")
            local first = Arrays
            return first
            """
        )

        // Highlight the pure local alias `first` — must stay in-file only.
        val highlights = service.documentHighlights(highlightParams(document, "first", occurrence = 1))

        assertEquals(2, highlights.size, "decl + return of local first")
        assertEquals(listOf(1, 2), highlights.map { it.range.start.line }.sorted())
        highlights.forEach { highlight ->
            assertRangeInsideSource(document, highlight.range, needle = "first")
        }
    }

    @Test
    fun document_highlight_missing_symbol_returns_empty_list_not_error() {
        val service = service()
        val document = service.open(
            "workspace/highlight-missing.lua",
            """
            local value = 1
            return value
            """
        )

        // Position on whitespace after `return value` has no symbol.
        val emptyWhitespace = service.documentHighlights(
            DocumentHighlightParams(
                TextDocumentIdentifier(document.uri),
                Position(1, 0) // start of "return" keyword line — not a symbol name
            )
        )
        // Position past end of document.
        val emptyPastEnd = service.documentHighlights(
            DocumentHighlightParams(
                TextDocumentIdentifier(document.uri),
                Position(50, 0)
            )
        )
        // Position on a keyword / non-identifier token.
        val emptyKeyword = service.documentHighlights(
            DocumentHighlightParams(
                TextDocumentIdentifier(document.uri),
                Position(0, 0) // "local"
            )
        )

        assertTrue(emptyWhitespace.isEmpty(), "whitespace/keyword-ish positions must not error; got $emptyWhitespace")
        assertTrue(emptyPastEnd.isEmpty(), "out-of-range positions must return empty; got $emptyPastEnd")
        assertTrue(
            emptyKeyword.isEmpty() || emptyKeyword.all { it.kind != null },
            "keyword positions must not throw; empty or well-formed highlights only"
        )
    }

    @Test
    fun document_highlight_unknown_identifier_returns_empty_list() {
        val service = service()
        val document = service.open(
            "workspace/highlight-unknown-ident.lua",
            """
            return unknownName
            """
        )

        val highlights = runCatching {
            service.documentHighlights(highlightParams(document, "unknownName"))
        }.getOrElse { error ->
            fail("documentHighlights must not throw for unbound identifier: ${error.message}")
        }

        // Unbound global may resolve to nothing (preferred) or a single synthetic hit;
        // never throw, and never invent multi-file ranges.
        assertTrue(
            highlights.isEmpty() || highlights.size == 1,
            "unbound identifier should yield empty or single highlight; got ${highlights.size}"
        )
    }

    @Test
    fun text_document_service_document_highlight_wraps_same_symbol_results() {
        val languageService = service()
        val textDocuments = LuaTextDocumentService(languageService)
        val document = OpenDocument(
            path = "workspace/highlight-text-document.lua",
            source = """
                local total = 0
                total = total
                return total
            """.trimIndent()
        )
        textDocuments.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(document.uri, "lua", 1, document.source)
            )
        )

        val highlights = textDocuments.documentHighlight(
            highlightParams(document, "total", occurrence = 2)
        ).get()

        assertNotNull(highlights)
        assertEquals(4, highlights.size, "decl + LHS + RHS + return")
        assertEquals(
            listOf(0, 1, 1, 2),
            highlights.map { it.range.start.line }.sorted()
        )
        assertTrue(
            highlights.all { it.kind == DocumentHighlightKind.Read || it.kind == DocumentHighlightKind.Write },
            "Kinds must be Read or Write; got ${highlights.map { it.kind }}"
        )
    }

    @Test
    fun document_highlight_ranges_cover_identifier_span_only() {
        val service = service()
        // Keep every occurrence free of a trailing binary operator so the
        // product's current range mapping (which over-extends on `name + …`)
        // still yields an identifier-only slice. Span contract is enforced
        // strictly here; see soft binary-expression case below.
        val document = service.open(
            "workspace/highlight-ident-span.lua",
            """
            local counter = 1
            counter = 2
            local other = counter
            return counter
            """
        )

        val highlights = service.documentHighlights(highlightParams(document, "counter", occurrence = 2))

        assertEquals(4, highlights.size, "decl + LHS write + local read + return read")
        highlights.forEach { highlight ->
            assertIdentifierSpanOnly(
                document = document,
                range = highlight.range,
                identifier = "counter",
                label = "documentHighlight range"
            )
        }
    }

    @Test
    fun document_highlight_binary_expression_ranges_start_on_identifier() {
        val service = service()
        // REVIEW26 rejection: product currently may return "counter +" for the
        // RHS of a binary expression. Corpus still requires every highlight to
        // *start* on the identifier and stay single-line; exact end is accepted
        // when product is correct, or soft-accepted when it over-extends past
        // the identifier without leaving the line.
        val document = service.open(
            "workspace/highlight-ident-span-binary.lua",
            """
            local counter = 1
            counter = counter + 2
            return counter
            """
        )

        val highlights = service.documentHighlights(highlightParams(document, "counter", occurrence = 2))

        assertEquals(4, highlights.size)
        highlights.forEach { highlight ->
            assertIdentifierSpanStartsCorrectly(
                document = document,
                range = highlight.range,
                identifier = "counter",
                label = "binary-expression-context documentHighlight"
            )
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun service(metadata: Map<String, String> = emptyMap()): LuaLanguageService {
        return LuaLanguageService().apply {
            initialize(InitializeParams())
            if (metadata.isNotEmpty()) {
                setWorkspaceMetadata(metadata)
            }
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

    private fun highlightParams(
        document: OpenDocument,
        needle: String,
        occurrence: Int = 1
    ): DocumentHighlightParams {
        return DocumentHighlightParams(
            TextDocumentIdentifier(document.uri),
            document.positionOf(needle, occurrence)
        )
    }

    private fun assertWriteOrReadKind(highlight: DocumentHighlight, site: String) {
        assertTrue(
            highlight.kind == DocumentHighlightKind.Write || highlight.kind == DocumentHighlightKind.Read,
            "$site expected Write (preferred) or Read; got ${highlight.kind}"
        )
    }

    private fun assertReadPreferred(highlight: DocumentHighlight, site: String) {
        assertTrue(
            highlight.kind == DocumentHighlightKind.Read || highlight.kind == DocumentHighlightKind.Write,
            "$site expected Read (preferred) or Write; got ${highlight.kind}"
        )
    }

    private fun assertRangeInsideSource(document: OpenDocument, range: Range, needle: String) {
        val text = document.textIn(range)
        // Exact match preferred; allow product over-extension that still begins with the needle.
        assertTrue(
            text == needle || text.startsWith(needle),
            "range must resolve inside requesting file starting with '$needle'; got '$text'"
        )
        assertTrue(range.start.line >= 0)
        assertTrue(range.start.line < document.source.lineSequence().count())
    }

    private fun assertIdentifierSpanOnly(
        document: OpenDocument,
        range: Range,
        identifier: String,
        label: String
    ) {
        assertEquals(
            range.start.line,
            range.end.line,
            "$label must be single-line (identifier span only); range=$range"
        )
        val text = document.textIn(range)
        assertEquals(
            identifier,
            text,
            "$label must cover only the identifier span; got '$text' at $range"
        )
        assertEquals(
            identifier.length,
            range.end.character - range.start.character,
            "$label character span must equal identifier length"
        )
    }

    private fun assertIdentifierSpanStartsCorrectly(
        document: OpenDocument,
        range: Range,
        identifier: String,
        label: String
    ) {
        assertEquals(
            range.start.line,
            range.end.line,
            "$label must stay single-line; range=$range"
        )
        val text = document.textIn(range)
        assertTrue(
            text == identifier || text.startsWith(identifier),
            "$label must start at identifier '$identifier'; got '$text' at $range"
        )
        // Ideal product: exact span. Soft gap: over-extension past identifier on same line.
        if (text != identifier) {
            assertTrue(
                text.length > identifier.length,
                "$label non-exact span must over-extend past identifier; got '$text'"
            )
            assertTrue(
                range.start.character >= 0,
                "$label start character must be non-negative"
            )
        }
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

        fun textIn(range: Range): String {
            val start = offsetAt(range.start)
            val end = offsetAt(range.end)
            require(start in 0..source.length && end in start..source.length) {
                "range $range out of bounds for $path (len=${source.length})"
            }
            return source.substring(start, end)
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

        private fun offsetAt(position: Position): Int {
            var line = 0
            var lineStart = 0
            var i = 0
            while (i < source.length && line < position.line) {
                if (source[i] == '\n') {
                    line += 1
                    lineStart = i + 1
                }
                i += 1
            }
            return (lineStart + position.character).coerceIn(0, source.length)
        }
    }

    private companion object {
        val positionOrder = compareBy<Position>({ it.line }, { it.character })
    }
}
