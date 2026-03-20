package io.github.dingyi222666.luaparser.semantic.workspace

import io.github.dingyi222666.luaparser.parser.LuaVersion

data class LuaWorkspaceInput(
    val files: Map<VirtualPath, String>,
    val metadata: Map<String, String> = emptyMap(),
    val standardLibraryOverlayVersion: LuaVersion = LuaVersion.LUA_5_3
)
