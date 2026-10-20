package semantic.workspace

import io.github.dingyi222666.luaparser.semantic.workspace.AnalysisProgress
import io.github.dingyi222666.luaparser.semantic.workspace.DocumentFacts
import io.github.dingyi222666.luaparser.semantic.workspace.LegacyModuleEnvironment
import io.github.dingyi222666.luaparser.semantic.workspace.ModuleExportSurface
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceModuleGraph
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspacePublicFingerprint
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceDelta
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceSnapshot
import io.github.dingyi222666.luaparser.semantic.workspace.std.BuiltinOverlayLoader
import io.github.dingyi222666.luaparser.semantic.types.model.AliasType
import io.github.dingyi222666.luaparser.semantic.types.model.ClassType
import io.github.dingyi222666.luaparser.semantic.types.model.CustomType
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionParameter
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.model.ModuleType
import io.github.dingyi222666.luaparser.semantic.types.model.Type
import io.github.dingyi222666.luaparser.semantic.types.model.TypeParameterType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WorkspaceFoundationTest {
    @Test
    fun virtualPath_normalizes_separators_and_preserves_relative_identity() {
        val path = VirtualPath.of("src\\lua/./module.lua")

        assertEquals("src/lua/module.lua", path.value)
        assertEquals(path, VirtualPath.of("src/lua/module.lua"))
    }

    @Test
    fun workspaceDelta_represents_upserts_and_removals_separately() {
        val delta = WorkspaceDelta(
            upserts = mapOf(VirtualPath.of("a.lua") to "return 1"),
            removals = setOf(VirtualPath.of("b.lua"))
        )

        assertEquals(setOf(VirtualPath.of("b.lua")), delta.removals)
        assertEquals("return 1", delta.upserts.getValue(VirtualPath.of("a.lua")))
        assertFalse(delta.isEmpty())
    }

    @Test
    fun analysisProgress_carries_phase_and_file_counts() {
        val progress = AnalysisProgress(
            phase = AnalysisProgress.Phase.BINDING,
            currentFile = VirtualPath.of("src/main.lua"),
            completedFiles = 2,
            totalFiles = 5
        )

        assertEquals(AnalysisProgress.Phase.BINDING, progress.phase)
        assertEquals(VirtualPath.of("src/main.lua"), progress.currentFile)
        assertEquals(2, progress.completedFiles)
        assertEquals(5, progress.totalFiles)
        assertTrue(progress.completedFiles < progress.totalFiles)
    }

    @Test
    fun workspaceSnapshot_fileSnapshot_can_carry_documentFacts_signature() {
        val path = VirtualPath.of("src/module.lua")
        val facts = DocumentFacts(
            path = path,
            fingerprint = "facts-1"
        )
        val snapshot = WorkspaceSnapshot(
            files = mapOf(
                path to WorkspaceSnapshot.FileSnapshot(
                    cacheKey = "cache-1",
                    documentFacts = facts,
                    factsSignature = facts.fingerprint
                )
            )
        )

        assertEquals("facts-1", snapshot.files.getValue(path).factsSignature)
    }

    @Test
    fun workspaceSnapshot_fileSnapshot_can_carry_module_extraction_payloads() {
        val path = VirtualPath.of("src/module.lua")
        val environment = LegacyModuleEnvironment.EMPTY
        val exportSurface = ModuleExportSurface(
            moduleType = ModuleType(moduleName = "src.module"),
            sourceForm = ModuleExportSurface.SourceForm.RETURN_IDENTIFIER
        )
        val publicFingerprint = WorkspacePublicFingerprint.from(
            documentFacts = DocumentFacts(path = path, fingerprint = "facts-1"),
            moduleExportSurface = exportSurface
        )
        val snapshot = WorkspaceSnapshot(
            files = mapOf(
                path to WorkspaceSnapshot.FileSnapshot(
                    legacyModuleEnvironment = environment,
                    moduleExportSurface = exportSurface,
                    publicFingerprint = publicFingerprint
                )
            )
        )

        assertEquals(environment, snapshot.files.getValue(path).legacyModuleEnvironment)
        assertEquals(exportSurface, snapshot.files.getValue(path).moduleExportSurface)
        assertEquals(publicFingerprint, snapshot.files.getValue(path).publicFingerprint)
    }

    @Test
    fun workspaceSnapshot_carries_graph_and_public_fingerprint_payloads() {
        val path = VirtualPath.of("src/module.lua")
        val fingerprint = WorkspacePublicFingerprint(
            providedModuleNames = setOf("src.module"),
            value = "public-1"
        )
        val graph = WorkspaceModuleGraph(
            activeProviders = mapOf(
                "src.module" to WorkspaceModuleGraph.ModuleProvider(
                    moduleName = "src.module",
                    path = path,
                    source = WorkspaceModuleGraph.ProviderSource.VIRTUAL_PATH
                )
            )
        )
        val snapshot = WorkspaceSnapshot(
            files = mapOf(path to WorkspaceSnapshot.FileSnapshot(publicFingerprint = fingerprint)),
            graph = graph
        )

        assertEquals(fingerprint, snapshot.files.getValue(path).publicFingerprint)
        assertEquals(graph, snapshot.graph)
    }

    @Test
    fun workspaceSnapshot_carries_builtin_overlay_separately_from_files() {
        val path = VirtualPath.of("src/module.lua")
        val overlay = BuiltinOverlayLoader.load(io.github.dingyi222666.luaparser.parser.LuaVersion.LUA_5_3) { _, _ ->
            WorkspaceSnapshot.FileSnapshot(cacheKey = "builtin")
        }
        val snapshot = WorkspaceSnapshot(
            files = mapOf(path to WorkspaceSnapshot.FileSnapshot(cacheKey = "user")),
            builtinOverlay = overlay
        )

        assertEquals(setOf(path), snapshot.files.keys)
        assertTrue(snapshot.builtinOverlay.providerModules.isNotEmpty())
        assertFalse(snapshot.builtinOverlay.providerModules.keys.any { it in snapshot.files.keys })
    }

    @Test
    fun workspacePublicFingerprint_changes_when_only_function_type_parameters_change() {
        val before = fingerprintForExport(
            FunctionType(
                parameters = listOf(
                    FunctionParameter(
                        name = "value",
                        type = CustomType("T")
                    )
                ),
                returnType = CustomType("T"),
                typeParameters = listOf(TypeParameterType(name = "T"))
            )
        )
        val after = fingerprintForExport(
            FunctionType(
                parameters = listOf(
                    FunctionParameter(
                        name = "value",
                        type = CustomType("T")
                    )
                ),
                returnType = CustomType("T"),
                typeParameters = listOf(
                    TypeParameterType(
                        name = "T",
                        constraint = PrimitiveType.STRING
                    )
                )
            )
        )

        assertNotEquals(before.value, after.value)
    }

    @Test
    fun workspacePublicFingerprint_changes_when_only_class_superclass_changes() {
        val before = fingerprintForExport(
            ClassType(
                name = "Widget",
                superClass = ClassType(name = "BaseWidget")
            )
        )
        val after = fingerprintForExport(
            ClassType(
                name = "Widget",
                superClass = ClassType(name = "RenderableWidget")
            )
        )

        assertNotEquals(before.value, after.value)
    }

    @Test
    fun workspacePublicFingerprint_changes_when_only_class_alias_changes() {
        val before = fingerprintForExport(
            ClassType(
                name = "Widget",
                alias = AliasType(name = "WidgetAlias", target = PrimitiveType.STRING)
            )
        )
        val after = fingerprintForExport(
            ClassType(
                name = "Widget",
                alias = AliasType(name = "WidgetAlias", target = PrimitiveType.NUMBER)
            )
        )

        assertNotEquals(before.value, after.value)
    }

    @Test
    fun workspacePublicFingerprint_changes_when_only_class_type_parameters_change() {
        val before = fingerprintForExport(
            ClassType(
                name = "Widget",
                typeParameters = listOf(TypeParameterType(name = "T"))
            )
        )
        val after = fingerprintForExport(
            ClassType(
                name = "Widget",
                typeParameters = listOf(
                    TypeParameterType(
                        name = "T",
                        constraint = PrimitiveType.STRING
                    )
                )
            )
        )

        assertNotEquals(before.value, after.value)
    }

    private fun fingerprintForExport(type: Type): WorkspacePublicFingerprint = WorkspacePublicFingerprint.from(
        documentFacts = null,
        moduleExportSurface = ModuleExportSurface(
            moduleType = ModuleType(
                moduleName = "test.module",
                fields = mapOf("export" to type)
            ),
            sourceForm = ModuleExportSurface.SourceForm.RETURN_IDENTIFIER
        )
    )
}
