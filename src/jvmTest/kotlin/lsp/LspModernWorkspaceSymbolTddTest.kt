package lsp

import io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider
import io.github.dingyi222666.luaparser.lsp.LuaLanguageServer
import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaWorkspaceService
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.SymbolCapabilities
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.WorkspaceClientCapabilities
import org.eclipse.lsp4j.WorkspaceSymbol
import org.eclipse.lsp4j.WorkspaceSymbolOptions
import org.eclipse.lsp4j.WorkspaceSymbolParams
import org.eclipse.lsp4j.WorkspaceSymbolResolveSupportCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TASK-399 — LSP modern WorkspaceSymbol capability-branch corpus.
 *
 * Encodes the dual-path contract for `workspace/symbol` between:
 * - **Legacy** [SymbolInformation] (`Either.left`) — current [LuaWorkspaceService.symbol]
 *   always returns this branch today.
 * - **Modern** [WorkspaceSymbol] (`Either.right`) — intended when the client advertises
 *   modern workspace-symbol support (typically `workspace.symbol.resolveSupport`) and
 *   the product wire (TASK-397) starts returning [LuaLanguageService.modernWorkspaceSymbols].
 *
 * Product surfaces already available (and locked here without inventing APIs):
 * ```
 * LuaLanguageService.workspaceSymbols(query)       → List<SymbolInformation>
 * LuaLanguageService.modernWorkspaceSymbols(query) → List<WorkspaceSymbol>
 * LuaWorkspaceService.symbol(params)               → Either.left(legacy list) today
 * ServerCapabilities.workspaceSymbolProvider       → Either.right(WorkspaceSymbolOptions)
 * ```
 *
 * Capability-branch policy this corpus documents (matches TASK-397 acceptance):
 * - Client **without** modern resolve/modern WorkspaceSymbol support →
 *   wire response is `Either.left` legacy SymbolInformation (today's behavior).
 * - Client **with** `workspace.symbol.resolveSupport` (and/or future product
 *   capability tracking) → wire response should be `Either.right` modern
 *   WorkspaceSymbol list built from [LuaLanguageService.modernWorkspaceSymbols].
 * - Until TASK-397 lands, the modern wire branch is a **documented gap**: tests
 *   dual-path so today's `Either.left` still passes, while hard-asserting modern
 *   right-branch payloads when product starts returning them.
 * - Direct [LuaLanguageService.modernWorkspaceSymbols] always yields modern payloads
 *   (name / kind / Location-left uri / containerName) in lock-step with legacy
 *   [LuaLanguageService.workspaceSymbols] for the same query.
 *
 * Notes:
 * - Blank / whitespace-only / unset query is treated like blank via
 *   `params.query.orEmpty()` on the workspace service (legacy wire).
 * - Filter remains case-insensitive **contains** on symbol name (product facade).
 * - Provider-backed JVM module/member URIs stay stable under modern payloads.
 * - Test-only; no production edits. Verification is review-owned and serial;
 *   this worker does not run Gradle.
 */
class LspModernWorkspaceSymbolTddTest {

    // -------------------------------------------------------------------------
    // Server capability probe
    // -------------------------------------------------------------------------

    @Test
    fun initialize_advertises_workspace_symbol_provider_options() {
        val service = service()
        val capabilities = service.initialize(InitializeParams()).capabilities

        val provider = assertNotNull(
            capabilities.workspaceSymbolProvider,
            "workspaceSymbolProvider must be advertised"
        )
        assertTrue(
            provider.isRight,
            "product advertises WorkspaceSymbolOptions (Either.right); got $provider"
        )
        val options = assertNotNull(provider.right, "WorkspaceSymbolOptions payload")
        // resolveProvider may stay null/false until workspaceSymbol/resolve ships;
        // the options object itself is the capability advertisement.
        assertTrue(
            options is WorkspaceSymbolOptions,
            "workspaceSymbolProvider.right must be WorkspaceSymbolOptions; got ${options::class}"
        )
    }

    @Test
    fun initialize_accepts_client_modern_resolve_support_without_throw() {
        // Clients that understand WorkspaceSymbol set resolveSupport on
        // workspace.symbol. Product may ignore the field today; initialize must
        // still succeed and keep workspaceSymbolProvider advertised.
        val service = service()
        val result = service.initialize(initializeParamsWithModernResolveSupport())

        assertNotNull(result.capabilities.workspaceSymbolProvider)
        assertTrue(result.capabilities.workspaceSymbolProvider.isRight)
    }

    @Test
    fun initialize_accepts_client_without_modern_resolve_support() {
        val service = service()
        val result = service.initialize(initializeParamsWithoutModernResolveSupport())

        assertNotNull(result.capabilities.workspaceSymbolProvider)
        assertTrue(result.capabilities.workspaceSymbolProvider.isRight)
    }

    @Test
    fun language_server_initialize_advertises_workspace_symbol_provider() {
        val server = LuaLanguageServer()
        val capabilities = server.initialize(InitializeParams()).get().capabilities

        assertNotNull(capabilities.workspaceSymbolProvider)
        assertTrue(
            capabilities.workspaceSymbolProvider.isRight ||
                (capabilities.workspaceSymbolProvider.isLeft &&
                    capabilities.workspaceSymbolProvider.left == true),
            "server must advertise workspace symbols; got ${capabilities.workspaceSymbolProvider}"
        )
    }

    // -------------------------------------------------------------------------
    // Direct modern helper surface (always available; independent of wire branch)
    // -------------------------------------------------------------------------

    @Test
    fun modern_workspace_symbols_return_workspace_symbol_payloads() {
        val service = service()
        service.open(
            "workspace/modern-ws-payload.lua",
            """
            local modernAlpha = 1
            local function modernBeta()
                return modernAlpha
            end
            return modernBeta
            """
        )

        val symbols = service.modernWorkspaceSymbols("modern")

        assertTrue(symbols.isNotEmpty(), "expected modern hits; got ${symbols.describeModern()}")
        symbols.forEach { symbol ->
            assertNotNull(symbol.name)
            assertNotNull(symbol.kind)
            assertNotNull(symbol.location)
            assertTrue(
                symbol.location.isLeft,
                "product maps WorkspaceSymbol.location as Either.left(Location); got ${symbol.location}"
            )
            assertNotNull(symbol.location.left.uri)
            assertNotNull(symbol.location.left.range)
        }
        assertTrue(symbols.any { it.name == "modernAlpha" })
        assertTrue(symbols.any { it.name == "modernBeta" })
    }

    @Test
    fun modern_and_legacy_workspace_symbols_stay_in_lockstep_for_query() {
        val service = service()
        service.open(
            "workspace/modern-ws-lockstep-a.lua",
            """
            local function alphaRender()
                return 1
            end
            return alphaRender
            """
        )
        service.open(
            "workspace/modern-ws-lockstep-b.lua",
            """
            local alphabet = "abc"
            local betaRender = 2
            return alphabet
            """
        )

        val query = "alph"
        val legacy = service.workspaceSymbols(query)
            .map { it.fingerprint() }
            .sorted()
        val modern = service.modernWorkspaceSymbols(query)
            .map { it.fingerprint() }
            .sorted()

        assertEquals(legacy, modern, "modern and legacy fingerprints must match for query='$query'")
        assertTrue(legacy.any { it.startsWith("alphaRender@") })
        assertTrue(legacy.any { it.startsWith("alphabet@") })
        assertTrue(legacy.none { it.startsWith("betaRender@") })
    }

    @Test
    fun modern_workspace_symbols_blank_query_matches_legacy_all_entries() {
        val service = service()
        service.open(
            "workspace/modern-ws-blank.lua",
            """
            local one = 1
            local two = 2
            return one
            """
        )

        val legacy = service.workspaceSymbols("").map { it.fingerprint() }.sorted()
        val modern = service.modernWorkspaceSymbols("").map { it.fingerprint() }.sorted()

        assertEquals(legacy, modern)
        assertTrue(legacy.any { it.startsWith("one@") })
        assertTrue(legacy.any { it.startsWith("two@") })
    }

    @Test
    fun modern_workspace_symbols_unknown_query_is_empty_not_error() {
        val service = service()
        service.open(
            "workspace/modern-ws-empty.lua",
            """
            local present = true
            return present
            """
        )

        val symbols = service.modernWorkspaceSymbols("zzz_no_such_modern_symbol_qqq")
        assertTrue(symbols.isEmpty(), "unmatched modern query must be empty; got ${symbols.describeModern()}")
    }

    @Test
    fun modern_workspace_symbols_preserve_function_and_variable_kinds() {
        val service = service()
        service.open(
            "workspace/modern-ws-kinds.lua",
            """
            local markerValue = 1
            local function markerFn()
                return markerValue
            end
            return markerFn
            """
        )

        val symbols = service.modernWorkspaceSymbols("marker")
        val value = symbols.firstOrNull { it.name == "markerValue" }
        val fn = symbols.firstOrNull { it.name == "markerFn" }

        assertTrue(value != null, "expected markerValue; got ${symbols.describeModern()}")
        assertTrue(fn != null, "expected markerFn; got ${symbols.describeModern()}")
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
    fun modern_workspace_symbols_include_module_export_members() {
        val service = service()
        service.open(
            "workspace/modern-ws-exports.lua",
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

        val renderHits = service.modernWorkspaceSymbols("render")
        val layoutHits = service.modernWorkspaceSymbols("layout")

        assertTrue(
            renderHits.any { it.name == "renderFrame" || it.name == "renderFlag" },
            "export members containing render should surface; got ${renderHits.describeModern()}"
        )
        assertTrue(
            layoutHits.any { it.name == "layoutBox" },
            "export member layoutBox should surface; got ${layoutHits.describeModern()}"
        )
        assertTrue(
            renderHits.none { it.name == "layoutBox" },
            "render query must not include layoutBox; got ${renderHits.describeModern()}"
        )
    }

    @Test
    fun modern_workspace_symbols_include_provider_modules_and_members() {
        val service = service(jdkMetadata)
        service.open(
            "workspace/modern-ws-provider.lua",
            """
            local Arrays = require("Arrays")
            local current = Arrays.asList
            return current
            """
        )

        val modules = service.modernWorkspaceSymbols("Arrays")
        val members = service.modernWorkspaceSymbols("asList")

        assertTrue(
            modules.any {
                it.name == "Arrays" &&
                    it.location.isLeft &&
                    it.location.left.uri == "file:///__jvm__/classes/java/util/Arrays.lua"
            },
            "provider module Arrays under modern payload; got ${modules.describeModern()}"
        )
        assertTrue(
            members.any {
                it.name == "asList" &&
                    it.location.isLeft &&
                    it.location.left.uri == "file:///__jvm__/classes/java/util/Arrays.lua"
            },
            "provider member asList under modern payload; got ${members.describeModern()}"
        )
        val arrays = modules.first { it.name == "Arrays" }
        assertEquals(SymbolKind.Module, arrays.kind)
    }

    @Test
    fun modern_workspace_symbols_query_is_case_insensitive_and_trimmed() {
        val service = service()
        service.open(
            "workspace/modern-ws-case-trim.lua",
            """
            local function drawCanvas()
                return 1
            end
            local DrawHelper = drawCanvas
            return DrawHelper
            """
        )

        val lower = service.modernWorkspaceSymbols("draw").map { it.fingerprint() }.sorted()
        val upper = service.modernWorkspaceSymbols("DRAW").map { it.fingerprint() }.sorted()
        val padded = service.modernWorkspaceSymbols("  draw  ").map { it.fingerprint() }.sorted()

        assertEquals(lower, upper)
        assertEquals(lower, padded)
        assertTrue(lower.any { it.startsWith("drawCanvas@") })
        assertTrue(lower.any { it.startsWith("DrawHelper@") })
    }

    // -------------------------------------------------------------------------
    // Wire branch: LuaWorkspaceService.symbol Either.left vs Either.right
    // -------------------------------------------------------------------------

    @Test
    fun workspace_service_symbol_without_modern_client_caps_returns_legacy_left_today() {
        // Default / non-modern client: product always returns Either.left today.
        // TASK-397 keeps this path when resolveSupport is absent.
        val service = service()
        val workspace = LuaWorkspaceService(service)
        service.open(
            "workspace/modern-ws-wire-legacy.lua",
            """
            local function legacyWireAlpha()
                return 1
            end
            local legacyWireBeta = 2
            return legacyWireAlpha
            """
        )

        val either = workspace.symbol(WorkspaceSymbolParams("legacyWire")).get()

        assertTrue(
            either.isLeft,
            "without modern client resolveSupport, wire must stay Either.left (legacy); " +
                "got isRight=${either.isRight}"
        )
        val symbols = either.left
        assertTrue(
            symbols.any {
                it.name == "legacyWireAlpha" &&
                    it.location.uri == "file:///workspace/modern-ws-wire-legacy.lua"
            },
            symbols.describeLegacy()
        )
        // Both names contain the "legacyWire" substring under product contains() filter.
        assertTrue(
            symbols.any { it.name == "legacyWireBeta" },
            "legacyWireBeta also contains the query substring; got ${symbols.describeLegacy()}"
        )
    }

    @Test
    fun workspace_service_symbol_modern_client_caps_dual_path_left_or_right() {
        // Ideal (TASK-397): client resolveSupport → Either.right(modern WorkspaceSymbol list).
        // Today product ignores client caps and always returns Either.left. Dual-path:
        // accept left (documented gap) OR right with modern payloads matching the helper.
        val service = service()
        // Client caps are accepted on initialize even if product does not branch yet.
        service.initialize(initializeParamsWithModernResolveSupport())
        val workspace = LuaWorkspaceService(service)
        service.open(
            "workspace/modern-ws-wire-modern-caps.lua",
            """
            local function modernWireAlpha()
                return 1
            end
            local modernWireBeta = 2
            return modernWireAlpha
            """
        )

        val query = "modernWire"
        val either = workspace.symbol(WorkspaceSymbolParams(query)).get()
        val expectedModern = service.modernWorkspaceSymbols(query)
            .map { it.fingerprint() }
            .sorted()
        val expectedLegacy = service.workspaceSymbols(query)
            .map { it.fingerprint() }
            .sorted()

        assertEquals(expectedLegacy, expectedModern, "helper parity is a hard contract")

        when {
            either.isRight -> {
                val modern = either.right
                assertTrue(modern.isNotEmpty(), "modern right branch should not be empty for hits")
                assertEquals(
                    expectedModern,
                    modern.map { it.fingerprint() }.sorted(),
                    "Either.right payloads must match modernWorkspaceSymbols()"
                )
                modern.forEach { symbol ->
                    assertTrue(
                        symbol.location.isLeft,
                        "modern wire location must be Either.left(Location); got ${symbol.location}"
                    )
                }
            }
            either.isLeft -> {
                // Documented gap until TASK-397 wires the capability branch.
                val legacy = either.left
                assertEquals(
                    expectedLegacy,
                    legacy.map { it.fingerprint() }.sorted(),
                    "Either.left payloads must still match workspaceSymbols()"
                )
            }
            else -> {
                throw AssertionError("workspace/symbol Either must be left or right; got $either")
            }
        }
    }

    @Test
    fun workspace_service_symbol_modern_branch_when_right_preserves_provider_uris() {
        val service = service(jdkMetadata)
        service.initialize(initializeParamsWithModernResolveSupport())
        val workspace = LuaWorkspaceService(service)
        service.open(
            "workspace/modern-ws-wire-provider.lua",
            """
            local Arrays = require("Arrays")
            local current = Arrays.asList
            return current
            """
        )

        val either = workspace.symbol(WorkspaceSymbolParams("asList")).get()
        val modernHelper = service.modernWorkspaceSymbols("asList")
        assertTrue(
            modernHelper.any {
                it.name == "asList" &&
                    it.location.left.uri == "file:///__jvm__/classes/java/util/Arrays.lua"
            },
            modernHelper.describeModern()
        )

        if (either.isRight) {
            assertTrue(
                either.right.any {
                    it.name == "asList" &&
                        it.location.isLeft &&
                        it.location.left.uri == "file:///__jvm__/classes/java/util/Arrays.lua"
                },
                either.right.describeModern()
            )
        } else {
            assertTrue(
                either.left.any {
                    it.name == "asList" &&
                        it.location.uri == "file:///__jvm__/classes/java/util/Arrays.lua"
                },
                either.left.describeLegacy()
            )
        }
    }

    @Test
    fun workspace_service_symbol_blank_query_dual_path_matches_helpers() {
        val service = service()
        val workspace = LuaWorkspaceService(service)
        service.open(
            "workspace/modern-ws-wire-blank.lua",
            """
            local blankModern = true
            return blankModern
            """
        )

        val either = workspace.symbol(WorkspaceSymbolParams("")).get()
        val legacyHelper = service.workspaceSymbols("").map { it.fingerprint() }.sorted()
        val modernHelper = service.modernWorkspaceSymbols("").map { it.fingerprint() }.sorted()
        assertEquals(legacyHelper, modernHelper)

        if (either.isRight) {
            assertEquals(modernHelper, either.right.map { it.fingerprint() }.sorted())
        } else {
            assertTrue(either.isLeft)
            assertEquals(legacyHelper, either.left.map { it.fingerprint() }.sorted())
            assertTrue(either.left.any { it.name == "blankModern" })
        }
    }

    @Test
    fun workspace_service_symbol_null_query_stays_safe_on_legacy_or_modern_branch() {
        // No-arg WorkspaceSymbolParams leaves query null; product uses orEmpty().
        val service = service()
        val workspace = LuaWorkspaceService(service)
        service.open(
            "workspace/modern-ws-wire-null.lua",
            """
            local nullModern = 1
            return nullModern
            """
        )

        val params = WorkspaceSymbolParams()
        val either = workspace.symbol(params).get()
        val blankLegacy = service.workspaceSymbols("").map { it.fingerprint() }.sorted()

        if (either.isRight) {
            assertEquals(blankLegacy, either.right.map { it.fingerprint() }.sorted())
        } else {
            assertTrue(either.isLeft)
            assertEquals(blankLegacy, either.left.map { it.fingerprint() }.sorted())
            assertTrue(either.left.any { it.name == "nullModern" })
        }
    }

    @Test
    fun language_server_workspace_symbol_wire_dual_path_matches_service() {
        val server = LuaLanguageServer()
        server.initialize(InitializeParams()).get()
        val textDocuments = server.textDocumentService
        // Open via language service path used by server internals.
        // LuaLanguageServer owns the same LuaLanguageService; open through workspace/docs
        // by driving didOpen on textDocumentService.
        textDocuments.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(
                    "file:///workspace/modern-ws-server-wire.lua",
                    "lua",
                    1,
                    """
                    local function serverWireAlpha()
                        return 1
                    end
                    return serverWireAlpha
                    """.trimIndent()
                )
            )
        )

        val either = server.workspaceService.symbol(WorkspaceSymbolParams("serverWire")).get()

        when {
            either.isRight -> {
                assertTrue(
                    either.right.any { it.name == "serverWireAlpha" },
                    either.right.describeModern()
                )
            }
            either.isLeft -> {
                assertTrue(
                    either.left.any { it.name == "serverWireAlpha" },
                    either.left.describeLegacy()
                )
            }
            else -> throw AssertionError("unexpected Either: $either")
        }
    }

    // -------------------------------------------------------------------------
    // Negative / isolation guards
    // -------------------------------------------------------------------------

    @Test
    fun modern_workspace_symbols_do_not_match_on_path_fragment_alone() {
        val service = service()
        service.open(
            "workspace/modern-ws-container.lua",
            """
            local M = {}
            function M.paint()
                return 1
            end
            return M
            """
        )

        val byPathFragment = service.modernWorkspaceSymbols("container")
        assertTrue(
            byPathFragment.none { it.name == "paint" },
            "path fragment must not drive name filter; got ${byPathFragment.describeModern()}"
        )
        val byName = service.modernWorkspaceSymbols("pai")
        assertTrue(
            byName.any { it.name == "paint" },
            "name substring must still hit paint; got ${byName.describeModern()}"
        )
    }

    @Test
    fun modern_workspace_symbols_partial_query_is_stricter_than_blank() {
        val service = service()
        service.open(
            "workspace/modern-ws-strict.lua",
            """
            local alpha = 1
            local beta = 2
            local gamma = 3
            return alpha
            """
        )

        val all = service.modernWorkspaceSymbols("").map { it.name }.toSet()
        val filtered = service.modernWorkspaceSymbols("alp").map { it.name }.toSet()

        assertTrue(all.containsAll(listOf("alpha", "beta", "gamma")))
        assertTrue("alpha" in filtered)
        assertFalse("beta" in filtered, "contains filter 'alp' must drop beta; got $filtered")
        assertFalse("gamma" in filtered, "contains filter 'alp' must drop gamma; got $filtered")
        assertTrue(filtered.size < all.size)
    }

    @Test
    fun modern_workspace_symbols_multi_file_prefix_filter() {
        val service = service()
        service.open(
            "workspace/modern-ws-file-a.lua",
            """
            local function sharedAlpha()
                return 1
            end
            return sharedAlpha
            """
        )
        service.open(
            "workspace/modern-ws-file-b.lua",
            """
            local sharedBeta = 2
            local other = 3
            return sharedBeta
            """
        )

        val symbols = service.modernWorkspaceSymbols("shared")
        assertTrue(
            symbols.any {
                it.name == "sharedAlpha" &&
                    it.location.left.uri == "file:///workspace/modern-ws-file-a.lua"
            },
            symbols.describeModern()
        )
        assertTrue(
            symbols.any {
                it.name == "sharedBeta" &&
                    it.location.left.uri == "file:///workspace/modern-ws-file-b.lua"
            },
            symbols.describeModern()
        )
        assertTrue(symbols.none { it.name == "other" }, symbols.describeModern())
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

    private fun initializeParamsWithModernResolveSupport(): InitializeParams {
        return InitializeParams().apply {
            capabilities = ClientCapabilities().apply {
                workspace = WorkspaceClientCapabilities().apply {
                    symbol = SymbolCapabilities().apply {
                        resolveSupport = WorkspaceSymbolResolveSupportCapabilities(
                            listOf("location.range")
                        )
                    }
                }
            }
        }
    }

    private fun initializeParamsWithoutModernResolveSupport(): InitializeParams {
        return InitializeParams().apply {
            capabilities = ClientCapabilities().apply {
                workspace = WorkspaceClientCapabilities().apply {
                    // Explicit symbol caps without resolveSupport → legacy branch.
                    symbol = SymbolCapabilities()
                }
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

    private fun SymbolInformation.fingerprint(): String {
        return "$name@${location.uri}#${kind}"
    }

    private fun WorkspaceSymbol.fingerprint(): String {
        val uri = when {
            location.isLeft -> location.left.uri
            location.isRight -> location.right.uri
            else -> "<no-uri>"
        }
        return "$name@$uri#$kind"
    }

    private fun List<SymbolInformation>.describeLegacy(): String {
        return joinToString(prefix = "[", postfix = "]") {
            "${it.name}@${it.location.uri}(${it.kind})"
        }
    }

    private fun List<WorkspaceSymbol>.describeModern(): String {
        return joinToString(prefix = "[", postfix = "]") { symbol ->
            val uri = when {
                symbol.location.isLeft -> symbol.location.left.uri
                symbol.location.isRight -> symbol.location.right.uri
                else -> "<no-uri>"
            }
            "${symbol.name}@$uri(${symbol.kind})"
        }
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
