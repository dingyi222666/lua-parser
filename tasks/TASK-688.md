id: TASK-688
title: FULL RealProject file URI with spaces normalize/path product fix
status: review
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/jvmMain/kotlin/io/github/dingyi222666/luaparser/lsp/LuaLanguageService.kt
  - src/jvmTest/kotlin/lsp/LspRealProjectDiagnosticsRefactorTddTest.kt
  - src/jvmTest/kotlin/lsp/LspRealProjectEditorLifecycleTddTest.kt
acceptance_criteria:
  - Clear FULL jvmTest failures (evidence run 29265516413 / WINFULL-29265516413):
    - lsp.LspRealProjectDiagnosticsRefactorTddTest#diagnostics_for_uri_with_spaces_publish_and_clear[jvm]
    - lsp.LspRealProjectEditorLifecycleTddTest#file_uri_with_spaces_change_and_close_preserve_uri[jvm]
  - Observed Windows URISyntaxException (run 29265516413):
    - `Illegal character in path at index 20: file:///workspace/my project/broken file.lua`
    - `Illegal character in path at index 27: file:///workspace/src/ui/My Screen.lua`
    - Stack through `normalizeLspFileUriPath` / `lspVirtualPathFromUri` / `LuaLanguageService.pathOf` (LuaLanguageService.kt ~3712–3732).
  - Product must accept LSP `file://` URIs whose path segments contain unencoded spaces (and round-trip encode/decode safely) for didOpen/didChange/didClose + diagnostics publish/clear without throwing URISyntaxException.
  - Prefer product normalizeLspFileUriPath / lspVirtualPathFromUri hardening (percent-encode path or URI.create-tolerant parse); do not weaken tests to require pre-encoded spaces only; never invent G:/.
  - Serialize exclusive LuaLanguageService.kt claim vs TASK-686/687 when overlapping; leave ready if blocked.
required_tests:
  - Deferred to TASK-043 / full jvmTest run 29265516413: `./gradlew.bat jvmTest --tests lsp.LspRealProjectDiagnosticsRefactorTddTest.diagnostics_for_uri_with_spaces_publish_and_clear --tests lsp.LspRealProjectEditorLifecycleTddTest.file_uri_with_spaces_change_and_close_preserve_uri`
notes:
  - Cluster uri-spaces-editor-lifecycle-diagnostics from REVIEW-FULL-29265516413 (2 reds).
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-688.lock
  - locks/files/tasks__TASK-688.md.lock
related_commits: []
progress:
  - 2026-07-14T materialize WINFULL-29265516413: created ready product fix clustering 2 URI-with-spaces RealProject reds (evidence run 29265516413).

  - 2026-07-13T16:22:47Z worker-WINFULL-29265516413-TASK-688: blocked on live TASK-685 exclusive lock of LuaLanguageService.kt; left status=ready owner=unassigned (no product edit).
  - 2026-07-13T16:57:36Z master full-loop: product fixes for URI with spaces normalize; status=review.
