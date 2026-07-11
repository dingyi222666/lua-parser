package semantic.interop

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * TASK-572 product lock: local functions/members named after LuaJava helpers must not inherit
 * helper typing. Workspace hover collapses structural table literals to the coarse `table`
 * display (preferredHoverType); hard-assert that shape (or unknown for unresolved members)
 * and reject JVM helper / class / callable surfaces. No CURRENTLY_ACCEPTS dual-path for
 * primary shadow cases.
 */
class LuaJavaHelperShadowingTddTest {
    @Test
    fun local_create_proxy_function_does_not_gain_luajava_proxy_surface() {
        val harness = jvmHarness(
            "main.lua" to """
                local function createProxy(target, impl)
                    return { value = target }
                end

                local proxy = createProxy("java.lang.Runnable", {})
                local run = proxy.run
                return proxy, run
            """.trimIndent()
        )

        assertHoverTypeIsLocalTableShadow(harness, "proxy", occurrence = 2)
        assertHoverType(harness, "run", "unknown", occurrence = 2)
        assertNotJvmHelperSurface(harness, "proxy", occurrence = 2)
    }

    @Test
    fun local_create_array_function_does_not_gain_luajava_array_surface() {
        val harness = jvmHarness(
            "main.lua" to """
                local function createArray(target, values)
                    return { value = target }
                end

                local array = createArray("java.lang.String", {})
                local first = array[1]
                return array, first
            """.trimIndent()
        )

        assertHoverTypeIsLocalTableShadow(harness, "array", occurrence = 2)
        assertHoverType(harness, "first", "unknown", occurrence = 2)
        assertNotJvmHelperSurface(harness, "array", occurrence = 2)
    }

    @Test
    fun local_new_array_function_does_not_gain_luajava_array_surface() {
        val harness = jvmHarness(
            "main.lua" to """
                local Locale = luajava.bindClass("java.util.Locale")
                local function newArray(target, size)
                    return { size = size }
                end

                local array = newArray(Locale, 2)
                local first = array[1]
                return array, first
            """.trimIndent()
        )

        assertHoverTypeIsLocalTableShadow(harness, "array", occurrence = 2)
        assertHoverType(harness, "first", "unknown", occurrence = 2)
        assertNotJvmHelperSurface(harness, "array", occurrence = 2)
    }

    @Test
    fun local_bare_bind_class_new_instance_and_load_lib_functions_do_not_gain_luajava_surfaces() {
        val harness = jvmHarness(
            "main.lua" to """
                local function bindClass(target)
                    return { value = target }
                end

                local function newInstance(target)
                    return { value = target }
                end

                local function loadLib(target, member)
                    return { target = target, member = member }
                end

                local classResult = bindClass("java.util.Locale")
                local instanceResult = newInstance("java.lang.StringBuilder")
                local loadResult = loadLib("java.lang.System", "currentTimeMillis")
                local classRoot = classResult.ROOT
                local instanceAppend = instanceResult.append
                local loadCall = loadResult()
                return classResult, instanceResult, loadResult, classRoot, instanceAppend, loadCall
            """.trimIndent()
        )

        assertHoverTypeIsLocalTableShadow(harness, "classResult", occurrence = 2)
        assertHoverTypeIsLocalTableShadow(harness, "instanceResult", occurrence = 2)
        assertHoverTypeIsLocalTableShadow(harness, "loadResult", occurrence = 2)
        assertHoverType(harness, "classRoot", "unknown", occurrence = 2)
        assertHoverType(harness, "instanceAppend", "unknown", occurrence = 2)
        assertHoverType(harness, "loadCall", "unknown", occurrence = 2)
        assertNotJvmHelperSurface(harness, "classResult", occurrence = 2)
        assertNotJvmHelperSurface(harness, "instanceResult", occurrence = 2)
        assertNotJvmHelperSurface(harness, "loadResult", occurrence = 2)
    }

    @Test
    fun local_luajava_create_proxy_member_does_not_gain_luajava_proxy_surface() {
        val harness = jvmHarness(
            "main.lua" to """
                local luajava = {
                    createProxy = function(target, impl)
                        return { value = target }
                    end
                }

                local proxy = luajava.createProxy("java.lang.Runnable", {})
                local run = proxy.run
                return proxy, run
            """.trimIndent()
        )

        assertHoverTypeIsLocalTableShadow(harness, "proxy", occurrence = 2)
        assertHoverType(harness, "run", "unknown", occurrence = 2)
        assertNotJvmHelperSurface(harness, "proxy", occurrence = 2)
    }

    @Test
    fun local_luajava_create_array_member_does_not_gain_luajava_array_surface() {
        val harness = jvmHarness(
            "main.lua" to """
                local luajava = {
                    createArray = function(target, values)
                        return { value = target }
                    end
                }

                local array = luajava.createArray("java.lang.String", {})
                local first = array[1]
                return array, first
            """.trimIndent()
        )

        assertHoverTypeIsLocalTableShadow(harness, "array", occurrence = 2)
        assertHoverType(harness, "first", "unknown", occurrence = 2)
        assertNotJvmHelperSurface(harness, "array", occurrence = 2)
    }

    @Test
    fun local_luajava_new_array_member_does_not_gain_luajava_array_surface() {
        val harness = jvmHarness(
            "main.lua" to """
                local luajava = {
                    newArray = function(target, size)
                        return { size = size }
                    end
                }

                local array = luajava.newArray("java.util.Locale", 2)
                local first = array[1]
                return array, first
            """.trimIndent()
        )

        assertHoverTypeIsLocalTableShadow(harness, "array", occurrence = 2)
        assertHoverType(harness, "first", "unknown", occurrence = 2)
        assertNotJvmHelperSurface(harness, "array", occurrence = 2)
    }

    @Test
    fun local_luajava_bind_class_member_and_alias_do_not_gain_luajava_class_surface() {
        val harness = jvmHarness(
            "main.lua" to """
                local luajava = {
                    bindClass = function(target)
                        return { value = target }
                    end
                }
                local bind = luajava.bindClass

                local classResult = luajava.bindClass("java.util.Locale")
                local aliasResult = bind("java.util.Locale")
                local classRoot = classResult.ROOT
                local aliasRoot = aliasResult.ROOT
                return classResult, aliasResult, classRoot, aliasRoot
            """.trimIndent()
        )

        assertHoverTypeIsLocalTableShadow(harness, "classResult", occurrence = 2)
        assertHoverTypeIsLocalTableShadow(harness, "aliasResult", occurrence = 2)
        assertHoverType(harness, "classRoot", "unknown", occurrence = 2)
        assertHoverType(harness, "aliasRoot", "unknown", occurrence = 2)
        assertNotJvmHelperSurface(harness, "classResult", occurrence = 2)
        assertNotJvmHelperSurface(harness, "aliasResult", occurrence = 2)
    }

    @Test
    fun local_luajava_new_instance_member_and_alias_do_not_gain_luajava_instance_surface() {
        val harness = jvmHarness(
            "main.lua" to """
                local luajava = {
                    newInstance = function(target)
                        return { value = target }
                    end
                }
                local make = luajava.newInstance

                local instanceResult = luajava.newInstance("java.lang.StringBuilder")
                local madeResult = make("java.lang.StringBuilder")
                local instanceAppend = instanceResult.append
                local madeAppend = madeResult.append
                return instanceResult, madeResult, instanceAppend, madeAppend
            """.trimIndent()
        )

        assertHoverTypeIsLocalTableShadow(harness, "instanceResult", occurrence = 2)
        assertHoverTypeIsLocalTableShadow(harness, "madeResult", occurrence = 2)
        assertHoverType(harness, "instanceAppend", "unknown", occurrence = 2)
        assertHoverType(harness, "madeAppend", "unknown", occurrence = 2)
        assertNotJvmHelperSurface(harness, "instanceResult", occurrence = 2)
        assertNotJvmHelperSurface(harness, "madeResult", occurrence = 2)
    }

    @Test
    fun local_luajava_load_lib_member_and_alias_do_not_gain_luajava_member_surface() {
        val harness = jvmHarness(
            "main.lua" to """
                local luajava = {
                    loadLib = function(target, member)
                        return { target = target, member = member }
                    end
                }
                local load = luajava.loadLib

                local loadResult = luajava.loadLib("java.lang.System", "currentTimeMillis")
                local aliasResult = load("java.lang.System", "currentTimeMillis")
                local loadCall = loadResult()
                local aliasCall = aliasResult()
                return loadResult, aliasResult, loadCall, aliasCall
            """.trimIndent()
        )

        assertHoverTypeIsLocalTableShadow(harness, "loadResult", occurrence = 2)
        assertHoverTypeIsLocalTableShadow(harness, "aliasResult", occurrence = 2)
        assertHoverType(harness, "loadCall", "unknown", occurrence = 2)
        assertHoverType(harness, "aliasCall", "unknown", occurrence = 2)
        assertNotJvmHelperSurface(harness, "loadResult", occurrence = 2)
        assertNotJvmHelperSurface(harness, "aliasResult", occurrence = 2)
    }

    @Test
    fun colon_luajava_helper_member_aliases_do_not_gain_luajava_surfaces() {
        val harness = jvmHarness(
            "main.lua" to """
                local Locale = luajava.bindClass("java.util.Locale")
                local bind = luajava:bindClass
                local make = luajava:newInstance
                local proxyFactory = luajava:createProxy
                local load = luajava:loadLib
                local createArray = luajava:createArray
                local newArray = luajava:newArray

                local classResult = bind("java.util.Locale")
                local instanceResult = make("java.lang.StringBuilder")
                local proxyResult = proxyFactory("java.lang.Runnable", {})
                local loadResult = load("java.lang.System", "currentTimeMillis")
                local createArrayResult = createArray("java.lang.String", {})
                local newArrayResult = newArray(Locale, 2)
                local classRoot = classResult.ROOT
                local instanceAppend = instanceResult.append
                local proxyRun = proxyResult.run
                local loadCall = loadResult()
                local createArrayFirst = createArrayResult[1]
                local newArrayFirst = newArrayResult[1]
                return classRoot, instanceAppend, proxyRun, loadCall, createArrayFirst, newArrayFirst
            """.trimIndent()
        )

        assertHoverType(harness, "classRoot", "unknown", occurrence = 2)
        assertHoverTypeIsNot(harness, "instanceResult", "java.lang.StringBuilder", occurrence = 2)
        assertHoverType(harness, "instanceAppend", "unknown", occurrence = 2)
        assertHoverType(harness, "proxyRun", "unknown", occurrence = 2)
        assertHoverType(harness, "loadCall", "unknown", occurrence = 2)
        assertHoverTypeIsNot(harness, "createArrayFirst", "string", occurrence = 2)
        assertHoverTypeIsNot(harness, "newArrayFirst", "java.util.Locale", occurrence = 2)
        assertNotJvmHelperSurface(harness, "instanceResult", occurrence = 2)
        assertNotJvmHelperSurface(harness, "proxyResult", occurrence = 2)
        assertNotJvmHelperSurface(harness, "loadResult", occurrence = 2)
        assertNotJvmHelperSurface(harness, "createArrayResult", occurrence = 2)
        assertNotJvmHelperSurface(harness, "newArrayResult", occurrence = 2)
    }

    private fun jvmHarness(vararg files: Pair<String, String>): WorkspaceSemanticHarness {
        return WorkspaceSemanticHarness.build(
            *files,
            engine = JvmWorkspaceEngine()
        )
    }

    private fun hoverDisplay(
        harness: WorkspaceSemanticHarness,
        needle: String,
        occurrence: Int
    ): String? {
        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", needle, occurrence))
        return hover?.typeInfo?.displayName
    }

    private fun assertHoverType(
        harness: WorkspaceSemanticHarness,
        needle: String,
        expected: String,
        occurrence: Int = 1
    ) {
        assertEquals(expected, hoverDisplay(harness, needle, occurrence))
    }

    /**
     * Local helper-shadow returns are ordinary table values. Product hover collapses structural
     * `{ field: ... }` display names to the coarse `table` kind via preferredHoverType; either
     * is a non-helper surface. Hard-reject JVM class/callable/array helper displays.
     */
    private fun assertHoverTypeIsLocalTableShadow(
        harness: WorkspaceSemanticHarness,
        needle: String,
        occurrence: Int = 1
    ) {
        val display = hoverDisplay(harness, needle, occurrence).orEmpty()
        assertTrue(
            display == "table" || display.startsWith("{"),
            "Expected local table shadow surface for $needle, got '$display'."
        )
        assertNotJvmDisplay(display, needle)
    }

    private fun assertNotJvmHelperSurface(
        harness: WorkspaceSemanticHarness,
        needle: String,
        occurrence: Int = 1
    ) {
        assertNotJvmDisplay(hoverDisplay(harness, needle, occurrence).orEmpty(), needle)
    }

    private fun assertNotJvmDisplay(display: String, label: String) {
        assertFalse(
            display.startsWith("java."),
            "Local helper shadow $label must not expose JVM class type, got '$display'."
        )
        assertFalse(
            display.contains("JavaProxy", ignoreCase = true) ||
                display.contains("JavaArray", ignoreCase = true) ||
                display.contains("JavaClass", ignoreCase = true) ||
                display.contains("JavaObject", ignoreCase = true),
            "Local helper shadow $label must not expose JavaProxy/JavaArray/JavaClass surface, got '$display'."
        )
        assertFalse(
            display.contains("fun(") || display.contains("fun<"),
            "Local helper shadow $label must not expose JVM callable surface, got '$display'."
        )
        assertFalse(
            display.endsWith("[]") || display.startsWith("Array<"),
            "Local helper shadow $label must not expose Java array helper surface, got '$display'."
        )
        assertNotEquals("java.lang.Runnable", display)
        assertNotEquals("java.lang.StringBuilder", display)
        assertNotEquals("java.util.Locale", display)
        assertNotEquals("java.lang.System", display)
        assertNotEquals("string", display)
    }

    private fun assertHoverTypeIsNot(
        harness: WorkspaceSemanticHarness,
        needle: String,
        unexpected: String,
        occurrence: Int = 1
    ) {
        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", needle, occurrence))
        assertNotEquals(unexpected, hover?.typeInfo?.displayName)
    }
}
