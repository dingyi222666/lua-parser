# LSP Capability Negotiation Matrix

Date: 2026-07-11 (rows regenerated 2026-09-10 from `serverCapabilities()` after the adversarial LSP audit)  
Task: TASK-478 (docs-only)

This matrix documents **how the JVM LSP advertises server capabilities on `initialize`, which client capabilities it reads for dual-path response shapes, and which request methods are implemented vs intentionally unadvertised**. It is a focused companion to:

- `docs/language-server-usage.md` — full usage surface, lifecycle, workspace folders, Android-Lua metadata
- `docs/lsp-concurrency-shutdown.md` — lifecycle gates, concurrent open/close, post-shutdown policy
- `docs/lsp-code-action-rename-limits.md` — codeAction / prepareRename / rename policy and limits
- `docs/serialized-verification.md` — TASK-043 serial verification ownership

Implementation anchors (inspection only):

| Path | Role |
| --- | --- |
| `src/jvmMain/kotlin/io/github/dingyi222666/luaparser/lsp/LuaLanguageService.kt` | `initialize` captures client caps; `serverCapabilities()` builds `ServerCapabilities` |
| `src/jvmMain/kotlin/io/github/dingyi222666/luaparser/lsp/LuaTextDocumentService.kt` | Text-document request overrides + dual-path `documentSymbol` / `definition` |
| `src/jvmMain/kotlin/io/github/dingyi222666/luaparser/lsp/LuaWorkspaceService.kt` | Dual-path `workspace/symbol`; configuration / watched files / workspace folders |
| `src/jvmMain/kotlin/io/github/dingyi222666/luaparser/lsp/LuaLanguageServer.kt` | Lifecycle wrapper, request policy, diagnostics publish |

**Honesty bound:** rows describe the product LSP surface as inspected in `LuaLanguageService.serverCapabilities()` and the `LuaTextDocumentService` / `LuaWorkspaceService` overrides at the 2026-09-10 refresh (post adversarial LSP audit: `onTypeFormatting` and `codeAction` are implemented but intentionally **not** advertised). This page does **not** claim TASK-043 serialized suite green, TASK-037 final acceptance, or production readiness. Workers must not run Gradle, tests, or compile; verification is review-owned under TASK-043.

---

## Status labels

| Status | Meaning |
| --- | --- |
| Advertised + wired | `ServerCapabilities` non-null and a product override handles the method. Still pending TASK-043 green. |
| Advertised + dual-path | Same, and response shape branches on a client capability captured at `initialize`. |
| Client-gated only | Server always advertises the provider; client capability only chooses response branch (not whether the method exists). |
| Wired, intentionally not advertised | A product override exists and soft-degrades (empty list, no throw), but the provider field is deliberately left null so well-behaved clients do not offer the UX. |
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
completionProvider = CompletionOptions(triggerCharacters = [".", ":"], resolveProvider = true)
signatureHelpProvider = SignatureHelpOptions(triggers ["(", ","], retrigger [")"])
documentSymbolProvider = DocumentSymbolOptions
workspaceSymbolProvider = WorkspaceSymbolOptions
foldingRangeProvider = true
selectionRangeProvider = true
semanticTokensProvider = SemanticTokensWithRegistrationOptions(legend, full = { delta = true })
inlayHintProvider = true
documentFormattingProvider = true
documentRangeFormattingProvider = true
callHierarchyProvider = true
renameProvider = RenameOptions(prepareProvider = true)
workspace = WorkspaceServerCapabilities(workspaceFolders = { supported = true, changeNotifications = true })
```

Intentionally **left unset / null** (see the matrix rows for why):

```text
documentOnTypeFormattingProvider   // handler exists; reformats the whole document on any 'd'/'n' keystroke
codeActionProvider                 // handler exists; collector always returns an empty list
typeHierarchyProvider, linkedEditingRangeProvider, monikerProvider, diagnosticProvider (pull)
```

### Client → server (captured at `initialize`)

Three client capability bits are currently **read and stored** for dual-path response selection:

| Client capability path | Stored flag | Effect |
| --- | --- | --- |
| `textDocument.documentSymbol.hierarchicalDocumentSymbolSupport == true` | `hierarchicalDocumentSymbolSupport` | `textDocument/documentSymbol` → nested `DocumentSymbol` (`Either.right`) vs flat `SymbolInformation` (`Either.left`) |
| `workspace.symbol.resolveSupport != null` (object presence) | `modernWorkspaceSymbolSupport` | `workspace/symbol` → modern `WorkspaceSymbol` (`Either.right`) vs legacy `SymbolInformation` (`Either.left`) |
| `textDocument.definition.linkSupport == true` | `definitionLinkSupport` | `textDocument/definition` → `LocationLink` list (`Either.right`) vs `Location` list (`Either.left`) |

Other client capabilities (completion item resolve support, markdown hover content formats, workspace folders client capability, etc.) are **not** currently used to change advertisement or primary response shape. `textDocument/declaration` stays a `Location` list regardless of `linkSupport`.

### Lifecycle gate (orthogonal to capability ads)

Even when a method is advertised, `LuaLanguageServer` only accepts traffic in `INITIALIZED`. Post-`shutdown` / post-`exit` policy is documented in `docs/lsp-concurrency-shutdown.md` (text-document requests: reject after shutdown, quiet after exit; workspace requests rejected when not accepting).

---

## Matrix: advertised text-document capabilities

| Capability / method | Advertised? | Product override? | Client dual-path? | Status | Notes |
| --- | --- | --- | --- | --- | --- |
| `textDocumentSync` | Yes — `TextDocumentSyncKind.Full` | Yes (`didOpen` / `didChange` / `didClose` / `didSave`) | No | Advertised + wired | `LuaTextDocumentService` applies ranged edits to its version-gated cache and forwards a full replace; `LuaLanguageService.didChange` also folds ranged edits onto the indexed source for files never opened. `didChange` republishes the edited document first plus every other **open** document the incremental update affected (require dependents). `didSave` accepted, no extra work. |
| `textDocument/hover` | Yes — `HoverOptions` | Yes | No | Advertised + wired | Markdown `MarkupContent` when hover facts exist; null/empty when no symbol. |
| `textDocument/completion` | Yes — `CompletionOptions` triggers `.` `:` | Yes | No | Advertised + wired | Returns `Either.right(CompletionList)`; `isIncomplete=false`. `obj:` surfaces drop non-callable FIELD members (JavaBean aliases, data fields); `obj.` keeps the full surface. |
| `completionItem/resolve` | Yes — `resolveProvider = true` | Yes | No | Advertised + wired | Identity-preserving copy (label / kind / `labelDetails` / `insertTextMode` / insertText / textEdit / data …) enriched with kind-derived `detail` and markdown `documentation` when absent; soft-identity on bad input. |
| `textDocument/signatureHelp` | Yes — triggers `(` `,`; retrigger `)` | Yes | No | Advertised + wired | Active signature/parameter from query facade; pending TASK-043 confirmation of edge corpora. |
| `textDocument/declaration` | Yes — boolean `true` | Yes | No | Advertised + wired | Location list (`Either.left`); not `LocationLink`. |
| `textDocument/definition` | Yes — `DefinitionOptions` | Yes | **Yes** — `linkSupport` | Advertised + dual-path | `LocationLink` list when `textDocument.definition.linkSupport == true`, else `Location` list; require-local alias navigates to the module provider when the snapshot allows. |
| `textDocument/references` | Yes — `ReferenceOptions` | Yes | No | Advertised + wired | Snapshot occurrences + provider surfaces; honours `includeDeclaration`. |
| `textDocument/documentHighlight` | Yes — boolean `true` | Yes | No | Advertised + wired | Same-file identifier-span highlights; also the reference set rename reuses. |
| `textDocument/documentSymbol` | Yes — `DocumentSymbolOptions` | Yes | **Yes** — hierarchical | Advertised + dual-path | Client `hierarchicalDocumentSymbolSupport` (TASK-396 done at task level). |
| `textDocument/prepareRename` | Yes — `RenameOptions(prepareProvider = true)` | Yes | No | Advertised + wired | Identifier-span `PrepareRenameResult` for local-like identifiers; null (reject) for keywords / literals / free globals / out-of-range. See `docs/lsp-code-action-rename-limits.md`. |
| `textDocument/rename` | Yes — `RenameOptions` | Yes | No | Advertised + wired | Same-file lexical `WorkspaceEdit`; invalid new names / non-renamable positions → empty edit. Cross-file member rename intentionally stays empty. |
| `textDocument/codeAction` | **No** (intentional) | Yes (`codeActions` → always empty list) | No | Wired, intentionally not advertised | No deterministic quick-fix exists yet; advertising would light up an empty lightbulb (adversarial audit). Probing clients get a well-formed empty list, never `UnsupportedOperationException`. |
| `textDocument/formatting` | Yes — boolean `true` | Yes | No | Advertised + wired | AST2Lua pretty-print when the buffer parses; minimal indent/newline normalize otherwise; empty edits on malformed input. |
| `textDocument/rangeFormatting` | Yes — boolean `true` | Yes | No | Advertised + wired | Table-body / selection formatting; empty edits for inverted / OOB ranges. |
| `textDocument/onTypeFormatting` | **No** (intentional) | Yes (`onTypeFormatting`, triggers `d` / `n` / newline) | No | Wired, intentionally not advertised | Handler reformats the whole document on any `d`/`n` keystroke, which is too aggressive to advertise (adversarial audit). |
| `textDocument/foldingRange` | Yes — boolean `true` | Yes | No | Advertised + wired | Multi-line function bodies and table constructors; empty on malformed / unknown docs. |
| `textDocument/selectionRange` | Yes — boolean `true` | Yes | No | Advertised + wired | AST parent chains per position; positions that resolve to no node are **dropped** (no JSON-null slots), all-unresolvable requests are empty. |
| `textDocument/inlayHint` | Yes — boolean `true` | Yes | No | Advertised + wired | Parameter-name hints for call arguments derived from signature-help formals. |
| `textDocument/semanticTokens/full` + `full/delta` | Yes — legend + `full = { delta = true }` | Yes | No | Advertised + wired | Per-document cache of the last payload / `resultId`; evicted on `didClose` and on watched-file delete of closed files. Unknown `previousResultId` → full re-send. `range` is not advertised. |
| `textDocument/prepareCallHierarchy` / `callHierarchy/incomingCalls` / `outgoingCalls` | Yes — boolean `true` | Yes | No | Advertised + wired | Same-file local-function call graph subset; non-function positions → empty. `prepare` currently resolves **same-file LOCAL functions only** — module methods / globals / Java methods return empty; incoming/outgoing are same-file local-function aggregations. |
| `textDocument/prepareTypeHierarchy` / type hierarchy | **No** | **No** | Safety / doc-class corpora | Not advertised / gap | LSP4J default (`UnsupportedOperationException`). |
| `textDocument/linkedEditingRange` | **No** | **No** | Local dual-path corpus | Not advertised / gap | LSP4J default. |
| `textDocument/moniker` | **No** | **No** | Safety corpus | Not advertised / gap | LSP4J default. |
| Pull `textDocument/diagnostic` | **No** | **No** | N/A | Deferred | Diagnostics are **push** via `publishDiagnostics` on open/change/close/config/watched updates. |

---

## Matrix: workspace capabilities and notifications

| Capability / method | Advertised? | Product override? | Client dual-path? | Status | Notes |
| --- | --- | --- | --- | --- | --- |
| `workspace/symbol` | Yes — `WorkspaceSymbolOptions` | Yes | **Yes** — modern vs legacy | Advertised + dual-path | Client `workspace.symbol.resolveSupport` presence. Helpers: `workspaceSymbols` / `modernWorkspaceSymbols`. |
| `workspaceSymbol/resolve` | Not separately advertised | **No** | N/A | Gap / deferred | Modern list path does not implement symbol resolve round-trip. |
| `workspace/didChangeConfiguration` | N/A (notification) | Yes | No | Notification accepted | Flat + nested `jvm.*` / `androlua.*` keys; rebuild + republish open diagnostics. |
| `workspace/didChangeWatchedFiles` | N/A (notification); server does **not** register watchers | Yes | No | Notification accepted | Client must subscribe; `.lua`/`.aly` create/change/delete update index. Deleting a closed file also drops its semantic-tokens cache entry. |
| Workspace folders **server** capability (`workspace.workspaceFolders`) | **Yes** — `supported = true`, `changeNotifications = true` | Yes | No | Advertised + wired | Server consumes `InitializeParams.workspaceFolders` (or `rootUri` fallback) for indexing and accepts dynamic folder changes (TASK-516). |
| `workspace/didChangeWorkspaceFolders` | Yes (via `changeNotifications = true`) | Yes (`LuaWorkspaceService` → `applyWorkspaceFolderChanges`) | No | Notification accepted | Added folders index `.lua`/`.aly` without restart; removed folders drop their sources/symbols; open overlays stay authoritative. Multi-root relative-path collision remains last-write-wins (see `LspDidChangeWorkspaceFoldersTddTest`). |

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

### Modern workspace symbols

Captured:

```text
params.capabilities.workspace.symbol.resolveSupport != null
```

| Client capability | Response | Helper |
| --- | --- | --- |
| `resolveSupport` object present | `Either.forRight` `WorkspaceSymbol` list | `modernWorkspaceSymbols(query)` |
| absent / null symbol caps | `Either.forLeft` `SymbolInformation` list | `workspaceSymbols(query)` |

When the server is not accepting workspace requests, quiet empty responses still pick the same branch shape based on the stored flag. **Not a final-green claim.**

### Definition location links (TASK-515)

Captured:

```text
params.capabilities.textDocument.definition.linkSupport == true
```

| Client capability | Response | Helper |
| --- | --- | --- |
| `true` | `Either.forRight` `LocationLink` list (`targetRange == targetSelectionRange`) | `definition(params)` + `supportsDefinitionLink()` |
| `false`, unset, or absent | `Either.forLeft` `Location` list | `definition(params)` |

`textDocument/declaration` always answers with a `Location` list.

### Completion item resolve (advertised)

| Product state | Advertisement | Request outcome |
| --- | --- | --- |
| Current source | `completionProvider.resolveProvider = true` | `resolveCompletionItem` returns a copy that keeps label / kind / `labelDetails` / `insertTextMode` / insertText / textEdit / data and fills `detail` / markdown `documentation` from kind metadata when absent |
| Bad input (blank label, internal error) | same | Unresolved item returned unchanged (soft identity); never throws |

### Rename / codeAction

| Method | Advertised | Outcome today |
| --- | --- | --- |
| `prepareRename` | Yes (`RenameOptions(prepareProvider = true)`) | Identifier-span result for local-like identifiers; null (reject) otherwise |
| `rename` | Yes | Same-file lexical `WorkspaceEdit`; empty edit for invalid names / non-renamable positions |
| `codeAction` | **No** (intentional) | Well-formed empty list from `codeActions`; no `UnsupportedOperationException` |

Details and limits: `docs/lsp-code-action-rename-limits.md`.

---

## Client capability bits intentionally ignored (today)

These may appear on modern clients but do **not** currently change server ads or primary branches:

| Client capability (examples) | Current server reaction |
| --- | --- |
| `textDocument.completion.completionItem.*` (resolve / labelDetails / insertTextMode support bits) | Completion still returns the full `CompletionList`; resolve is always advertised and preserves `labelDetails` / `insertTextMode` when the client sent them |
| `textDocument.hover.contentFormat` | Hover always Markdown when content exists |
| `textDocument.declaration.linkSupport` | Declaration stays a `Location` list (only `definition.linkSupport` is honoured) |
| `workspace.workspaceFolders` client support bit | Server always advertises `workspaceFolders.supported` + `changeNotifications`; folders come from initialize params and `didChangeWorkspaceFolders` |
| `general.positionEncodings` / UTF-16 vs UTF-8 | Default LSP position model assumed (UTF-16 code units as LSP positions) |
| Pull diagnostic client capability | Push diagnostics only |

---

## Diagnostics negotiation note

There is **no** `diagnosticProvider` pull capability advertisement. Diagnostics are published to the connected `LanguageClient` via `publishDiagnostics` after:

1. `textDocument/didOpen`
2. `textDocument/didChange` (version-gated on the open-document cache) — the edited document first, then every other **open** document the incremental update reported as affected (require dependents); closed indexed files stay pull-only through `diagnostics()` / `diagnosticsForUri()`
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

Expected dual-path branches: flat document symbols; legacy workspace symbols; `Location` lists for definition.

### Hierarchical document symbols + modern workspace symbols + location links

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
        },
        "definition": {
          "linkSupport": true
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

Expected dual-path branches: nested `DocumentSymbol`; modern `WorkspaceSymbol` lists; `LocationLink` lists for definition. Exact property list inside `resolveSupport` is not further interpreted — **presence** of the object is the gate.

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
| Completion item resolve (identity + `labelDetails` / `insertTextMode` carry-over) | `lsp.LspCompletionItemResolveTddTest`, `lsp.LspCompletionResolveLabelDetailsTddTest` |
| Colon vs dot member surface | `./gradlew.lf jvmTest --tests lsp.LspColonMemberCompletionCallableOnlyTddTest` |
| Hierarchical document symbols | `./gradlew.lf jvmTest --tests lsp.LspHierarchicalDocumentSymbolTddTest` |
| Navigation / symbols (TASK-396 accept path) | `./gradlew.lf jvmTest --tests lsp.LspNavigationSymbolsTddTest` |
| Modern workspace symbols | `./gradlew.lf jvmTest --tests lsp.LspModernWorkspaceSymbolTddTest` |
| Definition `LocationLink` dual-path | `./gradlew.lf jvmTest --tests lsp.LspDefinitionLocationLinkTddTest` |
| Workspace folders / watched files / URI | `lsp.LspDidChangeWorkspaceFoldersTddTest`, `lsp.LspUriHandlingTddTest` |
| didChange publish (affected dependents, unopened indexed files) | `lsp.LspDiagnosticsPublishOnChangeTddTest`, `lsp.LspDidChangeAffectedDocumentsPublishTddTest`, `lsp.LspDidChangeWithoutOpenTddTest` |
| prepareRename / rename | `./gradlew.lf jvmTest --tests lsp.LspRenamePrepareMultiFileTddTest` |
| Selection range (nesting, no null slots) | `lsp.LspSelectionRangeNestedBlockTddTest`, `lsp.LspSelectionRangeNullSlotFilterTddTest` |
| Semantic tokens (full / delta / cache eviction) | `lsp.LspSemanticTokensBasicTddTest`, `lsp.LspSemanticTokensCacheEvictionTddTest` |
| Formatting / folding / inlay / call hierarchy / on-type | matching `Lsp*TddTest` classes under `src/jvmTest/kotlin/lsp` |
| Shared compile gate | `./gradlew.lf compileTestKotlinJvm` |

Do not treat a focused green as global green. **TASK-043 remains blocked; this repository is not finally green.**

---

## Explicit non-claims

- No claim that unadvertised methods (`onTypeFormatting`, `codeAction`, type hierarchy, linked editing, moniker) are safe to call from production clients; the two implemented ones only promise a soft-empty answer.
- No claim that the advertised structural features (formatting family, folding, selection ranges, inlay hints, semantic tokens, call hierarchy, rename) are product-ready beyond the limits stated in their rows.
- No claim that cross-file rename or non-empty code actions exist.
- No claim that host `android.jar` presence changes capability ads.
- No claim of `./gradlew check` or full-suite success from this docs-only refresh.
- Final production-readiness remains TASK-037 after TASK-043.

---

## Summary

| Bucket | Current state (source inspection) |
| --- | --- |
| Core navigation + hover + completion (+ resolve) + signature help + highlights | Advertised and wired |
| Document / workspace symbols / definition | Advertised; dual-path on three client bits (hierarchical symbols, modern workspace symbols, definition link support) |
| Diagnostics | Push only; no pull capability; didChange republishes affected open dependents |
| Structural extras (formatting, range formatting, folding, selection range, inlay hints, semantic tokens + delta, call hierarchy, rename + prepareRename) | Advertised and wired with documented limits |
| Intentionally unadvertised but wired | `onTypeFormatting` (whole-document reformat on `d`/`n`), `codeAction` (always empty) |
| Not advertised, not implemented | Type hierarchy, linked editing range, moniker, pull diagnostics |
| Workspace folders | Advertised (`supported` + `changeNotifications`); dynamic add/remove wired |
| Verification | TASK-043 serial; not final green |

When product lands a new capability, it must (1) advertise the matching provider, (2) implement the request override, (3) keep unsafe cases fail-closed, and (4) turn ideal corpus paths green under review-owned serial verification without claiming global green early. When a wired handler is judged too aggressive to expose (as with `onTypeFormatting` / `codeAction` today), keep the override for probing clients but leave the provider field null and record the reason next to `serverCapabilities()`.
