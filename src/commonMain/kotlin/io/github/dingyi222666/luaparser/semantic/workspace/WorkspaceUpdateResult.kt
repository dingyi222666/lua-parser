package io.github.dingyi222666.luaparser.semantic.workspace

data class WorkspaceUpdateResult(
    val snapshot: WorkspaceSnapshot,
    val changedFiles: Set<VirtualPath>,
    val publicSurfaceChangedFiles: Set<VirtualPath>,
    val activeProviderChangedModuleNames: Set<String>,
    val filesWithRequireResolutionChanged: Set<VirtualPath>,
    val affectedDocuments: Set<VirtualPath>,
    val affectedModuleNames: Set<String>
)
