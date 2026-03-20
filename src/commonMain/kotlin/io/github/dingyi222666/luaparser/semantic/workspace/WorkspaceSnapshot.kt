package io.github.dingyi222666.luaparser.semantic.workspace

import io.github.dingyi222666.luaparser.semantic.workspace.std.BuiltinOverlaySnapshot

data class WorkspaceSnapshot(
    val files: Map<VirtualPath, FileSnapshot> = emptyMap(),
    val builtinOverlay: BuiltinOverlaySnapshot = BuiltinOverlaySnapshot.EMPTY,
    val graph: WorkspaceModuleGraph = WorkspaceModuleGraph.EMPTY
) {
    data class FileSnapshot(
        val cacheKey: String? = null,
        val documentFacts: DocumentFacts? = null,
        val factsSignature: String? = null,
        val legacyModuleEnvironment: LegacyModuleEnvironment? = null,
        val moduleExportSurface: ModuleExportSurface? = null,
        val publicFingerprint: WorkspacePublicFingerprint? = null,
        val semanticFile: WorkspaceSemanticFile? = null
    )
}
