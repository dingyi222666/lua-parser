package semantic.androidlua

import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.semantic.types.model.ClassType
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceSnapshot
import io.github.dingyi222666.luaparser.semantic.workspace.std.BuiltinOverlayLoader
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Task184OverlayProbeTddTest {
    @Test
    fun probe_activity_surface_from_androlua_overlay() {
        val overlay = BuiltinOverlayLoader.load(LuaVersion.ANDROLUA_5_3) { _, _ -> WorkspaceSnapshot.FileSnapshot() }
        val members = overlay.globals.file.moduleExportSurface?.members.orEmpty()
        val activity = members.firstOrNull { it.name == "activity" }
        println("ACTIVITY_MEMBER=$activity")
        println("ACTIVITY_TYPE=${activity?.type}")
        println("ACTIVITY_TYPE_CLASS=${activity?.type?.javaClass?.name}")
        val classType = activity?.type as? ClassType
        println("ACTIVITY_FIELDS=${classType?.fields?.keys}")
        println("ACTIVITY_METHODS=${classType?.methods?.keys}")
        println("ACTIVITY_ALL_METHODS=${classType?.getAllMethods()?.keys}")
        println("ACTIVITY_SUPER=${classType?.superClass?.name}")
        println("SUPER_METHODS=${classType?.superClass?.methods?.keys}")
        println("SUPER_FIELDS=${classType?.superClass?.fields?.keys}")
        assertNotNull(activity)
        assertTrue(classType != null || activity.type.toString().contains("LuaActivity"), activity.type.toString())
    }
}
