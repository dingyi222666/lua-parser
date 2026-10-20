package semantic.support

import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceInput
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceQueryFacade
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceSnapshot

class WorkspaceSemanticHarness private constructor(
    val files: Map<VirtualPath, String>,
    val snapshot: WorkspaceSnapshot,
    val queries: LuaWorkspaceQueryFacade
) {
    fun path(value: String): VirtualPath = VirtualPath.of(value)

    /**
     * True when the workspace snapshot registered a synthetic JVM class provider for [className]
     * (e.g. `android.widget.TextView` → `__jvm__/classes/android/widget/TextView.lua`).
     * Used by integration soft-skip paths when host `android.jar` is absent.
     */
    fun hasJvmClassProvider(className: String): Boolean {
        val providerPath = VirtualPath.of("__jvm__/classes/${className.replace('.', '/')}.lua")
        return providerPath in snapshot.extraProviders
    }

    fun positionOf(path: String, needle: String, occurrence: Int = 1): Position {
        val source = files.getValue(VirtualPath.of(path))
        var index = -1
        repeat(occurrence) {
            index = source.indexOf(needle, index + 1)
            check(index >= 0) { "Missing occurrence $occurrence of '$needle' in $path." }
        }

        var line = 1
        var column = 1
        for (i in 0 until index) {
            if (source[i] == '\n') {
                line += 1
                column = 1
            } else {
                column += 1
            }
        }
        return Position(line, column)
    }

    companion object {
        fun build(
            vararg files: Pair<String, String>,
            standardLibraryOverlayVersion: LuaVersion = LuaVersion.ANDROLUA_5_3,
            metadata: Map<String, String> = emptyMap(),
            engine: LuaWorkspaceEngine = LuaWorkspaceEngine()
        ): WorkspaceSemanticHarness {
            val mapped = files.associate { (path, source) -> VirtualPath.of(path) to source }
            val snapshot = engine.build(
                LuaWorkspaceInput(
                    files = mapped,
                    metadata = metadata,
                    standardLibraryOverlayVersion = standardLibraryOverlayVersion
                )
            ).snapshot
            return WorkspaceSemanticHarness(mapped, snapshot, LuaWorkspaceQueryFacade(snapshot))
        }

        /**
         * Build a workspace that preserves external multi-file relative paths (full-tree mode).
         *
         * Unlike [build], callers pass workspace-relative paths that match the on-disk layout under
         * an Android-Lua `app/src/main` root (e.g. `assets/main.lua`, `resources/lua/import.lua`).
         * Sources are still provided as text pairs (loaded by the caller from external files);
         * nothing is inlined as hardcoded product fixtures inside the harness.
         *
         * When [fullTreeMode] is true (default), path keys are left as-is so
         * `resolveRequire(..., "import")` can bind to providers whose [VirtualPath] ends with the
         * real relative source path rather than harness-remapped synthetic names like `import.lua`.
         */
        fun buildExternalRelative(
            vararg files: Pair<String, String>,
            standardLibraryOverlayVersion: LuaVersion = LuaVersion.ANDROLUA_5_3,
            metadata: Map<String, String> = emptyMap(),
            engine: LuaWorkspaceEngine = LuaWorkspaceEngine(),
            fullTreeMode: Boolean = true
        ): WorkspaceSemanticHarness {
            val mapped = linkedMapOf<VirtualPath, String>()
            for ((rawPath, source) in files) {
                val normalized = normalizeExternalRelativePath(rawPath, fullTreeMode = fullTreeMode)
                mapped[VirtualPath.of(normalized)] = source
            }
            val snapshot = engine.build(
                LuaWorkspaceInput(
                    files = mapped,
                    metadata = metadata,
                    standardLibraryOverlayVersion = standardLibraryOverlayVersion
                )
            ).snapshot
            return WorkspaceSemanticHarness(mapped, snapshot, LuaWorkspaceQueryFacade(snapshot))
        }

        /**
         * Normalize a host-relative Android-Lua path for workspace virtual paths.
         * Full-tree mode keeps `assets/…` / `resources/lua/…` segments; synthetic mode flattens
         * known runtime helpers to basename keys for legacy harnesses.
         */
        fun normalizeExternalRelativePath(path: String, fullTreeMode: Boolean = true): String {
            val normalized = path.replace('\\', '/').trim().removePrefix("./")
            require(normalized.isNotEmpty()) { "External relative path cannot be empty." }
            require(!normalized.startsWith('/')) {
                "External relative path must be workspace-relative, got: $path"
            }
            if (fullTreeMode) {
                return VirtualPath.of(normalized).value
            }
            // Legacy synthetic remaps used by older corpus gates.
            return when {
                normalized == "resources/lua/import.lua" || normalized.endsWith("/resources/lua/import.lua") ->
                    "import.lua"
                normalized == "resources/lua/loadlayout.lua" || normalized.endsWith("/resources/lua/loadlayout.lua") ->
                    "loadlayout.lua"
                normalized == "assets/main.lua" || normalized.endsWith("/assets/main.lua") ->
                    "asset-main.lua"
                else -> VirtualPath.of(normalized.substringAfterLast('/')).value
            }
        }
    }
}
