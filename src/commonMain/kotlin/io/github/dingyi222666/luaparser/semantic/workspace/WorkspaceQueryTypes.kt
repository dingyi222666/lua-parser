package io.github.dingyi222666.luaparser.semantic.workspace

import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.parser.ast.node.Range
import io.github.dingyi222666.luaparser.semantic.api.Symbol
import io.github.dingyi222666.luaparser.semantic.api.TypeInfo

data class WorkspaceLocation(
    val path: VirtualPath,
    val range: Range
)

data class WorkspaceModuleLookupResult(
    val moduleName: String,
    val provider: WorkspaceModuleGraph.ModuleProvider?,
    val exportSurface: ModuleExportSurface?
)

data class WorkspaceHoverResult(
    val path: VirtualPath,
    val position: Position,
    val symbol: Symbol?,
    val typeInfo: TypeInfo?
)
