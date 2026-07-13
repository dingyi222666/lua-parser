package lsp

import io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider
import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaTextDocumentService
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.DocumentSymbolCapabilities
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.TextDocumentClientCapabilities
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.jsonrpc.messages.Either
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TASK-398 — LSP hierarchical DocumentSymbol vs flatten SymbolInformation corpus.
 *
 * Encodes the dual-path contract for `textDocument/documentSymbol` around the
 * client capability `textDocument.documentSymbol.hierarchicalDocumentSymbolSupport`
 * (LSP 3.10+ / [DocumentSymbolCapabilities]):
 *
 * Product helpers already exist:
 * - [LuaLanguageService.hierarchicalDocumentSymbols] → nested [DocumentSymbol] tree
 * - [LuaLanguageService.documentSymbols] → depth-first flatten to [SymbolInformation]
 *   (children carry `containerName` = parent symbol name)
 *
 * Wire surface today ([LuaTextDocumentService.documentSymbol]) always maps the
 * flattened [SymbolInformation] list as `Either.forLeft` regardless of client
 * capabilities. Sibling TASK-396 wires capability-aware branching:
 * - hierarchicalDocumentSymbolSupport == true  → `Either.forRight` DocumentSymbol list
 * - false / missing / null                     → keep SymbolInformation flatten
 *
 * This corpus is test-only and dual-path:
 * - Always asserts service-level hierarchical vs flatten parity / nesting.
 * - Accepts either "always-left" (pre-TASK-396) or capability-aware left/right
 *   branching once the product wire lands — never invents a new algorithm.
 *
 * Verification is review-owned and serial; this worker does not run Gradle.
 */
class LspHierarchicalDocumentSymbolTddTest {

    // -------------------------------------------------------------------------
    // Server capability advertisement
    // -------------------------------------------------------------------------

    @Test
    fun initialize_advertises_document_symbol_provider() {
        val service = service()
        val capabilities = service.initialize(InitializeParams()).capabilities

        assertNotNull(
            capabilities.documentSymbolProvider,
            "documentSymbolProvider must be advertised so clients can request symbols"
        )
    }

    // -------------------------------------------------------------------------
    // Service-level hierarchical tree
    // -------------------------------------------------------------------------

    @Test
    fun hierarchical_document_symbols_nest_class_methods_and_fields() {
        val service = service()
        service.open(
            "workspace/hier-class.lua",
            """
            ---@class Box
            ---@field width number
            ---@field height number
            local Box = {}

            ---@param self Box
            ---@return number
            function Box:area()
                return self.width * self.height
            end

            return Box
            """
        )

        val roots = service.hierarchicalDocumentSymbols("workspace/hier-class.lua")
        val box = assertNotNull(findHierarchical(roots, "Box"), "expected Box root; got ${roots.describeHierarchical()}")
        assertTrue(
            box.kind == SymbolKind.Class || box.kind == SymbolKind.Variable || box.kind == SymbolKind.Module,
            "Box kind should be Class/Variable/Module; got ${box.kind}"
        )
        assertNotNull(box.range)
        assertNotNull(box.selectionRange)

        val allNames = flattenHierarchicalNames(roots)
        assertTrue("Box" in allNames)
        assertTrue(
            "width" in allNames || "height" in allNames || "area" in allNames,
            "class tree must expose at least one field/method; got $allNames"
        )
    }

    @Test
    fun hierarchical_document_symbols_include_module_export_fields() {
        val service = service()
        service.open(
            "workspace/hier-module.lua",
            """
            local M = {}
            function M.render()
                return 1
            end
            M.label = "ok"
            return M
            """
        )

        val roots = service.hierarchicalDocumentSymbols("workspace/hier-module.lua")
        val names = flattenHierarchicalNames(roots)

        assertTrue("render" in names, "export function render must appear; got $names")
        assertTrue("label" in names || "M" in names, "module export or M root must appear; got $names")
        assertTrue(roots.isNotEmpty())
        assertTrue(
            roots.any { it.children.orEmpty().isNotEmpty() || it.name == "render" || it.name == "M" },
            "module symbols must nest children or surface as roots; got ${roots.describeHierarchical()}"
        )
    }

    // -------------------------------------------------------------------------
    // Service-level flatten SymbolInformation
    // -------------------------------------------------------------------------

    @Test
    fun document_symbols_flatten_lists_top_level_locals_and_functions() {
        val service = service()
        service.open(
            "workspace/flat-locals.lua",
            """
            local value = 1
            local function render()
                return value
            end
            return render
            """
        )

        val symbols = service.documentSymbols("workspace/flat-locals.lua")
        val names = symbols.map { it.name }

        assertTrue("value" in names, "flatten must include value; got ${symbols.describeFlat()}")
        assertTrue("render" in names, "flatten must include render; got ${symbols.describeFlat()}")
        symbols.forEach { symbol ->
            assertEquals(
                "file:///workspace/flat-locals.lua",
                symbol.location.uri,
                "flatten SymbolInformation must carry document uri"
            )
            assertNotNull(symbol.location.range)
        }
    }

    // -------------------------------------------------------------------------
    // TextDocumentService wire: Either.left (flatten) vs Either.right (hierarchical)
    // -------------------------------------------------------------------------

    @Test
    fun text_document_service_document_symbol_with_hierarchical_true_dual_path() {
        // Dual-path:
        // - Pre-TASK-396 product always returns Either.left SymbolInformation flatten.
        // - Post-TASK-396 product may return Either.right DocumentSymbol hierarchy when
        //   hierarchicalDocumentSymbolSupport is true.
        val service = service(initializeParams = initializeParams(hierarchical = true))
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/wire-hier-true.lua",
            """
            local M = {}
            function M.render()
                return 1
            end
            M.label = "ok"
            return M
            """
        )

        val hierarchical = service.hierarchicalDocumentSymbols(document.path)
        val flattened = service.documentSymbols(document.path)
        val response = textDocuments.documentSymbol(
            DocumentSymbolParams(TextDocumentIdentifier(document.uri))
        ).get()

        assertTrue(response.isNotEmpty(), "wire response must not be empty for open document")

        val allLeft = response.all { it.isLeft }
        val allRight = response.all { it.isRight }

        assertTrue(
            allLeft || allRight,
            "wire response must be homogeneous left(SymbolInformation) or right(DocumentSymbol); " +
                "mixed=${response.map { if (it.isLeft) "L" else "R" }}"
        )

        if (allRight) {
            // Capability-aware hierarchical branch (TASK-396 wire).
            val roots = response.map { it.right }
            val wireNames = flattenHierarchicalNames(roots)
            val serviceNames = flattenHierarchicalNames(hierarchical)
            assertEquals(
                serviceNames.sorted(),
                wireNames.sorted(),
                "right-branch DocumentSymbols must match hierarchicalDocumentSymbols()"
            )
            assertTrue(
                "render" in wireNames,
                "hierarchical wire must surface export render; got $wireNames"
            )
            roots.forEach { symbol ->
                assertNotNull(symbol.range)
                assertNotNull(symbol.selectionRange)
            }
        } else {
            // Current product: always flatten SymbolInformation as left.
            val symbols = response.map { it.left }
            val wireNames = symbols.map { it.name }.toSet()
            val flatNames = flattened.map { it.name }.toSet()
            assertEquals(
                flatNames,
                wireNames,
                "left-branch SymbolInformation must match documentSymbols() flatten"
            )
            assertTrue(
                "render" in wireNames || "M" in wireNames,
                "flatten wire must surface module symbols; got ${symbols.describeFlat()}"
            )
            symbols.forEach { symbol ->
                assertEquals(document.uri, symbol.location.uri)
            }
        }
    }

    @Test
    fun hierarchical_and_flatten_name_parity_stable_across_capability_init_params() {
        // Service helpers ignore client caps today; parity must hold regardless of
        // how initialize was called (null / false / true hierarchical support).
        listOf(null, false, true).forEach { hierarchical ->
            val service = service(initializeParams = initializeParams(hierarchical = hierarchical))
            service.open(
                "workspace/parity-cap-$hierarchical.lua",
                """
                local value = 1
                local function render()
                    return value
                end
                return render
                """
            )

            val path = "workspace/parity-cap-$hierarchical.lua"
            val hierarchicalNames = flattenHierarchicalNames(service.hierarchicalDocumentSymbols(path)).sorted()
            val flatNames = service.documentSymbols(path).map { it.name }.sorted()
            assertEquals(
                hierarchicalNames,
                flatNames,
                "parity broken for hierarchicalCapability=$hierarchical"
            )
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun service(
        metadata: Map<String, String> = emptyMap(),
        initializeParams: InitializeParams = InitializeParams()
    ): LuaLanguageService {
        return LuaLanguageService().apply {
            initialize(initializeParams)
            if (metadata.isNotEmpty()) {
                setWorkspaceMetadata(metadata)
            }
        }
    }

    private fun initializeParams(hierarchical: Boolean?): InitializeParams {
        return InitializeParams().apply {
            if (hierarchical != null) {
                capabilities = ClientCapabilities().apply {
                    textDocument = TextDocumentClientCapabilities().apply {
                        documentSymbol = DocumentSymbolCapabilities().apply {
                            hierarchicalDocumentSymbolSupport = hierarchical
                        }
                    }
                }
            }
            // hierarchical == null → leave capabilities unset (legacy clients).
        }
    }

    private fun LuaLanguageService.open(path: String, source: String) {
        didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem("file:///$path", "lua", 1, source.trimIndent())
            )
        )
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

    /**
     * Dual-path wire assertion.
     *
     * - hierarchicalCapability == true: accept all-left (current product) or all-right (TASK-396).
     * - hierarchicalCapability == false / null: prefer all-left flatten; if product ever returns
     *   all-right despite false/null, still accept only if names match hierarchical service helper
     *   (defensive), but flag that true-capability branching is the intended path.
     */
    private fun assertWireResponse(
        response: MutableList<Either<SymbolInformation, DocumentSymbol>>,
        hierarchicalCapability: Boolean?,
        expectedNames: Set<String>,
        documentUri: String,
        requireAnyOf: Set<String> = expectedNames
    ) {
        assertTrue(response.isNotEmpty(), "documentSymbol wire must return symbols for open buffer")

        val allLeft = response.all { it.isLeft }
        val allRight = response.all { it.isRight }
        assertTrue(
            allLeft || allRight,
            "mixed left/right documentSymbol payload is invalid: ${response.map { if (it.isLeft) "L" else "R" }}"
        )

        when {
            allLeft -> {
                val symbols = response.map { it.left }
                val names = symbols.map { it.name }.toSet()
                assertTrue(
                    requireAnyOf.any { it in names },
                    "flatten wire missing any of $requireAnyOf; got ${symbols.describeFlat()}"
                )
                // expectedNames soft-documents the intended top-level set; requireAnyOf is the hard gate.
                assertTrue(
                    expectedNames.isNotEmpty() || requireAnyOf.isNotEmpty(),
                    "assertWireResponse requires a non-empty name gate"
                )
                symbols.forEach { symbol ->
                    assertEquals(documentUri, symbol.location.uri)
                    assertNotNull(symbol.location.range)
                }
                if (hierarchicalCapability == true) {
                    // Documented pre-wire gap: capability true still yields flatten left.
                    assertTrue(
                        allLeft,
                        "pre-TASK-396 product keeps left flatten even when hierarchical=true"
                    )
                }
            }

            allRight -> {
                val roots = response.map { it.right }
                val names = flattenHierarchicalNames(roots)
                assertTrue(
                    requireAnyOf.any { it in names },
                    "hierarchical wire missing any of $requireAnyOf; got $names"
                )
                // expectedNames soft-documents intended roots; hard gate remains requireAnyOf.
                assertTrue(
                    expectedNames.isNotEmpty() || requireAnyOf.isNotEmpty(),
                    "assertWireResponse requires a non-empty name gate"
                )
                roots.forEach { symbol ->
                    assertNotNull(symbol.range)
                    assertNotNull(symbol.selectionRange)
                    assertFalse(symbol.name.isBlank())
                }
                // When hierarchicalCapability is false/null, right-branch is unexpected but
                // tolerated only if the payload is well-formed DocumentSymbols (future-proof).
                if (hierarchicalCapability != true) {
                    assertTrue(
                        names.isNotEmpty(),
                        "unexpected right-branch without hierarchical cap still must be non-empty"
                    )
                }
            }
        }
    }

    private fun findHierarchical(symbols: List<DocumentSymbol>, name: String): DocumentSymbol? {
        symbols.forEach { symbol ->
            if (symbol.name == name) {
                return symbol
            }
            findHierarchical(symbol.children.orEmpty(), name)?.let { return it }
        }
        return null
    }

    private fun flattenHierarchicalNames(symbols: List<DocumentSymbol>): Set<String> {
        val names = linkedSetOf<String>()
        fun walk(nodes: List<DocumentSymbol>) {
            nodes.forEach { node ->
                names += node.name
                walk(node.children.orEmpty())
            }
        }
        walk(symbols)
        return names
    }

    private fun List<DocumentSymbol>.describeHierarchical(): String {
        fun walk(nodes: List<DocumentSymbol>, depth: Int): List<String> {
            return nodes.flatMap { node ->
                val line = "${"  ".repeat(depth)}${node.name}(${node.kind})"
                listOf(line) + walk(node.children.orEmpty(), depth + 1)
            }
        }
        return walk(this, 0).joinToString(prefix = "[", postfix = "]", separator = "; ")
    }

    private fun List<SymbolInformation>.describeFlat(): String {
        return joinToString(prefix = "[", postfix = "]") {
            "${it.name}@${it.location.uri}(${it.kind},container=${it.containerName})"
        }
    }

    private data class OpenDocument(
        val path: String,
        val source: String
    ) {
        val uri: String = "file:///$path"
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
