package semantic.binder

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.parser.ast.node.Range
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import io.github.dingyi222666.luaparser.semantic.binder.BinderPass
import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachPass
import io.github.dingyi222666.luaparser.semantic.types.model.ClassType
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionParameter
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.ModuleType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.model.UnknownType
import io.github.dingyi222666.luaparser.semantic.types.model.VarargType
import io.github.dingyi222666.luaparser.semantic.workspace.ModuleExportSurface
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceSnapshot
import io.github.dingyi222666.luaparser.semantic.workspace.std.BuiltinOverlayLoader
import io.github.dingyi222666.luaparser.semantic.workspace.std.BuiltinOverlaySnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Binder BUILTIN origin declaration range corpus (TASK-430).
 *
 * Locks the seeder half of the Android-Lua / overlay pipeline:
 * - AndroLua globals are seeded as [DeclarationOrigin.BUILTIN].
 * - When a single-path overlay [ModuleExportSurface.MemberExport] is present and
 *   qualifies for documented seeding, [BinderDeclaration.range] is taken from
 *   [ModuleExportSurface.MemberExport.range] (stable identity across reloads).
 * - When MemberExport.range is absent (null) — the current AndroLua globals
 *   surface intentionally nulls virtual-document ranges so real-file positions
 *   remain visible — BUILTIN declarations also carry null range.
 *
 * Test-only; no production edits. Verification deferred to review / TASK-043:
 * `jvmTest --tests semantic.binder.BinderBuiltinOriginRangeTddTest`
 */
class BinderBuiltinOriginRangeTddTest {

    private val parser = LuaParser(LuaVersion.ANDROLUA_5_3)

    // -------------------------------------------------------------------------
    // Real AndroLua overlay: MemberExport.range is currently null for globals
    // -------------------------------------------------------------------------

    @Test
    fun androlua_overlay_member_export_activity_service_ranges_are_stable_null() {
        val first = loadAndroluaOverlay()
        val second = loadAndroluaOverlay()

        for (name in listOf("activity", "service")) {
            val a = singlePathMember(first, name)
            val b = singlePathMember(second, name)
            // Product currently nulls documented-global ranges (virtual doc vs real file).
            assertNull(a.range, "$name MemberExport.range must be null on AndroLua globals surface")
            assertNull(b.range, "$name MemberExport.range must stay null across reloads")
            assertEquals(a.range, b.range, "$name MemberExport.range must be stable across reloads")
            assertEquals(listOf(name), a.exportPath)
            assertTrue(a.type !is UnknownType, "$name type must not be UnknownType: ${a.type}")
        }
    }

    @Test
    fun seeder_propagates_null_member_export_range_to_builtin_activity_service() {
        val overlay = loadAndroluaOverlay()
        val members = singlePathMembers(overlay)
        val activityExport = assertNotNull(members["activity"])
        val serviceExport = assertNotNull(members["service"])
        assertNull(activityExport.range)
        assertNull(serviceExport.range)

        val binder = bindWithGlobals(overlay.globals, "return activity")
        val activityDecl = builtinDecl(binder, "activity")
        val serviceDecl = builtinDecl(binder, "service")

        assertEquals(DeclarationOrigin.BUILTIN, activityDecl.origin)
        assertEquals(DeclarationOrigin.BUILTIN, serviceDecl.origin)
        assertEquals(DeclarationKind.GLOBAL, activityDecl.kind)
        assertEquals(DeclarationKind.GLOBAL, serviceDecl.kind)

        // Seeder path: documented.range → declaration.range (null when overlay nulls it).
        assertEquals(activityExport.range, activityDecl.range, "activity BUILTIN range must match MemberExport")
        assertEquals(serviceExport.range, serviceDecl.range, "service BUILTIN range must match MemberExport")
        assertNull(activityDecl.range)
        assertNull(serviceDecl.range)
    }

    @Test
    fun seeder_androlua_documented_globals_match_member_export_range_when_present_or_absent() {
        val overlay = loadAndroluaOverlay()
        val members = singlePathMembers(overlay)
        val binder = bindWithGlobals(overlay.globals, "return 1")

        // Sample of AndroLua-extended globals that the seeder documents via MemberExport.
        val documentedNames = listOf(
            "activity", "service", "loadlayout", "loadbitmap", "loadmenu",
            "import", "print", "require"
        )
        for (name in documentedNames) {
            val export = members[name] ?: continue
            // Only seeder-documented members (FUNCTION or non-Unknown type) receive range.
            val qualifies = export.kind == SymbolKind.FUNCTION || export.type != UnknownType
            if (!qualifies) continue

            val decl = builtinDecl(binder, name)
            assertEquals(
                export.range,
                decl.range,
                "BUILTIN $name range must equal MemberExport.range (export=$export)"
            )
            assertEquals(DeclarationOrigin.BUILTIN, decl.origin)
        }
    }

    @Test
    fun seeder_builtin_ranges_stable_across_two_androlua_binds() {
        val overlay = loadAndroluaOverlay()
        val first = bindWithGlobals(overlay.globals, "return activity")
        val second = bindWithGlobals(overlay.globals, "return activity")

        for (name in listOf("activity", "service", "loadlayout", "print")) {
            val a = builtinDecl(first, name)
            val b = builtinDecl(second, name)
            assertEquals(a.range, b.range, "$name BUILTIN range must be stable across binds")
            assertEquals(DeclarationOrigin.BUILTIN, a.origin)
            assertEquals(DeclarationOrigin.BUILTIN, b.origin)
        }
    }

    // -------------------------------------------------------------------------
    // Synthetic MemberExport with non-null ranges: seeder must copy them
    // -------------------------------------------------------------------------

    @Test
    fun seeder_copies_non_null_member_export_range_onto_builtin_global_decl() {
        val activityRange = Range(Position(10, 1), Position(10, 9))
        val serviceRange = Range(Position(20, 1), Position(20, 8))
        val loadlayoutRange = Range(Position(30, 1), Position(30, 12))
        val printRange = Range(Position(40, 1), Position(40, 6))

        val globals = syntheticAndroluaGlobals(
            members = listOf(
                member("activity", SymbolKind.FIELD, ClassType("LuaActivity"), activityRange),
                member("service", SymbolKind.FIELD, ClassType("LuaService"), serviceRange),
                member(
                    "loadlayout",
                    SymbolKind.FUNCTION,
                    FunctionType(
                        parameters = listOf(
                            FunctionParameter(
                                name = "...",
                                type = VarargType(PrimitiveType.ANY),
                                vararg = true
                            )
                        ),
                        returnType = PrimitiveType.ANY
                    ),
                    loadlayoutRange
                ),
                member(
                    "print",
                    SymbolKind.FUNCTION,
                    FunctionType(
                        parameters = listOf(
                            FunctionParameter(
                                name = "...",
                                type = VarargType(PrimitiveType.ANY),
                                vararg = true
                            )
                        ),
                        returnType = PrimitiveType.NIL
                    ),
                    printRange
                )
            ),
            globalNames = setOf("activity", "service", "loadlayout", "print", "_G", "_VERSION")
        )

        val binder = bindWithGlobals(globals, "return activity")

        val activity = builtinDecl(binder, "activity")
        val service = builtinDecl(binder, "service")
        val loadlayout = builtinDecl(binder, "loadlayout")
        val print = builtinDecl(binder, "print")

        assertEquals(DeclarationOrigin.BUILTIN, activity.origin)
        assertEquals(DeclarationOrigin.BUILTIN, service.origin)
        assertEquals(DeclarationOrigin.BUILTIN, loadlayout.origin)
        assertEquals(DeclarationOrigin.BUILTIN, print.origin)

        assertEquals(DeclarationKind.GLOBAL, activity.kind)
        assertEquals(DeclarationKind.GLOBAL, service.kind)
        assertEquals(DeclarationKind.FUNCTION, loadlayout.kind)
        assertEquals(DeclarationKind.FUNCTION, print.kind)

        assertEquals(activityRange, activity.range)
        assertEquals(serviceRange, service.range)
        assertEquals(loadlayoutRange, loadlayout.range)
        assertEquals(printRange, print.range)

        assertIs<ClassType>(assertNotNull(activity.declaredType))
        assertIs<ClassType>(assertNotNull(service.declaredType))
    }

    @Test
    fun seeder_non_null_member_export_ranges_are_stable_across_rebinds() {
        val activityRange = Range(Position(3, 5), Position(3, 13))
        val globals = syntheticAndroluaGlobals(
            members = listOf(
                member("activity", SymbolKind.FIELD, ClassType("LuaActivity"), activityRange)
            ),
            globalNames = setOf("activity", "_G")
        )

        val first = bindWithGlobals(globals, "return activity")
        val second = bindWithGlobals(globals, "return activity")

        val a = builtinDecl(first, "activity")
        val b = builtinDecl(second, "activity")
        assertEquals(activityRange, a.range)
        assertEquals(activityRange, b.range)
        assertEquals(a.range, b.range)
        assertEquals(DeclarationOrigin.BUILTIN, a.origin)
        assertEquals(DeclarationOrigin.BUILTIN, b.origin)
    }

    @Test
    fun seeder_null_member_export_range_stays_null_on_builtin_even_when_type_documented() {
        val globals = syntheticAndroluaGlobals(
            members = listOf(
                member("activity", SymbolKind.FIELD, ClassType("LuaActivity"), range = null),
                member(
                    "print",
                    SymbolKind.FUNCTION,
                    FunctionType(
                        parameters = listOf(
                            FunctionParameter(
                                name = "...",
                                type = VarargType(PrimitiveType.ANY),
                                vararg = true
                            )
                        ),
                        returnType = PrimitiveType.NIL
                    ),
                    range = null
                )
            ),
            globalNames = setOf("activity", "print", "_G")
        )

        val binder = bindWithGlobals(globals, "return activity")
        val activity = builtinDecl(binder, "activity")
        val print = builtinDecl(binder, "print")

        assertNull(activity.range, "null MemberExport.range must yield null BUILTIN range")
        assertNull(print.range, "null MemberExport.range must yield null BUILTIN function range")
        assertEquals(DeclarationOrigin.BUILTIN, activity.origin)
        assertEquals(DeclarationOrigin.BUILTIN, print.origin)
        assertIs<ClassType>(assertNotNull(activity.declaredType))
    }

    @Test
    fun multi_path_member_export_is_ignored_for_builtin_global_range() {
        // Seeder only associates exportPath.size == 1 members. Multi-path exports
        // must not supply the documented range for a top-level global of the same name.
        val multiOnlyRange = Range(Position(99, 1), Position(99, 20))
        val globals = syntheticAndroluaGlobals(
            members = listOf(
                ModuleExportSurface.MemberExport(
                    name = "activity",
                    exportPath = listOf("helpers", "activity"),
                    kind = SymbolKind.FIELD,
                    type = ClassType("LuaActivity"),
                    range = multiOnlyRange
                )
            ),
            globalNames = setOf("activity", "_G"),
            // activity is a module-field style global when no single-path export qualifies
            moduleFieldNames = mapOf("activity" to emptySet())
        )

        val binder = bindWithGlobals(globals, "return activity")
        val activity = builtinDecl(binder, "activity")
        assertEquals(DeclarationOrigin.BUILTIN, activity.origin)
        // Without a single-path MemberExport, seeder falls back to range-less globalDeclaration.
        assertNull(activity.range, "multi-path MemberExport must not seed global BUILTIN range")
        assertNull(activity.declaredType)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun loadAndroluaOverlay() =
        BuiltinOverlayLoader.load(LuaVersion.ANDROLUA_5_3) { _, _ -> WorkspaceSnapshot.FileSnapshot() }

    private fun singlePathMembers(overlay: BuiltinOverlaySnapshot): Map<String, ModuleExportSurface.MemberExport> =
        overlay.globals.file.moduleExportSurface?.members.orEmpty()
            .filter { it.exportPath.size == 1 }
            .associateBy { it.name }

    private fun singlePathMember(
        overlay: BuiltinOverlaySnapshot,
        name: String
    ): ModuleExportSurface.MemberExport {
        return assertNotNull(
            overlay.globals.file.moduleExportSurface?.members.orEmpty()
                .firstOrNull { it.name == name && it.exportPath == listOf(name) },
            "missing single-path MemberExport $name"
        )
    }

    private fun builtinDecl(binder: BinderPassResult, name: String) =
        assertNotNull(
            binder.declarationIndex.declarations.find {
                it.name == name && it.origin == DeclarationOrigin.BUILTIN
            },
            "missing BUILTIN declaration $name"
        )

    private fun bindWithGlobals(
        globals: BuiltinOverlaySnapshot.GlobalsSnapshot,
        source: String
    ): BinderPassResult {
        val chunk = parser.parse(source)
        return BinderPass(builtinGlobals = globals).bind(chunk, CommentAttachPass().attach(chunk))
    }

    private fun member(
        name: String,
        kind: SymbolKind,
        type: io.github.dingyi222666.luaparser.semantic.types.model.Type,
        range: Range?
    ): ModuleExportSurface.MemberExport = ModuleExportSurface.MemberExport(
        name = name,
        exportPath = listOf(name),
        kind = kind,
        type = type,
        range = range
    )

    private fun syntheticAndroluaGlobals(
        members: List<ModuleExportSurface.MemberExport>,
        globalNames: Set<String>,
        moduleFieldNames: Map<String, Set<String>> = emptyMap()
    ): BuiltinOverlaySnapshot.GlobalsSnapshot {
        val surface = ModuleExportSurface(
            moduleType = ModuleType(moduleName = "_G"),
            sourceForm = ModuleExportSurface.SourceForm.LEGACY_IMPLICIT,
            members = members
        )
        return BuiltinOverlaySnapshot.GlobalsSnapshot.create(
            path = VirtualPath.of("__lua_std__/androlua5.3/_G.lua"),
            file = WorkspaceSnapshot.FileSnapshot(
                cacheKey = "task-430-synthetic-globals",
                moduleExportSurface = surface
            ),
            globalNames = globalNames,
            moduleFieldNames = moduleFieldNames
        )
    }
}
