package semantic.api

import io.github.dingyi222666.luaparser.semantic.api.ScopeKind
import io.github.dingyi222666.luaparser.semantic.api.TypeInfo
import io.github.dingyi222666.luaparser.semantic.api.TypeInfoKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TypeInfoShapeTest {
    @Test
    fun typeInfo_defaults_remain_backwards_compatible() {
        val info = TypeInfo("number")

        assertEquals("number", info.displayName)
        assertNull(info.detail)
        assertNull(info.typeKey)
        assertEquals(TypeInfoKind.UNKNOWN, info.kind)
        assertNull(info.moduleName)
    }

    @Test
    fun typeInfo_can_represent_module_kind_and_moduleName() {
        val info = TypeInfo(
            displayName = "pkg.runtime",
            kind = TypeInfoKind.MODULE,
            moduleName = "pkg.runtime"
        )

        assertEquals(TypeInfoKind.MODULE, info.kind)
        assertEquals("pkg.runtime", info.moduleName)
    }

    @Test
    fun scopeKind_exposes_module_variant() {
        assertEquals(ScopeKind.MODULE, ScopeKind.valueOf("MODULE"))
    }
}
