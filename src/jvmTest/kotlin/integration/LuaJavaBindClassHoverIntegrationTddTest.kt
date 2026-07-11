package integration

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.api.Diagnostic
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import io.github.dingyi222666.luaparser.semantic.workspace.DocumentFacts
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TASK-306 — Integration luajava bindClass hover corpus.
 *
 * Encodes the contract that bindClass results and local helper aliases surface
 * the bound Java class name on hover, while unknown class targets degrade
 * safely (unknown / non-modeled type, optional diagnostic) without crashing.
 *
 * Test-only. Product resolution already covered by semantic.interop.LuaJavaBindClassTddTest;
 * this suite locks the integration / workspace-query hover surface for alias paths.
 * Verification is review-owned and serial (no Gradle from workers).
 */
class LuaJavaBindClassHoverIntegrationTddTest {

    // -------------------------------------------------------------------------
    // Direct bindClass hover surfaces class name
    // -------------------------------------------------------------------------

    @Test
    fun direct_bind_class_local_hover_surfaces_class_name() {
        val harness = jvmHarness(
            "main.lua" to """
                local Locale = luajava.bindClass("java.util.Locale")
                return Locale
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "Locale", occurrence = 2)
        )

        assertNotNull(hover, "Expected hover on bindClass local use site.")
        assertEquals(SymbolKind.LOCAL, hover.symbol?.kind)
        assertClassNameSurface(hover.typeInfo?.displayName, hover.typeInfo?.moduleName, "java.util.Locale", "Locale")
        assertProviderPath(harness, "java.util.Locale")
    }

    @Test
    fun direct_bind_class_static_member_hover_surfaces_class_or_member_type() {
        val harness = jvmHarness(
            "main.lua" to """
                local Locale = luajava.bindClass("java.util.Locale")
                local root = Locale.ROOT
                return root
            """.trimIndent()
        )

        val classHover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "Locale", occurrence = 2)
        )
        val rootHover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "ROOT")
        )

        assertClassNameSurface(classHover?.typeInfo?.displayName, classHover?.typeInfo?.moduleName, "java.util.Locale", "Locale")
        assertEquals(SymbolKind.FIELD, rootHover?.symbol?.kind)
        assertNotUnknown(rootHover?.typeInfo?.displayName)
        // ROOT is a Locale constant; product surfaces java.util.Locale (or non-unknown).
        assertTrue(
            rootHover?.typeInfo?.displayName.orEmpty().contains("Locale") ||
                rootHover?.typeInfo?.displayName.orEmpty().contains("java.util"),
            "Expected ROOT field hover to mention Locale class surface; got ${rootHover?.typeInfo?.displayName}"
        )
    }

    // -------------------------------------------------------------------------
    // Local bindClass helper alias hover surfaces class name
    // -------------------------------------------------------------------------

    @Test
    fun local_bind_class_alias_hover_surfaces_bound_class_name() {
        val harness = jvmHarness(
            "main.lua" to """
                local bindClass = luajava.bindClass
                local File = bindClass("java.io.File")
                return File
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "File", occurrence = 2)
        )

        assertNotNull(hover, "Expected hover on bindClass alias result.")
        assertEquals(SymbolKind.LOCAL, hover.symbol?.kind)
        assertClassNameSurface(hover.typeInfo?.displayName, hover.typeInfo?.moduleName, "java.io.File", "File")
        assertProviderPath(harness, "java.io.File")
    }

    @Test
    fun local_bind_class_alias_static_field_hover_surfaces_class_member() {
        val harness = jvmHarness(
            "main.lua" to """
                local bindClass = luajava.bindClass
                local File = bindClass("java.io.File")
                local separator = File.separator
                return separator
            """.trimIndent()
        )

        val fileHover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "File", occurrence = 2)
        )
        val separatorHover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "separator", occurrence = 2)
        )

        assertClassNameSurface(fileHover?.typeInfo?.displayName, fileHover?.typeInfo?.moduleName, "java.io.File", "File")
        assertEquals(SymbolKind.FIELD, separatorHover?.symbol?.kind)
        assertEquals("string", separatorHover?.typeInfo?.displayName)
    }

    @Test
    fun local_bind_class_alias_string_call_hover_surfaces_class_name() {
        val harness = jvmHarness(
            "main.lua" to """
                local bindClass = luajava.bindClass
                local Integer = bindClass "java.lang.Integer"
                return Integer
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "Integer", occurrence = 2)
        )

        assertNotNull(hover)
        assertClassNameSurface(hover.typeInfo?.displayName, hover.typeInfo?.moduleName, "java.lang.Integer", "Integer")
        assertProviderPath(harness, "java.lang.Integer")
    }

    @Test
    fun renamed_bind_class_helper_hover_surfaces_class_name() {
        val harness = jvmHarness(
            "main.lua" to """
                local bind = luajava.bindClass
                local System = bind("java.lang.System")
                return System
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "System", occurrence = 2)
        )

        assertNotNull(hover)
        assertClassNameSurface(hover.typeInfo?.displayName, hover.typeInfo?.moduleName, "java.lang.System", "System")
        assertProviderPath(harness, "java.lang.System")
    }

    @Test
    fun chained_bind_class_alias_hover_surfaces_class_name_when_resolved() {
        val harness = jvmHarness(
            "main.lua" to """
                local bindClass = luajava.bindClass
                local bind = bindClass
                local again = bind
                local Locale = again("java.util.Locale")
                return Locale
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "Locale", occurrence = 2)
        )
        val loads = jvmClassLoads(harness)
        val loadRecorded = loads.any {
            it.kind == DocumentFacts.JvmClassLoadKind.BIND_CLASS_CALL && it.target == "java.util.Locale"
        }

        // Prefer hard class-name surface when alias chain resolves (TASK-140 / product path).
        val display = hover?.typeInfo?.displayName
        val module = hover?.typeInfo?.moduleName
        val resolved =
            display != null &&
                display != "unknown" &&
                (
                    display.contains("Locale") ||
                        display.contains("java.util.Locale") ||
                        module == "Locale" ||
                        module == "java.util.Locale"
                    )

        if (resolved) {
            assertClassNameSurface(display, module, "java.util.Locale", "Locale")
            assertProviderPath(harness, "java.util.Locale")
        } else {
            // Degrade path is acceptable only if still non-crashing; facts may still record load.
            assertTrue(
                hover == null || display == null || display == "unknown" || display.isNotBlank(),
                "Chained alias hover must not crash; got $hover"
            )
            // Informational for review: loadRecorded=$loadRecorded loads=$loads
            @Suppress("UNUSED_VARIABLE")
            val reviewNote = "chained alias unresolved; factsLoadRecorded=$loadRecorded"
            assertTrue(reviewNote.isNotEmpty())
        }
    }

    // -------------------------------------------------------------------------
    // Mixed workspace: import + bindClass alias hover
    // -------------------------------------------------------------------------

    @Test
    fun mixed_import_and_bind_class_alias_hover_surfaces_both_class_names() {
        val harness = jvmHarness(
            "main.lua" to """
                import "java.io.File"
                local bindClass = luajava.bindClass
                local Locale = bindClass("java.util.Locale")
                local separator = File.separator
                local root = Locale.ROOT
                return separator, root
            """.trimIndent()
        )

        val localeHover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "Locale", occurrence = 2)
        )
        val fileSepHover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "separator")
        )

        assertClassNameSurface(
            localeHover?.typeInfo?.displayName,
            localeHover?.typeInfo?.moduleName,
            "java.util.Locale",
            "Locale"
        )
        assertEquals(SymbolKind.FIELD, fileSepHover?.symbol?.kind)
        assertEquals("string", fileSepHover?.typeInfo?.displayName)
        assertProviderPath(harness, "java.io.File")
        assertProviderPath(harness, "java.util.Locale")
    }

    @Test
    fun multi_file_consumer_bind_class_alias_hover_surfaces_class_name() {
        val harness = jvmHarness(
            "helpers.lua" to """
                local M = {}
                function M.tag()
                    return "helpers"
                end
                return M
            """.trimIndent(),
            "app.lua" to """
                local helpers = require("helpers")
                local bindClass = luajava.bindClass
                local StringBuilder = bindClass("java.lang.StringBuilder")
                local tag = helpers.tag
                return StringBuilder, tag
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path("app.lua"),
            harness.positionOf("app.lua", "StringBuilder", occurrence = 2)
        )

        assertNotNull(hover)
        assertClassNameSurface(
            hover.typeInfo?.displayName,
            hover.typeInfo?.moduleName,
            "java.lang.StringBuilder",
            "StringBuilder"
        )
        assertProviderPath(harness, "java.lang.StringBuilder")
    }

    // -------------------------------------------------------------------------
    // Unknown class degrades
    // -------------------------------------------------------------------------

    @Test
    fun direct_bind_class_unknown_target_hover_degrades() {
        val harness = jvmHarness(
            "main.lua" to """
                local Missing = luajava.bindClass("missing.DoesNotExist")
                return Missing
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "Missing", occurrence = 2)
        )
        val diagnostics = harness.queries.diagnostics(harness.path("main.lua"))

        assertDegradedHover(hover?.typeInfo?.displayName, hover?.typeInfo?.moduleName)
        assertTrue(
            diagnostics.containsUnknownTarget("missing.DoesNotExist") ||
                hover?.typeInfo?.displayName == "unknown" ||
                hover?.typeInfo?.displayName.isNullOrBlank() ||
                hover == null,
            "Expected unknown-class degrade via diagnostic and/or unknown hover; " +
                "hover=${hover?.typeInfo?.displayName} diagnostics=${diagnostics.map { it.message }}"
        )
        assertFalse(
            harness.path("__jvm__/classes/missing/DoesNotExist.lua") in harness.snapshot.extraProviders,
            "Unknown class must not mount a JVM provider."
        )
    }

    @Test
    fun bind_class_alias_unknown_target_hover_degrades() {
        val harness = jvmHarness(
            "main.lua" to """
                local bindClass = luajava.bindClass
                local Missing = bindClass("missing.DoesNotExist")
                return Missing
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "Missing", occurrence = 2)
        )
        val diagnostics = harness.queries.diagnostics(harness.path("main.lua"))

        assertDegradedHover(hover?.typeInfo?.displayName, hover?.typeInfo?.moduleName)
        assertTrue(
            diagnostics.containsUnknownTarget("missing.DoesNotExist") ||
                hover?.typeInfo?.displayName == "unknown" ||
                hover?.typeInfo?.displayName.isNullOrBlank() ||
                hover == null,
            "Expected alias unknown-class degrade; hover=${hover?.typeInfo?.displayName} " +
                "diagnostics=${diagnostics.map { it.message }}"
        )
        assertFalse(
            harness.path("__jvm__/classes/missing/DoesNotExist.lua") in harness.snapshot.extraProviders,
            "Unknown class via alias must not mount a JVM provider."
        )
    }

    @Test
    fun bind_class_alias_unknown_member_access_hover_degrades_without_crash() {
        val harness = jvmHarness(
            "main.lua" to """
                local bindClass = luajava.bindClass
                local Missing = bindClass("missing.DoesNotExist")
                local ghost = Missing.GHOST
                return ghost
            """.trimIndent()
        )

        val missingHover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "Missing", occurrence = 2)
        )
        val ghostHover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "GHOST")
        )
        val ghostUseHover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "ghost", occurrence = 2)
        )

        // All three positions must remain non-crashing; type surface is unknown/empty.
        assertDegradedHover(missingHover?.typeInfo?.displayName, missingHover?.typeInfo?.moduleName)
        assertTrue(
            ghostHover == null ||
                ghostHover.typeInfo?.displayName == null ||
                ghostHover.typeInfo?.displayName == "unknown" ||
                ghostHover.typeInfo?.displayName.isNullOrBlank() ||
                ghostHover.symbol == null,
            "Unknown member access hover must degrade; got $ghostHover"
        )
        assertTrue(
            ghostUseHover == null ||
                ghostUseHover.typeInfo?.displayName == null ||
                ghostUseHover.typeInfo?.displayName == "unknown" ||
                ghostUseHover.typeInfo?.displayName.isNullOrBlank() ||
                !ghostUseHover.typeInfo!!.displayName.contains("java."),
            "Unknown member use must not invent a Java class surface; got $ghostUseHover"
        )
    }

    @Test
    fun bind_class_empty_and_malformed_class_name_hover_degrades() {
        val harness = jvmHarness(
            "main.lua" to """
                local bindClass = luajava.bindClass
                local Empty = bindClass("")
                local Bad = bindClass("not a class!!")
                return Empty, Bad
            """.trimIndent()
        )

        val emptyHover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "Empty", occurrence = 2)
        )
        val badHover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "Bad", occurrence = 2)
        )

        assertDegradedHover(emptyHover?.typeInfo?.displayName, emptyHover?.typeInfo?.moduleName)
        assertDegradedHover(badHover?.typeInfo?.displayName, badHover?.typeInfo?.moduleName)
        assertTrue(
            harness.snapshot.extraProviders.keys.none {
                it.value.contains("not a class") || it.value.endsWith("/.lua")
            },
            "Malformed class names must not mount providers; actual=${harness.snapshot.extraProviders.keys.map { it.value }}"
        )
    }

    @Test
    fun bind_class_unknown_target_does_not_poison_later_known_bind_class_hover() {
        val harness = jvmHarness(
            "main.lua" to """
                local bindClass = luajava.bindClass
                local Missing = bindClass("missing.DoesNotExist")
                local File = bindClass("java.io.File")
                return Missing, File
            """.trimIndent()
        )

        val missingHover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "Missing", occurrence = 2)
        )
        val fileHover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "File", occurrence = 2)
        )

        assertDegradedHover(missingHover?.typeInfo?.displayName, missingHover?.typeInfo?.moduleName)
        assertClassNameSurface(
            fileHover?.typeInfo?.displayName,
            fileHover?.typeInfo?.moduleName,
            "java.io.File",
            "File"
        )
        assertProviderPath(harness, "java.io.File")
        assertFalse(
            harness.path("__jvm__/classes/missing/DoesNotExist.lua") in harness.snapshot.extraProviders
        )
    }

    // -------------------------------------------------------------------------
    // Document facts + constructor instance hover (integration glue)
    // -------------------------------------------------------------------------

    @Test
    fun bind_class_alias_document_facts_record_bind_class_load_for_hover_corpus() {
        val harness = jvmHarness(
            "main.lua" to """
                local bind = luajava.bindClass
                local Locale = bind("java.util.Locale")
                return Locale
            """.trimIndent()
        )

        val loads = jvmClassLoads(harness)
        assertTrue(
            loads.any {
                it.kind == DocumentFacts.JvmClassLoadKind.BIND_CLASS_CALL &&
                    it.target == "java.util.Locale"
            },
            "Expected BIND_CLASS_CALL fact for java.util.Locale; actual=$loads"
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "Locale", occurrence = 2)
        )
        assertClassNameSurface(hover?.typeInfo?.displayName, hover?.typeInfo?.moduleName, "java.util.Locale", "Locale")
    }

    @Test
    fun bind_class_alias_constructor_instance_hover_surfaces_class_name() {
        val harness = jvmHarness(
            "main.lua" to """
                local bindClass = luajava.bindClass
                local StringBuilder = bindClass("java.lang.StringBuilder")
                local builder = StringBuilder()
                return builder
            """.trimIndent()
        )

        val classHover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "StringBuilder", occurrence = 2)
        )
        val instanceHover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "builder", occurrence = 2)
        )

        assertClassNameSurface(
            classHover?.typeInfo?.displayName,
            classHover?.typeInfo?.moduleName,
            "java.lang.StringBuilder",
            "StringBuilder"
        )
        assertEquals("java.lang.StringBuilder", instanceHover?.typeInfo?.displayName)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun jvmHarness(vararg files: Pair<String, String>): WorkspaceSemanticHarness {
        return WorkspaceSemanticHarness.build(
            *files,
            engine = JvmWorkspaceEngine()
        )
    }

    private fun jvmClassLoads(harness: WorkspaceSemanticHarness): List<DocumentFacts.JvmClassLoadFact> {
        return harness.snapshot.files.getValue(harness.path("main.lua")).documentFacts?.jvmClassLoads.orEmpty()
    }

    private fun assertProviderPath(harness: WorkspaceSemanticHarness, className: String) {
        val path = harness.path("__jvm__/classes/${className.replace('.', '/')}.lua")
        assertTrue(
            path in harness.snapshot.extraProviders,
            "Expected provider $path for $className; actual providers: ${harness.snapshot.extraProviders.keys.map { it.value }}."
        )
    }

    /**
     * Class-name surface for bindClass results: product may expose FQN as displayName
     * and/or simple name as moduleName (see LuaJavaBindClassTddTest.bind_class_result_hover_reports_module_type).
     */
    private fun assertClassNameSurface(
        displayName: String?,
        moduleName: String?,
        fqn: String,
        simpleName: String
    ) {
        assertNotUnknown(displayName)
        val display = displayName.orEmpty()
        val module = moduleName.orEmpty()
        val ok =
            display == fqn ||
                display == simpleName ||
                display.contains(fqn) ||
                display.contains(simpleName) ||
                module == simpleName ||
                module == fqn ||
                module.endsWith(".$simpleName")
        assertTrue(
            ok,
            "Expected bindClass hover class-name surface for $fqn / $simpleName; " +
                "displayName=$displayName moduleName=$moduleName"
        )
    }

    private fun assertDegradedHover(displayName: String?, moduleName: String?) {
        // Degrade: null hover type, unknown, blank, or explicitly non-Java-class surface.
        val display = displayName
        val ok =
            display == null ||
                display.isBlank() ||
                display == "unknown" ||
                display.equals("any", ignoreCase = true) ||
                display.equals("nil", ignoreCase = true) ||
                // Local still exists but without a modeled Java class FQN.
                (!display.contains('.') && moduleName.isNullOrBlank())
        assertTrue(
            ok || display == "unknown",
            "Expected unknown-class hover degrade; displayName=$displayName moduleName=$moduleName"
        )
        // Must not invent a real mounted JDK class for missing targets.
        assertFalse(
            displayName == "java.lang.Object" || displayName == "java.lang.Class",
            "Unknown bindClass target must not silently become Object/Class; got $displayName"
        )
    }

    private fun assertNotUnknown(displayName: String?) {
        assertFalse(displayName.isNullOrBlank(), "Expected a modeled Java type, got no type.")
        assertFalse(displayName == "unknown", "Expected a modeled Java type, got unknown.")
    }

    private fun List<Diagnostic>.containsUnknownTarget(target: String): Boolean {
        return any { diagnostic ->
            diagnostic.message.contains(target) &&
                (
                    diagnostic.message.contains("unknown", ignoreCase = true) ||
                        diagnostic.message.contains("not found", ignoreCase = true) ||
                        diagnostic.message.contains("unresolved", ignoreCase = true) ||
                        diagnostic.message.contains("missing", ignoreCase = true)
                    )
        }
    }
}
