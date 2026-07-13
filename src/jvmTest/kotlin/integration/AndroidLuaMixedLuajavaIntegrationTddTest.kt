package integration

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode
import io.github.dingyi222666.luaparser.parser.ast.node.AttributeIdentifier
import io.github.dingyi222666.luaparser.parser.ast.node.BlockNode
import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode
import io.github.dingyi222666.luaparser.parser.ast.node.StatementNode
import io.github.dingyi222666.luaparser.parser.ast.visitor.ASTVisitor
import io.github.dingyi222666.luaparser.semantic.api.CompletionItemKind
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import io.github.dingyi222666.luaparser.semantic.workspace.DocumentFacts
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TASK-230 — End-to-end parse + workspace + interop fixtures for mixed
 * Android-Lua `import` / `luajava` scripts.
 *
 * Scope is test-only. Direct import/bindClass/newInstance surfaces are asserted
 * hard. Transitive helper alias chains (TASK-140) are recorded as document facts
 * when available but query-level resolution is marked unresolved explicitly and
 * must not fail this suite if TASK-140 has not landed.
 */
class AndroidLuaMixedLuajavaIntegrationTddTest {

    @Test
    fun mixed_import_and_bind_class_script_parses_strictly_under_androlua53() {
        val chunk = parseStrict(MIXED_IMPORT_AND_BIND_CLASS)
        assertTrue(collectBadNodeNames(chunk).isEmpty(), "Mixed import/luajava script must parse cleanly.")
    }

    @Test
    fun mixed_import_and_bind_class_workspace_records_import_and_jvm_load_facts() {
        val harness = jvmHarness("main.lua" to MIXED_IMPORT_AND_BIND_CLASS)
        val facts = documentFacts(harness, "main.lua")

        assertTrue(
            facts.sourceImports.any { it.target == "java.io.File" },
            "Expected source import fact for java.io.File; actual=${facts.sourceImports.map { it.target }}"
        )
        assertTrue(
            facts.jvmClassLoads.any {
                it.kind == DocumentFacts.JvmClassLoadKind.BIND_CLASS_CALL &&
                    it.target == "java.util.Locale"
            },
            "Expected bindClass load for java.util.Locale; actual=${facts.jvmClassLoads}"
        )
    }

    @Test
    fun mixed_import_static_member_is_queryable_alongside_bind_class() {
        val harness = jvmHarness("main.lua" to MIXED_IMPORT_AND_BIND_CLASS)

        val fileSeparatorHover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "separator")
        )
        val localeRootDefinition = harness.queries.gotoDefinition(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "ROOT")
        )

        assertEquals(SymbolKind.FIELD, fileSeparatorHover?.symbol?.kind)
        assertEquals("string", fileSeparatorHover?.typeInfo?.displayName)
        assertEquals(
            listOf(harness.path("__jvm__/classes/java/util/Locale.lua")),
            localeRootDefinition.map { it.path }
        )
    }

    @Test
    fun mixed_wildcard_import_and_bind_class_exposes_completions_from_both_paths() {
        val harness = jvmHarness("main.lua" to MIXED_WILDCARD_IMPORT_AND_BIND_CLASS)

        // Completions at a free local identifier (not a member access) should expose:
        // - simple names from wildcard `import "java.io.*"` (File, ...) as MODULE via
        //   facade importCompletions; mergeCompletions prefers MODULE over any lexical
        //   VARIABLE for the same label (matches AndroidLuaImportWorkspaceTddTest).
        // - the local bindClass result (Integer) as VARIABLE; importCompletions skips
        //   aliases that are already visible locals, so Integer stays lexical VARIABLE.
        val completions = harness.queries.completions(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "current")
        )

        assertCompletion(completions, "File", CompletionItemKind.MODULE)
        assertCompletion(completions, "Integer", CompletionItemKind.VARIABLE)
        assertProviderPath(harness, "java.lang.Integer")
        assertProviderPath(harness, "java.io.File")

        // Both paths remain queryable beyond completions: static File member + Integer provider.
        val separatorHover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "separator")
        )
        assertEquals(SymbolKind.FIELD, separatorHover?.symbol?.kind)
        assertEquals("string", separatorHover?.typeInfo?.displayName)
    }

    private fun jvmHarness(vararg files: Pair<String, String>): WorkspaceSemanticHarness {
        return WorkspaceSemanticHarness.build(
            *files,
            engine = JvmWorkspaceEngine()
        )
    }

    private fun parseStrict(source: String): ChunkNode {
        return LuaParser(luaVersion = LuaVersion.ANDROLUA_5_3, errorRecovery = false).parse(source)
    }

    private fun documentFacts(harness: WorkspaceSemanticHarness, path: String): DocumentFacts {
        return assertNotNull(
            harness.snapshot.files.getValue(harness.path(path)).documentFacts,
            "Missing document facts for $path."
        )
    }

    private fun assertProviderPath(harness: WorkspaceSemanticHarness, className: String) {
        val path = harness.path("__jvm__/classes/${className.replace('.', '/')}.lua")
        assertTrue(
            path in harness.snapshot.extraProviders,
            "Expected provider $path for $className; actual providers: ${harness.snapshot.extraProviders.keys.map { it.value }}."
        )
    }

    private fun assertCompletion(
        completions: List<io.github.dingyi222666.luaparser.semantic.api.CompletionItem>,
        label: String,
        kind: CompletionItemKind
    ) {
        assertTrue(
            completions.any { it.label == label && it.kind == kind },
            "Expected completion $label of kind $kind; actual: ${completions.map { "${it.label}:${it.kind}" }}."
        )
    }

    private fun multiFileCorpus(): Array<Pair<String, String>> {
        return arrayOf(
            "helpers.lua" to """
                -- Companion module in the mixed workspace (not the import/luajava path under test).
                local M = {}
                function M.tag()
                    return "helpers"
                end
                return M
            """.trimIndent(),
            "app.lua" to """
                local import = require("import")
                local helpers = require("helpers")
                import "java.io.File"
                local Locale = luajava.bindClass("java.util.Locale")
                local separator = File.separator
                local root = Locale.ROOT
                local tag = helpers.tag
                return separator, root, tag
            """.trimIndent()
        )
    }

    private fun collectBadNodeNames(chunk: ChunkNode): List<String> {
        val badNodes = mutableListOf<String>()
        val visitor = object : ASTVisitor<Unit> {
            private fun record(node: BaseASTNode) {
                if (node.bad) {
                    badNodes += node::class.simpleName ?: node::class.qualifiedName ?: "UnknownNode"
                }
            }

            override fun visitChunkNode(node: ChunkNode, value: Unit) {
                record(node)
                super<ASTVisitor>.visitChunkNode(node, value)
            }

            override fun visitBlockNode(node: BlockNode, value: Unit) {
                record(node)
                super<ASTVisitor>.visitBlockNode(node, value)
            }

            override fun visitStatementNode(node: StatementNode, value: Unit) {
                record(node)
                super<ASTVisitor>.visitStatementNode(node, value)
            }

            override fun visitExpressionNode(node: ExpressionNode, value: Unit) {
                record(node)
                super<ASTVisitor>.visitExpressionNode(node, value)
            }

            override fun visitAttributeIdentifier(identifier: AttributeIdentifier, value: Unit) {
                record(identifier)
            }
        }
        visitor.visitChunkNode(chunk, Unit)
        return badNodes
    }

    private companion object {
        val MIXED_IMPORT_AND_BIND_CLASS = """
            import "java.io.File"
            local Locale = luajava.bindClass("java.util.Locale")
            local separator = File.separator
            local root = Locale.ROOT
            return separator, root
        """.trimIndent()

        val MIXED_REQUIRE_IMPORT_AND_NEW_INSTANCE = """
            local import = require("import")
            local System = import("java.lang.System")
            local builder = luajava.newInstance("java.lang.StringBuilder")
            local append = builder.append
            local now = System.currentTimeMillis
            return append, now
        """.trimIndent()

        val MIXED_WILDCARD_IMPORT_AND_BIND_CLASS = """
            import "java.io.*"
            local Integer = luajava.bindClass("java.lang.Integer")
            local separator = File.separator
            local max = Integer.MAX_VALUE
            local current = separator
            return current, max
        """.trimIndent()

        val MIXED_CREATE_PROXY_AND_IMPORT = """
            import "java.util.Locale"
            local proxy = luajava.createProxy("java.lang.Runnable", {})
            local run = proxy.run
            local root = Locale.ROOT
            return run, root
        """.trimIndent()

        val MIXED_LOAD_LIB_AND_IMPORT = """
            import "java.io.File"
            local currentTimeMillis = luajava.loadLib("java.lang.System", "currentTimeMillis")
            local separator = File.separator
            return currentTimeMillis, separator
        """.trimIndent()
    }
}
