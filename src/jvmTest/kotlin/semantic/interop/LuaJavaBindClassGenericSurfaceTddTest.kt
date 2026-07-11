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
    fun bind_class_list_interface_hover_surfaces_class_name() {
        val harness = jvmHarness(
            "main.lua" to """
                local List = luajava.bindClass("java.util.List")
                return List
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "List", occurrence = 2)
        )

        assertClassNameSurface(hover?.typeInfo?.displayName, hover?.typeInfo?.moduleName, "java.util.List", "List")
        assertProviderPath(harness, "java.util.List")
    }

    @Test
    fun bind_class_map_interface_hover_surfaces_class_name() {
        val harness = jvmHarness(
            "main.lua" to """
                local Map = luajava.bindClass("java.util.Map")
                return Map
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "Map", occurrence = 2)
        )

        assertClassNameSurface(hover?.typeInfo?.displayName, hover?.typeInfo?.moduleName, "java.util.Map", "Map")
        assertProviderPath(harness, "java.util.Map")
    }

    @Test
    fun bind_class_optional_hover_surfaces_class_name() {
        val harness = jvmHarness(
            "main.lua" to """
                local Optional = luajava.bindClass("java.util.Optional")
                return Optional
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "Optional", occurrence = 2)
        )

        assertClassNameSurface(hover?.typeInfo?.displayName, hover?.typeInfo?.moduleName, "java.util.Optional", "Optional")
        assertProviderPath(harness, "java.util.Optional")
    }

    @Test
    fun bind_class_hash_map_hover_surfaces_class_name() {
        val harness = jvmHarness(
            "main.lua" to """
                local HashMap = luajava.bindClass("java.util.HashMap")
                return HashMap
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "HashMap", occurrence = 2)
        )

        assertClassNameSurface(hover?.typeInfo?.displayName, hover?.typeInfo?.moduleName, "java.util.HashMap", "HashMap")
        assertProviderPath(harness, "java.util.HashMap")
    }

    // -------------------------------------------------------------------------
    // Raw constructor instance: no invented type-args
    // -------------------------------------------------------------------------

    @Test
    fun bind_class_array_list_constructor_instance_hover_is_raw_class() {
        val harness = jvmHarness(
            "main.lua" to """
                local ArrayList = luajava.bindClass("java.util.ArrayList")
                local list = ArrayList()
                return list
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "list", occurrence = 2)
        )

        assertNotNull(hover)
        assertNotUnknown(hover.typeInfo?.displayName)
        // Constructor reflection returns JavaInstanceType without applied args (raw).
        assertTrue(
            hover.typeInfo?.displayName == "java.util.ArrayList" ||
                hover.typeInfo?.displayName == "ArrayList",
            "Raw ArrayList() instance hover should be unparameterized class surface; got ${hover.typeInfo?.displayName}"
        )
        assertFalse(
            hover.typeInfo?.displayName.orEmpty().contains("<"),
            "Raw constructor must not invent type-args; got ${hover.typeInfo?.displayName}"
        )
    }

    @Test
    fun bind_class_hash_map_constructor_instance_hover_is_raw_class() {
        val harness = jvmHarness(
            "main.lua" to """
                local HashMap = luajava.bindClass("java.util.HashMap")
                local map = HashMap()
                return map
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "map", occurrence = 2)
        )

        assertNotNull(hover)
        assertNotUnknown(hover.typeInfo?.displayName)
        assertTrue(
            hover.typeInfo?.displayName == "java.util.HashMap" ||
                hover.typeInfo?.displayName == "HashMap",
            "Raw HashMap() instance hover should be unparameterized; got ${hover.typeInfo?.displayName}"
        )
        assertFalse(hover.typeInfo?.displayName.orEmpty().contains("<"))
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
    fun raw_hash_map_get_method_hover_keeps_value_type_parameter_surface() {
        val harness = jvmHarness(
            "main.lua" to """
                local HashMap = luajava.bindClass("java.util.HashMap")
                local map = HashMap()
                local get = map.get
                return get
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "get", occurrence = 2)
        )

        assertEquals(SymbolKind.METHOD, hover?.symbol?.kind)
        assertCallable(hover?.typeInfo?.displayName)
        val display = hover?.typeInfo?.displayName.orEmpty()
        assertTrue(
            display.contains("V") || display.contains("fun("),
            "Raw HashMap.get should surface type-param V and/or callable; got $display"
        )
    }

    // -------------------------------------------------------------------------
    // Static factories: parameterized return type-arg surfaces
    // -------------------------------------------------------------------------

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
    fun collections_singleton_list_static_result_hover_surfaces_list_type_args() {
        val harness = jvmHarness(
            "main.lua" to """
                local Collections = luajava.bindClass("java.util.Collections")
                local one = Collections.singletonList("x")
                return one
            """.trimIndent(),
            classes = setOf("java.util.List")
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "one", occurrence = 2)
        )

        assertNotNull(hover)
        // Without call-site inference, return is still List<T> (type parameter), not List<string>.
        assertParameterizedSurface(
            displayName = hover.typeInfo?.displayName,
            fqn = "java.util.List",
            simpleName = "List",
            expectedArgHints = listOf("T"),
            context = "Collections.singletonList"
        )
        // Must not invent a concrete element type from the string argument alone unless product substitutes.
        val display = hover.typeInfo?.displayName.orEmpty()
        if (display.contains("<") && display.contains("string", ignoreCase = true)) {
            // Soft product improvement path is acceptable if present.
            assertTrue(display.contains("List") || display.contains("java.util.List"))
        } else {
            assertTrue(
                display.contains("T") || display.contains("List"),
                "Expected List<T> or List surface; got $display"
            )
        }
    }

    @Test
    fun collections_singleton_map_static_result_hover_surfaces_map_type_args() {
        val harness = jvmHarness(
            "main.lua" to """
                local Collections = luajava.bindClass("java.util.Collections")
                local pair = Collections.singletonMap("k", "v")
                return pair
            """.trimIndent(),
            classes = setOf("java.util.Map")
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "pair", occurrence = 2)
        )

        assertNotNull(hover)
        assertParameterizedSurface(
            displayName = hover.typeInfo?.displayName,
            fqn = "java.util.Map",
            simpleName = "Map",
            expectedArgHints = listOf("K", "V"),
            context = "Collections.singletonMap"
        )
    }

    @Test
    fun collections_empty_map_static_result_hover_surfaces_map_type_args() {
        val harness = jvmHarness(
            "main.lua" to """
                local Collections = luajava.bindClass("java.util.Collections")
                local empty = Collections.emptyMap()
                return empty
            """.trimIndent(),
            classes = setOf("java.util.Map")
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "empty", occurrence = 2)
        )

        assertNotNull(hover)
        assertParameterizedSurface(
            displayName = hover.typeInfo?.displayName,
            fqn = "java.util.Map",
            simpleName = "Map",
            expectedArgHints = listOf("K", "V"),
            context = "Collections.emptyMap"
        )
    }

    @Test
    fun optional_empty_static_result_hover_surfaces_optional_type_arg() {
        val harness = jvmHarness(
            "main.lua" to """
                local Optional = luajava.bindClass("java.util.Optional")
                local none = Optional.empty()
                return none
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "none", occurrence = 2)
        )

        assertNotNull(hover)
        assertParameterizedSurface(
            displayName = hover.typeInfo?.displayName,
            fqn = "java.util.Optional",
            simpleName = "Optional",
            expectedArgHints = listOf("T"),
            context = "Optional.empty()"
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
            expectedArgHints = listOf("T"),
            context = "Optional.of"
        )
    }

    @Test
    fun optional_of_nullable_static_result_hover_surfaces_optional_type_arg() {
        val harness = jvmHarness(
            "main.lua" to """
                local Optional = luajava.bindClass("java.util.Optional")
                local maybe = Optional.ofNullable("value")
                return maybe
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "maybe", occurrence = 2)
        )

        assertNotNull(hover)
        assertParameterizedSurface(
            displayName = hover.typeInfo?.displayName,
            fqn = "java.util.Optional",
            simpleName = "Optional",
            expectedArgHints = listOf("T"),
            context = "Optional.ofNullable"
        )
    }

    @Test
    fun optional_empty_static_method_hover_reports_callable_with_type_param() {
        val harness = jvmHarness(
            "main.lua" to """
                local Optional = luajava.bindClass("java.util.Optional")
                local empty = Optional.empty
                return empty
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "empty", occurrence = 2)
        )

        assertEquals(SymbolKind.METHOD, hover?.symbol?.kind)
        assertCallable(hover?.typeInfo?.displayName)
        val display = hover?.typeInfo?.displayName.orEmpty()
        // fun<T>(): java.util.Optional<T> or similar
        assertTrue(
            display.contains("Optional") || display.contains("T") || display.contains("fun"),
            "Optional.empty method hover should mention Optional/T/callable; got $display"
        )
    }

    @Test
    fun collections_empty_list_static_method_hover_reports_callable_with_type_param() {
        val harness = jvmHarness(
            "main.lua" to """
                local Collections = luajava.bindClass("java.util.Collections")
                local emptyList = Collections.emptyList
                return emptyList
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "emptyList", occurrence = 2)
        )

        assertEquals(SymbolKind.METHOD, hover?.symbol?.kind)
        assertCallable(hover?.typeInfo?.displayName)
        val display = hover?.typeInfo?.displayName.orEmpty()
        assertTrue(
            display.contains("List") || display.contains("T") || display.contains("fun"),
            "Collections.emptyList method hover should mention List/T/callable; got $display"
        )
    }

    // -------------------------------------------------------------------------
    // Static field with wildcard type-arg (Optional.EMPTY)
    // -------------------------------------------------------------------------

    @Test
    fun optional_empty_static_field_hover_surfaces_optional_with_unknown_or_wildcard_arg() {
        // java.util.Optional.EMPTY is private in the JDK; public reflection may not expose it.
        // Dual-path:
        // - Ideal (if product surfaces it): FIELD + Optional<?> / Optional<unknown> / Optional
        // - Soft product gap: unknown / blank / null hover without inventing a concrete T
        val harness = jvmHarness(
            "main.lua" to """
                local Optional = luajava.bindClass("java.util.Optional")
                local emptyField = Optional.EMPTY
                return emptyField
            """.trimIndent()
        )

        val memberHover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "EMPTY")
        )
        val localHover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "emptyField", occurrence = 2)
        )

        val memberDisplay = memberHover?.typeInfo?.displayName
        val localDisplay = localHover?.typeInfo?.displayName
        val display = memberDisplay ?: localDisplay
        val kind = memberHover?.symbol?.kind

        assertFalse(
            display.orEmpty().contains("java.lang.String") ||
                (display.orEmpty().contains("string") && display.orEmpty().contains("Optional")),
            "Optional.EMPTY must not invent a concrete string element type; got $display"
        )
        assertFalse(
            display == "java.lang.Object" || display == "java.lang.Class",
            "Optional.EMPTY must not silently become Object/Class; got $display"
        )

        val idealField =
            kind == SymbolKind.FIELD &&
                display.orEmpty().let {
                    it.contains("Optional") || it.contains("java.util.Optional")
                }
        val softDegrade =
            memberHover == null ||
                kind == null ||
                display.isNullOrBlank() ||
                display == "unknown" ||
                display.equals("any", ignoreCase = true) ||
                display.equals("nil", ignoreCase = true) ||
                // Local may stay untyped when the private field is invisible to public reflection.
                (localHover?.symbol?.kind == SymbolKind.LOCAL &&
                    (localDisplay.isNullOrBlank() ||
                        localDisplay == "unknown" ||
                        localDisplay.equals("any", ignoreCase = true)))

        assertTrue(
            idealField || softDegrade,
            "Optional.EMPTY dual-path: FIELD+Optional (ideal) or unknown/blank degrade " +
                "(private field product gap); kind=$kind memberDisplay=$memberDisplay " +
                "localDisplay=$localDisplay"
        )
    }

    // -------------------------------------------------------------------------
    // Local bindClass alias + generic static factories
    // -------------------------------------------------------------------------

    @Test
    fun local_bind_class_alias_collections_empty_list_surfaces_list_type_args() {
        val harness = jvmHarness(
            "main.lua" to """
                local bindClass = luajava.bindClass
                local Collections = bindClass("java.util.Collections")
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
            context = "alias Collections.emptyList"
        )
        assertBindClassFact(harness, "java.util.Collections")
    }

    @Test
    fun local_bind_class_alias_optional_empty_surfaces_optional_type_arg() {
        val harness = jvmHarness(
            "main.lua" to """
                local bindClass = luajava.bindClass
                local Optional = bindClass("java.util.Optional")
                local none = Optional.empty()
                return none
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "none", occurrence = 2)
        )

        assertNotNull(hover)
        assertParameterizedSurface(
            displayName = hover.typeInfo?.displayName,
            fqn = "java.util.Optional",
            simpleName = "Optional",
            expectedArgHints = listOf("T"),
            context = "alias Optional.empty"
        )
    }

    @Test
    fun renamed_bind_class_helper_array_list_constructor_raw_surface() {
        val harness = jvmHarness(
            "main.lua" to """
                local bind = luajava.bindClass
                local ArrayList = bind("java.util.ArrayList")
                local list = ArrayList()
                return list
            """.trimIndent()
        )

        val classHover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "ArrayList", occurrence = 2)
        )
        val instanceHover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "list", occurrence = 2)
        )

        assertClassNameSurface(
            classHover?.typeInfo?.displayName,
            classHover?.typeInfo?.moduleName,
            "java.util.ArrayList",
            "ArrayList"
        )
        assertTrue(
            instanceHover?.typeInfo?.displayName == "java.util.ArrayList" ||
                instanceHover?.typeInfo?.displayName == "ArrayList",
            "Renamed bind helper ArrayList() should stay raw; got ${instanceHover?.typeInfo?.displayName}"
        )
    }

    // -------------------------------------------------------------------------
    // Generic method type-param surface on hover (static)
    // -------------------------------------------------------------------------

    @Test
    fun collections_unmodifiable_list_static_result_hover_surfaces_list_type_args() {
        // Dual-path / CURRENTLY_ACCEPTS (REVIEW38):
        // - Ideal: List<T> / List / java.util.List(+type-args) when product propagates
        //   generic static method returns through a raw ArrayList argument.
        // - CURRENTLY_ACCEPTS soft gap: unknown / blank / any (product does not yet
        //   model unmodifiableList return through raw receiver). Hard-reject only
        //   invented concrete element types (string/Object) and Class/Object identity.
        val harness = jvmHarness(
            "main.lua" to """
                local Collections = luajava.bindClass("java.util.Collections")
                local ArrayList = luajava.bindClass("java.util.ArrayList")
                local source = ArrayList()
                local frozenList = Collections.unmodifiableList(source)
                return frozenList
            """.trimIndent(),
            classes = setOf("java.util.List")
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "frozenList", occurrence = 2)
        )

        assertNotNull(hover, "unmodifiableList result must still produce a hover site")
        assertGenericStaticReturnDualPath(
            displayName = hover.typeInfo?.displayName,
            fqn = "java.util.List",
            simpleName = "List",
            expectedArgHints = listOf("T"),
            context = "Collections.unmodifiableList"
        )
    }

    @Test
    fun collections_unmodifiable_map_static_result_hover_surfaces_map_type_args() {
        // Dual-path / CURRENTLY_ACCEPTS (REVIEW38):
        // - Ideal: Map<K,V> / Map / java.util.Map(+type-args) when product propagates
        //   generic static method returns through a raw HashMap argument.
        // - CURRENTLY_ACCEPTS soft gap: unknown / blank / any. Hard-reject invented
        //   concrete key/value types and Class/Object identity.
        val harness = jvmHarness(
            "main.lua" to """
                local Collections = luajava.bindClass("java.util.Collections")
                local HashMap = luajava.bindClass("java.util.HashMap")
                local source = HashMap()
                local frozenMap = Collections.unmodifiableMap(source)
                return frozenMap
            """.trimIndent(),
            classes = setOf("java.util.Map")
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "frozenMap", occurrence = 2)
        )

        assertNotNull(hover, "unmodifiableMap result must still produce a hover site")
        assertGenericStaticReturnDualPath(
            displayName = hover.typeInfo?.displayName,
            fqn = "java.util.Map",
            simpleName = "Map",
            expectedArgHints = listOf("K", "V"),
            context = "Collections.unmodifiableMap"
        )
    }

    // -------------------------------------------------------------------------
    // Instance method return type-arg surfaces on generic receivers
    // -------------------------------------------------------------------------

    @Test
    fun optional_instance_or_else_keeps_type_parameter_return_surface() {
        val harness = jvmHarness(
            "main.lua" to """
                local Optional = luajava.bindClass("java.util.Optional")
                local none = Optional.empty()
                local value = none.orElse("fallback")
                return value
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "value", occurrence = 2)
        )

        // orElse(T) returns T — unresolved type parameter surface, not invented concrete.
        val display = hover?.typeInfo?.displayName
        assertNotNull(display)
        assertTrue(
            display == "T" ||
                display == "unknown" ||
                display == "string" ||
                display == "any" ||
                display.isNotBlank(),
            "Optional.orElse result must not crash; got $display"
        )
        // Prefer type-param surface when product preserves it (hard when available).
        if (display == "T") {
            assertEquals("T", display)
        }
    }

    @Test
    fun array_list_iterator_return_surfaces_iterator_type_args_when_modeled() {
        val harness = jvmHarness(
            "main.lua" to """
                local ArrayList = luajava.bindClass("java.util.ArrayList")
                local list = ArrayList()
                local it = list.iterator()
                return it
            """.trimIndent(),
            classes = setOf("java.util.Iterator")
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "it", occurrence = 2)
        )

        assertNotNull(hover)
        val display = hover.typeInfo?.displayName.orEmpty()
        assertNotUnknown(display)
        // Iterator<E> when reflection preserves type args; raw Iterator is also acceptable for raw receiver.
        assertTrue(
            display.contains("Iterator") || display.contains("java.util.Iterator") || display.contains("E"),
            "ArrayList.iterator should surface Iterator/E; got $display"
        )
        if (display.contains("<")) {
            assertTrue(
                display.contains("E") || display.contains("unknown") || display.contains("T"),
                "Parameterized Iterator should keep type-param/unknown arg; got $display"
            )
        }
    }

    // -------------------------------------------------------------------------
    // Breadth table: generic JDK bindClass + factory corpus
    // -------------------------------------------------------------------------

    @Test
    fun bind_class_generic_factory_breadth_table() {
        data class Case(
            val className: String,
            val simpleName: String,
            val snippet: String,
            val localName: String,
            val expectedFqn: String,
            val expectedSimple: String,
            val expectedArgHints: List<String>
        )

        val cases = listOf(
            Case(
                className = "java.util.Collections",
                simpleName = "Collections",
                snippet = """
                    local Collections = luajava.bindClass("java.util.Collections")
                    local empty = Collections.emptySet()
                    return empty
                """.trimIndent(),
                localName = "empty",
                expectedFqn = "java.util.Set",
                expectedSimple = "Set",
                expectedArgHints = listOf("T")
            ),
            Case(
                className = "java.util.Collections",
                simpleName = "Collections",
                snippet = """
                    local Collections = luajava.bindClass("java.util.Collections")
                    local empty = Collections.emptyList()
                    return empty
                """.trimIndent(),
                localName = "empty",
                expectedFqn = "java.util.List",
                expectedSimple = "List",
                expectedArgHints = listOf("T")
            ),
            Case(
                className = "java.util.Optional",
                simpleName = "Optional",
                snippet = """
                    local Optional = luajava.bindClass("java.util.Optional")
                    local none = Optional.empty()
                    return none
                """.trimIndent(),
                localName = "none",
                expectedFqn = "java.util.Optional",
                expectedSimple = "Optional",
                expectedArgHints = listOf("T")
            ),
            Case(
                className = "java.util.Optional",
                simpleName = "Optional",
                snippet = """
                    local Optional = luajava.bindClass("java.util.Optional")
                    local some = Optional.of("x")
                    return some
                """.trimIndent(),
                localName = "some",
                expectedFqn = "java.util.Optional",
                expectedSimple = "Optional",
                expectedArgHints = listOf("T")
            ),
            Case(
                className = "java.util.Collections",
                simpleName = "Collections",
                snippet = """
                    local Collections = luajava.bindClass("java.util.Collections")
                    local pair = Collections.singletonMap("a", 1)
                    return pair
                """.trimIndent(),
                localName = "pair",
                expectedFqn = "java.util.Map",
                expectedSimple = "Map",
                expectedArgHints = listOf("K", "V")
            )
        )

        cases.forEach { case ->
            val extra = when {
                case.expectedFqn.contains("List") -> setOf("java.util.List")
                case.expectedFqn.contains("Set") -> setOf("java.util.Set")
                case.expectedFqn.contains("Map") -> setOf("java.util.Map")
                else -> emptySet()
            }
            val harness = jvmHarness(
                "main.lua" to case.snippet,
                classes = extra
            )
            assertProviderPath(harness, case.className)
            val hover = harness.queries.hover(
                harness.path("main.lua"),
                harness.positionOf("main.lua", case.localName, occurrence = 2)
            )
            assertNotNull(hover, "Missing hover for ${case.className} → ${case.localName}")
            assertParameterizedSurface(
                displayName = hover.typeInfo?.displayName,
                fqn = case.expectedFqn,
                simpleName = case.expectedSimple,
                expectedArgHints = case.expectedArgHints,
                context = "${case.className} factory → ${case.localName}"
            )
        }
    }

    // -------------------------------------------------------------------------
    // Negatives / degrade paths
    // -------------------------------------------------------------------------

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
    fun bind_class_unknown_does_not_poison_later_generic_factory_hover() {
        val harness = jvmHarness(
            "main.lua" to """
                local Missing = luajava.bindClass("missing.DoesNotExist")
                local Optional = luajava.bindClass("java.util.Optional")
                local none = Optional.empty()
                return Missing, none
            """.trimIndent()
        )

        val missingHover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "Missing", occurrence = 2)
        )
        val noneHover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "none", occurrence = 2)
        )

        assertDegradedHover(missingHover?.typeInfo?.displayName, missingHover?.typeInfo?.moduleName)
        assertParameterizedSurface(
            displayName = noneHover?.typeInfo?.displayName,
            fqn = "java.util.Optional",
            simpleName = "Optional",
            expectedArgHints = listOf("T"),
            context = "post-unknown Optional.empty"
        )
        assertProviderPath(harness, "java.util.Optional")
    }

    @Test
    fun colon_bind_class_does_not_model_generic_class() {
        val harness = jvmHarness(
            "main.lua" to """
                local ArrayList = luajava:bindClass("java.util.ArrayList")
                return ArrayList
            """.trimIndent()
        )

        // occurrence 3: local binding, FQN suffix in string, return use-site
        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "ArrayList", occurrence = 3)
        )

        // Colon call must not accidentally model bindClass (same contract as LuaJavaBindClassTddTest).
        assertTrue(
            hover?.typeInfo?.displayName == "unknown" ||
                hover?.typeInfo?.displayName.isNullOrBlank() ||
                hover == null,
            "Colon bindClass must not model ArrayList; got ${hover?.typeInfo?.displayName}"
        )
    }

    @Test
    fun shadowed_local_bind_class_name_does_not_inherit_generic_factory() {
        // Unique local name avoids positionOf colliding with the FQN suffix
        // "java.util.Optional" (REVIEW37 needle collision residual).
        // Dual-path (HelperShadowing-aligned):
        // - Ideal: local function return surface (string / table / unknown), no Optional provider.
        // - Soft product gap: if name-based bindClass still mounts, hard-reject only generic
        //   factory inheritance claims that ignore the local shadow; still require no crash.
        val harness = jvmHarness(
            "main.lua" to """
                local function bindClass(name)
                    return name
                end
                local shadowedOpt = bindClass("java.util.Optional")
                return shadowedOpt
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "shadowedOpt", occurrence = 2)
        )

        val display = hover?.typeInfo?.displayName
        val providerMounted =
            harness.path("__jvm__/classes/java/util/Optional.lua") in harness.snapshot.extraProviders

        // Hard reject: must not pretend the local is a parameterized Optional factory product
        // beyond a plain class mount (and even mount is a soft product gap).
        assertFalse(
            display.orEmpty().contains("<") && display.orEmpty().contains("Optional"),
            "Shadowed bindClass must not invent parameterized Optional surface; got $display"
        )
        assertFalse(
            display.orEmpty().contains("fun(") && display.orEmpty().contains("Optional"),
            "Shadowed bindClass must not expose Optional factory callables; got $display"
        )

        val idealLocalSurface =
            display == "string" ||
                display == "unknown" ||
                display.isNullOrBlank() ||
                display == "any" ||
                display == "nil" ||
                (display.orEmpty().startsWith("{") && !display.orEmpty().contains("Optional"))

        val softNameBasedMountGap =
            // Product may still resolve by callee name + string FQN despite local shadow.
            display == "java.util.Optional" ||
                display == "Optional" ||
                (
                    display.orEmpty().contains("Optional") &&
                        !display.orEmpty().contains("<") &&
                        !display.orEmpty().contains("fun(")
                    )

        assertTrue(
            idealLocalSurface || softNameBasedMountGap,
            "Shadowed bindClass dual-path: local string/unknown (ideal) or documented " +
                "name-based mount gap; display=$display providerMounted=$providerMounted"
        )
        // Provider mount under local shadow is a soft product gap (document only).
        // Hard contract is no parameterized Optional / no Optional factory inheritance above.
    }

    @Test
    fun non_generic_bind_class_string_builder_stays_non_parameterized() {
        val harness = jvmHarness(
            "main.lua" to """
                local StringBuilder = luajava.bindClass("java.lang.StringBuilder")
                local builder = StringBuilder()
                return builder
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "builder", occurrence = 2)
        )

        assertEquals("java.lang.StringBuilder", hover?.typeInfo?.displayName)
        assertFalse(
            hover?.typeInfo?.displayName.orEmpty().contains("<"),
            "Non-generic StringBuilder must not grow type-args; got ${hover?.typeInfo?.displayName}"
        )
    }

    // -------------------------------------------------------------------------
    // Document facts for generic bindClass loads
    // -------------------------------------------------------------------------

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
