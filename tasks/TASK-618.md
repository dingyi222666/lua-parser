id: TASK-618
title: Windows JvmReflectionModel assignability + static call surface product fix
status: review
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/jvmMain/kotlin/io/github/dingyi222666/luaparser/interop/jvm/JvmClassModuleProvider.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/types
  - src/jvmTest/kotlin/interop/jvm/JvmReflectionModelTddTest.kt
acceptance_criteria:
  - Clear Windows slice failure (evidence run 29175624965 / WINSLICE-29175624965 / slice s002):
    - interop.jvm.JvmReflectionModelTddTest#reflected_class_type_hydrates_transitive_interfaces_for_assignability
  - Product reflected ClassType must hydrate transitive interfaces for assignability checks when host classpath/jrt reflection is available.
  - Prefer product type/model hydration over CURRENTLY_ACCEPTS; do not invent android.jar presence.
  - Do not regress other green JvmReflectionModelTddTest methods on the same class.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s002: `./gradlew.bat jvmTest --tests interop.jvm.JvmReflectionModelTddTest.reflected_class_type_hydrates_transitive_interfaces_for_assignability`
notes:
  - Windows slice s002 run 29175624965 failures.txt lists reflected_class_type_hydrates_transitive_interfaces_for_assignability.
  - TASK-148 historical hierarchy work is done; re-open product gap under Windows evidence as new ready task.
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-618.lock
  - locks/files/tasks__TASK-618.md.lock
  - locks/files/src__jvmTest__kotlin__interop__jvm__JvmReflectionModelTddTest.kt.lock
related_commits: []
progress:
  - 2026-07-12T materialize WINSLICE-29175624965: created ready product fix for reflection assignability hydration red.
  - 2026-07-12T01:49:42Z worker-WINSLICE-29175624965-TASK-618: claim + fix transitive interface hydration for reflected ClassType assignability.
  - 2026-07-12T01:50:46Z worker-WINSLICE-29175624965-TASK-618: product fix hierarchy skeleton after member-expand cap so transitive interfaces (Iterable) hydrate for assignability; status=review.
