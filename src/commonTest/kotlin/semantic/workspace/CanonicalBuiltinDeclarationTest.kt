package semantic.workspace

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.semantic.SemanticPipeline
import io.github.dingyi222666.luaparser.semantic.SemanticWorkspaceContext
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.checker.ExpressionTypeEvaluator
import io.github.dingyi222666.luaparser.semantic.types.model.ClassType
import io.github.dingyi222666.luaparser.semantic.types.model.CallableType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceSnapshot
import io.github.dingyi222666.luaparser.semantic.workspace.std.BuiltinOverlayLoader
import io.github.dingyi222666.luaparser.semantic.workspace.std.BuiltinOverlaySnapshot
import io.github.dingyi222666.luaparser.semantic.workspace.std.BuiltinOverlayResourceAccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CanonicalBuiltinDeclarationTest {
    @Test
    fun a_global_type_annotation_is_bound_as_the_same_class_in_user_code() {
        val chunk = LuaParser().parse("""
            ---@class Host
            ---@field label string
            local Host = {}
            ---@type Host
            host = host
        """.trimIndent())
        val binder = SemanticPipeline().bindForGlobalFingerprint(chunk, BuiltinOverlaySnapshot.EMPTY.globals)
        val declaration = binder.declarationIndex.declarations.single { it.name == "host" && it.kind == DeclarationKind.GLOBAL }
        val type = assertIs<ClassType>(declaration.declaredType)
        assertEquals(PrimitiveType.STRING, type.fields["label"])
        assertEquals("Host", declaration.documentation?.resolvedInlineType?.displayName)
    }

    @Test
    fun standard_library_signatures_come_from_the_canonical_resolved_declarations() {
        val root = "/io/github/dingyi222666/luaparser/semantic/workspace/std/lua53/"
        val overlay = BuiltinOverlayLoader.loadCore(io.github.dingyi222666.luaparser.parser.LuaVersion.LUA_5_3) { _, _ ->
            WorkspaceSnapshot.FileSnapshot()
        }
        listOf("math" to "log", "table" to "insert", "string" to "find").forEach { (module, name) ->
            val source = BuiltinOverlayResourceAccess.readText("$root$module.lua")
            val chunk = LuaParser().parse(source)
            val binder = SemanticPipeline().bindForGlobalFingerprint(chunk, BuiltinOverlaySnapshot.EMPTY.globals)
            val declared = binder.declarationIndex.declarations.single { it.name == name && it.kind == DeclarationKind.METHOD }
            val loaded = overlay.providerModules.values.single { it.moduleName == module }.file.moduleExportSurface!!
                .members.single { it.name == name }.type
            val signature = assertIs<CallableType>(loaded).callSignatures
            assertEquals(assertIs<CallableType>(declared.declaredType).callSignatures, signature)
            if (name == "log") assertTrue(signature.size > 1, "The short overload belongs to the canonical declaration.")
        }
    }
}
