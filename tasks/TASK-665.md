id: TASK-665
title: Windows Android-Lua import definition surface for direct class and static member
status: ready
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/workspace/LuaWorkspaceQueryFacade.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/model/ReferenceQueries.kt
  - src/jvmMain/kotlin/io/github/dingyi222666/luaparser/interop/jvm/JvmWorkspaceEngine.kt
  - src/jvmTest/kotlin/semantic/workspace/AndroidLuaImportWorkspaceTddTest.kt
acceptance_criteria:
  - Clear Windows slice s017 failure (evidence run 29210211230 / WINSLICE-29210211230; same red on 29209854844):
    - semantic.workspace.AndroidLuaImportWorkspaceTddTest#import_definition_surface_handles_direct_class_and_static_member[jvm]
  - Observed Windows AssertionError: expected definition paths `[__jvm__/classes/java/util/Locale.lua]` but was `[]` for direct class import / static-member definition surface.
  - Product gotoDefinition for imported Java class identifiers and static members must land on the mounted JVM class provider path (`__jvm__/classes/...`), not empty.
  - Prefer query/facade + JVM provider mount repair over CURRENTLY_ACCEPTS or weakening hard locks.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s017: `./gradlew.bat jvmTest --tests semantic.workspace.AndroidLuaImportWorkspaceTddTest.import_definition_surface_handles_direct_class_and_static_member`
notes:
  - Windows slice s017 run 29210211230: 16 failures; this is the direct class/static-member definition empty-list red.
  - Lineage: TASK-176 Android-Lua import surfaces (done) regressed under must-green-to-advance s017.
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-665.lock
  - locks/files/tasks__TASK-665.md.lock
related_commits: []
progress:
  - 2026-07-13T materialize WINSLICE-29210211230: created ready product fix for AndroidLuaImportWorkspace definition surface empty Locale provider path.
  - 2026-07-12T21:54:52Z worker-WINSLICE-29210211230-TASK-665: claimed; inspecting gotoDefinition empty for Locale/ROOT
  - 2026-07-12T21:56:16Z worker-WINSLICE-29210211230-TASK-665: blocked; live locks on scope files by TASK-666 (LuaWorkspaceQueryFacade/JvmWorkspaceEngine/AndroidLuaImportWorkspaceTddTest) and TASK-664 ReferenceQueries.lock; claim released to ready
