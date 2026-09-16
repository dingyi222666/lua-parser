package io.github.dingyi222666.luaparser.semantic.workspace

import io.github.dingyi222666.luaparser.semantic.WorkspaceImportedSymbol

/**
 * Engine-level cache for cross-file shared-global resolution. Keyed by provider source
 * content so entries survive across resolvers and keystrokes unchanged; a provider whose
 * source changed is re-evaluated once and the entry is replaced.
 *
 * Single-threaded by contract: workspace updates and queries run under the LSP state
 * lock (the analyzer never evaluates concurrently).
 */
internal class WorkspaceSharedGlobalIndex {
    class Entry(val source: String?, val symbols: List<WorkspaceImportedSymbol>)

    val providerGlobals = HashMap<VirtualPath, Entry>()
    val aliasResolutions = HashMap<String, WorkspaceImportedSymbol>()
    val inFlight = HashSet<String>()
}
