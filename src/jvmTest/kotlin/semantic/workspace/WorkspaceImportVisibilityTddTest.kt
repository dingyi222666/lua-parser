package semantic.workspace

import io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
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
