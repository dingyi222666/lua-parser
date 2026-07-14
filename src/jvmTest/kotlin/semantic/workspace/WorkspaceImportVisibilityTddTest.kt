package semantic.workspace

import io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.api.CompletionItemKind
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorkspaceImportVisibilityTddTest {
    @Test
    fun source_level_import_does_not_leak_simple_name_into_sibling_file() {
        val harness = jvmHarness(
            "imports.lua" to """
                import "java.math.BigInteger"
                local current = BigInteger.TEN
                return current
            """.trimIndent(),
            "consumer.lua" to """
                local current = BigInteger.TEN
                return current
            """.trimIndent()
        )

        val importedDefinition = harness.queries.gotoDefinition(
            harness.path("imports.lua"),
            harness.positionOf("imports.lua", "BigInteger", occurrence = 2)
        )
        val leakedDefinition = harness.queries.gotoDefinition(
            harness.path("consumer.lua"),
            harness.positionOf("consumer.lua", "BigInteger")
        )

        assertEquals(listOf(harness.path("__jvm__/classes/java/math/BigInteger.lua")), importedDefinition.map { it.path })
        assertTrue(
            leakedDefinition.none { it.path == harness.path("__jvm__/classes/java/math/BigInteger.lua") },
            "Source-level Android-Lua import activation must stay scoped to the file that calls import."
        )
    }

    @Test
    fun source_level_lua_import_creates_path_scoped_global_from_last_module_segment() {
        val harness = jvmHarness(
            "cc/aa/index.lua" to "return { value = \"lua module\" }",
            "imports.lua" to """
                import "cc.aa"
                local current = aa.value
                return current
            """.trimIndent(),
            "consumer.lua" to """
                local current = aa.value
                return current
            """.trimIndent()
        )

        val importedDefinition = harness.queries.gotoDefinition(
            harness.path("imports.lua"),
            harness.positionOf("imports.lua", "aa", occurrence = 2)
        )
        val leakedDefinition = harness.queries.gotoDefinition(
            harness.path("consumer.lua"),
            harness.positionOf("consumer.lua", "aa")
        )
        val hover = harness.queries.hover(
            harness.path("imports.lua"),
            harness.positionOf("imports.lua", "current", occurrence = 2)
        )

        assertEquals(listOf(harness.path("cc/aa/index.lua")), importedDefinition.map { it.path })
        assertEquals("\"lua module\"", hover?.typeInfo?.displayName)
        assertEquals(emptyList(), harness.queries.diagnostics(harness.path("imports.lua")))
        assertTrue(
            leakedDefinition.none { it.path == harness.path("cc/aa/index.lua") },
            "Lua import globals must stay scoped to the file containing the import call."
        )
    }

    @Test
    fun imported_class_self_return_keeps_ast_declared_methods_for_chained_completion() {
        val harness = jvmHarness(
            "model/AppListStream.lua" to """
                ---@class AppListMode
                ---@field GETALLAPP integer

                ---@class AppListStream
                ---@field MODE AppListMode
                ---@type AppListStream
                local t = {
                    MODE = { GETALLAPP = 1 },
                }

                ---@param self AppListStream
                ---@param mode integer
                ---@return AppListStream
                function t:mode(mode)
                    return self
                end

                ---@param self AppListStream
                ---@return table[]
                function t:build()
                    return {}
                end

                return t
            """.trimIndent(),
            "main.lua" to """
                import "model.AppListStream"
                local data = AppListStream
                    :mode(AppListStream.MODE.GETALLAPP)
                    :build()
                return data
            """.trimIndent()
        )

        val completions = harness.queries.completions(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "build")
        )
        val buildCompletion = assertNotNull(
            completions.singleOrNull { it.label == "build" },
            "Expected build after AppListStream:mode(...); actual=${completions.map { "${it.label}:${it.kind}" }}"
        )
        assertEquals(CompletionItemKind.METHOD, buildCompletion.kind)

        val hover = assertNotNull(
            harness.queries.hover(
                harness.path("main.lua"),
                harness.positionOf("main.lua", "build")
            )
        )
        assertEquals(SymbolKind.METHOD, hover.symbol?.kind)
    }

    @Test
    fun lua_import_and_require_apply_provider_globals_only_to_the_consumer_file() {
        val harness = jvmHarness(
            "mods/util.lua" to """
                AppUtil = {}

                ---@param packageName string
                ---@return string
                function AppUtil.getAppApk(packageName)
                    return packageName
                end

                ---@param text string
                ---@return string
                function globalHelper(text)
                    return text
                end
            """.trimIndent(),
            "import-consumer.lua" to """
                import "mods.util"
                local apk = AppUtil.getAppApk("demo")
                local echoed = globalHelper("import")
                return apk, echoed
            """.trimIndent(),
            "require-consumer.lua" to """
                require "mods.util"
                local apk = AppUtil.getAppApk("demo")
                local echoed = globalHelper("require")
                return apk, echoed
            """.trimIndent(),
            "sibling.lua" to """
                local app = AppUtil
                local helper = globalHelper
                return app, helper
            """.trimIndent()
        )

        listOf("import-consumer.lua", "require-consumer.lua").forEach { path ->
            val appCompletions = harness.queries.completions(
                harness.path(path),
                harness.positionOf(path, "AppUtil")
            )
            val helperCompletions = harness.queries.completions(
                harness.path(path),
                harness.positionOf(path, "globalHelper")
            )
            assertEquals(CompletionItemKind.MODULE, appCompletions.single { it.label == "AppUtil" }.kind)
            assertEquals(CompletionItemKind.FUNCTION, helperCompletions.single { it.label == "globalHelper" }.kind)

            val apkHover = assertNotNull(
                harness.queries.hover(harness.path(path), harness.positionOf(path, "apk", occurrence = 2))
            )
            val echoedHover = assertNotNull(
                harness.queries.hover(harness.path(path), harness.positionOf(path, "echoed", occurrence = 2))
            )
            assertEquals("string", apkHover.typeInfo?.displayName)
            assertEquals("string", echoedHover.typeInfo?.displayName)

            assertEquals(
                listOf(harness.path("mods/util.lua")),
                harness.queries.gotoDefinition(harness.path(path), harness.positionOf(path, "globalHelper"))
                    .map { it.path }
            )
        }

        val siblingCompletions = harness.queries.completions(
            harness.path("sibling.lua"),
            harness.positionOf("sibling.lua", "AppUtil")
        ).map { it.label }
        assertTrue("AppUtil" !in siblingCompletions)
        assertTrue("globalHelper" !in siblingCompletions)
    }

    @Test
    fun lua_import_and_require_apply_provider_extensions_to_existing_globals_per_file() {
        val harness = jvmHarness(
            "mods/extensions.lua" to """
                table.addObserver = function(old, func)
                    func(old)
                    return old
                end
            """.trimIndent(),
            "import-consumer.lua" to """
                import "mods.extensions"
                local observed = table.addObserver({}, function() end)
                return observed
            """.trimIndent(),
            "require-consumer.lua" to """
                require "mods.extensions"
                local observed = table.addObserver({}, function() end)
                return observed
            """.trimIndent(),
            "sibling.lua" to """
                local missing = table.addObserver
                return missing
            """.trimIndent()
        )

        val declarationHover = assertNotNull(
            harness.queries.hover(
                harness.path("mods/extensions.lua"),
                harness.positionOf("mods/extensions.lua", "addObserver")
            )
        )
        assertTrue(
            declarationHover.typeInfo?.displayName?.startsWith("fun(") == true,
            "Assigned function member hover must expose its callable type; actual=${declarationHover.typeInfo}"
        )

        listOf("import-consumer.lua", "require-consumer.lua").forEach { path ->
            val completions = harness.queries.completions(
                harness.path(path),
                harness.positionOf(path, "addObserver")
            )
            val completion = assertNotNull(
                completions.singleOrNull { it.label == "addObserver" },
                "Expected imported table extension in $path; actual=${completions.map { it.label }}"
            )
            assertEquals(CompletionItemKind.METHOD, completion.kind)

            val hover = assertNotNull(
                harness.queries.hover(harness.path(path), harness.positionOf(path, "addObserver"))
            )
            assertTrue(
                hover.typeInfo?.displayName?.startsWith("fun(") == true,
                "Imported table extension hover must remain callable; actual=${hover.typeInfo}"
            )
        }

        val siblingCompletions = harness.queries.completions(
            harness.path("sibling.lua"),
            harness.positionOf("sibling.lua", "addObserver")
        )
        assertTrue(
            siblingCompletions.none { it.label == "addObserver" },
            "A provider's global table extension must not leak into files that do not import it."
        )
    }

    @Test
    fun require_import_alias_activation_does_not_leak_simple_name_into_sibling_file() {
        val harness = jvmHarness(
            "imports.lua" to """
                local import = require("import")
                import("java.math.BigInteger")
                local current = BigInteger.TEN
                return current
            """.trimIndent(),
            "consumer.lua" to """
                local current = BigInteger.TEN
                return current
            """.trimIndent()
        )

        val resolvedImportModule = harness.queries.resolveRequire(harness.path("imports.lua"), "import")
        val importedDefinition = harness.queries.gotoDefinition(
            harness.path("imports.lua"),
            harness.positionOf("imports.lua", "BigInteger", occurrence = 2)
        )
        val leakedDefinition = harness.queries.gotoDefinition(
            harness.path("consumer.lua"),
            harness.positionOf("consumer.lua", "BigInteger")
        )

        assertEquals(harness.path("__lua_std__/androlua5.3/import.lua"), resolvedImportModule.provider?.path)
        assertEquals(listOf(harness.path("__jvm__/classes/java/math/BigInteger.lua")), importedDefinition.map { it.path })
        assertTrue(
            leakedDefinition.none { it.path == harness.path("__jvm__/classes/java/math/BigInteger.lua") },
            "`require(\"import\")` activates Android-Lua import calls only for the source file that performs them."
        )
    }

    @Test
    fun configured_androlua_import_remains_visible_across_workspace_files() {
        val harness = jvmHarness(
            "one.lua" to """
                local current = BigInteger.TEN
                return current
            """.trimIndent(),
            "two.lua" to """
                local current = BigInteger.ONE
                return current
            """.trimIndent(),
            metadata = mapOf(
                JvmClassModuleProvider.IMPORTS_METADATA_KEY to "BigInteger",
                JvmWorkspaceConfiguration.IMPORT_PREFIXES_METADATA_KEY to "java.math"
            )
        )

        val oneDefinition = harness.queries.gotoDefinition(
            harness.path("one.lua"),
            harness.positionOf("one.lua", "BigInteger")
        )
        val twoDefinition = harness.queries.gotoDefinition(
            harness.path("two.lua"),
            harness.positionOf("two.lua", "BigInteger")
        )

        assertEquals(listOf(harness.path("__jvm__/classes/java/math/BigInteger.lua")), oneDefinition.map { it.path })
        assertEquals(listOf(harness.path("__jvm__/classes/java/math/BigInteger.lua")), twoDefinition.map { it.path })
    }

    @Test
    fun configured_androlua_wildcard_import_remains_visible_across_workspace_files() {
        val harness = jvmHarness(
            "one.lua" to """
                local current = BigInteger.TEN
                return current
            """.trimIndent(),
            "two.lua" to """
                local current = BigDecimal.ONE
                return current
            """.trimIndent(),
            metadata = mapOf(
                JvmClassModuleProvider.IMPORTS_METADATA_KEY to "java.math.*"
            )
        )

        val oneDefinition = harness.queries.gotoDefinition(
            harness.path("one.lua"),
            harness.positionOf("one.lua", "BigInteger")
        )
        val twoDefinition = harness.queries.gotoDefinition(
            harness.path("two.lua"),
            harness.positionOf("two.lua", "BigDecimal")
        )

        assertEquals(harness.path("__jvm__/packages/java/math.lua"), harness.queries.lookupModule("java.math").provider?.path)
        assertEquals(listOf(harness.path("__jvm__/classes/java/math/BigInteger.lua")), oneDefinition.map { it.path })
        assertEquals(listOf(harness.path("__jvm__/classes/java/math/BigDecimal.lua")), twoDefinition.map { it.path })
    }

    @Test
    fun source_level_wildcard_import_does_not_leak_simple_names_into_sibling_file() {
        val harness = jvmHarness(
            "imports.lua" to """
                import "java.math.*"
                local current = BigInteger.TEN
                return current
            """.trimIndent(),
            "consumer.lua" to """
                local current = BigDecimal.ONE
                return current
            """.trimIndent()
        )

        val importedDefinition = harness.queries.gotoDefinition(
            harness.path("imports.lua"),
            harness.positionOf("imports.lua", "BigInteger")
        )
        val leakedDefinition = harness.queries.gotoDefinition(
            harness.path("consumer.lua"),
            harness.positionOf("consumer.lua", "BigDecimal")
        )

        assertEquals(listOf(harness.path("__jvm__/classes/java/math/BigInteger.lua")), importedDefinition.map { it.path })
        assertTrue(
            leakedDefinition.none { it.path == harness.path("__jvm__/classes/java/math/BigDecimal.lua") },
            "Source-level Android-Lua wildcard imports activate simple names only in the file that imports them."
        )
    }

    @Test
    fun configured_androlua_wildcard_import_resolves_luajava_targets_across_workspace_files() {
        val harness = jvmHarness(
            "one.lua" to """
                local current = luajava.bindClass("BigInteger")
                return current
            """.trimIndent(),
            "two.lua" to """
                local current = luajava.bindClass("BigDecimal")
                return current
            """.trimIndent(),
            metadata = mapOf(
                JvmClassModuleProvider.IMPORTS_METADATA_KEY to "java.math.*"
            )
        )

        assertEquals(emptyList(), harness.queries.diagnostics(harness.path("one.lua")))
        assertEquals(emptyList(), harness.queries.diagnostics(harness.path("two.lua")))
    }

    private fun jvmHarness(
        vararg files: Pair<String, String>,
        metadata: Map<String, String> = emptyMap()
    ): WorkspaceSemanticHarness {
        return WorkspaceSemanticHarness.build(
            *files,
            metadata = metadata,
            engine = JvmWorkspaceEngine()
        )
    }
}
