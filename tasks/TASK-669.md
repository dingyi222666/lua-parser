id: TASK-669
title: Windows DocumentFacts table import must ignore explicit sparse numeric fields
status: review
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/workspace/DocumentFactsCollector.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/workspace/DocumentFacts.kt
  - src/commonTest/kotlin/semantic/workspace/DocumentFactsAndroidImportTddTest.kt
acceptance_criteria:
  - Clear Windows slice s017 failure (evidence run 29210211230 / WINSLICE-29210211230; same red on 29209854844):
    - semantic.workspace.DocumentFactsAndroidImportTddTest#table_import_ignores_explicit_sparse_numeric_fields[jvm]
  - Observed Windows AssertionError: expected import targets `[java.io.File]` but was `[java.io.File, java.util.Locale]` — sparse explicit numeric table fields incorrectly treated as import class targets.
  - Product DocumentFacts collector for `import { ... }` table forms must ignore explicit sparse numeric fields and only collect intended class-name string/identifier fields.
  - Prefer DocumentFactsCollector product fix; no CURRENTLY_ACCEPTS weaken.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s017: `./gradlew.bat jvmTest --tests semantic.workspace.DocumentFactsAndroidImportTddTest.table_import_ignores_explicit_sparse_numeric_fields`
notes:
  - Windows slice s017 run 29210211230: single DocumentFacts sparse numeric field import red.
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-669.lock
  - locks/files/tasks__TASK-669.md.lock
related_commits: []
progress:
  - 2026-07-13T materialize WINSLICE-29210211230: created ready product fix for DocumentFacts sparse numeric table-import red.
  - 2026-07-12T21:56:18Z worker-WINSLICE-29210211230-TASK-669: claimed; fixing isImplicitTableSequenceField to ignore explicit sparse numeric keys.
  - 2026-07-12T21:56:37Z worker-WINSLICE-29210211230-TASK-669: fixed isImplicitTableSequenceField to require zero-width integer key (ignore explicit sparse [n]=); status=review.
