# LSP Code Action And Rename Limits

This document records the **current** JVM language-server surface and limits for
`textDocument/codeAction`, `textDocument/prepareRename`, and
`textDocument/rename`. It is a policy and gap note for clients, harness
authors, and reviewers — not a product implementation plan and not a claim
that those capabilities are production-ready.

Related usage surface: [language-server-usage.md](language-server-usage.md).
Capability rows: [lsp-capability-negotiation-matrix.md](lsp-capability-negotiation-matrix.md).
Concurrency / lifecycle policy: [lsp-concurrency-shutdown.md](lsp-concurrency-shutdown.md).
Implementation lives under `src/jvmMain/kotlin/io/github/dingyi222666/luaparser/lsp`.

Command-level confirmation of the corpora remains deferred to TASK-043
serialized verification. This page documents the surface observed in source
(refreshed 2026-09-10 after the adversarial LSP audit) and locked by test-only
corpora; it does not re-run Gradle.

## Scope Boundaries

| In scope for this page | Out of scope / not claimed |
| --- | --- |
| Whether `initialize` advertises `codeActionProvider` / `renameProvider` | Full rename engine, multi-file refactor, or cross-module rewrite |
| Current request surface on `LuaTextDocumentService` / `LuaLanguageService` for codeAction / prepareRename / rename | Quickfix product catalog (unused local, import insertion, etc.) |
| Accept / reject rules the implemented prepareRename and rename follow | That every editor client hides unadvertised methods the same way |
| Why `codeAction` stays unadvertised even though a handler exists | Concurrent rename/codeAction under load (see concurrency doc) |
| Focused verification command pointers for review-owned serial runs | Global green or release readiness (TASK-043 / TASK-037 still gate those) |

**Do not treat this document as a capability certificate.** Claims stop at the
advertised capabilities, the implemented request behaviour, and the corpora
named below.

## Current Capability Advertisement

`LuaLanguageService.serverCapabilities()` currently advertises:

| Capability | Advertised today? |
| --- | --- |
| Text document sync (full) | Yes |
| Hover / completion (+ `completionItem/resolve`) / signature help | Yes |
| Declaration / definition / references / document highlight | Yes |
| Document symbols / workspace symbols | Yes |
| **`codeActionProvider`** | **No** (`null`, intentional — handler exists but always answers with an empty list) |
| **`renameProvider`** (and prepare-rename options) | **Yes** — `RenameOptions(prepareProvider = true)` |
| Formatting / range formatting | Yes |
| On-type formatting | **No** (`null`, intentional — handler reformats the whole document on any `d`/`n` keystroke) |
| Folding range / selection range / inlay hints / semantic tokens / call hierarchy | Yes |
| Workspace folders (`supported` + `changeNotifications`) | Yes |

Clients that honor `ServerCapabilities` will offer rename UI (with a prepare
step) and will **not** offer code-action lightbulbs against this server.
Clients that still probe `textDocument/codeAction` hit the request-surface
behaviour below rather than an exception.

## Request Surface Today

`LuaTextDocumentService` overrides all three methods and delegates to
`LuaLanguageService`; the lifecycle wrapper on `LuaLanguageServer` reaches them
through delegation and applies the usual accept / quiet / reject request policy.

| Method | Advertised? | Product override? | Current outcome |
| --- | --- | --- | --- |
| `textDocument/codeAction` | No (intentional) | Yes (`codeActions` → `collectCodeActions`) | Well-formed **empty** list for every input (kind filter, empty context, inverted / OOB ranges); never throws |
| `textDocument/prepareRename` | Yes | Yes (`prepareRename`) | `PrepareRenameResult(identifierRange, placeholder)` for renamable identifiers; `null` (LSP reject) otherwise; never throws |
| `textDocument/rename` | Yes | Yes (`rename`) | `WorkspaceEdit` with identifier-span `TextEdit`s for the **requesting document only**; empty `WorkspaceEdit` for invalid names / non-renamable positions; never throws |

### What "fail-closed" means here

1. **No silent partial edits.** `rename` only emits edits for identifier spans
   it resolved through document highlights / references in the requesting file;
   cross-file member sites are left out rather than guessed.
2. **No capability lie.** `codeAction` stays unadvertised until a deterministic
   fix exists, so well-behaved clients never show an empty lightbulb. Probing
   clients get a zero-length action list, not an `UnsupportedOperationException`.
3. **Reject over rewrite.** `prepareRename` returns `null` for anything that is
   not a local-like identifier (keywords, literals, operators, comments, free
   globals without a local binding, out-of-range positions), so the client never
   enters the rename UX for those positions.

## Implemented Policy

The rules below are what the product does today; the corpora listed further
down lock them so a later change cannot weaken them silently.

### `textDocument/prepareRename`

| Position / symbol | Behaviour |
| --- | --- |
| Whitespace, keywords, numeric/string literals, operators, comments | Reject (`null`) — no rename range is invented |
| Globals without a local lexical binding (e.g. `print`) and free undeclared names | Reject (`null`) — renames stay lexical |
| Local identifiers, parameters, for-loop names, local functions, attribute locals | Accept with a range covering the **identifier span** (single-line token text) and a placeholder equal to the current name |
| Table field / method **name** token under the caret (`t.field`, `t:method`) | Soft-accept the identifier span (rename itself stays same-file, see below) |
| Missing document / negative or out-of-range positions | Reject (`null`) — never NPE / assert / process-kill |

`DefaultBehavior`-style prepare results (client word range) are never returned;
a prepare result is always an explicit identifier span.

### `textDocument/rename`

| Case | Behaviour |
| --- | --- |
| Empty / blank / non-identifier `newName` | Empty `WorkspaceEdit` |
| Position that `prepareRename` would reject | Empty `WorkspaceEdit` |
| Local identifier rename | Edits cover identifier spans only (declaration + same-file references via document highlights, falling back to references); `newText` equals the requested name; ranges single-line |
| Field / method name rename | Same-file sites only; cross-file provider / consumer sites are **not** edited (documented limit) |
| Internal error | Empty `WorkspaceEdit` (soft fail), never a thrown exception |

### `textDocument/codeAction` (quickfix surface)

| Case | Behaviour |
| --- | --- |
| Any `only` filter that excludes `quickfix` / empty kind | Empty list |
| Empty diagnostics context | Empty list |
| Diagnostics present (parse / semantic) | Empty list — no deterministic auto-fix exists yet |
| Empty selection, inverted / malformed / OOB ranges | Empty list; the range is never used to index into the source |

Product may later advertise `codeActionProvider` as `true`, `Either`, or
`CodeActionOptions` once at least one deterministic fix ships. Until then
`null` remains the advertised truth on purpose.

## Identifier-Span Floor Shared With Document Highlight

`rename` reuses the same-file `documentHighlight` locations (tightened to
identifier spans) as its primary edit set, so the two surfaces stay consistent:

- Highlights for a local are non-empty and ordered; rename edits are the same
  spans sorted by position.
- A highlight that is not a single-line identifier span of the placeholder's
  length is skipped by rename rather than widened.
- Highlight on whitespace does not throw; rename on whitespace yields an empty
  edit.

Clients that only need "find occurrences under cursor" can keep using document
highlight / references; rename adds the edit envelope on top of the same data.

## Corpora And Verification Pointers

Workers and documentation waves **must not** run these commands. They are
deferred acceptance references for **review-owned serial verification**
(TASK-043 policy; see [serialized-verification.md](serialized-verification.md)).

Environment shape (macOS host used by recent review waves):

```bash
export JAVA_HOME=/Users/dingyi/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
```

Windows coordinated path uses the same filters with
`JAVA_HOME=C:/Users/dingyi/.jdks/temurin-17.0.11` and `./gradlew.bat`.

| Corpus / role | Owner task | Focused filter (review-only) |
| --- | --- | --- |
| prepareRename / rename (accept / reject / same-file limit) | TASK-518 | `./gradlew.lf jvmTest --tests lsp.LspRenamePrepareMultiFileTddTest` |
| codeAction empty-list contract (real-project refactor corpus) | TASK-542 | `./gradlew.lf jvmTest --tests lsp.LspRealProjectDiagnosticsRefactorTddTest` |
| Capability advertisement (rename yes, codeAction / onType no) | TASK-478 | `./gradlew.lf jvmTest --tests lsp.LspCompletionCapabilitiesTddTest` and the `initialize` probes inside the `Lsp*TddTest` classes |
| Shared compile gate before focused filters | TASK-043 | `./gradlew.lf compileTestKotlinJvm` |
| Document highlight product surface (rename edit-set dependency) | navigation / highlight corpora | `./gradlew.lf jvmTest --tests lsp.LspDocumentHighlightTddTest` |

Source anchors (inspection only):

| Path | Role |
| --- | --- |
| `src/jvmMain/kotlin/.../lsp/LuaLanguageService.kt` (`serverCapabilities`) | Advertises `renameProvider = RenameOptions(true)`; leaves `codeActionProvider` and `documentOnTypeFormattingProvider` null with the reason in a comment |
| `src/jvmMain/kotlin/.../lsp/LuaLanguageService.kt` (`prepareRename`, `rename`, `resolveRenameTarget`) | Accept / reject rules and same-file edit construction |
| `src/jvmMain/kotlin/.../lsp/LuaLanguageService.kt` (`codeActions`, `collectCodeActions`) | Empty-list quickfix collector |
| `src/jvmMain/kotlin/.../lsp/LuaTextDocumentService.kt` | `codeAction` / `prepareRename` / `rename` overrides with quiet-policy fallbacks |
| `src/jvmMain/kotlin/.../lsp/LuaLanguageServer.kt` | Lifecycle wrapper delegates the three methods and applies request policy |
| `src/jvmTest/kotlin/lsp/LspRenamePrepareMultiFileTddTest.kt` | Rename / prepareRename contract |
| `src/jvmTest/kotlin/lsp/LspRealProjectDiagnosticsRefactorTddTest.kt` | Code-action empty-list contract on real-project shapes |

## Explicit Non-Claims

- No claim that any code action is produced; the handler is an empty-list stub
  kept for probing clients.
- No claim that rename edits cross file boundaries (module fields, required
  providers, Java members stay untouched).
- No claim that clients must hide the code-action UI the same way on every
  editor.
- No claim that concurrent rename/codeAction under load is tested (see
  [lsp-concurrency-shutdown.md](lsp-concurrency-shutdown.md)).
- No claim of global suite green; TASK-043 / TASK-037 remain the gates.

## Summary

| Surface | Today | Fail-closed stance |
| --- | --- | --- |
| Capability ads | rename **advertised** (`RenameOptions(prepareProvider = true)`); codeAction **not** advertised (intentional) | Clients get rename UX, no lightbulb |
| `prepareRename` | Identifier-span accept for local-like identifiers; `null` otherwise | Reject over guess |
| `rename` | Same-file identifier-span `WorkspaceEdit`; empty edit when unsafe | No cross-file smash |
| `codeAction` | Empty list for every input, never throws | No empty-lightbulb capability lie |
| Shared data | `documentHighlight` spans feed rename edits | One identifier-span model |

When a product task ships a real code action, it must (1) advertise
`codeActionProvider`, (2) keep the empty-list behaviour for inputs it cannot
fix, and (3) extend the corpora above under serial review verification. When a
future task widens rename beyond the requesting file, it must document the new
cross-file policy here before advertising anything stronger.
