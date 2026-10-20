package lsp

import org.eclipse.lsp4j.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LspLexicalBindingRegressionTest {
    @Test
    fun semantic_tokens_keep_parameter_and_shadowing_local_distinct() {
        val service = service()
        val uri = "file:///workspace/shadow-tokens.lua"
        val source = """
            local function outer(value)
              print(value)
              do
                local value = 1
                print(value)
              end
            end
            local value = 2
            print(value)
        """.trimIndent()
        service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "lua", 1, source)))
        val legend = service.initialize(InitializeParams()).capabilities.semanticTokensProvider.legend.tokenTypes
        val data = service.semanticTokensFull(SemanticTokensParams(TextDocumentIdentifier(uri))).data
        val kinds = mutableMapOf<Pair<Int, Int>, String>()
        var line = 0
        var column = 0
        data.chunked(5).forEach { token ->
            line += token[0]
            column = if (token[0] == 0) column + token[1] else token[1]
            kinds[line to column] = legend[token[3]]
        }
        val lines = source.lines()
        assertEquals(SemanticTokenTypes.Parameter, kinds[1 to lines[1].indexOf("value")])
        assertEquals(SemanticTokenTypes.Variable, kinds[4 to lines[4].indexOf("value")])
        assertEquals(SemanticTokenTypes.Variable, kinds[8 to lines[8].indexOf("value")])
        service.close()
    }

    @Test
    fun call_hierarchy_never_guesses_edges_from_a_shadowed_name() {
        val service = service()
        val uri = "file:///workspace/shadow-calls.lua"
        val source = """
            local function target() return 1 end
            local function caller(target)
              return target()
            end
            local function realCaller()
              return target()
            end
        """.trimIndent()
        service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "lua", 1, source)))
        val document = TextDocumentIdentifier(uri)
        val shadowed = service.prepareCallHierarchy(CallHierarchyPrepareParams(document, Position(2, 9)))
        assertTrue(shadowed.isEmpty(), "A parameter call has no binding to the same-named local function.")
        val target = service.prepareCallHierarchy(CallHierarchyPrepareParams(document, Position(0, 15))).single()
        val incoming = service.callHierarchyIncomingCalls(CallHierarchyIncomingCallsParams(target))
        assertEquals(listOf("realCaller"), incoming.map { it.from.name })
        val caller = service.prepareCallHierarchy(CallHierarchyPrepareParams(document, Position(1, 15))).single()
        assertTrue(service.callHierarchyOutgoingCalls(CallHierarchyOutgoingCallsParams(caller)).isEmpty())
        val invalidItem = CallHierarchyItem("target", SymbolKind.Function, uri,
            Range(Position(1, 0), Position(1, 5)), Range(Position(1, 0), Position(1, 5)))
        assertTrue(service.callHierarchyIncomingCalls(CallHierarchyIncomingCallsParams(invalidItem)).isEmpty(),
            "An item without a matching declaration range must not select a same-named function.")
        service.close()
    }
}
