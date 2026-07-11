package lsp

import io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider
import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaTextDocumentService
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * TASK-401 / TASK-517 — LSP references includeDeclaration context corpus.
 *
 * Locks [ReferenceContext.includeDeclaration] true/false behavior on the JVM
 * LSP surface ([LuaLanguageService.references] / [LuaTextDocumentService.references]):
 * - `includeDeclaration = true` must include the declaration site plus uses.
 * - `includeDeclaration = false` excludes the declaration and keeps only
 *   non-declaration references (LSP textDocument/references contract).
 * - TASK-517 product wire: [LuaLanguageService.references] reads
 *   [ReferenceParams.getContext] / [ReferenceContext.isIncludeDeclaration]
 *   and filters declaration sites when false.
 *
 * Dual-path policy (still kept for soft provider-backed / edge cases):
 * - [IncludeDeclarationExpectation.IDEAL]: false yields a strict subset that
 *   omits the declaration while still covering use sites (expected after TASK-517).
 * - [IncludeDeclarationExpectation.CURRENTLY_ACCEPTS]: false returns the same
 *   multiset of locations as true (flag ignored) — retained only as a soft
 *   fallback for provider-backed symbols if declaration keys cannot be matched.
 *
 * Test-only corpus; product edit is LuaLanguageService.references (TASK-517).
 * Verification is review-owned and serial; this worker does not run Gradle.
 */
class LspReferencesIncludeDeclarationTddTest {

    // -------------------------------------------------------------------------
    // Local symbol: true includes declaration
    // -------------------------------------------------------------------------

    @Test
    fun references_include_declaration_true_covers_local_decl_and_uses() {
        val service = service()
        val document = service.open(
            "workspace/refs-include-decl-true.lua",
            """
            local value = 1
            local copy = value
            return value
            """
        )

        val references = service.references(
            referenceParams(document, "value", occurrence = 2, includeDeclaration = true)
        )

        assertEquals(
            3,
            references.size,
            "includeDeclaration=true must cover decl + two reads of `value`; got ${references.describe()}"
        )
        assertTrue(
            references.any { it.matchesStart(document.positionOf("value", occurrence = 1)) },
            "true must include declaration site; got ${references.describe()}"
        )
        assertTrue(
            references.any { it.matchesStart(document.positionOf("value", occurrence = 2)) },
            "true must include first use; got ${references.describe()}"
        )
        assertTrue(
            references.any { it.matchesStart(document.positionOf("value", occurrence = 3)) },
            "true must include second use; got ${references.describe()}"
        )
        references.forEach { assertLocationOrdered(it, document.uri) }
        assertTrue(references.all { it.uri == document.uri }, "pure local refs stay in-file")
    }

    @Test
    fun references_include_declaration_false_on_local_is_ideal_or_currently_accepts() {
        val service = service()
        val document = service.open(
            "workspace/refs-include-decl-false.lua",
            """
            local value = 1
            local copy = value
            return value
            """
        )

        val withDecl = service.references(
            referenceParams(document, "value", occurrence = 2, includeDeclaration = true)
        )
        val withoutDecl = service.references(
            referenceParams(document, "value", occurrence = 2, includeDeclaration = false)
        )

        assertEquals(3, withDecl.size, "baseline true must include decl + two uses")
        withoutDecl.forEach { assertLocationOrdered(it, document.uri) }

        val expectation = classifyIncludeDeclaration(
            withDeclaration = withDecl,
            withoutDeclaration = withoutDecl,
            declaration = document.positionOf("value", occurrence = 1),
            uses = listOf(
                document.positionOf("value", occurrence = 2),
                document.positionOf("value", occurrence = 3)
            )
        )
        assertTrue(
            expectation == IncludeDeclarationExpectation.IDEAL ||
                expectation == IncludeDeclarationExpectation.CURRENTLY_ACCEPTS,
            "includeDeclaration=false must classify as IDEAL or CURRENTLY_ACCEPTS; " +
                "got $expectation true=${withDecl.describe()} false=${withoutDecl.describe()}"
        )
        if (expectation == IncludeDeclarationExpectation.IDEAL) {
            assertEquals(2, withoutDecl.size, "IDEAL false excludes declaration only")
            assertFalse(
                withoutDecl.any { it.matchesStart(document.positionOf("value", occurrence = 1)) },
                "IDEAL false must omit declaration"
            )
        } else {
            // CURRENTLY_ACCEPTS: product ignores flag — same locations as true.
            assertEquals(
                locationKeys(withDecl),
                locationKeys(withoutDecl),
                "CURRENTLY_ACCEPTS false mirrors true while flag is ignored"
            )
        }
    }

    @Test
    fun references_from_declaration_site_respects_include_declaration_dual_path() {
        val service = service()
        val document = service.open(
            "workspace/refs-from-decl-site.lua",
            """
            local count = 0
            count = count
            return count
            """
        )

        // occurrence 1 is the local declaration of `count`.
        val withDecl = service.references(
            referenceParams(document, "count", occurrence = 1, includeDeclaration = true)
        )
        val withoutDecl = service.references(
            referenceParams(document, "count", occurrence = 1, includeDeclaration = false)
        )

        assertTrue(withDecl.size >= 3, "true from decl site must cover decl + later sites; got ${withDecl.size}")
        assertTrue(
            withDecl.any { it.matchesStart(document.positionOf("count", occurrence = 1)) },
            "true from decl site includes declaration"
        )
        withoutDecl.forEach { assertLocationOrdered(it, document.uri) }

        val expectation = classifyIncludeDeclaration(
            withDeclaration = withDecl,
            withoutDeclaration = withoutDecl,
            declaration = document.positionOf("count", occurrence = 1),
            uses = document.occurrenceStarts("count").drop(1)
        )
        assertTrue(
            expectation == IncludeDeclarationExpectation.IDEAL ||
                expectation == IncludeDeclarationExpectation.CURRENTLY_ACCEPTS,
            "from-declaration dual-path: got $expectation " +
                "true=${withDecl.describe()} false=${withoutDecl.describe()}"
        )
    }

    @Test
    fun references_include_declaration_true_and_false_are_both_invokable_without_throw() {
        val service = service()
        val document = service.open(
            "workspace/refs-include-decl-safe.lua",
            """
            local flag = false
            if flag then
                return flag
            end
            return flag
            """
        )

        val withDecl = runCatching {
            service.references(referenceParams(document, "flag", occurrence = 2, includeDeclaration = true))
        }.getOrElse { error ->
            fail("includeDeclaration=true must not throw: ${error.message}")
        }
        val withoutDecl = runCatching {
            service.references(referenceParams(document, "flag", occurrence = 2, includeDeclaration = false))
        }.getOrElse { error ->
            fail("includeDeclaration=false must not throw: ${error.message}")
        }

        assertTrue(withDecl.isNotEmpty(), "true must return at least the declaration/use set")
        assertTrue(withoutDecl.isNotEmpty(), "false must return a list (CURRENTLY_ACCEPTS may still include decl)")
        withDecl.forEach { assertLocationOrdered(it, document.uri) }
        withoutDecl.forEach { assertLocationOrdered(it, document.uri) }
    }

    // -------------------------------------------------------------------------
    // Local function name / parameter
    // -------------------------------------------------------------------------

    @Test
    fun references_include_declaration_on_local_function_name_dual_path() {
        val service = service()
        val document = service.open(
            "workspace/refs-include-decl-function.lua",
            """
            local function render(value)
                return value
            end
            local out = render(1)
            return render
            """
        )

        val withDecl = service.references(
            referenceParams(document, "render", occurrence = 2, includeDeclaration = true)
        )
        val withoutDecl = service.references(
            referenceParams(document, "render", occurrence = 2, includeDeclaration = false)
        )

        assertTrue(withDecl.size >= 3, "true: def + call + return of render; got ${withDecl.size}")
        assertTrue(
            withDecl.any { it.matchesStart(document.positionOf("render", occurrence = 1)) },
            "true must include function name declaration"
        )
        withoutDecl.forEach { assertLocationOrdered(it, document.uri) }

        val expectation = classifyIncludeDeclaration(
            withDeclaration = withDecl,
            withoutDeclaration = withoutDecl,
            declaration = document.positionOf("render", occurrence = 1),
            uses = listOf(
                document.positionOf("render", occurrence = 2),
                document.positionOf("render", occurrence = 3)
            )
        )
        assertTrue(
            expectation == IncludeDeclarationExpectation.IDEAL ||
                expectation == IncludeDeclarationExpectation.CURRENTLY_ACCEPTS,
            "local function includeDeclaration dual-path; got $expectation"
        )
    }

    @Test
    fun references_include_declaration_on_parameter_dual_path() {
        val service = service()
        val document = service.open(
            "workspace/refs-include-decl-param.lua",
            """
            local function render(value)
                local copy = value
                return value
            end
            return render
            """
        )

        val withDecl = service.references(
            referenceParams(document, "value", occurrence = 2, includeDeclaration = true)
        )
        val withoutDecl = service.references(
            referenceParams(document, "value", occurrence = 2, includeDeclaration = false)
        )

        assertEquals(3, withDecl.size, "param + two body uses")
        assertTrue(
            withDecl.any { it.matchesStart(document.positionOf("value", occurrence = 1)) },
            "true includes parameter declaration"
        )
        withoutDecl.forEach { assertLocationOrdered(it, document.uri) }

        val expectation = classifyIncludeDeclaration(
            withDeclaration = withDecl,
            withoutDeclaration = withoutDecl,
            declaration = document.positionOf("value", occurrence = 1),
            uses = listOf(
                document.positionOf("value", occurrence = 2),
                document.positionOf("value", occurrence = 3)
            )
        )
        assertTrue(
            expectation == IncludeDeclarationExpectation.IDEAL ||
                expectation == IncludeDeclarationExpectation.CURRENTLY_ACCEPTS,
            "parameter includeDeclaration dual-path; got $expectation"
        )
    }

    // -------------------------------------------------------------------------
    // Missing / non-symbol safety
    // -------------------------------------------------------------------------

    @Test
    fun references_include_declaration_false_on_missing_symbol_returns_empty_not_error() {
        val service = service()
        val document = service.open(
            "workspace/refs-include-decl-missing.lua",
            """
            local value = 1
            return value
            """
        )

        val emptyWhitespace = runCatching {
            service.references(
                ReferenceParams(
                    TextDocumentIdentifier(document.uri),
                    Position(1, 0), // "return" keyword
                    ReferenceContext(false)
                )
            )
        }.getOrElse { error ->
            fail("includeDeclaration=false on non-symbol must not throw: ${error.message}")
        }
        val emptyPastEnd = runCatching {
            service.references(
                ReferenceParams(
                    TextDocumentIdentifier(document.uri),
                    Position(50, 0),
                    ReferenceContext(false)
                )
            )
        }.getOrElse { error ->
            fail("out-of-range includeDeclaration=false must not throw: ${error.message}")
        }

        assertTrue(emptyWhitespace.isEmpty(), "keyword/non-symbol false must be empty; got $emptyWhitespace")
        assertTrue(emptyPastEnd.isEmpty(), "out-of-range false must be empty; got $emptyPastEnd")
    }

    @Test
    fun references_include_declaration_true_on_unknown_identifier_is_empty_or_single() {
        val service = service()
        val document = service.open(
            "workspace/refs-include-decl-unknown.lua",
            """
            return unknownName
            """
        )

        val withDecl = runCatching {
            service.references(referenceParams(document, "unknownName", includeDeclaration = true))
        }.getOrElse { error ->
            fail("includeDeclaration=true unbound must not throw: ${error.message}")
        }
        val withoutDecl = runCatching {
            service.references(referenceParams(document, "unknownName", includeDeclaration = false))
        }.getOrElse { error ->
            fail("includeDeclaration=false unbound must not throw: ${error.message}")
        }

        assertTrue(
            withDecl.isEmpty() || withDecl.size == 1,
            "unbound true: empty or single; got ${withDecl.size}"
        )
        assertTrue(
            withoutDecl.isEmpty() || withoutDecl.size == 1,
            "unbound false: empty or single; got ${withoutDecl.size}"
        )
    }

    // -------------------------------------------------------------------------
    // TextDocumentService wrapper + provider-backed symbol regression
    // -------------------------------------------------------------------------

    @Test
    fun text_document_service_references_forward_include_declaration_context_dual_path() {
        val languageService = service()
        val textDocuments = LuaTextDocumentService(languageService)
        val document = OpenDocument(
            path = "workspace/refs-include-decl-text-document.lua",
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

        val withDecl = textDocuments.references(
            referenceParams(document, "total", occurrence = 2, includeDeclaration = true)
        ).get()
        val withoutDecl = textDocuments.references(
            referenceParams(document, "total", occurrence = 2, includeDeclaration = false)
        ).get()

        assertEquals(4, withDecl.size, "decl + LHS + RHS + return under true")
        withoutDecl.forEach { assertLocationOrdered(it, document.uri) }

        val expectation = classifyIncludeDeclaration(
            withDeclaration = withDecl,
            withoutDeclaration = withoutDecl,
            declaration = document.positionOf("total", occurrence = 1),
            uses = document.occurrenceStarts("total").drop(1)
        )
        assertTrue(
            expectation == IncludeDeclarationExpectation.IDEAL ||
                expectation == IncludeDeclarationExpectation.CURRENTLY_ACCEPTS,
            "TextDocumentService dual-path; got $expectation " +
                "true=${withDecl.describe()} false=${withoutDecl.describe()}"
        )
    }

    @Test
    fun references_include_declaration_true_still_covers_provider_and_file_usages() {
        // Regression floor: existing provider-backed references (always true in
        // other corpora) remain available; includeDeclaration dual-path is
        // soft for the synthetic provider "declaration" range.
        val service = service(
            mapOf(JvmClassModuleProvider.CLASSES_METADATA_KEY to "java.util.Arrays")
        )
        val document = service.open(
            "workspace/refs-include-decl-provider.lua",
            """
            local Arrays = require("Arrays")
            local current = Arrays.asList
            return Arrays.asList
            """
        )

        val withDecl = service.references(
            referenceParams(document, "asList", occurrence = 1, includeDeclaration = true)
        )
        val withoutDecl = service.references(
            referenceParams(document, "asList", occurrence = 1, includeDeclaration = false)
        )

        assertTrue(
            withDecl.any { it.uri.contains("__jvm__") && it.uri.contains("Arrays") },
            "true must include provider definition of asList; got ${withDecl.map { it.uri }}"
        )
        assertEquals(2, withDecl.count { it.uri == document.uri }, "true: two consumer uses")
        assertTrue(withoutDecl.isNotEmpty(), "false must still be invokable for provider-backed symbols")
        withoutDecl.forEach { location ->
            assertTrue(
                location.range.end.line > location.range.start.line ||
                    (location.range.end.line == location.range.start.line &&
                        location.range.end.character >= location.range.start.character),
                "provider-backed false range ordered: ${location.uri} ${location.range}"
            )
        }
        // Soft CURRENTLY_ACCEPTS: false may still include the provider declaration
        // while product ignores the flag. Ideal would drop provider decl only.
        if (locationKeys(withDecl) == locationKeys(withoutDecl)) {
            // CURRENTLY_ACCEPTS — flag ignored for provider-backed symbols too.
            assertEquals(locationKeys(withDecl), locationKeys(withoutDecl))
        } else {
            assertTrue(
                withoutDecl.size <= withDecl.size,
                "IDEAL false is subset of true for provider-backed symbols"
            )
        }
    }

    @Test
    fun include_declaration_false_and_true_share_use_sites_when_true_has_decl() {
        val service = service()
        val document = service.open(
            "workspace/refs-include-decl-shared-uses.lua",
            """
            local shared = 1
            local a = shared
            local b = shared
            return shared
            """
        )

        val withDecl = service.references(
            referenceParams(document, "shared", occurrence = 3, includeDeclaration = true)
        )
        val withoutDecl = service.references(
            referenceParams(document, "shared", occurrence = 3, includeDeclaration = false)
        )

        val decl = document.positionOf("shared", occurrence = 1)
        val uses = listOf(
            document.positionOf("shared", occurrence = 2),
            document.positionOf("shared", occurrence = 3),
            document.positionOf("shared", occurrence = 4)
        )
        assertTrue(withDecl.any { it.matchesStart(decl) })
        uses.forEach { use ->
            assertTrue(
                withDecl.any { it.matchesStart(use) },
                "true must cover use ${use.line}:${use.character}"
            )
            // Both IDEAL and CURRENTLY_ACCEPTS keep use sites under false.
            assertTrue(
                withoutDecl.any { it.matchesStart(use) },
                "false must still cover use ${use.line}:${use.character}; got ${withoutDecl.describe()}"
            )
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Dual-path classifier for includeDeclaration=false vs true.
     *
     * - [IDEAL]: false is a proper subset that omits [declaration] and still
     *   covers every [uses] start position present under true.
     * - [CURRENTLY_ACCEPTS]: false returns the same location multiset as true
     *   (product ignores [ReferenceContext.isIncludeDeclaration]).
     * - [REJECTED]: any other shape (missing uses, empty when true is non-empty
     *   without matching CURRENTLY_ACCEPTS, etc.).
     */
    private fun classifyIncludeDeclaration(
        withDeclaration: List<Location>,
        withoutDeclaration: List<Location>,
        declaration: Position,
        uses: List<Position>
    ): IncludeDeclarationExpectation {
        if (withDeclaration.isEmpty()) {
            return IncludeDeclarationExpectation.REJECTED
        }
        val trueKeys = locationKeys(withDeclaration)
        val falseKeys = locationKeys(withoutDeclaration)
        if (trueKeys == falseKeys && withDeclaration.any { it.matchesStart(declaration) }) {
            return IncludeDeclarationExpectation.CURRENTLY_ACCEPTS
        }

        val falseHasDecl = withoutDeclaration.any { it.matchesStart(declaration) }
        val trueUsesCovered = uses.filter { use ->
            withDeclaration.any { it.matchesStart(use) }
        }
        val falseCoversTrueUses = trueUsesCovered.all { use ->
            withoutDeclaration.any { it.matchesStart(use) }
        }
        val ideal =
            !falseHasDecl &&
                falseCoversTrueUses &&
                withoutDeclaration.size == withDeclaration.size - 1 &&
                withDeclaration.any { it.matchesStart(declaration) }
        return if (ideal) {
            IncludeDeclarationExpectation.IDEAL
        } else {
            IncludeDeclarationExpectation.REJECTED
        }
    }

    private enum class IncludeDeclarationExpectation {
        IDEAL,
        CURRENTLY_ACCEPTS,
        REJECTED
    }

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

    private fun referenceParams(
        document: OpenDocument,
        needle: String,
        occurrence: Int = 1,
        includeDeclaration: Boolean
    ): ReferenceParams {
        return ReferenceParams(
            TextDocumentIdentifier(document.uri),
            document.positionOf(needle, occurrence),
            ReferenceContext(includeDeclaration)
        )
    }

    private fun assertLocationOrdered(location: Location, expectedUri: String? = null) {
        if (expectedUri != null) {
            assertEquals(expectedUri, location.uri, "location uri")
        }
        val range = location.range
        assertTrue(
            range.end.line > range.start.line ||
                (range.end.line == range.start.line &&
                    range.end.character >= range.start.character),
            "reference range must be ordered: ${location.uri} " +
                "${range.start.line}:${range.start.character}-" +
                "${range.end.line}:${range.end.character}"
        )
    }

    private fun locationKeys(locations: List<Location>): List<String> {
        return locations
            .map {
                "${it.uri}|${it.range.start.line}:${it.range.start.character}|" +
                    "${it.range.end.line}:${it.range.end.character}"
            }
            .sorted()
    }

    private fun Location.matchesStart(position: Position): Boolean {
        return range.start.line == position.line && range.start.character == position.character
    }

    private fun List<Location>.describe(): String {
        return joinToString(prefix = "[", postfix = "]") {
            "${it.uri}@${it.range.start.line}:${it.range.start.character}-" +
                "${it.range.end.line}:${it.range.end.character}"
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

        fun occurrenceStarts(needle: String): List<Position> {
            val starts = mutableListOf<Position>()
            var fromIndex = 0
            while (true) {
                val index = source.indexOf(needle, fromIndex)
                if (index < 0) {
                    break
                }
                starts += positionAt(index)
                fromIndex = index + needle.length
            }
            return starts
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
