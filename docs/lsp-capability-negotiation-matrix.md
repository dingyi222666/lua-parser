# LSP Capability Negotiation Matrix

Date: 2026-07-11  
Task: TASK-478 (docs-only)

This matrix documents **how the JVM LSP advertises server capabilities on `initialize`, which client capabilities it reads for dual-path response shapes, and which request methods are implemented vs fail-closed gaps**. It is a focused companion to:

- `docs/language-server-usage.md` — full usage surface, lifecycle, workspace folders, Android-Lua metadata
- `docs/lsp-concurrency-shutdown.md` — lifecycle gates, concurrent open/close, post-shutdown policy
- `docs/lsp-code-action-rename-limits.md` — codeAction / prepareRename / rename fail-closed policy
- `docs/serialized-verification.md` — TASK-043 serial verification ownership

Implementation anchors (inspection only):

| Path | Role |
| --- | --- |
| `src/jvmMain/kotlin/io/github/dingyi222666/luaparser/lsp/LuaLanguageService.kt` | `initialize` captures client caps; `serverCapabilities()` builds `ServerCapabilities` |
| `src/jvmMain/kotlin/io/github/dingyi222666/luaparser/lsp/LuaTextDocumentService.kt` | Text-document request overrides + dual-path `documentSymbol` |
| `src/jvmMain/kotlin/io/github/dingyi222666/luaparser/lsp/LuaWorkspaceService.kt` | Dual-path `workspace/symbol`; configuration / watched files |
| `src/jvmMain/kotlin/io/github/dingyi222666/luaparser/lsp/LuaLanguageServer.kt` | Lifecycle wrapper, request policy, diagnostics publish |

**Honesty bound (post-TASK-184 / pre-TASK-043):** rows describe the product LSP surface as inspected in source after TASK-184 (Android-Lua library stubs done at task level) and after hierarchical document-symbol dual-path (TASK-396 done) plus modern workspace-symbol product wire (TASK-397 in review at docs snapshot). This page does **not** claim TASK-043 serialized suite green, TASK-037 final acceptance, or production readiness. Workers must not run Gradle, tests, or compile; verification is review-owned under TASK-043.

---

## Status labels

| Status | Meaning |
| --- | --- |
| Advertised + wired | `ServerCapabilities` non-null and a product override handles the method. Still pending TASK-043 green. |
| Advertised + dual-path | Same, and response shape branches on a client capability captured at `initialize`. |
| Client-gated only | Server always advertises the provider; client capability only chooses response branch (not whether the method exists). |
| Not advertised (null) | Provider field left null/unset; well-behaved clients should not offer the UX. |
| Gap / fail-closed | Method not overridden; LSP4J default completes exceptionally (`UnsupportedOperationException`) or dual-path corpora accept gap/identity/enriched. |
| Notification accepted | Notification is handled without a server capability advertisement bit (or with sync-only ads). |
| Deferred | Known LSP surface; not claimed as product-complete. |

---

## Host `android.jar` dual-path (never `G:/`)

Capability negotiation itself does not load `android.jar`. Reflective Android/JVM providers that appear in hover/completion/definition after configuration still use host-local jars only.

| Priority | Path / source | Host check (2026-07-11 WAVE36F re-check) |
| --- | --- | --- |
| 1 | Explicit metadata `jvm.androidJar` | Preferred for reproducible analysis |
| 2 | `/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar` | **Present** (~27,092,450 bytes; SDK Platform 35) |
| 3 (optional) | `/Users/dingyi/Downloads/android.jar` | **Absent** on this host; allowed when present |

Rules:

1. **Never hard-code `G:/Android/Sdk/...`** (or any Windows-only path) in new docs, product, or tests. Historical Windows paths may appear only as negative fixtures.
2. Prefer SDK android-35 when the file exists; use Downloads only when that file exists.
3. Missing jar ⇒ skip reflective Android mounts; keep overlay + static providers. Do not invent class members.
4. Capability advertisement does not depend on jar presence.

Preferred SDK path:

```text
/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar
```

Optional Downloads path (only when the file exists):

```text
/Users/dingyi/Downloads/android.jar
```

---

## Negotiation model

### Server → client (`InitializeResult.capabilities`)

Built once per successful `initialize` in `LuaLanguageService.serverCapabilities()`:

```text
textDocumentSync = Full
hoverProvider = HoverOptions
declarationProvider = true
definitionProvider = DefinitionOptions
referencesProvider = ReferenceOptions
documentHighlightProvider = true
completionProvider = CompletionOptions(triggerCharacters = [".", ":"])  // resolveProvider unset
signatureHelpProvider = SignatureHelpOptions(triggers ["(", ","], retrigger [")"])
documentSymbolProvider = DocumentSymbolOptions
workspaceSymbolProvider = WorkspaceSymbolOptions
```

All other common LSP provider fields (rename, codeAction, formatting family, folding, selection range, inlay hints, semantic tokens, call/type hierarchy, moniker, linked editing, diagnostic pull, workspace folder server capability object, etc.) are **left unset / null**.

### Client → server (captured at `initialize`)

Only two client capability bits are currently **read and stored** for dual-path response selection:

| Client capability path | Stored flag | Effect |
| --- | --- | --- |
| `textDocument.documentSymbol.hierarchicalDocumentSymbolSupport == true` | `hierarchicalDocumentSymbolSupport` | `textDocument/documentSymbol` → nested `DocumentSymbol` (`Either.right`) vs flat `SymbolInformation` (`Either.left`) |
| `workspace.symbol.resolveSupport != null` (object presence) | `modernWorkspaceSymbolSupport` | `workspace/symbol` → modern `WorkspaceSymbol` (`Either.right`) vs legacy `SymbolInformation` (`Either.left`) |

Other client capabilities (completion item resolve support, markdown hover content formats, location-link definition support, workspace folders client capability, etc.) are **not** currently used to change advertisement or primary response shape. Location-link navigation remains out of product scope (location lists only).

### Lifecycle gate (orthogonal to capability ads)

Even when a method is advertised, `LuaLanguageServer` only accepts traffic in `INITIALIZED`. Post-`shutdown` / post-`exit` policy is documented in `docs/lsp-concurrency-shutdown.md` (text-document requests: reject after shutdown, quiet after exit; workspace requests rejected when not accepting).

---

## Matrix: advertised text-document capabilities

| Capability / method | Advertised? | Product override? | Client dual-path? | Status | Notes |
| --- | --- | --- | --- | --- | --- |
| `textDocumentSync` | Yes — `TextDocumentSyncKind.Full` | Yes (`didOpen` / `didChange` / `didClose` / `didSave`) | No | Advertised + wired | Ranged change events may be applied in `LuaTextDocumentService` cache before full replace to analysis; advertised kind remains Full. `didSave` accepted, no extra work. |
| `textDocument/hover` | Yes — `HoverOptions` | Yes | No | Advertised + wired | Markdown `MarkupContent` when hover facts exist; null/empty when no symbol. |
| `textDocument/completion` | Yes — `CompletionOptions` triggers `.` `:` | Yes | No | Advertised + wired | Returns `Either.right(CompletionList)`; `isIncomplete=false`. Triggers do not change item list shape. |
| `completionItem/resolve` | **No** (`resolveProvider` unset/null on `CompletionOptions`) | **No** (LSP4J default) | Dual-path **tests** only | Gap / fail-closed | Live path: `UnsupportedOperationException` until product lands. Corpora accept gap / identity / enriched (`LspCompletionItemResolveTddTest`). |
| `textDocument/signatureHelp` | Yes — triggers `(` `,`; retrigger `)` | Yes | No | Advertised + wired | Active signature/parameter from query facade; pending TASK-043 confirmation of edge corpora. |
| `textDocument/declaration` | Yes — boolean `true` | Yes | No | Advertised + wired | Location list (`Either.left`); not `LocationLink`. |
| `textDocument/definition` | Yes — `DefinitionOptions` | Yes | No | Advertised + wired | Location list; require-local alias may navigate to module provider when snapshot allows. |
| `textDocument/references` | Yes — `ReferenceOptions` | Yes | No | Advertised + wired | Snapshot occurrences + provider surfaces. |
| `textDocument/documentHighlight` | Yes — boolean `true` | Yes | No | Advertised + wired | Used as rename-proxy safety floor while rename is absent. |
| `textDocument/documentSymbol` | Yes — `DocumentSymbolOptions` | Yes | **Yes** — hierarchical | Advertised + dual-path | Client `hierarchicalDocumentSymbolSupport` (TASK-396 done at task level). |
| `textDocument/prepareRename` | **No** | **No** | Dual-path safety corpora | Not advertised / gap | See `docs/lsp-code-action-rename-limits.md`. |
| `textDocument/rename` | **No** | **No** | Dual-path safety corpora | Not advertised / gap | Fail-closed exceptional completion. |
| `textDocument/codeAction` | **No** | **No** | Dual-path safety corpora | Not advertised / gap | Fail-closed exceptional completion. |
| `textDocument/formatting` | **No** | **No** | Safety corpora | Not advertised / gap | `LspFormattingFullDocumentSafetyTddTest` dual-path. |
| `textDocument/rangeFormatting` | **No** | **No** | Safety / table-body corpora | Not advertised / gap | |
| `textDocument/onTypeFormatting` | **No** | **No** | Safety / end-keyword corpora | Not advertised / gap | |
| `textDocument/foldingRange` | **No** | **No** | Safety / ranges corpora | Not advertised / gap | |
| `textDocument/selectionRange` | **No** | **No** | Safety / nested-block corpora | Not advertised / gap | |
| `textDocument/inlayHint` | **No** | **No** | Safety / param-name corpora | Not advertised / gap | |
| `textDocument/semanticTokens/*` | **No** | **No** | Safety / basic corpora | Not advertised / gap | |
| `textDocument/prepareCallHierarchy` / call hierarchy | **No** | **No** | Safety / local-function corpora | Not advertised / gap | |
| `textDocument/prepareTypeHierarchy` / type hierarchy | **No** | **No** | Safety / doc-class corpora | Not advertised / gap | |
| `textDocument/linkedEditingRange` | **No** | **No** | Local dual-path corpus | Not advertised / gap | |
| `textDocument/moniker` | **No** | **No** | Safety corpus | Not advertised / gap | |
| Pull `textDocument/diagnostic` | **No** | **No** | N/A | Deferred | Diagnostics are **push** via `publishDiagnostics` on open/change/close/config/watched updates. |

---

## Matrix: workspace capabilities and notifications

| Capability / method | Advertised? | Product override? | Client dual-path? | Status | Notes |
| --- | --- | --- | --- | --- | --- |
| `workspace/symbol` | Yes — `WorkspaceSymbolOptions` | Yes | **Yes** — modern vs legacy | Advertised + dual-path | Client `workspace.symbol.resolveSupport` presence (TASK-397 product wire under review). Helpers: `workspaceSymbols` / `modernWorkspaceSymbols`. |
| `workspaceSymbol/resolve` | Not separately advertised | **No** | N/A | Gap / deferred | Modern list path does not implement symbol resolve round-trip. |
| `workspace/didChangeConfiguration` | N/A (notification) | Yes | No | Notification accepted | Flat + nested `jvm.*` / `androlua.*` keys; rebuild + republish open diagnostics. |
| `workspace/didChangeWatchedFiles` | N/A (notification); server does **not** register watchers | Yes | No | Notification accepted | Client must subscribe; `.lua`/`.aly` create/change/delete update index. |
| Workspace folders **server** capability (`workspace.workspaceFolders`) | **Not set** in `serverCapabilities()` | Partial | No | Deferred ad | Server still **consumes** `InitializeParams.workspaceFolders` and `rootUri` fallback for indexing (TASK-156). Clients should not assume dynamic folder change protocol is advertised. |
| `workspace/didChangeWorkspaceFolders` | Not advertised | **No** (unless delegated elsewhere) | Dual-path tests exist | Gap / partial | Folder set is fixed at `initialize` in current product path; see `LspDidChangeWorkspaceFoldersTddTest` / `LspWorkspaceFoldersTddTest` for intended policy coverage under serial review. |

---

## Dual-path decision tables

### Hierarchical document symbols (TASK-396 done at task level)

Captured:

```text
params.capabilities.textDocument.documentSymbol.hierarchicalDocumentSymbolSupport == true
```

| Client capability | Response | Helper |
| --- | --- | --- |
| `true` | `Either.forRight` nested `DocumentSymbol` list | `hierarchicalDocumentSymbols(path)` |
| `false`, unset, or absent | `Either.forLeft` flattened `SymbolInformation` list | `documentSymbols(path)` |

Server still advertises `documentSymbolProvider = DocumentSymbolOptions` in both cases. Command-level full confirmation remains TASK-043.

### Modern workspace symbols (TASK-397 product wire under review)

Captured:

```text
params.capabilities.workspace.symbol.resolveSupport != null
```

| Client capability | Response | Helper |
| --- | --- | --- |
| `resolveSupport` object present | `Either.forRight` `WorkspaceSymbol` list | `modernWorkspaceSymbols(query)` |
| absent / null symbol caps | `Either.forLeft` `SymbolInformation` list | `workspaceSymbols(query)` |

When the server is not accepting workspace requests, quiet empty responses still pick the same branch shape based on the stored flag. **Not a final-green claim.**

### Completion item resolve (gap)

| Product state | Advertisement | Request outcome |
| --- | --- | --- |
| Current source | `completionProvider` present; `resolveProvider` unset | `resolveCompletionItem` → LSP4J default `UnsupportedOperationException` |
| Future ideal | `resolveProvider = true` | Identity or enriched item; never hard-crash; never invent unrelated labels |

### Rename / codeAction (gap)

| Method | Advertised | Outcome today |
| --- | --- | --- |
| `prepareRename` | No | Exceptional completion |
| `rename` | No | Exceptional completion |
| `codeAction` | No | Exceptional completion |

Safety corpora encode dual-path gap **or** future fail-closed product paths. Details: `docs/lsp-code-action-rename-limits.md`.

---

## Client capability bits intentionally ignored (today)

These may appear on modern clients but do **not** currently change server ads or primary branches:

| Client capability (examples) | Current server reaction |
| --- | --- |
| `textDocument.completion.completionItem.*` / resolve support | Completion still returns full `CompletionList`; resolve remains gap |
| `textDocument.hover.contentFormat` | Hover always Markdown when content exists |
| `textDocument.definition.linkSupport` | Still location lists, not `LocationLink` |
| `workspace.workspaceFolders` client support bit | Folders still read from initialize params; no dynamic folder server ad |
| `general.positionEncodings` / UTF-16 vs UTF-8 | Default LSP position model assumed (UTF-16 code units as LSP positions) |
| Pull diagnostic client capability | Push diagnostics only |

---

## Diagnostics negotiation note

There is **no** `diagnosticProvider` pull capability advertisement. Diagnostics are published to the connected `LanguageClient` via `publishDiagnostics` after:

1. `textDocument/didOpen`
2. `textDocument/didChange` (version-gated on the open-document cache)
3. `textDocument/didClose` (empty list for that URI)
4. `workspace/didChangeConfiguration` (republish open docs after metadata rebuild)
5. `workspace/didChangeWatchedFiles` (republish open docs after index mutation)

After `exit`, the client reference is cleared so publishes stop. Severity mapping: parser recovery → Error; semantic → Error / Warning / Information.

---

## Representative client initialize shapes

### Minimal (legacy flat symbols)

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "initialize",
  "params": {
    "processId": null,
    "rootUri": "file:///Users/dingyi/example-workspace",
    "capabilities": {}
  }
}
```

Expected dual-path branches: flat document symbols; legacy workspace symbols.

### Hierarchical document symbols + modern workspace symbols

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "initialize",
  "params": {
    "processId": null,
    "workspaceFolders": [
      {
        "uri": "file:///Users/dingyi/example-workspace",
        "name": "example-workspace"
      }
    ],
    "capabilities": {
      "textDocument": {
        "documentSymbol": {
          "hierarchicalDocumentSymbolSupport": true
        }
      },
      "workspace": {
        "symbol": {
          "resolveSupport": {
            "properties": ["location.range"]
          }
        }
      }
    }
  }
}
```

Expected dual-path branches: nested `DocumentSymbol`; modern `WorkspaceSymbol` lists. Exact property list inside `resolveSupport` is not further interpreted — **presence** of the object is the gate.

---

## Focused verification pointers (review-owned only)

Workers and documentation waves **must not** run these commands. They are deferred acceptance references for TASK-043 serial verification (macOS primary env).

```bash
export JAVA_HOME=/Users/dingyi/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
```

| Corpus / role | Focused filter (review-only) |
| --- | --- |
| Completion trigger advertisement | `./gradlew.lf jvmTest --tests lsp.LspCompletionCapabilitiesTddTest` |
| Completion item resolve dual-path gap | `./gradlew.lf jvmTest --tests lsp.LspCompletionItemResolveTddTest` |
| Hierarchical document symbols | `./gradlew.lf jvmTest --tests lsp.LspHierarchicalDocumentSymbolTddTest` |
| Navigation / symbols (TASK-396 accept path) | `./gradlew.lf jvmTest --tests lsp.LspNavigationSymbolsTddTest` |
| Modern workspace symbols | `./gradlew.lf jvmTest --tests lsp.LspModernWorkspaceSymbolTddTest` |
| Workspace folders / watched files / URI | `lsp.LspWorkspaceFoldersTddTest`, `lsp.LspWatchedFilesTddTest`, `lsp.LspUriHandlingTddTest` |
| prepareRename / rename / codeAction safety | `lsp.LspPrepareRenameSafetyTddTest`, `lsp.LspCodeActionQuickFixSafetyTddTest` |
| Formatting / folding / selection / inlay / semantic tokens / hierarchy / moniker safety | matching `Lsp*SafetyTddTest` classes under `src/jvmTest/kotlin/lsp` |
| Shared compile gate | `./gradlew.lf compileTestKotlinJvm` |

Do not treat a focused green as global green. **TASK-043 remains blocked; this repository is not finally green.**

---

## Explicit non-claims

- No claim that unadvertised methods are safe to call from production clients.
- No claim that TASK-397 modern workspace-symbol dual-path is serialized-verified or task-done.
- No claim that `LocationLink`, completion resolve, rename, code actions, formatting, folding, selection ranges, inlay hints, semantic tokens, or hierarchies are product-ready.
- No claim that workspace folder **dynamic** change protocol is fully advertised or complete.
- No claim that host `android.jar` presence changes capability ads.
- No claim of `./gradlew check` or full-suite success from this docs-only refresh.
- Final production-readiness remains TASK-037 after TASK-043.

---

## Summary

| Bucket | Current state (source inspection) |
| --- | --- |
| Core navigation + hover + completion + signature help + highlights | Advertised and wired |
| Document / workspace symbols | Advertised; dual-path on two client bits |
| Diagnostics | Push only; no pull capability |
| Completion resolve / rename / codeAction / formatting family / structural extras | Not advertised; fail-closed gap or safety dual-path corpora |
| Client caps read for negotiation | Hierarchical document symbols + modern workspace symbol resolveSupport only |
| Verification | TASK-043 serial; not final green |

When product lands a new capability, it must (1) advertise the matching provider, (2) implement the request override, (3) keep unsafe cases fail-closed, and (4) turn ideal corpus paths green under review-owned serial verification without claiming global green early.
