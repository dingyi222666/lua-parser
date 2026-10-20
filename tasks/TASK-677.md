id: TASK-677
title: FULL NewTestInventory recount after suite cull (Windows full 29265516413)
status: review
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/jvmTest/kotlin/testinventory/NewTestInventoryTddTest.kt
  - docs/test-strategy.md
acceptance_criteria:
  - Clear FULL jvmTest failures (evidence run 29265516413 / WINFULL-29265516413):
    - testinventory.NewTestInventoryTddTest#documentsHowNewTddSuitesCountTowardFiveHundredTestGoal[jvm]
    - testinventory.NewTestInventoryTddTest#reportsCurrentCommonAndJvmTestInventory[jvm]
  - Observed Windows AssertionError drift (run 29265516413): expected baseline files `<366>` but was `<223>`; expected campaign files `<307>` but was `<167>` (suite cull after prior s018 raise in TASK-677).
  - Live-recount on the checked-in tree and set `expectedBaselineFiles` / `expectedBaselineTestMethods` / `expectedCampaignFiles` / `expectedCampaignTestMethods` (and matching docs/test-strategy.md totals) to the live Windows/full-tree counts together only. Review note: post-cull lower is intentional inventory lockstep, not silent bar-drop of product coverage.
  - Keep testinventory self-exclusion rules identical; preserve remaining_to_500 intent and excluded non-*Test.kt map unless live recount proves otherwise.
  - Inventory-only constants + docs lockstep; no product source edits; do not invent tests.
  - Cluster both classname#method reds in this single task (same fixture).
required_tests:
  - Deferred to TASK-043 / full jvmTest run 29265516413: `./gradlew.bat jvmTest --tests testinventory.NewTestInventoryTddTest.reportsCurrentCommonAndJvmTestInventory`
  - Deferred to TASK-043 / full jvmTest run 29265516413: `./gradlew.bat jvmTest --tests testinventory.NewTestInventoryTddTest.documentsHowNewTddSuitesCountTowardFiveHundredTestGoal`
notes:
  - Prefer this evidence-owned task over older TASK-408 (ready but pre-full AC without method list / docs pairing alone). Prefer over docs-only TASK-407.
  - Prior s018 raise (366/5378, 307/4857) is stale after suite cull; full suite 29265516413 observes ~223 baseline files / ~167 campaign files (method totals must be live-recounted).
  - Workers no Gradle. One task one agent. No docs filler beyond required strategy lockstep for the fixture.
related_locks:
  - locks/tasks/TASK-677.lock
  - locks/files/tasks__TASK-677.md.lock
  - locks/files/src__jvmTest__kotlin__testinventory__NewTestInventoryTddTest.kt.lock
  - locks/files/docs__test-strategy.md.lock
related_commits: []
progress:
  - 2026-07-13T16:23:10Z worker-WINFULL-29265516413-TASK-677: live recount baseline 223/1697 campaign 167/1259; fixture+docs lockstep; status=review owner=unassigned.
  - 2026-07-13T16:22:16Z worker-WINFULL-29265516413-TASK-677: claimed in_progress; live recount after suite cull (expected ~223 baseline / ~167 campaign files); lockstep fixture+docs.
  - 2026-07-14T materialize WINFULL-29265516413: reuse TASK-677; status review→ready; retarget AC to full suite cull drift expected 366→223 / 307→167 (evidence run 29265516413); live method recount required.
  - 2026-07-13T materialize WINSLICE-29220594557: created ready inventory recount task covering both NewTestInventoryTddTest reds.
  - 2026-07-13T03:02:27Z worker-WINSLICE-29220594557-TASK-677: claimed in_progress; live recount baseline 366/5378 campaign 307/4857; raising fixture+docs lockstep.
  - 2026-07-13T03:03:00Z worker-WINSLICE-29220594557-TASK-677: raised inventory bars to baseline 366/5378 and campaign 307/4857; docs lockstep; status=review.
