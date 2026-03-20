package semantic.types

import io.github.dingyi222666.luaparser.semantic.types.ModuleType as LegacyModuleType
import io.github.dingyi222666.luaparser.semantic.types.TableType as LegacyTableType
import io.github.dingyi222666.luaparser.semantic.types.bridges.toLegacyType
import io.github.dingyi222666.luaparser.semantic.types.bridges.toModelType
import io.github.dingyi222666.luaparser.semantic.types.model.ModuleType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.model.TableType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ModuleTypeBridgeTest {
    @Test
    fun modelModuleType_bridges_to_legacyModuleType() {
        val model = ModuleType(
            moduleName = "pkg.mod",
            fields = mapOf("value" to PrimitiveType.NUMBER)
        )

        val legacy = model.toLegacyType()

        assertIs<LegacyModuleType>(legacy)
        assertEquals("pkg.mod", legacy.moduleName)
    }

    @Test
    fun legacyModuleType_bridges_back_to_modelModuleType() {
        val legacy = LegacyModuleType(
            moduleName = "pkg.mod",
            fields = mapOf("value" to io.github.dingyi222666.luaparser.semantic.types.PrimitiveType.NUMBER)
        )

        val model = legacy.toModelType()

        assertIs<ModuleType>(model)
        assertEquals("pkg.mod", model.moduleName)
    }

    @Test
    fun module_bridge_does_not_collapse_to_tableType() {
        val model = ModuleType(moduleName = "pkg.mod")
        val legacy = model.toLegacyType()

        assertIs<LegacyModuleType>(legacy)
        assertTrue(legacy !is LegacyTableType)

        val roundTrip = legacy.toModelType()
        assertIs<ModuleType>(roundTrip)
        assertTrue(roundTrip !is TableType)
    }
}
