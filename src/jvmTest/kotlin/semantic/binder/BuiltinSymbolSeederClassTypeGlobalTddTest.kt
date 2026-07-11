package semantic.binder

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import io.github.dingyi222666.luaparser.semantic.binder.BinderPass
import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachPass
import io.github.dingyi222666.luaparser.semantic.types.model.ClassType
import io.github.dingyi222666.luaparser.semantic.types.model.UnknownType
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceSnapshot
import io.github.dingyi222666.luaparser.semantic.workspace.std.BuiltinOverlayLoader
import io.github.dingyi222666.luaparser.semantic.workspace.std.BuiltinOverlaySnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * BuiltinSymbolSeeder ClassType global declaredType corpus (TASK-384).
 *
 * Locks the seeder half of the TASK-184 Android-Lua pipeline:
 * - AndroLua overlay MemberExport for single-path globals `activity` / `service`
 *   carries a non-unknown [ClassType] (LuaActivity / LuaService).
 * - BuiltinSymbolSeeder seeds those as GLOBAL / BUILTIN declarations with the
 *   same declaredType from MemberExport.
 * - API mapping: DeclarationKind.GLOBAL → SymbolKind.VARIABLE (variable/global).
 *
 * Test-only; no production edits. Verification deferred to review / TASK-043:
 * `jvmTest --tests semantic.binder.BuiltinSymbolSeederClassTypeGlobalTddTest`
 */
class BuiltinSymbolSeederClassTypeGlobalTddTest {

    private val parser = LuaParser(LuaVersion.ANDROLUA_5_3)

    @Test
    fun androlua_overlay_member_export_activity_service_are_single_path_classtype() {
        val overlay = loadAndroluaOverlay()
        val members = overlay.globals.file.moduleExportSurface?.members.orEmpty()

        val activity = assertNotNull(
            members.firstOrNull { it.name == "activity" && it.exportPath == listOf("activity") },
            "AndroLua overlay must export single-path MemberExport activity"
        )
        val service = assertNotNull(
            members.firstOrNull { it.name == "service" && it.exportPath == listOf("service") },
            "AndroLua overlay must export single-path MemberExport service"
        )

        assertEquals(listOf("activity"), activity.exportPath)
        assertEquals(listOf("service"), service.exportPath)
        assertEquals(1, activity.exportPath.size)
        assertEquals(1, service.exportPath.size)

        val activityType = assertIs<ClassType>(activity.type, "activity MemberExport.type=${activity.type}")
        val serviceType = assertIs<ClassType>(service.type, "service MemberExport.type=${service.type}")

        assertTrue(activity.type !is UnknownType, "activity type must not be UnknownType")
        assertTrue(service.type !is UnknownType, "service type must not be UnknownType")
        assertTrue(
            activityType.name.contains("LuaActivity") || activityType.displayName.contains("LuaActivity"),
            "activity ClassType name=${activityType.name} display=${activityType.displayName}"
        )
        assertTrue(
            serviceType.name.contains("LuaService") || serviceType.displayName.contains("LuaService"),
            "service ClassType name=${serviceType.name} display=${serviceType.displayName}"
        )
    }

    @Test
    fun seeder_associates_member_export_classtype_on_activity_service_globals() {
        val overlay = loadAndroluaOverlay()
        val members = overlay.globals.file.moduleExportSurface?.members.orEmpty()
            .filter { it.exportPath.size == 1 }
            .associateBy { it.name }

        val activityExport = assertNotNull(members["activity"])
        val serviceExport = assertNotNull(members["service"])
        assertIs<ClassType>(activityExport.type)
        assertIs<ClassType>(serviceExport.type)

        val binder = bindWithAndroluaGlobals(overlay.globals, "return activity, service")
        val activityDecl = assertNotNull(
            binder.declarationIndex.declarations.find {
                it.name == "activity" && it.origin == DeclarationOrigin.BUILTIN
            }
        )
        val serviceDecl = assertNotNull(
            binder.declarationIndex.declarations.find {
                it.name == "service" && it.origin == DeclarationOrigin.BUILTIN
            }
        )

        assertEquals(DeclarationKind.GLOBAL, activityDecl.kind)
        assertEquals(DeclarationKind.GLOBAL, serviceDecl.kind)
        assertEquals(DeclarationOrigin.BUILTIN, activityDecl.origin)
        assertEquals(DeclarationOrigin.BUILTIN, serviceDecl.origin)

        val activityDeclared = assertNotNull(activityDecl.declaredType, "activity declaredType must be non-null")
        val serviceDeclared = assertNotNull(serviceDecl.declaredType, "service declaredType must be non-null")
        assertNotEquals(UnknownType, activityDeclared)
        assertNotEquals(UnknownType, serviceDeclared)

        val activityClass = assertIs<ClassType>(activityDeclared)
        val serviceClass = assertIs<ClassType>(serviceDeclared)
        assertEquals(activityExport.type, activityDeclared)
        assertEquals(serviceExport.type, serviceDeclared)
        assertTrue(
            activityClass.name.contains("LuaActivity") || activityClass.displayName.contains("LuaActivity"),
            activityClass.toString()
        )
        assertTrue(
            serviceClass.name.contains("LuaService") || serviceClass.displayName.contains("LuaService"),
            serviceClass.toString()
        )
    }

    @Test
    fun seeded_activity_service_map_to_symbolkind_variable_global() {
        val overlay = loadAndroluaOverlay()
        val binder = bindWithAndroluaGlobals(overlay.globals, "require \"import\"\nreturn activity")

        for (name in listOf("activity", "service")) {
            val decl = assertNotNull(
                binder.declarationIndex.declarations.find {
                    it.name == name && it.origin == DeclarationOrigin.BUILTIN
                },
                "missing BUILTIN global $name"
            )
            assertEquals(DeclarationKind.GLOBAL, decl.kind, "$name must be GLOBAL (global)")
            // ApiAdapters DeclarationKind.GLOBAL → SymbolKind.VARIABLE
            assertEquals(SymbolKind.VARIABLE, decl.kind.toApiSymbolKind(), "$name SymbolKind VARIABLE")
            val declared = assertNotNull(decl.declaredType, "$name declaredType")
            assertNotEquals(UnknownType, declared)
            assertIs<ClassType>(declared)
        }
    }

    @Test
    fun single_path_only_globals_receive_documented_classtype_from_member_export() {
        val overlay = loadAndroluaOverlay()
        val multiPath = overlay.globals.file.moduleExportSurface?.members.orEmpty()
            .filter { it.exportPath.size != 1 && it.name == "activity" }
        // activity itself must remain single-path; multi-path names are not seeder targets
        assertTrue(multiPath.isEmpty(), "activity should not appear as multi-path export: $multiPath")

        val singlePathClassGlobals = overlay.globals.file.moduleExportSurface?.members.orEmpty()
            .filter { it.exportPath.size == 1 && it.type is ClassType }
            .map { it.name }
            .toSet()
        assertTrue("activity" in singlePathClassGlobals, singlePathClassGlobals.toString())
        assertTrue("service" in singlePathClassGlobals, singlePathClassGlobals.toString())

        val binder = bindWithAndroluaGlobals(overlay.globals, "return 1")
        for (name in listOf("activity", "service")) {
            val decl = binder.declarationIndex.declarations.single {
                it.name == name && it.origin == DeclarationOrigin.BUILTIN
            }
            assertIs<ClassType>(assertNotNull(decl.declaredType))
            assertEquals(DeclarationKind.GLOBAL, decl.kind)
        }
    }

    private fun loadAndroluaOverlay() =
        BuiltinOverlayLoader.load(LuaVersion.ANDROLUA_5_3) { _, _ -> WorkspaceSnapshot.FileSnapshot() }

    private fun bindWithAndroluaGlobals(
        globals: BuiltinOverlaySnapshot.GlobalsSnapshot,
        source: String
    ): BinderPassResult {
        val chunk = parser.parse(source)
        return BinderPass(builtinGlobals = globals).bind(chunk, CommentAttachPass().attach(chunk))
    }

    private fun DeclarationKind.toApiSymbolKind(): SymbolKind = when (this) {
        DeclarationKind.LOCAL -> SymbolKind.LOCAL
        DeclarationKind.GLOBAL -> SymbolKind.VARIABLE
        DeclarationKind.FUNCTION -> SymbolKind.FUNCTION
        DeclarationKind.MODULE -> SymbolKind.MODULE
        DeclarationKind.PARAMETER -> SymbolKind.PARAMETER
        DeclarationKind.CLASS -> SymbolKind.CLASS
        DeclarationKind.TYPE_ALIAS -> SymbolKind.TYPE_ALIAS
        DeclarationKind.TYPE_PARAMETER -> SymbolKind.TYPE_ALIAS
        DeclarationKind.FIELD -> SymbolKind.FIELD
        DeclarationKind.METHOD -> SymbolKind.METHOD
    }
}
