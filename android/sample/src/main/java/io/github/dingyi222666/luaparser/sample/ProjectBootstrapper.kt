package io.github.dingyi222666.luaparser.sample

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * Materializes the bundled demo project from `assets/project` into
 * `filesDir/project`.
 *
 * The bundled project is the REAL demo corpus from
 * `tools/monaco-lsp-demo/workspace` (main.lua + adapter/ + model/ + mods/ +
 * views/ + layout/ + image/ + libs/classes.dex), not a hand-written
 * sample.lua — so the in-app LSP exercises cross-file workspace resolution
 * (`require("mods.util")`, `layout/<name>.aly` references, and dex mounting
 * through jvm.classpath) exactly like the web demo does.
 *
 * Why a manifest: [android.content.res.AssetManager] cannot enumerate asset
 * directories (it only opens known paths), so `assets/project/manifest.txt`
 * ships one project-relative path per line and the bootstrapper copies
 * exactly those entries, creating parent directories as needed.
 *
 * Versioning: the SHA-256 hex digest of the manifest bytes is the project
 * version. It is persisted in `filesDir/.project-version` (deliberately
 * OUTSIDE the workspace so the LSP never indexes the marker itself). On the
 * first run the marker is missing and every entry is copied; when a newer
 * app build ships a different corpus, the manifest changes, the stored hash
 * no longer matches, and every listed entry is re-copied over the existing
 * workspace. The fast path (hash matches AND all entries already exist, which
 * also heals an interrupted copy) does no I/O beyond the manifest read.
 *
 * Leftover files from an older corpus are intentionally NOT deleted — this is
 * a demo workspace a user may have edited; overwriting the shipped entries is
 * the safe contract.
 */
object ProjectBootstrapper {

    /** Asset root holding the demo corpus + [MANIFEST_ASSET]. */
    const val PROJECT_ASSET_DIR: String = "project"

    /** One project-relative path per line, LF-separated, sorted. */
    const val MANIFEST_ASSET: String = "$PROJECT_ASSET_DIR/manifest.txt"

    /**
     * Version marker (manifest SHA-256) in filesDir — outside the workspace
     * so the language server never indexes it.
     */
    const val VERSION_MARKER_FILE: String = ".project-version"

    private const val TAG = "ProjectBootstrapper"

    /**
     * Ensures `filesDir/project` mirrors `assets/project`, returning the
     * workspace directory. Blocking I/O — call from a background dispatcher.
     */
    fun ensureWorkspace(context: Context): File {
        val projectDir = File(context.filesDir, PROJECT_ASSET_DIR).apply { mkdirs() }
        val manifestBytes = context.assets.open(MANIFEST_ASSET).use { it.readBytes() }
        val version = sha256Hex(manifestBytes)
        val marker = File(context.filesDir, VERSION_MARKER_FILE)
        val entries = String(manifestBytes, Charsets.UTF_8)
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()

        val upToDate = marker.isFile &&
            marker.readText().trim() == version &&
            entries.all { File(projectDir, it).isFile }
        if (upToDate) {
            Log.i(TAG, "demo project up to date ($version)")
            return projectDir
        }

        Log.i(TAG, "(re)copying ${entries.size} demo project files (version $version)")
        for (entry in entries) {
            // Sanitize: manifest paths are project-relative and must not escape
            // the workspace (guards against a hand-edited/committed manifest).
            require(!entry.contains("..") && !entry.startsWith('/')) {
                "illegal manifest entry: $entry"
            }
            val target = File(projectDir, entry)
            target.parentFile?.mkdirs()
            context.assets.open("$PROJECT_ASSET_DIR/$entry").use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
        marker.writeText(version)
        return projectDir
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
