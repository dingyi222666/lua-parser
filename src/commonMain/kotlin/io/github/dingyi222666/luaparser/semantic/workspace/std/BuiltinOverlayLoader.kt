package io.github.dingyi222666.luaparser.semantic.workspace.std

import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceSnapshot

object BuiltinOverlayLoader {
    fun load(
        version: LuaVersion,
        analyze: (VirtualPath, String) -> WorkspaceSnapshot.FileSnapshot
    ): BuiltinOverlaySnapshot {
        val catalog = catalogFor(version)
        val providerModules = linkedMapOf<VirtualPath, BuiltinOverlaySnapshot.ProviderModuleSnapshot>()
        catalog.providerModuleSources.forEach { (moduleName, source) ->
            val path = overlayModulePath(catalog.versionSegment, moduleName)
            providerModules[path] = BuiltinOverlaySnapshot.ProviderModuleSnapshot(
                moduleName = moduleName,
                file = analyze(path, source)
            )
        }
        val globalsPath = overlayGlobalsPath(catalog.versionSegment)
        val globals = BuiltinOverlaySnapshot.GlobalsSnapshot.create(
            path = globalsPath,
            file = analyze(globalsPath, catalog.globalsSource),
            globalNames = catalog.globalNames,
            moduleFieldNames = catalog.moduleFieldNames
        )

        return BuiltinOverlaySnapshot(
            version = catalog.normalizedVersion,
            providerModules = providerModules,
            globals = globals
        )
    }

    fun standaloneGlobals(version: LuaVersion = LuaVersion.LUA_5_3): BuiltinOverlaySnapshot.GlobalsSnapshot {
        val overlay = load(version) { _, _ -> WorkspaceSnapshot.FileSnapshot() }
        return overlay.globals
    }

    private fun overlayModulePath(versionSegment: String, moduleName: String): VirtualPath =
        VirtualPath.of("__lua_std__/$versionSegment/$moduleName.lua")

    private fun overlayGlobalsPath(versionSegment: String): VirtualPath =
        VirtualPath.of("__lua_std__/$versionSegment/_G.lua")

    private fun catalogFor(version: LuaVersion): Catalog = when (normalize(version)) {
        LuaVersion.LUA_5_3 -> Catalog(
            normalizedVersion = LuaVersion.LUA_5_3,
            versionSegment = "5.3",
            providerModuleSources = Lua53BuiltinOverlaySources.providerModuleSources,
            globalsSource = Lua53BuiltinOverlaySources.globalsSource,
            globalNames = Lua53BuiltinOverlaySources.globalNames,
            moduleFieldNames = Lua53BuiltinOverlaySources.moduleFieldNames
        )

        LuaVersion.LUA_5_4 -> Catalog(
            normalizedVersion = LuaVersion.LUA_5_4,
            versionSegment = "5.4",
            providerModuleSources = Lua54BuiltinOverlaySources.providerModuleSources,
            globalsSource = Lua54BuiltinOverlaySources.globalsSource,
            globalNames = Lua54BuiltinOverlaySources.globalNames,
            moduleFieldNames = Lua54BuiltinOverlaySources.moduleFieldNames
        )

        LuaVersion.ANDROLUA_5_3 -> error("normalize(version) should collapse ANDROLUA_5_3 before catalog selection")
    }

    private fun normalize(version: LuaVersion): LuaVersion = when (version) {
        LuaVersion.ANDROLUA_5_3 -> LuaVersion.LUA_5_3
        else -> version
    }

    private data class Catalog(
        val normalizedVersion: LuaVersion,
        val versionSegment: String,
        val providerModuleSources: Map<String, String>,
        val globalsSource: String,
        val globalNames: Set<String>,
        val moduleFieldNames: Map<String, Set<String>>
    )
}
