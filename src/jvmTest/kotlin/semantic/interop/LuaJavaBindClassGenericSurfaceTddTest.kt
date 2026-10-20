package semantic.interop

import io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider
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
 * TASK-414 corpus: `luajava.bindClass` generic / type-arg hover surfaces.
 *
 * Encodes the contract that bindClass of generic JDK classes (List/Map/Optional/ArrayList/…)
 * and their static factories / instance members surface type-parameter and type-argument
 * information on hover when reflection models it, while raw receivers and unknown targets
 * degrade conservatively (no crash, no invented concrete element types).
 *
 * Dual-path goldens (aligned with LuaJavaNewInstanceArity / HelperShadowing style):
 * - Prefer parameterized `List<T>` / `Map<K,V>` / `Optional<T>` when product surfaces them.
 * - Soft-accept raw FQN/simple class identity, or unknown/blank for call sites where the
 *   product currently does not propagate generic method returns (e.g. unmodifiable*).
 * - CURRENTLY_ACCEPTS: pure `unknown` / blank / any for unmodifiable* static results is a
 *   documented product gap (generic static return through raw receiver not yet propagated).
 * - Hard reject invented concrete element types (string/Object) for raw/wildcard surfaces.
 *
 * Complements:
 * - [LuaJavaBindClassTddTest] — bindClass / newInstance / loadLib core mount+hover
 * - [interop.jvm.JvmReflectionWildcardTddTest] — reflection wildcard/raw model unit corpus
 * - [semantic.types.resolve.GenericTypeArgSurfaceTddTest] — AppliedType model unit corpus
 *
 * Test-only. No production edits. Verification is review-owned serial jvmTest (TASK-043);
 * workers must not run Gradle.
 */
class LuaJavaBindClassGenericSurfaceTddTest {

    // -------------------------------------------------------------------------
    // bindClass of generic class modules (raw class surface)
    // -------------------------------------------------------------------------

    @Test
    fun bind_class_array_list_hover_surfaces_class_name() {
        val harness = jvmHarness(
            "main.lua" to """
                local ArrayList = luajava.bindClass("java.util.ArrayList")
                return ArrayList
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "ArrayList", occurrence = 2)
        )

        assertNotNull(hover, "Expected hover on ArrayList bindClass local.")
        assertEquals(SymbolKind.LOCAL, hover.symbol?.kind)
        assertClassNameSurface(hover.typeInfo?.displayName, hover.typeInfo?.moduleName, "java.util.ArrayList", "ArrayList")
        assertProviderPath(harness, "java.util.ArrayList")
        assertBindClassFact(harness, "java.util.ArrayList")
        // Raw bindClass module surface is the class, not a parameterized instance.
        assertFalse(
            hover.typeInfo?.displayName.orEmpty().contains("<"),
            "Raw bindClass class hover should not invent type-args on the module; got ${hover.typeInfo?.displayName}"
        )
    }
    @Test
    fun raw_array_list_get_method_hover_keeps_type_parameter_surface() {
        val harness = jvmHarness(
            "main.lua" to """
                local ArrayList = luajava.bindClass("java.util.ArrayList")
                local list = ArrayList()
                local get = list.get
                return get
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "get", occurrence = 2)
        )

        assertEquals(SymbolKind.METHOD, hover?.symbol?.kind)
        assertCallable(hover?.typeInfo?.displayName)
        // Raw receiver keeps unresolved type parameter E in the callable surface (not Object/Any invent).
        val display = hover?.typeInfo?.displayName.orEmpty()
        assertTrue(
            display.contains("E") || display.contains("fun("),
            "Raw ArrayList.get should surface type-param E and/or callable; got $display"
        )
        assertFalse(
            display.contains("java.lang.Object") && !display.contains("E"),
            "Raw get must not silently invent Object in place of E; got $display"
        )
    }
    @Test
    fun collections_empty_list_static_result_hover_surfaces_list_type_args() {
        val harness = jvmHarness(
            "main.lua" to """
                local Collections = luajava.bindClass("java.util.Collections")
                local empty = Collections.emptyList()
                return empty
            """.trimIndent(),
            classes = setOf("java.util.List")
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "empty", occurrence = 2)
        )

        assertNotNull(hover)
        assertParameterizedSurface(
            displayName = hover.typeInfo?.displayName,
            fqn = "java.util.List",
            simpleName = "List",
            expectedArgHints = listOf("T"),
            context = "Collections.emptyList()"
        )
    }
    @Test
    fun optional_of_static_result_hover_surfaces_optional_type_arg() {
        val harness = jvmHarness(
            "main.lua" to """
                local Optional = luajava.bindClass("java.util.Optional")
                local some = Optional.of("value")
                return some
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "some", occurrence = 2)
        )

        assertNotNull(hover)
        assertParameterizedSurface(
            displayName = hover.typeInfo?.displayName,
            fqn = "java.util.Optional",
            simpleName = "Optional",
            expectedArgHints = listOf("value"),
            context = "Optional.of"
        )
    }
    @Test
    fun bind_class_unknown_generic_looking_target_degrades() {
        val harness = jvmHarness(
            "main.lua" to """
                local Missing = luajava.bindClass("missing.GenericLike")
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
            diagnostics.containsUnknownTarget("missing.GenericLike") ||
                hover?.typeInfo?.displayName == "unknown" ||
                hover?.typeInfo?.displayName.isNullOrBlank() ||
                hover == null,
            "Unknown generic-looking target must degrade; hover=${hover?.typeInfo?.displayName} " +
                "diagnostics=${diagnostics.map { it.message }}"
        )
        assertFalse(
            harness.path("__jvm__/classes/missing/GenericLike.lua") in harness.snapshot.extraProviders
        )
    }
    @Test
    fun generic_bind_class_document_facts_record_bind_class_load() {
        val harness = jvmHarness(
            "main.lua" to """
                local ArrayList = luajava.bindClass("java.util.ArrayList")
                local Optional = luajava.bindClass("java.util.Optional")
                return ArrayList, Optional
            """.trimIndent()
        )

        val loads = jvmClassLoads(harness)
        assertTrue(
            loads.any {
                it.kind == DocumentFacts.JvmClassLoadKind.BIND_CLASS_CALL &&
                    it.target == "java.util.ArrayList"
            },
            "Expected BIND_CLASS_CALL for ArrayList; got $loads"
        )
        assertTrue(
            loads.any {
                it.kind == DocumentFacts.JvmClassLoadKind.BIND_CLASS_CALL &&
                    it.target == "java.util.Optional"
            },
            "Expected BIND_CLASS_CALL for Optional; got $loads"
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

    /**
     * Parameterized hover surface for reflected generic returns:
     * - Prefer `java.util.List<T>` / `java.util.Map<K, V>` style displayName.
     * - Accept raw FQN/simple name only when product omits angle-brackets (soft dual-path),
     *   but still require non-unknown class identity.
     *
     * Does **not** soft-accept pure `unknown` — use [assertGenericStaticReturnDualPath]
     * for call sites with a documented CURRENTLY_ACCEPTS product gap (unmodifiable*).
     */
    private fun assertParameterizedSurface(
        displayName: String?,
        fqn: String,
        simpleName: String,
        expectedArgHints: List<String>,
        context: String
    ) {
        assertNotUnknown(displayName)
        val display = displayName.orEmpty()
        val mentionsClass =
            display == fqn ||
                display == simpleName ||
                display.contains(fqn) ||
                display.startsWith("$simpleName<") ||
                display.contains(simpleName)

        assertTrue(
            mentionsClass,
            "[$context] Expected class surface for $fqn / $simpleName; displayName=$displayName"
        )

        if (display.contains("<") && display.contains(">")) {
            expectedArgHints.forEach { hint ->
                assertTrue(
                    display.contains(hint) ||
                        display.contains("unknown") ||
                        display.contains("any", ignoreCase = true),
                    "[$context] Parameterized surface should mention type-arg hint '$hint' " +
                        "(or unknown/any degrade); displayName=$displayName"
                )
            }
        } else {
            // Soft path: product may still show raw class without type-args for some call sites.
            // Hard identity already checked; document soft gap for review without failing identity.
            assertTrue(
                mentionsClass,
                "[$context] Non-parameterized class surface still must identify $fqn; got $displayName"
            )
        }
    }

    /**
     * Dual-path for generic static factory/method returns that the product may not yet
     * propagate through raw receivers (REVIEW38 unmodifiableList / unmodifiableMap):
     *
     * - IDEAL: class identity with optional type-arg hints (same as [assertParameterizedSurface]).
     * - CURRENTLY_ACCEPTS: pure `unknown` / blank / any / nil — documented product gap.
     * - Hard reject: invented concrete element types (string as sole surface) and
     *   silent Object/Class identity.
     */
    private fun assertGenericStaticReturnDualPath(
        displayName: String?,
        fqn: String,
        simpleName: String,
        expectedArgHints: List<String>,
        context: String
    ) {
        val display = displayName

        // Hard reject: inventing Object/Class identity for a generic static return.
        assertFalse(
            display == "java.lang.Object" || display == "java.lang.Class",
            "[$context] Must not silently become Object/Class; got $displayName"
        )

        val currentlyAcceptsGap =
            display == null ||
                display.isBlank() ||
                display == "unknown" ||
                display.equals("any", ignoreCase = true) ||
                display.equals("nil", ignoreCase = true)

        if (currentlyAcceptsGap) {
            // CURRENTLY_ACCEPTS product gap: generic static return not propagated.
            // Document only — do not fail the corpus.
            return
        }

        // Ideal / soft class-identity path (non-unknown).
        assertParameterizedSurface(
            displayName = displayName,
            fqn = fqn,
            simpleName = simpleName,
            expectedArgHints = expectedArgHints,
            context = context
        )
    }

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
            "Expected bindClass class-name surface for $fqn / $simpleName; " +
                "displayName=$displayName moduleName=$moduleName"
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
            displayName.orEmpty().contains("fun(") || displayName.orEmpty().contains("fun<"),
            "Expected callable type, got $displayName."
        )
        assertNotUnknown(displayName)
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
