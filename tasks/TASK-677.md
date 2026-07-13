id: TASK-677
title: Windows NewTestInventory recount raise for s018 baseline/campaign drift
status: review
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/jvmTest/kotlin/testinventory/NewTestInventoryTddTest.kt
  - docs/test-strategy.md
acceptance_criteria:
  - Clear Windows slice s018 failures (evidence run 29220594557 / WINSLICE-29220594557):
    - testinventory.NewTestInventoryTddTest#documentsHowNewTddSuitesCountTowardFiveHundredTestGoal[jvm]
    - testinventory.NewTestInventoryTddTest#reportsCurrentCommonAndJvmTestInventory[jvm]
  - Observed Windows AssertionError drift: expected baseline files `<331>` but was `<366>`; expected campaign files `<272>` but was `<307>` (counts from junit for run 29220594557). Raise expected* constants and matching docs/test-strategy.md totals together only (raise-only bar policy; never lower without review note).
  - Keep testinventory self-exclusion rules identical; preserve remaining_to_500=0 intent and excluded non-*Test.kt map unless live recount proves otherwise.
  - Inventory-only constants + docs lockstep; no product source edits; do not invent tests.
  - Cluster both classname#method reds in this single task (same fixture).
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s018: `./gradlew.bat jvmTest --tests testinventory.NewTestInventoryTddTest.reportsCurrentCommonAndJvmTestInventory`
  - Deferred to TASK-043 / windows-jvmtest slice s018: `./gradlew.bat jvmTest --tests testinventory.NewTestInventoryTddTest.documentsHowNewTddSuitesCountTowardFiveHundredTestGoal`
notes:
  - Windows slice s018 run 29220594557: inventory fixture stale vs live tree (+35 baseline/campaign files observed on Windows).
  - Prefer this evidence-owned task over older TASK-408 (ready but pre-s018 AC without method list / docs pairing alone).
  - Workers no Gradle. One task one agent. No docs filler beyond required strategy lockstep for the fixture.
related_locks:
  - locks/tasks/TASK-677.lock
  - locks/files/tasks__TASK-677.md.lock
  - locks/files/src__jvmTest__kotlin__testinventory__NewTestInventoryTddTest.kt.lock
  - locks/files/docs__test-strategy.md.lock
related_commits: []
progress:
  - 2026-07-13T materialize WINSLICE-29220594557: created ready inventory recount task covering both NewTestInventoryTddTest reds.
  - 2026-07-13T03:02:27Z worker-WINSLICE-29220594557-TASK-677: claimed in_progress; live recount baseline 366/5378 campaign 307/4857; raising fixture+docs lockstep.
  - 2026-07-13T03:03:00Z worker-WINSLICE-29220594557-TASK-677: raised inventory bars to baseline 366/5378 and campaign 307/4857; docs lockstep; status=review.
