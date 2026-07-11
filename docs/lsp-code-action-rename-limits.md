# LSP Code Action And Rename Limits

This document records the **current** JVM language-server limits for
`textDocument/codeAction`, `textDocument/prepareRename`, and
`textDocument/rename`. It is a policy and gap note for clients, harness
authors, and reviewers — not a product implementation plan and not a claim
that those capabilities are production-ready.

Related usage surface: [language-server-usage.md](language-server-usage.md).
Concurrency / lifecycle policy: [lsp-concurrency-shutdown.md](lsp-concurrency-shutdown.md).
Implementation lives under `src/jvmMain/kotlin/io/github/dingyi222666/luaparser/lsp`.

Command-level confirmation of the safety corpora remains deferred to TASK-043
serialized verification. This page documents the **fail-closed** surface
observed in source and locked by test-only corpora; it does not re-run Gradle
or expand production code.

## Scope Boundaries

| In scope for this page | Out of scope / not claimed |
| --- | --- |
| Whether `initialize` advertises `codeActionProvider` / `renameProvider` | Full rename engine, multi-file refactor, or cross-module rewrite |
| Current request surface on `LuaTextDocumentService` for codeAction / prepareRename / rename | Quickfix product catalog (unused local, import insertion, etc.) |
| Fail-closed / dual-path safety contracts from TASK-249 and TASK-268 corpora | That every editor client hides unadvertised methods the same way |
| Identifier-span safety floor via existing `documentHighlight` (rename proxy) | Concurrent rename/codeAction under load (see concurrency doc) |
| Focused verification command pointers for review-owned serial runs | Global green or release readiness (TASK-043 / TASK-037 still gate those) |

**Do not treat this document as a capability certificate.** Claims stop at the
advertised capabilities, the unimplemented request defaults, and the safety
corpora named below.

## Current Capability Advertisement

`LuaLanguageService.serverCapabilities()` currently advertises:

| Capability | Advertised today? |
| --- | --- |
| Text document sync (full) | Yes |
| Hover / completion / signature help | Yes |
| Declaration / definition / references / document highlight | Yes |
| Document symbols / workspace symbols | Yes |
| **`codeActionProvider`** | **No** (`null`) |
| **`renameProvider`** (and prepare-rename options) | **No** (`null`) |
| Formatting / range formatting / on-type formatting | No |
| Folding range / selection range / inlay hints | No |

Clients that honor `ServerCapabilities` should **not** offer rename UI or code
action lightbulbs against this server until those providers are non-null.
Clients that still probe unadvertised methods hit the request-surface behavior
below.

## Request Surface Today (Fail-Closed)

Neither `LuaTextDocumentService` nor the lifecycle wrapper on
`LuaLanguageServer` overrides:

- `textDocument/codeAction`
- `textDocument/prepareRename`
- `textDocument/rename`

Those methods therefore inherit the **LSP4J `TextDocumentService` defaults**,
which complete exceptionally with `UnsupportedOperationException` (wrapped by
LSP4J/CompletableFuture as `ExecutionException` / `CompletionException` at the
call site).

| Method | Advertised? | Product override? | Current outcome |
| --- | --- | --- | --- |
| `textDocument/codeAction` | No | No | Exceptional completion (`UnsupportedOperationException`) |
| `textDocument/prepareRename` | No | No | Exceptional completion (`UnsupportedOperationException`) |
| `textDocument/rename` | No | No | Exceptional completion (`UnsupportedOperationException`) |

### What “fail-closed” means here

1. **No silent partial edits.** The server does not invent empty-looking
   success payloads that clients might apply as no-ops while believing rename
   succeeded, and it does not emit half-baked `WorkspaceEdit` content for
   unvalidated positions.
2. **No capability lie.** Because providers are not advertised, well-behaved
   clients should not enter the rename/code-action UX. Probing clients still
   get a hard “not implemented” failure rather than a best-effort rewrite.
3. **Safety corpora dual-path.** Test-only corpora accept either the documented
   gap (`UnsupportedOperationException`) **or** a future ideal product path that
   is still fail-closed on unsafe inputs (reject / empty edit / empty action
   list) and never hard-crashes the process.

This is intentionally stricter than “return empty and pretend success.” Empty
lists / soft rejects are only acceptable **after** product implements the
methods and keeps unsafe cases closed.

## Intended Safety Policy (When Product Lands)

The following policy is locked by dual-path TDD corpora so a future product
lane cannot weaken fail-closed behavior without turning the tests red. Until
product lands, the documented gap path is the live behavior.

### `textDocument/prepareRename`

| Position / symbol | Required behavior once implemented |
| --- | --- |
| Whitespace, keywords, numeric/string literals, operators | Reject (null / error / non-accepting prepare result) — **must not** return a rename range |
| Globals without a local lexical binding (e.g. `print`) and free undeclared names | Reject when policy keeps renames lexical |
| Local identifiers and local function names | Accept only with a range covering the **identifier span** (single-line token text), optional placeholder equal to current name |
| Missing document / out-of-range positions | Soft reject or gap — **must not** NPE / assert / process-kill |

`DefaultBehavior`-style prepare results (client word range) are treated as
**not** a server-endorsed rename for safety policy purposes.

### `textDocument/rename`

| Case | Required behavior once implemented |
| --- | --- |
| Missing symbol / whitespace / non-identifier | Soft fail or empty `WorkspaceEdit` — **must not** invent edits or NPE |
| Local identifier rename | Edits cover identifier spans only; `newText` equals the requested name; ranges single-line |
| Globals / free names under lexical policy | Prefer reject over workspace-wide text smash |

### `textDocument/codeAction` (quickfix surface)

| Case | Required behavior once implemented |
| --- | --- |
| Known diagnostics with no fix | Empty action list or soft reject — **must not** hard-crash |
| Empty selection, inverted/malformed ranges, OOB ranges | No crash; empty or soft fail |
| Empty diagnostics / quickfix-only filter on clean docs | Empty or well-formed quickfix-kind actions only — **must not** invent hard failures |
| Returned `CodeAction` / `Command` entries | Non-blank title (or command id); nested command coherent when present |

Product may later advertise `codeActionProvider` as `true`, `Either`, or
`CodeActionOptions`. Until then, `null` remains the advertised truth.

## Identifier-Span Safety Floor (Available Today)

Rename product is absent, but **`textDocument/documentHighlight` is implemented
and advertised**. The prepareRename/rename safety corpus uses document
highlights as a **product-available proxy** for identifier coverage:

- Highlights for a local must be non-empty and ordered.
- Ranges must cover the identifier (exact single-line span when product already
  emits one; wider declaration/expression ranges that still cover/begin with
  the identifier are soft-accepted while product ranges remain imperfect).
- Highlight on whitespace must not throw.

Clients that need “find occurrences under cursor” today should use document
highlight / references, **not** rename.

## Safety Corpora And Verification Pointers

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
| prepareRename / rename safety (dual-path gap + ideal) | TASK-249 | `./gradlew.lf jvmTest --tests lsp.LspPrepareRenameSafetyTddTest` |
| codeAction quickfix safety (dual-path gap + ideal) | TASK-268 | `./gradlew.lf jvmTest --tests lsp.LspCodeActionQuickFixSafetyTddTest` |
| Shared compile gate before focused filters | TASK-043 | `./gradlew.lf compileTestKotlinJvm` |
| Document highlight product surface (rename proxy dependency) | navigation / highlight corpora | `./gradlew.lf jvmTest --tests lsp.LspDocumentHighlightTddTest` (when present in suite) |

Source anchors (inspection only; no product edits in this docs task):

| Path | Role |
| --- | --- |
| `src/jvmMain/kotlin/.../lsp/LuaLanguageService.kt` (`serverCapabilities`) | Capability advertisement — no codeAction/rename providers |
| `src/jvmMain/kotlin/.../lsp/LuaTextDocumentService.kt` | No `codeAction` / `prepareRename` / `rename` overrides |
| `src/jvmMain/kotlin/.../lsp/LuaLanguageServer.kt` | Lifecycle wrapper does not add those methods |
| `src/jvmTest/kotlin/lsp/LspPrepareRenameSafetyTddTest.kt` | TASK-249 safety contract |
| `src/jvmTest/kotlin/lsp/LspCodeActionQuickFixSafetyTddTest.kt` | TASK-268 safety contract |

Docs-only review of this page (TASK-307) accepts by reading this document
against the TASK-307 acceptance criteria; it does **not** require Gradle.

## Explicit Non-Claims

- No claim that rename, prepareRename, or code actions are implemented.
- No claim that clients must hide the UI the same way on every editor.
- No claim that documentHighlight ranges are already exact identifier tokens in
  every expression shape (binary / multi-line soft cases remain).
- No claim that concurrent rename/codeAction under load is tested (see
  [lsp-concurrency-shutdown.md](lsp-concurrency-shutdown.md)).
- No claim of global suite green; TASK-043 / TASK-037 remain the gates.

## Summary

| Surface | Today | Fail-closed stance |
| --- | --- | --- |
| Capability ads | codeAction / rename **not** advertised | Clients should not offer the UX |
| Request methods | LSP4J default → `UnsupportedOperationException` | Hard gap, not partial rewrite |
| Future product | Dual-path corpora already encode reject/empty/well-formed rules | Unsafe positions stay closed |
| Proxy today | `documentHighlight` / references for occurrence navigation | Not a substitute for rename |

When a product task lands codeAction or rename, it must (1) advertise the
matching provider, (2) keep unsafe cases fail-closed as above, and (3) turn the
ideal paths of the TASK-249 / TASK-268 corpora green under serial review
verification — without relaxing the gap-path safety floor mid-migration.
