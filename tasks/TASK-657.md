id: TASK-657
title: Windows unannotated function declaredType FunctionType unknown product fix
status: review
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/binder/DeclarationBinder.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/binder/DeclarationFactories.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/types/resolve/TypeResolver.kt
  - src/jvmTest/kotlin/semantic/checker/ReturnMultiValueCorpusTddTest.kt
acceptance_criteria:
  - Clear Windows slice s015 failure (evidence run 29206739850 / WINSLICE-29206739850):
    - semantic.checker.ReturnMultiValueCorpusTddTest#unannotatedFunctionDeclaredReturnIsUnknown[jvm]
  - Observed Windows NullPointerException at ReturnMultiValueCorpusTddTest.kt:474:
    - `null cannot be cast to non-null type FunctionType` when reading `harness.function("freeform").declaredType` for unannotated `local function freeform() return "x", 1, true end`.
  - Product must attach a FunctionType with bare UnknownType return (no invented multi-return slots) on unannotated local function declarations so `declaredType as FunctionType` and `functionType.returnType == UnknownType` hold.
  - Keep annotated ---@return / multi-return hard diagnostics green; do not invent synthetic FunctionType for undocumented GLOBAL export-only cases that intentionally stay non-FunctionType (see undocumentedGlobalFunctionHasNoSyntheticFunctionType). Prefer binder/type-resolution product path over CURRENTLY_ACCEPTS.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s015: `./gradlew.bat jvmTest --tests semantic.checker.ReturnMultiValueCorpusTddTest.unannotatedFunctionDeclaredReturnIsUnknown`
notes:
  - Windows slice s015 run 29206739850: 35 tests in class, 1 failure (this method).
  - Adjacent review TASK-555 unknown-friendly freeform multi-return is separate; this red is missing FunctionType on declaredType for unannotated local freeform.
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-657.lock
  - locks/files/tasks__TASK-657.md.lock
  - locks/files/src__commonMain__kotlin__io__github__dingyi222666__luaparser__semantic__binder__DeclarationBinder.kt.lock
related_commits: []
progress:
  - 2026-07-13T materialize WINSLICE-29206739850: created ready product fix for unannotatedFunctionDeclaredReturnIsUnknown (1 method).
  - 2026-07-12T20:21:59Z worker-WINSLICE-29206739850-TASK-657: claimed; materialize FunctionType(Unknown return) for bare local FUNCTION only.
  - 2026-07-12T20:22:39Z worker-WINSLICE-29206739850-TASK-657: product fix in TypeResolver.resolveFunctionDeclaration — bare local FUNCTION gets FunctionType(return=UnknownType); bare GLOBAL still no synthetic FunctionType. No gradle (contract). status=review.
