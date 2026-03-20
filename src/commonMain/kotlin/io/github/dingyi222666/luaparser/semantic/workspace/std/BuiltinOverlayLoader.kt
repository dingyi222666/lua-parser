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
        catalog.providerModuleResourcePaths.forEach { (moduleName, resourcePath) ->
            val path = overlayModulePath(catalog.versionSegment, moduleName)
            providerModules[path] = BuiltinOverlaySnapshot.ProviderModuleSnapshot(
                moduleName = moduleName,
                file = analyze(path, moduleSource(moduleName, resourcePath))
            )
        }
        val globalsPath = overlayGlobalsPath(catalog.versionSegment)
        val globals = BuiltinOverlaySnapshot.GlobalsSnapshot.create(
            path = globalsPath,
            file = analyze(globalsPath, globalsSource(catalog)),
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

    private fun moduleSource(moduleName: String, resourcePath: String): String {
        val resourceText = BuiltinOverlayResourceAccess.readText(resourcePath)
        val members = extractModuleMembers(moduleName, resourceText)
        return buildString {
            append("local ")
            append(moduleName)
            append(" = {}\n")
            members.forEach { memberName ->
                append("function ")
                append(moduleName)
                append('.')
                append(memberName)
                append("(...) end\n")
            }
            append("return ")
            append(moduleName)
        }
    }

    private fun globalsSource(catalog: Catalog): String {
        return buildString {
            catalog.globalNames.forEach { name ->
                append(name)
                append(" = ")
                append(name)
                append('\n')
            }
            catalog.compatibilityGlobalsSource.trim()
                .takeIf { it.isNotEmpty() }
                ?.let {
                    append('\n')
                    append(it)
                }
        }.trim()
    }

    private fun extractModuleMembers(moduleName: String, resourceText: String): Set<String> {
        val functionRegex = Regex("""function\s+""" + Regex.escape(moduleName) + """[.:]([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
        val assignmentRegex = Regex("""(?:^|\n)\s*""" + Regex.escape(moduleName) + """\.([A-Za-z_][A-Za-z0-9_]*)\s*=""")
        return buildSet {
            functionRegex.findAll(resourceText).forEach { add(it.groupValues[1]) }
            assignmentRegex.findAll(resourceText).forEach { add(it.groupValues[1]) }
        }
    }

    private fun overlayModulePath(versionSegment: String, moduleName: String): VirtualPath =
        VirtualPath.of("__lua_std__/$versionSegment/$moduleName.lua")

    private fun overlayGlobalsPath(versionSegment: String): VirtualPath =
        VirtualPath.of("__lua_std__/$versionSegment/_G.lua")

    private fun catalogFor(version: LuaVersion): Catalog = when (normalize(version)) {
        LuaVersion.LUA_5_3 -> Catalog(
            normalizedVersion = LuaVersion.LUA_5_3,
            versionSegment = "5.3",
            providerModuleResourcePaths = linkedMapOf(
                "coroutine" to "/io/github/dingyi222666/luaparser/semantic/workspace/std/lua53/coroutine.lua",
                "debug" to "/io/github/dingyi222666/luaparser/semantic/workspace/std/lua53/debug.lua",
                "io" to "/io/github/dingyi222666/luaparser/semantic/workspace/std/lua53/io.lua",
                "math" to "/io/github/dingyi222666/luaparser/semantic/workspace/std/lua53/math.lua",
                "os" to "/io/github/dingyi222666/luaparser/semantic/workspace/std/lua53/os.lua",
                "package" to "/io/github/dingyi222666/luaparser/semantic/workspace/std/lua53/package.lua",
                "string" to "/io/github/dingyi222666/luaparser/semantic/workspace/std/lua53/string.lua",
                "table" to "/io/github/dingyi222666/luaparser/semantic/workspace/std/lua53/table.lua",
                "utf8" to "/io/github/dingyi222666/luaparser/semantic/workspace/std/lua53/utf8.lua"
            ),
            compatibilityGlobalsSource = """
                bit32 = bit32
                function package.seeall(module) end
            """.trimIndent(),
            globalNames = linkedSetOf(
                "_G", "_VERSION", "assert", "bit32", "collectgarbage", "coroutine", "debug", "dofile",
                "error", "getmetatable", "io", "ipairs", "load", "loadfile", "math", "module", "next",
                "os", "package", "pairs", "pcall", "print", "rawequal", "rawget", "rawlen", "rawset",
                "require", "select", "setmetatable", "string", "table", "tonumber", "tostring", "type",
                "utf8", "xpcall"
            ),
            moduleFieldNames = linkedMapOf(
                "bit32" to linkedSetOf("band"),
                "coroutine" to linkedSetOf("create"),
                "debug" to linkedSetOf("traceback"),
                "io" to linkedSetOf("open"),
                "math" to linkedSetOf("abs"),
                "os" to linkedSetOf("clock"),
                "package" to linkedSetOf("loaded", "searchpath", "seeall"),
                "string" to linkedSetOf("format"),
                "table" to linkedSetOf("insert"),
                "utf8" to linkedSetOf("len")
            )
        )

        LuaVersion.LUA_5_4 -> Catalog(
            normalizedVersion = LuaVersion.LUA_5_4,
            versionSegment = "5.4",
            providerModuleResourcePaths = linkedMapOf(
                "coroutine" to "/io/github/dingyi222666/luaparser/semantic/workspace/std/lua54/coroutine.lua",
                "debug" to "/io/github/dingyi222666/luaparser/semantic/workspace/std/lua54/debug.lua",
                "io" to "/io/github/dingyi222666/luaparser/semantic/workspace/std/lua54/io.lua",
                "math" to "/io/github/dingyi222666/luaparser/semantic/workspace/std/lua54/math.lua",
                "os" to "/io/github/dingyi222666/luaparser/semantic/workspace/std/lua54/os.lua",
                "package" to "/io/github/dingyi222666/luaparser/semantic/workspace/std/lua54/package.lua",
                "string" to "/io/github/dingyi222666/luaparser/semantic/workspace/std/lua54/string.lua",
                "table" to "/io/github/dingyi222666/luaparser/semantic/workspace/std/lua54/table.lua",
                "utf8" to "/io/github/dingyi222666/luaparser/semantic/workspace/std/lua54/utf8.lua"
            ),
            compatibilityGlobalsSource = """
                warn = warn
                function package.seeall(module) end
            """.trimIndent(),
            globalNames = linkedSetOf(
                "_G", "_VERSION", "assert", "collectgarbage", "coroutine", "debug", "dofile", "error",
                "getmetatable", "io", "ipairs", "load", "loadfile", "math", "next", "os", "package",
                "pairs", "pcall", "print", "rawequal", "rawget", "rawlen", "rawset", "require", "select",
                "setmetatable", "string", "table", "tonumber", "tostring", "type", "utf8", "warn", "xpcall"
            ),
            moduleFieldNames = linkedMapOf(
                "coroutine" to linkedSetOf("create"),
                "debug" to linkedSetOf("traceback"),
                "io" to linkedSetOf("open"),
                "math" to linkedSetOf("abs"),
                "os" to linkedSetOf("clock"),
                "package" to linkedSetOf("loaded", "searchpath", "seeall"),
                "string" to linkedSetOf("format"),
                "table" to linkedSetOf("insert"),
                "utf8" to linkedSetOf("len")
            )
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
        val providerModuleResourcePaths: Map<String, String>,
        val compatibilityGlobalsSource: String,
        val globalNames: Set<String>,
        val moduleFieldNames: Map<String, Set<String>>
    )
}
