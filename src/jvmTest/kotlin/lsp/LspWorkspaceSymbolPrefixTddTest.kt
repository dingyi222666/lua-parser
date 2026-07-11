package lsp

import io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider
import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaWorkspaceService
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.WorkspaceSymbolParams
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue


/**
 * TASK-375 — LSP workspace symbol prefix / query corpus.
 *
 * Encodes the contract for `workspace/symbol` (and the direct
 * [LuaLanguageService.workspaceSymbols] / [LuaLanguageService.modernWorkspaceSymbols]
 * helpers) against the existing product filter:
 *
 * ```
 * LuaWorkspaceQueryFacade.workspaceSymbolEntries(query)
 *   normalized = query.trim()
 *   blank → all workspace symbol entries
 *   else  → entry.name.contains(normalized, ignoreCase = true)
 * ```
 *
 * Notes on "prefix":
 * - Clients often type a short prefix into workspace-symbol search; the product
 *   surface today is case-insensitive **substring** match on the symbol name
 *   (not a strict startsWith-only prefix). This corpus locks that behavior
 *   without inventing a new API.
 * - Blank / whitespace-only queries return the full symbol set (trimmed).
 * - Matches cover locals, functions, module-export members, and (with JVM
 *   metadata) provider modules / members — same sources already exercised by
 *   [LspNavigationSymbolsTddTest].
 * - Legacy [SymbolInformation] and modern [WorkspaceSymbol] results stay in
 *   lock-step for name / uri / kind under the same query.
 * - [LuaWorkspaceService.symbol] wraps the service list and returns
 *   `Either.left` (legacy SymbolInformation list). Product reads
 *   `params.query.orEmpty()` so a default-constructed params object whose
 *   query field is still null is treated like blank.
 *
 * Product code is intentionally out of scope (test-only). Verification is
 * review-owned and serial; this worker does not run Gradle.
 */
class LspWorkspaceSymbolPrefixTddTest {

    // -------------------------------------------------------------------------
    // Exact + prefix / substring filtering
    // -------------------------------------------------------------------------

    @Test
    fun workspace_symbols_exact_name_query_hits_open_file_local() {
        val service = service()
        service.open(
            "workspace/ws-prefix-exact.lua",
            """
            local renderTarget = 1
            local function paint()
                return renderTarget
            end
            return paint
            """
        )

        val symbols = service.workspaceSymbols("renderTarget")

        assertTrue(
            symbols.any { it.name == "renderTarget" && it.location.uri == "file:///workspace/ws-prefix-exact.lua" },
            "exact query must return the local; got ${symbols.describe()}"
        )
        assertTrue(
            symbols.none { it.name == "paint" },
            "exact query must not leak unrelated names; got ${symbols.describe()}"
        )
    }

    @Test
    fun workspace_symbols_leading_prefix_matches_name_start() {
        val service = service()
        service.open(
            "workspace/ws-prefix-leading.lua",
            """
            local alphaValue = 1
            local alphabet = "abc"
            local betaValue = 2
            return alphaValue
            """
        )

        val symbols = service.workspaceSymbols("alph")

        assertNamesContain(symbols, "alphaValue", "alphabet")
        assertTrue(
            symbols.none { it.name == "betaValue" },
            "leading prefix 'alph' must not match betaValue; got ${symbols.describe()}"
        )
        assertTrue(
            symbols.all { it.name.contains("alph", ignoreCase = true) },
            "every hit must contain the query substring; got ${symbols.describe()}"
        )
    }

    @Test
    fun workspace_symbols_mid_name_substring_matches_contains_policy() {
        // Product filter is contains(), not startsWith — mid-name hits are intentional.
        val service = service()
        service.open(
            "workspace/ws-prefix-mid.lua",
            """
            local preRender = 1
            local postRender = 2
            local layout = 3
            return preRender
            """
        )

        val symbols = service.workspaceSymbols("Render")

        assertNamesContain(symbols, "preRender", "postRender")
        assertTrue(
            symbols.none { it.name == "layout" },
            "mid-name substring must not match layout; got ${symbols.describe()}"
        )
    }

    @Test
    fun workspace_symbols_query_is_case_insensitive() {
        val service = service()
        service.open(
            "workspace/ws-prefix-case.lua",
            """
            local function drawCanvas()
                return 1
            end
            local DrawHelper = drawCanvas
            return DrawHelper
            """
        )

        val lower = service.workspaceSymbols("draw")
        val upper = service.workspaceSymbols("DRAW")
        val mixed = service.workspaceSymbols("DrAw")

        assertNamesContain(lower, "drawCanvas", "DrawHelper")
        assertNamesContain(upper, "drawCanvas", "DrawHelper")
        assertNamesContain(mixed, "drawCanvas", "DrawHelper")

        assertEquals(
            lower.map { it.name to it.location.uri }.sortedBy { it.first + it.second },
            upper.map { it.name to it.location.uri }.sortedBy { it.first + it.second },
            "case variants of the same query must yield the same name/uri set"
        )
    }

    @Test
    fun workspace_symbols_query_is_trimmed_before_filter() {
        val service = service()
        service.open(
            "workspace/ws-prefix-trim.lua",
            """
            local padding = 0
            local function padLeft()
                return padding
            end
            return padLeft
            """
        )

        val trimmed = service.workspaceSymbols("pad")
        val padded = service.workspaceSymbols("  pad  ")
        val tabs = service.workspaceSymbols("\tpad\n")

        assertEquals(
            trimmed.map { Triple(it.name, it.location.uri, it.kind) }.sortedBy { it.first + it.second },
            padded.map { Triple(it.name, it.location.uri, it.kind) }.sortedBy { it.first + it.second }
        )
        assertEquals(
            trimmed.map { Triple(it.name, it.location.uri, it.kind) }.sortedBy { it.first + it.second },
            tabs.map { Triple(it.name, it.location.uri, it.kind) }.sortedBy { it.first + it.second }
        )
        assertNamesContain(trimmed, "padding", "padLeft")
    }

    // -------------------------------------------------------------------------
    // Blank query + empty results
    // -------------------------------------------------------------------------

    @Test
    fun blank_workspace_symbol_query_returns_all_open_file_symbols() {
        val service = service()
        service.open(
            "workspace/ws-prefix-blank-a.lua",
            """
            local alpha = 1
            local function alphaFn()
                return alpha
            end
            return alphaFn
            """
        )
        service.open(
            "workspace/ws-prefix-blank-b.lua",
            """
            local beta = 2
            return beta
            """
        )

        val blank = service.workspaceSymbols("")
        val whitespace = service.workspaceSymbols("   \t  ")

        assertNamesContain(blank, "alpha", "alphaFn", "beta")
        assertEquals(
            blank.map { Triple(it.name, it.location.uri, it.kind) }.sortedBy { it.first + it.second },
            whitespace.map { Triple(it.name, it.location.uri, it.kind) }.sortedBy { it.first + it.second },
            "whitespace-only query must behave like blank after trim"
        )
        assertTrue(blank.isNotEmpty())
    }

    @Test
    fun workspace_symbols_unknown_query_returns_empty_list_not_error() {
        val service = service()
        service.open(
            "workspace/ws-prefix-empty.lua",
            """
            local present = true
            return present
            """
        )

        val symbols = service.workspaceSymbols("zzz_no_such_symbol_qqq")

        assertTrue(symbols.isEmpty(), "unmatched query must be empty; got ${symbols.describe()}")
    }

    // -------------------------------------------------------------------------
    // Multi-file + kind surface
    // -------------------------------------------------------------------------

    @Test
    fun workspace_symbols_prefix_filters_across_open_files() {
        val service = service()
        service.open(
            "workspace/ws-prefix-file-a.lua",
            """
            local function sharedAlpha()
                return 1
            end
            return sharedAlpha
            """
        )
        service.open(
            "workspace/ws-prefix-file-b.lua",
            """
            local sharedBeta = 2
            local other = 3
            return sharedBeta
            """
        )

        val symbols = service.workspaceSymbols("shared")

        assertTrue(
            symbols.any {
                it.name == "sharedAlpha" && it.location.uri == "file:///workspace/ws-prefix-file-a.lua"
            },
            symbols.describe()
        )
        assertTrue(
            symbols.any {
                it.name == "sharedBeta" && it.location.uri == "file:///workspace/ws-prefix-file-b.lua"
            },
            symbols.describe()
        )
        assertTrue(
            symbols.none { it.name == "other" },
            "unrelated name must drop out of prefix filter; got ${symbols.describe()}"
        )
    }

    @Test
    fun workspace_symbols_function_and_variable_kinds_surface_under_prefix() {
        val service = service()
        service.open(
            "workspace/ws-prefix-kinds.lua",
            """
            local markerValue = 1
            local function markerFn()
                return markerValue
            end
            return markerFn
            """
        )

        val symbols = service.workspaceSymbols("marker")

        val value = symbols.firstOrNull { it.name == "markerValue" }
        val fn = symbols.firstOrNull { it.name == "markerFn" }

        assertTrue(value != null, "expected markerValue; got ${symbols.describe()}")
        assertTrue(fn != null, "expected markerFn; got ${symbols.describe()}")

        // Variable-ish locals map to Variable; functions map to Function.
        assertTrue(
            value!!.kind == SymbolKind.Variable || value.kind == SymbolKind.Field,
            "markerValue kind should be variable-like; got ${value.kind}"
        )
        assertTrue(
            fn!!.kind == SymbolKind.Function || fn.kind == SymbolKind.Method,
            "markerFn kind should be function-like; got ${fn.kind}"
        )
    }

    @Test
    fun workspace_symbols_module_export_members_match_prefix() {
        val service = service()
        service.open(
            "workspace/ws-prefix-exports.lua",
            """
            local M = {}
            function M.renderFrame()
                return 1
            end
            function M.layoutBox()
                return 2
            end
            M.renderFlag = true
            return M
            """
        )

        val renderHits = service.workspaceSymbols("render")
        val layoutHits = service.workspaceSymbols("layout")

        assertTrue(
            renderHits.any { it.name == "renderFrame" || it.name == "renderFlag" },
            "export members starting with / containing render should surface; got ${renderHits.describe()}"
        )
        assertTrue(
            layoutHits.any { it.name == "layoutBox" },
            "export member layoutBox should surface; got ${layoutHits.describe()}"
        )
        assertTrue(
            renderHits.none { it.name == "layoutBox" },
            "render query must not include layoutBox; got ${renderHits.describe()}"
        )
    }

    // -------------------------------------------------------------------------
    // Provider-backed symbols (existing metadata surface; no new APIs)
    // -------------------------------------------------------------------------

    @Test
    fun workspace_symbols_prefix_matches_provider_module_and_member() {
        val service = service(jdkMetadata)
        service.open(
            "workspace/ws-prefix-provider.lua",
            """
            local Arrays = require("Arrays")
            local current = Arrays.asList
            return current
            """
        )

        val modules = service.workspaceSymbols("Arr")
        val members = service.workspaceSymbols("asL")

        assertTrue(
            modules.any {
                it.name == "Arrays" && it.location.uri == "file:///__jvm__/classes/java/util/Arrays.lua"
            },
            "provider module should match prefix Arr; got ${modules.describe()}"
        )
        assertTrue(
            members.any {
                it.name == "asList" && it.location.uri == "file:///__jvm__/classes/java/util/Arrays.lua"
            },
            "provider member should match prefix asL; got ${members.describe()}"
        )
    }

    // -------------------------------------------------------------------------
    // Modern WorkspaceSymbol parity + workspace service wrapper
    // -------------------------------------------------------------------------

    @Test
    fun modern_workspace_symbols_match_legacy_for_same_prefix_query() {
        val service = service()
        service.open(
            "workspace/ws-prefix-modern-a.lua",
            """
            local function alpha()
                return 1
            end
            return alpha
            """
        )
        service.open(
            "workspace/ws-prefix-modern-b.lua",
            """
            local alphabet = "abc"
            local beta = 2
            return alphabet
            """
        )

        val query = "alph"
        val legacy = service.workspaceSymbols(query)
            .map { Triple(it.name, it.location.uri, it.kind) }
            .sortedBy { it.first + it.second }
        val modern = service.modernWorkspaceSymbols(query)
            .map { Triple(it.name, it.location.left.uri, it.kind) }
            .sortedBy { it.first + it.second }

        assertEquals(legacy, modern, "modern and legacy workspace symbols must stay in lock-step")
        assertTrue(legacy.any { it.first == "alpha" })
        assertTrue(legacy.any { it.first == "alphabet" })
        assertTrue(legacy.none { it.first == "beta" })
    }

    @Test
    fun modern_workspace_symbols_blank_query_matches_legacy_all_entries() {
        val service = service()
        service.open(
            "workspace/ws-prefix-modern-blank.lua",
            """
            local one = 1
            local two = 2
            return one
            """
        )

        val legacy = service.workspaceSymbols("")
            .map { Triple(it.name, it.location.uri, it.kind) }
            .sortedBy { it.first + it.second }
        val modern = service.modernWorkspaceSymbols("")
            .map { Triple(it.name, it.location.left.uri, it.kind) }
            .sortedBy { it.first + it.second }

        assertEquals(legacy, modern)
        assertTrue(legacy.map { it.first }.containsAll(listOf("one", "two")))
    }

    @Test
    fun workspace_service_symbol_applies_same_prefix_filter() {
        val service = service()
        val workspace = LuaWorkspaceService(service)
        service.open(
            "workspace/ws-prefix-service.lua",
            """
            local function serviceAlpha()
                return 1
            end
            local serviceBeta = 2
            return serviceAlpha
            """
        )

        val symbols = workspace.symbol(WorkspaceSymbolParams("serviceA")).get().left

        assertTrue(
            symbols.any {
                it.name == "serviceAlpha" && it.location.uri == "file:///workspace/ws-prefix-service.lua"
            },
            symbols.describe()
        )
        assertTrue(
            symbols.none { it.name == "serviceBeta" },
            "serviceA prefix must not match serviceBeta; got ${symbols.describe()}"
        )
    }

    @Test
    fun workspace_service_symbol_blank_query_returns_open_file_symbols() {
        val service = service()
        val workspace = LuaWorkspaceService(service)
        service.open(
            "workspace/ws-prefix-service-blank.lua",
            """
            local blankMarker = true
            return blankMarker
            """
        )

        val symbols = workspace.symbol(WorkspaceSymbolParams("")).get().left

        assertTrue(
            symbols.any {
                it.name == "blankMarker" && it.location.uri == "file:///workspace/ws-prefix-service-blank.lua"
            },
            symbols.describe()
        )
    }

    @Test
    fun workspace_service_symbol_null_query_treated_as_blank_or_empty_safely() {
        // Clients / JSON deserializers may leave query unset. lsp4j's String ctor
        // rejects null via Preconditions, so model "missing query" with the no-arg
        // ctor (field stays null). Product uses params.query.orEmpty() → blank.
        val service = service()
        val workspace = LuaWorkspaceService(service)
        service.open(
            "workspace/ws-prefix-service-null.lua",
            """
            local nullSafe = 1
            return nullSafe
            """
        )

        val params = WorkspaceSymbolParams()
        // Leave query unset (null field). Do not call setQuery(null) — that NPE's.
        val symbols = workspace.symbol(params).get().left
        val blank = service.workspaceSymbols("")

        assertTrue(
            symbols.any { it.name == "nullSafe" },
            "null/unset query must not throw and should behave like blank; got ${symbols.describe()}"
        )
        assertEquals(
            blank.map { Triple(it.name, it.location.uri, it.kind) }.sortedBy { it.first + it.second },
            symbols.map { Triple(it.name, it.location.uri, it.kind) }.sortedBy { it.first + it.second },
            "unset query must match blank-query workspaceSymbols"
        )
    }

    // -------------------------------------------------------------------------
    // Negative / isolation guards
    // -------------------------------------------------------------------------

    @Test
    fun workspace_symbols_do_not_match_on_path_fragment_alone() {
        val service = service()
        service.open(
            "workspace/ws-prefix-container.lua",
            """
            local M = {}
            function M.paint()
                return 1
            end
            return M
            """
        )

        // Path contains "container", but name filter is on symbol name only.
        val byPathFragment = service.workspaceSymbols("container")
        assertTrue(
            byPathFragment.none { it.name == "paint" },
            "path fragment must not drive name filter; got ${byPathFragment.describe()}"
        )
        // paint is still findable by its own name prefix
        val byName = service.workspaceSymbols("pai")
        assertTrue(
            byName.any { it.name == "paint" },
            "name prefix must still hit paint; got ${byName.describe()}"
        )
    }


    @Test
    fun workspace_symbols_partial_prefix_is_stricter_than_blank() {
        // Product filter is case-insensitive contains(), not startsWith.
        // Use a distinctive substring so only one of the open locals matches —
        // query "a" would incorrectly match beta/gamma too under contains().
        val service = service()
        service.open(
            "workspace/ws-prefix-strict.lua",
            """
            local alpha = 1
            local beta = 2
            local gamma = 3
            return alpha
            """
        )

        val all = service.workspaceSymbols("").map { it.name }.toSet()
        val filtered = service.workspaceSymbols("alp").map { it.name }.toSet()

        assertTrue(all.containsAll(listOf("alpha", "beta", "gamma")))
        assertTrue("alpha" in filtered)
        assertFalse("beta" in filtered, "contains filter 'alp' must drop beta; got $filtered")
        assertFalse("gamma" in filtered, "contains filter 'alp' must drop gamma; got $filtered")
        assertTrue(filtered.size < all.size, "non-blank filter must shrink the blank result set")
        assertTrue(
            filtered.all { it.contains("alp", ignoreCase = true) },
            "every hit must contain the query substring; got $filtered"
        )
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

    private fun LuaLanguageService.open(path: String, source: String) {
        didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem("file:///$path", "lua", 1, source.trimIndent())
            )
        )
    }

    private fun assertNamesContain(symbols: List<SymbolInformation>, vararg names: String) {
        val actual = symbols.map { it.name }.toSet()
        names.forEach { name ->
            assertTrue(name in actual, "expected symbol '$name' in ${symbols.describe()}")
        }
    }

    private fun List<SymbolInformation>.describe(): String {
        return joinToString(prefix = "[", postfix = "]") { "${it.name}@${it.location.uri}(${it.kind})" }
    }

    private companion object {
        val jdkMetadata = mapOf(
            JvmClassModuleProvider.CLASSES_METADATA_KEY to listOf(
                "java.util.Arrays",
                "java.util.Locale"
            ).joinToString("\n")
        )
    }
}
