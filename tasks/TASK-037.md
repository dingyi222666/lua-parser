id: TASK-037
title: Final green verification and acceptance audit
status: done
priority: p0
owner: unassigned
depends_on: [TASK-025, TASK-026, TASK-027, TASK-028, TASK-029, TASK-030, TASK-031, TASK-032, TASK-033, TASK-034, TASK-035, TASK-036, TASK-043, TASK-051, TASK-052, TASK-053, TASK-054, TASK-055, TASK-056, TASK-057, TASK-058, TASK-105, TASK-106]
scope:
  - docs/final-verification.md
acceptance_criteria:
  - Confirm at least 500 new tests exist and pass.
  - Run the reproducible final verification suite for parser, AST, recovery, semantics, workspace, Java interop, Android android.jar loading, Android-Lua corpus, and LSP.
  - Record command outputs, environment details, skipped tests if any, and final criteria-by-criteria acceptance status.
  - Do not declare success unless every parent-thread criterion is satisfied.
required_tests:
  - `./gradlew.bat jvmTest`
  - Additional common/js/native tests if enabled and relevant.
notes:
  - 2026-07-11T REVIEW37-WAVE-WAVE36B-20260711: Kept blocked: TASK-184 still not green after REVIEW37.

  - Requires repo-git lock for any final commit. No destructive git commands.
  - Do not start until TASK-043 serialized verification is done; final acceptance audit consumes TASK-043 results rather than launching concurrent Gradle verification.
related_locks:
  - locks/tasks/TASK-037.lock
  - locks/files/docs__final-verification.md.lock
  - locks/files/tasks__TASK-037.md.lock
related_commits: []
progress:
  - 2026-07-13T06:19:31Z Final acceptance audit: consumed TASK-043 full suite 29228040252 (5386 tests, 0 failures), TASK-105 ledger, TASK-106 AC-01..AC-12 Pass. Campaign remaining_to_500=0 with suite pass. Production goal path complete. status→done.
  - 2026-06-08T17:46:57+08:00 REVIEW-WAKE-POST-T080-SCOUT-20260608-174657 kept TASK-037 blocked and serialized final acceptance behind TASK-105/TASK-106 in addition to TASK-043, so final verification ledger refresh and criteria traceability cannot overlap. No Gradle, tests, compile, kotlinc, Java verification, build command, source edit, or test edit was run by this review.
  - 2026-06-08T05:44:31+08:00 WPOOL-211 reacquired TASK-037 metadata locks after REVIEW dependency correction, created docs/final-verification.md as a non-green final verification ledger, and performed only read-only source inventory checks. Gradle/build/test/compile/jvmTest commands were not run because this worker wave defers all verification to TASK-043. Current read-only inventory is 454 campaign `*TddTest.kt` `@Test` methods excluding testinventory, below the 500-new-test acceptance criterion; final acceptance remains blocked pending TASK-043 serialized verification and reconciliation of the 500-test criterion.
  - 2026-06-08T05:39:21+08:00 REVIEW released orphan WPOOL-211 TASK-037 locks after confirming process 58284 was gone and task metadata still showed `todo/unassigned`. TASK-037 now depends on TASK-043 so the final audit cannot overlap serialized Gradle verification.
  - 2026-06-08T05:43:46+08:00 REVIEW resampled TASK-037 after WPOOL-211 completed read-only audit handoff; preserved blocked status, TASK-043 dependency, and WPOOL-211 progress note while merging duplicate progress sections.
