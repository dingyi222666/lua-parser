package io.github.dingyi222666.luaparser.semantic.workspace.std

import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceSnapshot
import io.github.dingyi222666.luaparser.semantic.workspace.workspaceFingerprintHash

data class BuiltinOverlaySnapshot(
    val version: LuaVersion,
    val providerModules: Map<VirtualPath, ProviderModuleSnapshot>,
    val globals: GlobalsSnapshot
) {
    data class ProviderModuleSnapshot(
        val moduleName: String,
        val file: WorkspaceSnapshot.FileSnapshot
    )

    data class GlobalsSnapshot(
        val path: VirtualPath,
        val file: WorkspaceSnapshot.FileSnapshot,
        val globalNames: Set<String>,
        val moduleFieldNames: Map<String, Set<String>>,
        val metadataFingerprint: String
    ) {
        companion object {
            fun create(
                path: VirtualPath,
                file: WorkspaceSnapshot.FileSnapshot,
                globalNames: Set<String>,
                moduleFieldNames: Map<String, Set<String>>
            ): GlobalsSnapshot {
                val normalizedGlobals = globalNames.toSortedSet()
                val normalizedModuleFields = moduleFieldNames
                    .mapValues { (_, fields) -> fields.toSortedSet() }
                    .toSortedMap()
                val fingerprint = buildString {
                    append("globals=")
                    append(normalizedGlobals.joinToString("|"))
                    append('\n')
                    append("modules=")
                    append(normalizedModuleFields.entries.joinToString("|") { (moduleName, fields) ->
                        "$moduleName:${fields.joinToString(",")}"
                    })
                }
                return GlobalsSnapshot(
                    path = path,
                    file = file,
                    globalNames = normalizedGlobals.toCollection(linkedSetOf()),
                    moduleFieldNames = normalizedModuleFields.mapValues { it.value.toCollection(linkedSetOf()) },
                    metadataFingerprint = workspaceFingerprintHash(fingerprint)
                )
            }
        }
    }

    companion object {
        val EMPTY = BuiltinOverlaySnapshot(
            version = LuaVersion.LUA_5_3,
            providerModules = emptyMap(),
            globals = GlobalsSnapshot.create(
                path = VirtualPath.of("__lua_std__/empty/_G.lua"),
                file = WorkspaceSnapshot.FileSnapshot(),
                globalNames = emptySet(),
                moduleFieldNames = emptyMap()
            )
        )
    }
}
