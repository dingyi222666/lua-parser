package semantic.model

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.semantic.SemanticPipeline
import io.github.dingyi222666.luaparser.semantic.workspace.std.BuiltinOverlaySnapshot
import io.github.dingyi222666.luaparser.semantic.model.ApiAdapters
import io.github.dingyi222666.luaparser.semantic.types.model.*
import kotlin.test.*

class TypeHandleIdentityTest {
    @Test
    fun presentation_handles_preserve_same_named_surfaces_and_do_not_hash_shared_member_graphs() {
        val binder = SemanticPipeline().bindForGlobalFingerprint(LuaParser().parse("return nil"), BuiltinOverlaySnapshot.EMPTY.globals)
        val adapters = ApiAdapters(binder)
        val left = ClassType("Same", fields = mapOf("left" to PrimitiveType.STRING))
        val right = ClassType("Same", fields = mapOf("right" to PrimitiveType.NUMBER))
        val leftHandle = assertNotNull(adapters.toTypeInfo(left)).typeKey
        val rightHandle = assertNotNull(adapters.toTypeInfo(right)).typeKey
        assertNotEquals(leftHandle, rightHandle)
        assertSame(left, adapters.typeByKey(leftHandle))
        assertSame(right, adapters.typeByKey(rightHandle))
        assertEquals(leftHandle, adapters.toTypeInfo(left)!!.typeKey)

        // Each node refers twice to the preceding immutable graph. Structural hashCode
        // would revisit all paths, whereas a presentation handle needs only this object.
        var graph = JavaClassType(JavaTypeName("test", listOf("Leaf")))
        repeat(22) { index ->
            val name = JavaTypeName("test", listOf("Node$index"))
            val value = JavaInstanceType(graph)
            graph = JavaClassType(name, instanceMembers = mapOf(
                "left" to JavaInstanceMemberType(name, "left", value, JavaMemberKind.FIELD),
                "right" to JavaInstanceMemberType(name, "right", value, JavaMemberKind.FIELD)
            ))
        }
        val handle = assertNotNull(adapters.toTypeInfo(graph)).typeKey
        assertSame(graph, adapters.typeByKey(handle))
        assertEquals(handle, adapters.toTypeInfo(graph)!!.typeKey)
    }
}
