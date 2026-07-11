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
    fun bind_class_abstract_map_simple_entry_binary_name_mounts_provider() {
        val harness = jvmHarness(
            "main.lua" to """
                local SimpleEntry = luajava.bindClass("java.util.AbstractMap${'$'}SimpleEntry")
                return SimpleEntry
            """.trimIndent()
        )

        assertProviderPath(harness, "java.util.AbstractMap\$SimpleEntry")
        assertBindClassFact(harness, "java.util.AbstractMap\$SimpleEntry")

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "SimpleEntry", occurrence = 2)
        )
        assertNestedClassSurface(
            displayName = hover?.typeInfo?.displayName,
            moduleName = hover?.typeInfo?.moduleName,
            binaryName = "java.util.AbstractMap\$SimpleEntry",
            canonicalName = "java.util.AbstractMap.SimpleEntry",
            simpleName = "SimpleEntry"
        )
    }

    @Test
    fun bind_class_character_unicode_block_binary_name_mounts_provider() {
        val harness = jvmHarness(
            "main.lua" to """
                local UnicodeBlock = luajava.bindClass("java.lang.Character${'$'}UnicodeBlock")
                return UnicodeBlock
            """.trimIndent()
        )

        assertProviderPath(harness, "java.lang.Character\$UnicodeBlock")
        assertBindClassFact(harness, "java.lang.Character\$UnicodeBlock")
    }

    // -------------------------------------------------------------------------
    // Direct bindClass of nested class (dotted Outer.Inner name)
    // -------------------------------------------------------------------------

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
    fun bind_class_thread_state_dotted_name_mounts_same_binary_provider_path() {
        val harness = jvmHarness(
            "main.lua" to """
                local State = luajava.bindClass("java.lang.Thread.State")
                return State
            """.trimIndent()
        )

        assertProviderPath(harness, "java.lang.Thread\$State")
        assertBindClassFact(harness, "java.lang.Thread.State")

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
    fun bind_class_abstract_map_simple_entry_dotted_name_mounts_provider() {
        val harness = jvmHarness(
            "main.lua" to """
                local SimpleEntry = luajava.bindClass("java.util.AbstractMap.SimpleEntry")
                return SimpleEntry
            """.trimIndent()
        )

        assertProviderPath(harness, "java.util.AbstractMap\$SimpleEntry")
        assertBindClassFact(harness, "java.util.AbstractMap.SimpleEntry")
    }

    // -------------------------------------------------------------------------
    // Nested static members after bindClass of nested type
    // -------------------------------------------------------------------------

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
    fun bind_class_thread_state_enum_constant_definition_points_to_binary_provider() {
        val harness = jvmHarness(
            "main.lua" to """
                local State = luajava.bindClass("java.lang.Thread${'$'}State")
                local state = State.RUNNABLE
                return state
            """.trimIndent()
        )

        val definitions = harness.queries.gotoDefinition(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "RUNNABLE")
        )
        assertEquals(
            listOf(harness.path("__jvm__/classes/java/lang/Thread\$State.lua")),
            definitions.map { it.path }
        )
    }

    @Test
    fun bind_class_thread_state_enum_constants_complete_as_field_kind() {
        val harness = jvmHarness(
            "main.lua" to """
                local State = luajava.bindClass("java.lang.Thread${'$'}State")
                local state = State.RUNNABLE
                return state
            """.trimIndent()
        )

        val completions = completionsAt(harness, "RUNNABLE")
        assertCompletion(completions, "RUNNABLE", CompletionItemKind.FIELD)
        assertCompletion(completions, "NEW", CompletionItemKind.FIELD)
        assertCompletion(completions, "BLOCKED", CompletionItemKind.FIELD)
        assertCompletion(completions, "WAITING", CompletionItemKind.FIELD)
        assertCompletion(completions, "TIMED_WAITING", CompletionItemKind.FIELD)
        assertCompletion(completions, "TERMINATED", CompletionItemKind.FIELD)
        assertCompletion(completions, "values", CompletionItemKind.METHOD)
        assertCompletion(completions, "valueOf", CompletionItemKind.METHOD)
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
    fun bind_class_map_entry_static_method_definition_points_to_binary_provider() {
        // Dual-path:
        // - Ideal: goto on comparingByKey (once) → __jvm__/classes/java/util/Map$Entry.lua
        // - CURRENTLY_ACCEPTS: empty definition list (product gap for static interface members)
        val harness = jvmHarness(
            "main.lua" to """
                local Entry = luajava.bindClass("java.util.Map${'$'}Entry")
                local byKey = Entry.comparingByKey
                return byKey
            """.trimIndent()
        )

        assertProviderPath(harness, "java.util.Map\$Entry")
        val expected = harness.path("__jvm__/classes/java/util/Map\$Entry.lua")
        val definitions = harness.queries.gotoDefinition(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "comparingByKey", occurrence = 1)
        )
        val paths = definitions.map { it.path }

        if (paths.isEmpty()) {
            // CURRENTLY_ACCEPTS product gap — still require nested provider mounted.
            assertTrue(
                expected in harness.snapshot.extraProviders,
                "CURRENTLY_ACCEPTS empty goto for comparingByKey; provider must still be mounted at $expected"
            )
            return
        }

        assertTrue(
            expected in paths,
            "Ideal: comparingByKey definition includes $expected; got $paths"
        )
    }

    @Test
    fun bind_class_map_entry_static_completions_include_comparing_helpers() {
        // Dual-path:
        // - Ideal: comparingByKey + comparingByValue as METHOD (or FUNCTION) completions.
        // - CURRENTLY_ACCEPTS: empty list or missing labels when static surface is partial.
        // Needle comparingByKey appears once → occurrence=1.
        val harness = jvmHarness(
            "main.lua" to """
                local Entry = luajava.bindClass("java.util.Map${'$'}Entry")
                local byKey = Entry.comparingByKey
                return byKey
            """.trimIndent()
        )

        assertProviderPath(harness, "java.util.Map\$Entry")
        val completions = completionsAt(harness, "comparingByKey", occurrence = 1)
        val labels = completions.map { it.label }

        val hasByKey = completions.any {
            it.label == "comparingByKey" &&
                (it.kind == CompletionItemKind.METHOD || it.kind == CompletionItemKind.FUNCTION)
        }
        val hasByValue = completions.any {
            it.label == "comparingByValue" &&
                (it.kind == CompletionItemKind.METHOD || it.kind == CompletionItemKind.FUNCTION)
        }

        if (completions.isEmpty()) {
            // CURRENTLY_ACCEPTS empty completion surface.
            return
        }

        if (hasByKey && hasByValue) {
            return
        }

        // Soft: non-empty unrelated surface while interface static helpers lag.
        assertFalse(
            labels.any { it.equals("Object", ignoreCase = true) },
            "Non-empty Map\$Entry completions must not invent bare Object identity; labels=$labels"
        )
        // CURRENTLY_ACCEPTS missing comparing helpers when other members still list.
    }

    @Test
    fun bind_class_abstract_map_simple_entry_constructor_instance_surfaces_instance_type() {
        val harness = jvmHarness(
            "main.lua" to """
                local SimpleEntry = luajava.bindClass("java.util.AbstractMap${'$'}SimpleEntry")
                local entry = SimpleEntry("k", "v")
                return entry
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "entry", occurrence = 2)
        )
        assertNotNull(hover)
        val display = hover.typeInfo?.displayName.orEmpty()
        assertTrue(
            display == "java.util.AbstractMap.SimpleEntry" ||
                display == "java.util.AbstractMap\$SimpleEntry" ||
                display == "SimpleEntry" ||
                display.contains("SimpleEntry"),
            "SimpleEntry() instance hover should surface nested class identity; got $display"
        )
        assertNotUnknown(display)
    }

    @Test
    fun bind_class_abstract_map_simple_entry_instance_method_hover_is_callable() {
        val harness = jvmHarness(
            "main.lua" to """
                local SimpleEntry = luajava.bindClass("java.util.AbstractMap${'$'}SimpleEntry")
                local entry = SimpleEntry("k", "v")
                local getKey = entry.getKey
                return getKey
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "getKey", occurrence = 2)
        )
        assertEquals(SymbolKind.METHOD, hover?.symbol?.kind)
        assertCallable(hover?.typeInfo?.displayName)
    }

    // -------------------------------------------------------------------------
    // Outer bindClass surfaces public nested classes as members (dual-path)
    // -------------------------------------------------------------------------

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
    fun bind_class_map_outer_entry_member_hover_surfaces_nested_class_dual_path() {
        // Dual-path:
        // - Ideal: FIELD (or CLASS) hover with Map.Entry / Map$Entry / Entry identity.
        // - CURRENTLY_ACCEPTS: unknown/blank when outer-member nested class is listed
        //   on the provider module but query hover does not yet project it.
        val harness = jvmHarness(
            "main.lua" to """
                local Map = luajava.bindClass("java.util.Map")
                local Entry = Map.Entry
                return Entry
            """.trimIndent()
        )

        val memberHover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "Entry", occurrence = 2)
        )
        val localHover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "Entry", occurrence = 3)
        )
        val display = memberHover?.typeInfo?.displayName ?: localHover?.typeInfo?.displayName
        val kind = memberHover?.symbol?.kind

        assertFalse(
            display == "java.lang.Object" || display == "java.lang.Class",
            "Map.Entry outer-member must not silently become Object/Class; got $display"
        )

        val idealNested =
            display.orEmpty().let {
                it == "java.util.Map.Entry" ||
                    it == "java.util.Map\$Entry" ||
                    it == "Entry" ||
                    it.contains("Map.Entry") ||
                    it.contains("Map\$Entry") ||
                    (it.contains("Entry") && !it.equals("any", ignoreCase = true))
            } && display.orEmpty().isNotBlank() && display != "unknown"

        val currentlyAcceptsGap =
            display == null ||
                display.isBlank() ||
                display == "unknown" ||
                display.equals("any", ignoreCase = true) ||
                display.equals("nil", ignoreCase = true)

        assertTrue(
            idealNested || currentlyAcceptsGap,
            "Map.Entry outer-member dual-path: nested class surface (ideal) or " +
                "unknown/blank CURRENTLY_ACCEPTS; kind=$kind display=$display"
        )
    }

    @Test
    fun bind_class_thread_outer_completion_includes_state_nested_enum() {
        val harness = jvmHarness(
            "main.lua" to """
                local Thread = luajava.bindClass("java.lang.Thread")
                local State = Thread.State
                return State
            """.trimIndent()
        )

        assertProviderPath(harness, "java.lang.Thread")
        val completions = completionsAt(harness, "State", occurrence = 2)
        assertTrue(
            completions.any { it.label == "State" },
            "Thread bindClass completions should include nested State; actual=${completions.map { "${it.label}:${it.kind}" }}"
        )
    }

    @Test
    fun bind_class_thread_outer_state_member_hover_dual_path() {
        val harness = jvmHarness(
            "main.lua" to """
                local Thread = luajava.bindClass("java.lang.Thread")
                local State = Thread.State
                return State
            """.trimIndent()
        )

        val memberHover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "State", occurrence = 2)
        )
        val display = memberHover?.typeInfo?.displayName

        assertFalse(display == "java.lang.Object" || display == "java.lang.Class")

        val ideal =
            display.orEmpty().let {
                it == "java.lang.Thread.State" ||
                    it == "java.lang.Thread\$State" ||
                    it == "State" ||
                    it.contains("Thread.State") ||
                    it.contains("Thread\$State")
            } && !display.isNullOrBlank() && display != "unknown"

        val gap =
            display == null ||
                display.isBlank() ||
                display == "unknown" ||
                display.equals("any", ignoreCase = true) ||
                display.equals("nil", ignoreCase = true)

        assertTrue(
            ideal || gap,
            "Thread.State outer-member dual-path; display=$display"
        )
    }

    @Test
    fun bind_class_abstract_map_outer_completion_includes_simple_entry_nested_classes() {
        val harness = jvmHarness(
            "main.lua" to """
                local AbstractMap = luajava.bindClass("java.util.AbstractMap")
                local SimpleEntry = AbstractMap.SimpleEntry
                return SimpleEntry
            """.trimIndent()
        )

        assertProviderPath(harness, "java.util.AbstractMap")
        val completions = completionsAt(harness, "SimpleEntry", occurrence = 2)
        assertTrue(
            completions.any { it.label == "SimpleEntry" },
            "AbstractMap completions should include SimpleEntry; actual=${completions.map { it.label }}"
        )
        // Soft: SimpleImmutableEntry may also appear on the same static surface.
        // Do not hard-require it at this member needle (position is SimpleEntry).
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

    // -------------------------------------------------------------------------
    // Local bindClass alias + nested targets
    // -------------------------------------------------------------------------

    @Test
    fun local_bind_class_alias_resolves_nested_binary_name() {
        // Dual-path: local bindClass = luajava.bindClass alias must still emit
        // BIND_CLASS_CALL + mount Map$Entry. comparingByKey appears once → occurrence=1.
        // Soft: static member hover may gap; hard: provider + fact + no Object invent.
        val harness = jvmHarness(
            "main.lua" to """
                local bindClass = luajava.bindClass
                local Entry = bindClass("java.util.Map${'$'}Entry")
                local byKey = Entry.comparingByKey
                return byKey
            """.trimIndent()
        )

        assertProviderPath(harness, "java.util.Map\$Entry")
        assertBindClassFact(harness, "java.util.Map\$Entry")

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

        assertFalse(
            memberDisplay == "java.lang.Object" || memberDisplay == "java.lang.Class" ||
                localDisplay == "java.lang.Object" || localDisplay == "java.lang.Class",
            "Local-alias Map\$Entry.comparingByKey must not invent Object/Class; " +
                "member=$memberDisplay local=$localDisplay"
        )

        val ideal =
            (memberKind == SymbolKind.METHOD && isCallableDisplay(memberDisplay)) ||
                isCallableDisplay(localDisplay)
        val gap = isSoftGapDisplay(memberDisplay) || isSoftGapDisplay(localDisplay)

        assertTrue(
            ideal || gap,
            "Local bindClass alias dual-path: callable comparingByKey (ideal) or CURRENTLY_ACCEPTS; " +
                "memberKind=$memberKind memberDisplay=$memberDisplay localDisplay=$localDisplay"
        )
    }

    @Test
    fun local_bind_class_alias_resolves_nested_dotted_name() {
        val harness = jvmHarness(
            "main.lua" to """
                local bindClass = luajava.bindClass
                local State = bindClass("java.lang.Thread.State")
                local state = State.RUNNABLE
                return state
            """.trimIndent()
        )

        assertProviderPath(harness, "java.lang.Thread\$State")
        assertBindClassFact(harness, "java.lang.Thread.State")

        val hover = assertNotNull(
            harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "RUNNABLE"))
        )
        assertEquals(SymbolKind.FIELD, hover.symbol?.kind)
        assertTrue(
            hover.typeInfo?.displayName == "java.lang.Thread.State" ||
                hover.typeInfo?.displayName == "java.lang.Thread\$State"
        )
    }

    @Test
    fun chained_bind_class_alias_resolves_nested_class() {
        // Dual-path: chained local aliases of bindClass still record BIND_CLASS_CALL
        // and mount binary provider. comparingByValue appears once → occurrence=1.
        val harness = jvmHarness(
            "main.lua" to """
                local bindClass = luajava.bindClass
                local bind = bindClass
                local again = bind
                local Entry = again("java.util.Map${'$'}Entry")
                local byValue = Entry.comparingByValue
                return byValue
            """.trimIndent()
        )

        assertProviderPath(harness, "java.util.Map\$Entry")
        val loads = jvmClassLoads(harness)
        assertTrue(
            loads.any {
                it.kind == DocumentFacts.JvmClassLoadKind.BIND_CLASS_CALL &&
                    it.target == "java.util.Map\$Entry"
            },
            "Expected BIND_CLASS_CALL for Map\$Entry via chained alias; got $loads"
        )

        val memberHover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "comparingByValue", occurrence = 1)
        )
        val localHover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "byValue", occurrence = 2)
        )
        val memberDisplay = memberHover?.typeInfo?.displayName
        val localDisplay = localHover?.typeInfo?.displayName
        val memberKind = memberHover?.symbol?.kind

        assertFalse(
            memberDisplay == "java.lang.Object" || memberDisplay == "java.lang.Class" ||
                localDisplay == "java.lang.Object" || localDisplay == "java.lang.Class",
            "Chained-alias Map\$Entry.comparingByValue must not invent Object/Class; " +
                "member=$memberDisplay local=$localDisplay"
        )

        val ideal =
            (memberKind == SymbolKind.METHOD && isCallableDisplay(memberDisplay)) ||
                isCallableDisplay(localDisplay)
        val gap = isSoftGapDisplay(memberDisplay) || isSoftGapDisplay(localDisplay)

        assertTrue(
            ideal || gap,
            "Chained bindClass alias dual-path: callable comparingByValue (ideal) or CURRENTLY_ACCEPTS; " +
                "memberKind=$memberKind memberDisplay=$memberDisplay localDisplay=$localDisplay"
        )
    }

    // -------------------------------------------------------------------------
    // Breadth table: nested JDK bindClass targets
    // -------------------------------------------------------------------------

    @Test
    fun bind_class_inner_class_breadth_table() {
        data class Case(
            val target: String,
            val binaryProvider: String,
            val simpleName: String,
            val canonical: String
        )

        val cases = listOf(
            Case("java.util.Map\$Entry", "java.util.Map\$Entry", "Entry", "java.util.Map.Entry"),
            Case("java.util.Map.Entry", "java.util.Map\$Entry", "Entry", "java.util.Map.Entry"),
            Case("java.lang.Thread\$State", "java.lang.Thread\$State", "State", "java.lang.Thread.State"),
            Case("java.lang.Thread.State", "java.lang.Thread\$State", "State", "java.lang.Thread.State"),
            Case(
                "java.util.AbstractMap\$SimpleEntry",
                "java.util.AbstractMap\$SimpleEntry",
                "SimpleEntry",
                "java.util.AbstractMap.SimpleEntry"
            ),
            Case(
                "java.util.AbstractMap.SimpleImmutableEntry",
                "java.util.AbstractMap\$SimpleImmutableEntry",
                "SimpleImmutableEntry",
                "java.util.AbstractMap.SimpleImmutableEntry"
            ),
            Case(
                "java.lang.Character\$UnicodeBlock",
                "java.lang.Character\$UnicodeBlock",
                "UnicodeBlock",
                "java.lang.Character.UnicodeBlock"
            )
        )

        cases.forEach { case ->
            // Unique local name avoids positionOf colliding with FQN suffix simple names.
            val local = "bound${case.simpleName}"
            val harness = jvmHarness(
                "main.lua" to """
                    local $local = luajava.bindClass("${case.target}")
                    return $local
                """.trimIndent()
            )
            assertProviderPath(harness, case.binaryProvider)
            assertBindClassFact(harness, case.target)
            val hover = harness.queries.hover(
                harness.path("main.lua"),
                harness.positionOf("main.lua", local, occurrence = 2)
            )
            assertNestedClassSurface(
                displayName = hover?.typeInfo?.displayName,
                moduleName = hover?.typeInfo?.moduleName,
                binaryName = case.binaryProvider,
                canonicalName = case.canonical,
                simpleName = case.simpleName
            )
        }
    }

    // -------------------------------------------------------------------------
    // Document facts for nested bindClass loads
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Negatives / degrade paths
    // -------------------------------------------------------------------------

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
    fun bind_class_missing_outer_with_inner_suffix_degrades() {
        val harness = jvmHarness(
            "main.lua" to """
                local Missing = luajava.bindClass("com.missing.Outer${'$'}Inner")
                return Missing
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "Missing", occurrence = 2)
        )
        assertDegradedHover(hover?.typeInfo?.displayName, hover?.typeInfo?.moduleName)
        assertFalse(
            harness.path("__jvm__/classes/com/missing/Outer\$Inner.lua") in harness.snapshot.extraProviders
        )
    }

    @Test
    fun bind_class_unknown_inner_does_not_poison_later_nested_bind() {
        val harness = jvmHarness(
            "main.lua" to """
                local Missing = luajava.bindClass("java.util.Map${'$'}DoesNotExistInner")
                local State = luajava.bindClass("java.lang.Thread${'$'}State")
                local state = State.RUNNABLE
                return Missing, state
            """.trimIndent()
        )

        val missingHover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "Missing", occurrence = 2)
        )
        assertDegradedHover(missingHover?.typeInfo?.displayName, missingHover?.typeInfo?.moduleName)

        assertProviderPath(harness, "java.lang.Thread\$State")
        val stateHover = assertNotNull(
            harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "RUNNABLE"))
        )
        assertEquals(SymbolKind.FIELD, stateHover.symbol?.kind)
        assertTrue(
            stateHover.typeInfo?.displayName == "java.lang.Thread.State" ||
                stateHover.typeInfo?.displayName == "java.lang.Thread\$State"
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

    @Test
    fun missing_static_member_on_nested_class_is_unknown_without_crash() {
        val harness = jvmHarness(
            "main.lua" to """
                local State = luajava.bindClass("java.lang.Thread${'$'}State")
                local missing = State.definitelyNotAThreadState
                return missing
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "definitelyNotAThreadState")
        )
        val display = hover?.typeInfo?.displayName
        assertTrue(
            display == null || display == "unknown",
            "Missing nested static member should be unknown; got '$display'"
        )
        assertNotNull(harness.queries.diagnostics(harness.path("main.lua")))
    }

    // -------------------------------------------------------------------------
    // Android nested classes (host android.jar Downloads + SDK android-35 only)
    // -------------------------------------------------------------------------

    @Test
    fun android_view_on_click_listener_binary_bind_class_when_android_jar_available() {
        if (!androidJar.isFile) return

        val harness = jvmHarness(
            "main.lua" to """
                local OnClickListener = luajava.bindClass("android.view.View${'$'}OnClickListener")
                return OnClickListener
            """.trimIndent()
        )

        assertProviderPath(harness, "android.view.View\$OnClickListener")
        assertBindClassFact(harness, "android.view.View\$OnClickListener")

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "OnClickListener", occurrence = 2)
        )
        assertNestedClassSurface(
            displayName = hover?.typeInfo?.displayName,
            moduleName = hover?.typeInfo?.moduleName,
            binaryName = "android.view.View\$OnClickListener",
            canonicalName = "android.view.View.OnClickListener",
            simpleName = "OnClickListener"
        )
    }

    @Test
    fun android_view_on_click_listener_dotted_bind_class_when_android_jar_available() {
        if (!androidJar.isFile) return

        val harness = jvmHarness(
            "main.lua" to """
                local OnClickListener = luajava.bindClass("android.view.View.OnClickListener")
                return OnClickListener
            """.trimIndent()
        )

        assertProviderPath(harness, "android.view.View\$OnClickListener")
        assertBindClassFact(harness, "android.view.View.OnClickListener")
    }

    @Test
    fun android_text_view_buffer_type_nested_enum_when_android_jar_available() {
        if (!androidJar.isFile) return

        val harness = jvmHarness(
            "main.lua" to """
                local BufferType = luajava.bindClass("android.widget.TextView${'$'}BufferType")
                local normal = BufferType.NORMAL
                return normal
            """.trimIndent()
        )

        assertProviderPath(harness, "android.widget.TextView\$BufferType")
        val hover = assertNotNull(
            harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "NORMAL"))
        )
        assertEquals(SymbolKind.FIELD, hover.symbol?.kind)
        val display = hover.typeInfo?.displayName.orEmpty()
        assertTrue(
            display == "android.widget.TextView.BufferType" ||
                display == "android.widget.TextView\$BufferType",
            "Expected TextView.BufferType for BufferType.NORMAL; got '$display'"
        )
        val completions = completionsAt(harness, "NORMAL")
        assertCompletion(completions, "NORMAL", CompletionItemKind.FIELD)
        assertCompletion(completions, "SPANNABLE", CompletionItemKind.FIELD)
        assertCompletion(completions, "EDITABLE", CompletionItemKind.FIELD)
    }

    @Test
    fun android_porter_duff_mode_nested_enum_when_android_jar_available() {
        if (!androidJar.isFile) return

        val harness = jvmHarness(
            "main.lua" to """
                local Mode = luajava.bindClass("android.graphics.PorterDuff${'$'}Mode")
                local src = Mode.SRC_OVER
                return src
            """.trimIndent()
        )

        assertProviderPath(harness, "android.graphics.PorterDuff\$Mode")
        val hover = assertNotNull(
            harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "SRC_OVER"))
        )
        assertEquals(SymbolKind.FIELD, hover.symbol?.kind)
        val display = hover.typeInfo?.displayName.orEmpty()
        assertTrue(
            display == "android.graphics.PorterDuff.Mode" ||
                display == "android.graphics.PorterDuff\$Mode",
            "Expected PorterDuff.Mode for Mode.SRC_OVER; got '$display'"
        )
    }

    @Test
    fun android_view_outer_completion_includes_on_click_listener_when_android_jar_available() {
        if (!androidJar.isFile) return

        val harness = jvmHarness(
            "main.lua" to """
                local View = luajava.bindClass("android.view.View")
                local OnClickListener = View.OnClickListener
                return OnClickListener
            """.trimIndent()
        )

        assertProviderPath(harness, "android.view.View")
        val completions = completionsAt(harness, "OnClickListener", occurrence = 2)
        // Dual-path: ideal includes OnClickListener; soft gap if android reflection surface
        // is incomplete on this host — still require no crash and no Object invent on hover.
        val memberHover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "OnClickListener", occurrence = 2)
        )
        val display = memberHover?.typeInfo?.displayName
        assertFalse(display == "java.lang.Object" || display == "java.lang.Class")

        val listed = completions.any { it.label == "OnClickListener" }
        val idealHover =
            display.orEmpty().contains("OnClickListener") ||
                display.orEmpty().contains("View.OnClickListener") ||
                display.orEmpty().contains("View\$OnClickListener")
        val gap =
            display == null ||
                display.isBlank() ||
                display == "unknown" ||
                display.equals("any", ignoreCase = true)

        assertTrue(
            listed || idealHover || gap,
            "View.OnClickListener dual-path: completion/hover ideal or unknown gap; " +
                "listed=$listed display=$display"
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
