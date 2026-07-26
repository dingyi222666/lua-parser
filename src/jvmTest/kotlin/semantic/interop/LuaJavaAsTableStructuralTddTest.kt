package semantic.interop

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LuaJavaAsTableStructuralTddTest {
    @Test
    fun astable_recursively_converts_list_map_and_array_containers() {
        val fixtureName = "semantic.interop.LuaJavaAsTableStructuralTddTest${'$'}AstableFixtures"
        val harness = jvmHarness(
            "main.lua" to """
                local Fixtures = luajava.bindClass("$fixtureName")
                local fixture = Fixtures()

                local listSource = fixture.packageList()
                local listTable = luajava.astable(listSource)
                local listItem = listTable[1]
                local listName = listItem.packageName

                local concreteTable = luajava.astable(fixture.arrayList())
                local concreteName = concreteTable[1].packageName

                local nestedTable = luajava.astable(fixture.nestedMap())
                local nestedNumber = nestedTable["numbers"][1]

                local arrayTable = luajava.astable(fixture.arrayOfNestedMaps())
                local arrayName = arrayTable[1]["apps"][1].packageName

                return listSource, listTable, listItem, listName, concreteTable, concreteName,
                    nestedTable, nestedNumber, arrayTable, arrayName
            """.trimIndent()
        )

        assertHoverContains(harness, "listItem", "PackageRecord")
        assertHoverType(harness, "listName", "string")
        assertHoverType(harness, "concreteName", "string")
        assertHoverType(harness, "nestedNumber", "number")
        assertHoverType(harness, "arrayName", "string")
    }

    @Test
    fun astable_alias_keeps_structural_conversion_but_shadow_does_not() {
        val fixtureName = "semantic.interop.LuaJavaAsTableStructuralTddTest${'$'}AstableFixtures"
        val aliasHarness = jvmHarness(
            "main.lua" to """
                local Fixtures = luajava.bindClass("$fixtureName")
                local astable = luajava.astable
                local converted = astable(Fixtures().nestedMap())
                local value = converted["numbers"][1]
                return converted, value
            """.trimIndent()
        )
        assertHoverType(aliasHarness, "value", "number")

        val shadowHarness = jvmHarness(
            "main.lua" to """
                local luajava = {
                    astable = function(value)
                        return "shadow"
                    end
                }
                local converted = luajava.astable({})
                return converted
            """.trimIndent()
        )
        assertHoverType(shadowHarness, "converted", "\"shadow\"")
    }

    private fun jvmHarness(vararg files: Pair<String, String>): WorkspaceSemanticHarness {
        return WorkspaceSemanticHarness.build(*files, engine = JvmWorkspaceEngine())
    }

    private fun assertHoverType(
        harness: WorkspaceSemanticHarness,
        needle: String,
        expected: String,
        occurrence: Int = 2
    ) {
        val displayName = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", needle, occurrence)
        )?.typeInfo?.displayName
        assertEquals(expected, displayName)
    }

    private fun assertHoverContains(
        harness: WorkspaceSemanticHarness,
        needle: String,
        expected: String,
        occurrence: Int = 2
    ) {
        val displayName = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", needle, occurrence)
        )?.typeInfo?.displayName.orEmpty()
        assertTrue(expected in displayName, "Expected '$needle' type to contain '$expected', got '$displayName'")
    }

    class AstableFixtures {
        fun packageList(): List<PackageRecord> = emptyList()

        fun arrayList(): ArrayList<PackageRecord> = arrayListOf()

        fun nestedMap(): Map<String, List<Int>> = emptyMap()

        @Suppress("UNCHECKED_CAST")
        fun arrayOfNestedMaps(): Array<Map<String, List<PackageRecord>>> =
            emptyArray<Map<String, List<PackageRecord>>>()
    }

    data class PackageRecord(val packageName: String)
}
