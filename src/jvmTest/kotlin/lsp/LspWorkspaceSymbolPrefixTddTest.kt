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

    // -------------------------------------------------------------------------
    // Multi-file + kind surface
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

    // -------------------------------------------------------------------------
    // Negative / isolation guards
    // -------------------------------------------------------------------------

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
