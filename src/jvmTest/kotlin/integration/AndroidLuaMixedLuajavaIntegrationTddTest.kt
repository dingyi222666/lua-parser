package integration

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode
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
    fun mixed_require_import_and_new_instance_script_parses_strictly() {
        val chunk = parseStrict(MIXED_REQUIRE_IMPORT_AND_NEW_INSTANCE)
        assertTrue(collectBadNodeNames(chunk).isEmpty())
    }

    @Test
    fun mixed_multi_file_scripts_parse_strictly() {
        for ((path, source) in multiFileCorpus()) {
            val bad = collectBadNodeNames(parseStrict(source))
            assertTrue(bad.isEmpty(), "$path must parse cleanly; bad nodes=$bad")
        }
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
    fun mixed_import_and_bind_class_mounts_both_jvm_providers() {
        val harness = jvmHarness("main.lua" to MIXED_IMPORT_AND_BIND_CLASS)

        assertProviderPath(harness, "java.io.File")
        assertProviderPath(harness, "java.util.Locale")
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
    fun mixed_require_import_and_new_instance_records_import_call_and_new_instance_load() {
        val harness = jvmHarness("main.lua" to MIXED_REQUIRE_IMPORT_AND_NEW_INSTANCE)
        val facts = documentFacts(harness, "main.lua")

        assertTrue(facts.requires.any { it.moduleName == "import" })
        assertTrue(
            facts.jvmClassLoads.any {
                it.kind == DocumentFacts.JvmClassLoadKind.IMPORT_CALL &&
                    it.target == "java.lang.System"
            } || facts.sourceImports.any { it.target == "java.lang.System" },
            "Expected import() fact for java.lang.System; loads=${facts.jvmClassLoads} imports=${facts.sourceImports}"
        )
        assertTrue(
            facts.jvmClassLoads.any {
                it.kind == DocumentFacts.JvmClassLoadKind.NEW_INSTANCE_CALL &&
                    it.target == "java.lang.StringBuilder"
            },
            "Expected newInstance load for StringBuilder; actual=${facts.jvmClassLoads}"
        )
    }

    @Test
    fun mixed_require_import_and_new_instance_query_surfaces_are_modeled() {
        val harness = jvmHarness("main.lua" to MIXED_REQUIRE_IMPORT_AND_NEW_INSTANCE)

        val systemHover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "System", occurrence = 1)
        )
        val builderHover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "builder", occurrence = 2)
        )
        val appendDefinition = harness.queries.gotoDefinition(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "append", occurrence = 2)
        )

        assertEquals("System", systemHover?.typeInfo?.moduleName)
        assertEquals("java.lang.StringBuilder", builderHover?.typeInfo?.displayName)
        assertEquals(
            listOf(harness.path("__jvm__/classes/java/lang/StringBuilder.lua")),
            appendDefinition.map { it.path }
        )
    }

    @Test
    fun mixed_wildcard_import_and_bind_class_exposes_completions_from_both_paths() {
        val harness = jvmHarness("main.lua" to MIXED_WILDCARD_IMPORT_AND_BIND_CLASS)

        val completions = harness.queries.completions(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "current")
        )

        assertCompletion(completions, "File", CompletionItemKind.MODULE)
        assertTrue(
            completions.any { it.label == "Integer" },
            "Expected local Integer (bindClass) in completions; actual: ${completions.map { "${it.label}:${it.kind}" }}"
        )
        assertProviderPath(harness, "java.lang.Integer")
    }

    @Test
    fun multi_file_mixed_import_module_and_luajava_consumer_resolves_across_workspace() {
        val harness = jvmHarness(*multiFileCorpus())

        val importProvider = harness.queries.resolveRequire(harness.path("app.lua"), "import")
        val facts = documentFacts(harness, "app.lua")
        val fileDefinition = harness.queries.gotoDefinition(
            harness.path("app.lua"),
            harness.positionOf("app.lua", "File", occurrence = 2)
        )
        val localeDefinition = harness.queries.gotoDefinition(
            harness.path("app.lua"),
            harness.positionOf("app.lua", "ROOT")
        )

        assertEquals(harness.path("__lua_std__/androlua5.3/import.lua"), importProvider.provider?.path)
        assertTrue(facts.sourceImports.any { it.target == "java.io.File" } ||
            facts.jvmClassLoads.any {
                it.kind == DocumentFacts.JvmClassLoadKind.IMPORT_CALL && it.target == "java.io.File"
            })
        assertTrue(
            facts.jvmClassLoads.any {
                it.kind == DocumentFacts.JvmClassLoadKind.BIND_CLASS_CALL &&
                    it.target == "java.util.Locale"
            }
        )
        assertEquals(listOf(harness.path("__jvm__/classes/java/io/File.lua")), fileDefinition.map { it.path })
        assertEquals(listOf(harness.path("__jvm__/classes/java/util/Locale.lua")), localeDefinition.map { it.path })
        assertTrue(
            harness.snapshot.files.values.all { it.semanticFile != null },
            "Every multi-file corpus entry should receive semantic state."
        )
    }

    @Test
    fun mixed_create_proxy_and_import_records_interface_and_class_loads() {
        val harness = jvmHarness("main.lua" to MIXED_CREATE_PROXY_AND_IMPORT)
        val facts = documentFacts(harness, "main.lua")

        assertTrue(facts.sourceImports.any { it.target == "java.util.Locale" })
        assertTrue(
            facts.jvmClassLoads.any {
                it.kind == DocumentFacts.JvmClassLoadKind.CREATE_PROXY_CALL &&
                    it.target == "java.lang.Runnable"
            }
        )
        assertProviderPath(harness, "java.util.Locale")
        assertProviderPath(harness, "java.lang.Runnable")

        val runHover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "run", occurrence = 2)
        )
        assertEquals(SymbolKind.METHOD, runHover?.symbol?.kind)
        assertTrue(runHover?.typeInfo?.displayName.orEmpty().contains("fun("))
    }

    @Test
    fun mixed_load_lib_and_direct_import_surfaces_static_callable() {
        val harness = jvmHarness("main.lua" to MIXED_LOAD_LIB_AND_IMPORT)

        assertProviderPath(harness, "java.io.File")
        assertProviderPath(harness, "java.lang.System")

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "currentTimeMillis", occurrence = 2)
        )
        assertTrue(
            hover?.typeInfo?.displayName.orEmpty().contains("fun("),
            "Expected loadLib static method callable; got ${hover?.typeInfo?.displayName}"
        )
    }

    /**
     * TASK-140 owns transitive local helper alias resolution
     * (`local bindClass = luajava.bindClass; local bind = bindClass; local again = bind`).
     *
     * This corpus intentionally does **not** require query-level resolution of such chains.
     * Document facts may still record the load when the collector walks aliases; query
     * definition/hover for chained results is treated as unresolved-until-TASK-140.
     */
    @Test
    fun chained_bind_class_alias_marked_unresolved_without_requiring_task_140() {
        val source = """
            import "java.io.File"
            local bindClass = luajava.bindClass
            local bind = bindClass
            local again = bind
            local Locale = again("java.util.Locale")
            local root = Locale.ROOT
            local separator = File.separator
            return root, separator
        """.trimIndent()

        val harness = jvmHarness("main.lua" to source)
        val facts = documentFacts(harness, "main.lua")

        // Hard: direct import still works in the same mixed script.
        assertProviderPath(harness, "java.io.File")
        assertEquals(
            listOf(harness.path("__jvm__/classes/java/io/File.lua")),
            harness.queries.gotoDefinition(
                harness.path("main.lua"),
                harness.positionOf("main.lua", "separator")
            ).map { it.path }
        )

        // Soft: chained bindClass load may appear in document facts (collector lane).
        val chainedLoadRecorded = facts.jvmClassLoads.any {
            it.kind == DocumentFacts.JvmClassLoadKind.BIND_CLASS_CALL &&
                it.target == "java.util.Locale"
        }

        // Soft: query resolution for the chained alias result is TASK-140 territory.
        val localeRootDefinitions = harness.queries.gotoDefinition(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "ROOT")
        )
        val chainedResolved =
            localeRootDefinitions.any { it.path.value.contains("__jvm__/classes/java/util/Locale.lua") }

        if (chainedResolved) {
            // Optional green if TASK-140 already landed — still assert provider path.
            assertEquals(
                listOf(harness.path("__jvm__/classes/java/util/Locale.lua")),
                localeRootDefinitions.map { it.path }
            )
        } else {
            // Explicit unresolved mark (does not fail the suite).
            assertTrue(
                localeRootDefinitions.isEmpty() ||
                    localeRootDefinitions.none { it.path.value.contains("Locale.lua") },
                "TASK-140 unresolved alias chain: expected no Locale provider definition; got $localeRootDefinitions"
            )
            // Document facts may still record the load via collector alias walk (not a hard gate).
            // chainedLoadRecorded=$chainedLoadRecorded is informational for review.
            @Suppress("UNUSED_VARIABLE")
            val reviewNote = "TASK-140 unresolved; factsLoadRecorded=$chainedLoadRecorded"
            assertTrue(reviewNote.isNotEmpty())
        }
    }

    /**
     * Same TASK-140 policy for chained `newInstance` aliases mixed with direct import.
     */
    @Test
    fun chained_new_instance_alias_marked_unresolved_without_requiring_task_140() {
        val source = """
            import "java.util.Locale"
            local newInstance = luajava.newInstance
            local make = newInstance
            local again = make
            local builder = again("java.lang.StringBuilder")
            local append = builder.append
            local root = Locale.ROOT
            return append, root
        """.trimIndent()

        val harness = jvmHarness("main.lua" to source)

        // Hard: direct import surface remains modeled.
        assertProviderPath(harness, "java.util.Locale")
        assertEquals(
            listOf(harness.path("__jvm__/classes/java/util/Locale.lua")),
            harness.queries.gotoDefinition(
                harness.path("main.lua"),
                harness.positionOf("main.lua", "ROOT")
            ).map { it.path }
        )

        val appendHover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "append", occurrence = 2)
        )
        val chainedResolved = appendHover?.symbol?.kind == SymbolKind.METHOD

        if (chainedResolved) {
            assertTrue(appendHover?.typeInfo?.displayName.orEmpty().contains("fun("))
        } else {
            // Explicit unresolved mark for TASK-140 transitive newInstance chain.
            assertTrue(
                appendHover == null ||
                    appendHover.symbol?.kind != SymbolKind.METHOD ||
                    appendHover.typeInfo?.displayName == "unknown",
                "TASK-140 unresolved alias chain: expected non-METHOD/unknown hover for chained newInstance; got $appendHover"
            )
        }
    }

    @Test
    fun mixed_script_semantic_file_state_is_present_for_all_workspace_entries() {
        val harness = jvmHarness(
            "main.lua" to MIXED_IMPORT_AND_BIND_CLASS,
            "other.lua" to MIXED_REQUIRE_IMPORT_AND_NEW_INSTANCE
        )

        assertTrue(harness.snapshot.files.size >= 2)
        assertTrue(
            harness.snapshot.files.values.all { it.semanticFile != null },
            "Expected semantic state for every mixed corpus file."
        )
        assertFalse(
            documentFacts(harness, "main.lua").sourceImports.isEmpty() &&
                documentFacts(harness, "main.lua").jvmClassLoads.isEmpty(),
            "main.lua should expose import or luajava facts."
        )
        assertFalse(
            documentFacts(harness, "other.lua").jvmClassLoads.isEmpty(),
            "other.lua should expose luajava/import class load facts."
        )
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
