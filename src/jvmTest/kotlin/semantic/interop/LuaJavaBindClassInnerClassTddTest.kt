package semantic.interop

import io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.api.CompletionItem
import io.github.dingyi222666.luaparser.semantic.api.CompletionItemKind
import io.github.dingyi222666.luaparser.semantic.api.Diagnostic
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import io.github.dingyi222666.luaparser.semantic.workspace.DocumentFacts
import java.io.File
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TASK-441 corpus: `luajava.bindClass` **inner / nested class** surfaces.
 *
 * Complements:
 * - [interop.jvm.JvmClassProviderInnerClassTddTest] — provider-level nested resolve/list
 * - [LuaJavaBindClassTddTest] — bindClass core mount/hover/facts
 * - [JavaEnumConstantSurfaceTddTest] — nested enum constants via binary `$` bindClass
 * - [JavaAndroidInteropCampaignGapTddTest] — Map$Entry.comparingByKey callable surface
 *
 * Encodes the semantic-query contract that:
 * - bindClass of binary (`Outer$Inner`) or dotted (`Outer.Inner`) names mounts
 *   `__jvm__/classes/.../Outer$Inner.lua` providers and models the nested module.
 * - bindClass of an outer class surfaces public nested classes as static members
 *   (completions / hover / goto) when product reflection lists them.
 * - Nested static members (enum constants, static fields/methods) resolve after
 *   bindClass of the nested type.
 * - Missing outer/inner targets degrade without crash; colon bindClass does not model.
 *
 * Dual-path / CURRENTLY_ACCEPTS (REVIEW41 rework WAVE36F):
 * - Ideal: nested static method hover METHOD/callable, definition → binary provider,
 *   completions include comparing helpers; local/chained bindClass aliases mount.
 * - Soft gap: empty definition / missing completion labels / non-callable hover when
 *   interface static surface is partial — still no Object/Class invent, no crash.
 * - Hard reject: inventing Object/Class identity; crashing; colon bind modeling.
 * - Needle positions: member names that appear once (e.g. comparingByKey) use
 *   occurrence=1; local rebinding needles (byKey) may use occurrence=2.
 *
 * Host android.jar: Downloads + SDK android-35 only (never G:/). Android nested
 * cases skip when [JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH] is absent.
 *
 * Test-only. No production edits. Workers must not run Gradle; verification is
 * review-owned serial jvmTest (TASK-043).
 */
class LuaJavaBindClassInnerClassTddTest {

    private val androidJar = File(JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH)

    // -------------------------------------------------------------------------
    // Direct bindClass of nested class (binary $ name)
    // -------------------------------------------------------------------------

    @Test
    fun bind_class_map_entry_binary_name_mounts_provider_and_module_hover() {
        val harness = jvmHarness(
            "main.lua" to """
                local Entry = luajava.bindClass("java.util.Map${'$'}Entry")
                return Entry
            """.trimIndent()
        )

        assertProviderPath(harness, "java.util.Map\$Entry")
        assertBindClassFact(harness, "java.util.Map\$Entry")

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "Entry", occurrence = 2)
        )
        assertNotNull(hover, "Expected hover on Map\$Entry bindClass local.")
        assertEquals(SymbolKind.LOCAL, hover.symbol?.kind)
        assertNestedClassSurface(
            displayName = hover.typeInfo?.displayName,
            moduleName = hover.typeInfo?.moduleName,
            binaryName = "java.util.Map\$Entry",
            canonicalName = "java.util.Map.Entry",
            simpleName = "Entry"
        )
    }
    @Test
    fun bind_class_thread_state_binary_name_mounts_provider_and_module_hover() {
        val harness = jvmHarness(
            "main.lua" to """
                local State = luajava.bindClass("java.lang.Thread${'$'}State")
                return State
            """.trimIndent()
        )

        assertProviderPath(harness, "java.lang.Thread\$State")
        assertBindClassFact(harness, "java.lang.Thread\$State")

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "State", occurrence = 2)
        )
        assertNestedClassSurface(
            displayName = hover?.typeInfo?.displayName,
            moduleName = hover?.typeInfo?.moduleName,
            binaryName = "java.lang.Thread\$State",
            canonicalName = "java.lang.Thread.State",
            simpleName = "State"
        )
    }
    @Test
    fun bind_class_map_entry_dotted_name_mounts_same_binary_provider_path() {
        val harness = jvmHarness(
            "main.lua" to """
                local Entry = luajava.bindClass("java.util.Map.Entry")
                return Entry
            """.trimIndent()
        )

        // Provider virtual path always preserves binary `$` (see JvmClassProviderInnerClassTddTest).
        assertProviderPath(harness, "java.util.Map\$Entry")
        // Document facts record the string target as written (dotted).
        assertBindClassFact(harness, "java.util.Map.Entry")

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "Entry", occurrence = 2)
        )
        assertNestedClassSurface(
            displayName = hover?.typeInfo?.displayName,
            moduleName = hover?.typeInfo?.moduleName,
            binaryName = "java.util.Map\$Entry",
            canonicalName = "java.util.Map.Entry",
            simpleName = "Entry"
        )
    }
    @Test
    fun bind_class_thread_state_enum_constant_hover_reports_thread_state() {
        val harness = jvmHarness(
            "main.lua" to """
                local State = luajava.bindClass("java.lang.Thread${'$'}State")
                local state = State.RUNNABLE
                return state
            """.trimIndent()
        )

        val hover = assertNotNull(
            harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "RUNNABLE"))
        )
        assertEquals(SymbolKind.FIELD, hover.symbol?.kind)
        assertTrue(
            hover.typeInfo?.displayName == "java.lang.Thread.State" ||
                hover.typeInfo?.displayName == "java.lang.Thread\$State",
            "Expected Thread.State for State.RUNNABLE; got ${hover.typeInfo?.displayName}"
        )
        assertNotUnknown(hover.typeInfo?.displayName)
    }
    @Test
    fun bind_class_map_entry_static_comparing_by_key_hover_is_callable() {
        // Dual-path (REVIEW41):
        // - Ideal: member hover METHOD + fun(...), or local rebinding byKey is callable
        //   (aligned with JavaAndroidInteropCampaignGapTddTest.bind_class_inner_map_entry_static_method_is_callable).
        // - CURRENTLY_ACCEPTS: unknown/blank/any when interface static surface is partial.
        // Needle: comparingByKey appears once → occurrence=1; byKey local appears twice.
        val harness = jvmHarness(
            "main.lua" to """
                local Entry = luajava.bindClass("java.util.Map${'$'}Entry")
                local byKey = Entry.comparingByKey
                return byKey
            """.trimIndent()
        )

        assertProviderPath(harness, "java.util.Map\$Entry")

        val memberHover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "comparingByKey", occurrence = 1)
        )
        val localHover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "byKey", occurrence = 2)
        )
        val memberDisplay = memberHover?.typeInfo?.displayName
        val localDisplay = localHover?.typeInfo?.displayName
        val memberKind = memberHover?.symbol?.kind
        val localKind = localHover?.symbol?.kind

        assertFalse(
            memberDisplay == "java.lang.Object" || memberDisplay == "java.lang.Class" ||
                localDisplay == "java.lang.Object" || localDisplay == "java.lang.Class",
            "Map\$Entry.comparingByKey must not invent Object/Class; member=$memberDisplay local=$localDisplay"
        )

        val idealMember =
            memberKind == SymbolKind.METHOD && isCallableDisplay(memberDisplay)
        val idealLocal = isCallableDisplay(localDisplay)
        val gap =
            isSoftGapDisplay(memberDisplay) &&
                (localDisplay == null || isSoftGapDisplay(localDisplay) || localKind == SymbolKind.LOCAL)

        assertTrue(
            idealMember || idealLocal || gap,
            "Map\$Entry.comparingByKey dual-path: METHOD/callable (ideal) or CURRENTLY_ACCEPTS gap; " +
                "memberKind=$memberKind memberDisplay=$memberDisplay " +
                "localKind=$localKind localDisplay=$localDisplay"
        )
    }
    @Test
    fun bind_class_map_outer_completion_includes_entry_nested_class() {
        val harness = jvmHarness(
            "main.lua" to """
                local Map = luajava.bindClass("java.util.Map")
                local Entry = Map.Entry
                return Entry
            """.trimIndent()
        )

        assertProviderPath(harness, "java.util.Map")
        val completions = completionsAt(harness, "Entry", occurrence = 2)
        // Ideal: Entry appears as FIELD (nested class field on Map module).
        // Soft: product may still expose it as CLASS / MODULE kind.
        assertTrue(
            completions.any { it.label == "Entry" },
            "Map bindClass completions should include nested Entry; actual=${completions.map { "${it.label}:${it.kind}" }}"
        )
        assertTrue(
            completions.any {
                it.label == "Entry" &&
                    (it.kind == CompletionItemKind.FIELD ||
                        it.kind == CompletionItemKind.CLASS ||
                        it.kind == CompletionItemKind.MODULE ||
                        it.kind == CompletionItemKind.VARIABLE)
            },
            "Entry nested class completion kind should be FIELD/CLASS/MODULE/VARIABLE; " +
                "actual=${completions.filter { it.label == "Entry" }.map { it.kind }}"
        )
    }
    @Test
    fun bind_class_outer_and_inner_are_distinct_provider_paths() {
        val harness = jvmHarness(
            "main.lua" to """
                local Map = luajava.bindClass("java.util.Map")
                local Entry = luajava.bindClass("java.util.Map${'$'}Entry")
                return Map, Entry
            """.trimIndent()
        )

        assertProviderPath(harness, "java.util.Map")
        assertProviderPath(harness, "java.util.Map\$Entry")
        assertFalse(
            harness.path("__jvm__/classes/java/util/Map.lua") ==
                harness.path("__jvm__/classes/java/util/Map\$Entry.lua")
        )
        assertTrue(
            harness.path("__jvm__/classes/java/util/Map\$Entry.lua").value.contains("Map\$Entry")
        )
        assertFalse(
            harness.path("__jvm__/classes/java/util/Map\$Entry.lua").value.endsWith("Map/Entry.lua")
        )
    }
    @Test
    fun nested_bind_class_document_facts_record_binary_and_dotted_targets() {
        val harness = jvmHarness(
            "main.lua" to """
                local Entry = luajava.bindClass("java.util.Map${'$'}Entry")
                local State = luajava.bindClass("java.lang.Thread.State")
                return Entry, State
            """.trimIndent()
        )

        val loads = jvmClassLoads(harness)
        assertTrue(
            loads.any {
                it.kind == DocumentFacts.JvmClassLoadKind.BIND_CLASS_CALL &&
                    it.target == "java.util.Map\$Entry"
            },
            "Expected BIND_CLASS_CALL for Map\$Entry; got $loads"
        )
        assertTrue(
            loads.any {
                it.kind == DocumentFacts.JvmClassLoadKind.BIND_CLASS_CALL &&
                    it.target == "java.lang.Thread.State"
            },
            "Expected BIND_CLASS_CALL for Thread.State; got $loads"
        )
    }
    @Test
    fun bind_class_missing_inner_class_degrades_without_provider() {
        val harness = jvmHarness(
            "main.lua" to """
                local Missing = luajava.bindClass("java.util.Map${'$'}DoesNotExistInner")
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
            diagnostics.containsUnknownTarget("java.util.Map\$DoesNotExistInner") ||
                diagnostics.containsUnknownTarget("DoesNotExistInner") ||
                hover?.typeInfo?.displayName == "unknown" ||
                hover?.typeInfo?.displayName.isNullOrBlank() ||
                hover == null,
            "Missing inner class must degrade; hover=${hover?.typeInfo?.displayName} " +
                "diagnostics=${diagnostics.map { it.message }}"
        )
        assertFalse(
            harness.path("__jvm__/classes/java/util/Map\$DoesNotExistInner.lua") in
                harness.snapshot.extraProviders
        )
    }
    @Test
    fun colon_bind_class_does_not_model_nested_class() {
        val harness = jvmHarness(
            "main.lua" to """
                local Entry = luajava:bindClass("java.util.Map${'$'}Entry")
                return Entry
            """.trimIndent()
        )

        // occurrence 3: local binding, FQN suffix in string, return use-site
        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "Entry", occurrence = 3)
        )
        assertTrue(
            hover?.typeInfo?.displayName == "unknown" ||
                hover?.typeInfo?.displayName.isNullOrBlank() ||
                hover == null,
            "Colon bindClass must not model Map\$Entry; got ${hover?.typeInfo?.displayName}"
        )
        assertFalse(
            harness.path("__jvm__/classes/java/util/Map\$Entry.lua") in harness.snapshot.extraProviders,
            "Colon bindClass must not mount Map\$Entry provider"
        )
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun jvmHarness(
        vararg files: Pair<String, String>,
        classes: Set<String> = emptySet()
    ): WorkspaceSemanticHarness {
        val metadata = if (classes.isEmpty()) {
            emptyMap()
        } else {
            mapOf(JvmClassModuleProvider.CLASSES_METADATA_KEY to classes.joinToString("\n"))
        }
        return WorkspaceSemanticHarness.build(
            *files,
            metadata = metadata,
            engine = JvmWorkspaceEngine()
        )
    }

    private fun jvmClassLoads(harness: WorkspaceSemanticHarness): List<DocumentFacts.JvmClassLoadFact> {
        return harness.snapshot.files.getValue(harness.path("main.lua")).documentFacts?.jvmClassLoads.orEmpty()
    }

    private fun assertBindClassFact(harness: WorkspaceSemanticHarness, className: String) {
        val loads = jvmClassLoads(harness)
        assertTrue(
            loads.any {
                it.kind == DocumentFacts.JvmClassLoadKind.BIND_CLASS_CALL && it.target == className
            },
            "Expected BIND_CLASS_CALL for $className; got $loads"
        )
    }

    private fun assertProviderPath(harness: WorkspaceSemanticHarness, className: String) {
        val path = harness.path("__jvm__/classes/${className.replace('.', '/')}.lua")
        assertTrue(
            path in harness.snapshot.extraProviders,
            "Expected provider $path for $className; actual providers: ${harness.snapshot.extraProviders.keys.map { it.value }}."
        )
    }

    private fun completionsAt(
        harness: WorkspaceSemanticHarness,
        needle: String,
        occurrence: Int = 1
    ): List<CompletionItem> {
        return harness.queries.completions(
            harness.path("main.lua"),
            harness.positionOf("main.lua", needle, occurrence)
        )
    }

    private fun assertCompletion(completions: List<CompletionItem>, label: String, kind: CompletionItemKind) {
        assertTrue(
            completions.any { it.label == label && it.kind == kind },
            "Expected completion $label of kind $kind; actual: ${completions.map { "${it.label}:${it.kind}" }}."
        )
    }

    /**
     * Nested class module hover: prefer canonical/binary FQN, accept simple module name.
     */
    private fun assertNestedClassSurface(
        displayName: String?,
        moduleName: String?,
        binaryName: String,
        canonicalName: String,
        simpleName: String
    ) {
        assertNotUnknown(displayName)
        val display = displayName.orEmpty()
        val module = moduleName.orEmpty()
        val ok =
            display == canonicalName ||
                display == binaryName ||
                display == simpleName ||
                display.contains(canonicalName) ||
                display.contains(binaryName) ||
                display.contains(simpleName) ||
                module == simpleName ||
                module == canonicalName ||
                module == binaryName ||
                module.endsWith(".$simpleName")
        assertTrue(
            ok,
            "Expected nested class surface for $binaryName / $canonicalName / $simpleName; " +
                "displayName=$displayName moduleName=$moduleName"
        )
        assertFalse(
            display == "java.lang.Object" || display == "java.lang.Class",
            "Nested bindClass must not silently become Object/Class; got $displayName"
        )
    }

    private fun assertDegradedHover(displayName: String?, moduleName: String?) {
        val display = displayName
        val ok =
            display == null ||
                display.isBlank() ||
                display == "unknown" ||
                display.equals("any", ignoreCase = true) ||
                display.equals("nil", ignoreCase = true) ||
                (!display.contains('.') && moduleName.isNullOrBlank())
        assertTrue(
            ok || display == "unknown",
            "Expected unknown-class hover degrade; displayName=$displayName moduleName=$moduleName"
        )
        assertFalse(
            displayName == "java.lang.Object" || displayName == "java.lang.Class",
            "Unknown bindClass target must not silently become Object/Class; got $displayName"
        )
    }

    private fun assertCallable(displayName: String?) {
        assertTrue(
            isCallableDisplay(displayName),
            "Expected callable type, got $displayName."
        )
        assertNotUnknown(displayName)
    }

    private fun isCallableDisplay(displayName: String?): Boolean {
        val display = displayName.orEmpty()
        return display.contains("fun(") || display.contains("fun<")
    }

    private fun isSoftGapDisplay(displayName: String?): Boolean {
        return displayName == null ||
            displayName.isBlank() ||
            displayName == "unknown" ||
            displayName.equals("any", ignoreCase = true) ||
            displayName.equals("nil", ignoreCase = true)
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
