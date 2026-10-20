id: TASK-616
title: Windows JvmPackageProvider package virtual paths must not mount classes prefix
status: review
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/jvmMain/kotlin/io/github/dingyi222666/luaparser/interop/jvm/JvmClassModuleProvider.kt
  - src/jvmMain/kotlin/io/github/dingyi222666/luaparser/interop/jvm/JvmWorkspaceEngine.kt
  - src/jvmTest/kotlin/interop/jvm/JvmPackageProviderEmptyRootTddTest.kt
  - src/jvmTest/kotlin/interop/jvm/JvmPackageProviderListingTddTest.kt
  - src/jvmTest/kotlin/interop/jvm/JvmPackageProviderNestedPackageTddTest.kt
acceptance_criteria:
  - Clear Windows slice failures (evidence run 29175624965 / WINSLICE-29175624965 / slice s002):
    - interop.jvm.JvmPackageProviderEmptyRootTddTest#non_empty_packages_still_list_when_empty_and_missing_roots_are_present
    - interop.jvm.JvmPackageProviderEmptyRootTddTest#package_paths_never_use_classes_prefix_for_non_empty_roots
    - interop.jvm.JvmPackageProviderEmptyRootTddTest#multiple_non_empty_packages_list_stable_paths_and_classes_despite_empty_root
    - interop.jvm.JvmPackageProviderListingTddTest#duplicate_import_targets_collapse_to_single_stable_path
    - interop.jvm.JvmPackageProviderListingTddTest#java_util_wildcard_lists_stable_package_virtual_path
    - interop.jvm.JvmPackageProviderListingTddTest#multi_package_configuration_lists_stable_paths_for_each_target
    - interop.jvm.JvmPackageProviderListingTddTest#mix_of_valid_and_empty_packages_lists_only_valid_stable_paths
    - interop.jvm.JvmPackageProviderListingTddTest#java_io_wildcard_lists_stable_package_virtual_path
    - interop.jvm.JvmPackageProviderListingTddTest#package_provider_path_never_uses_classes_prefix
    - interop.jvm.JvmPackageProviderNestedPackageTddTest#nested_jdk_package_still_lists_when_extra_empty_classpath_present
    - interop.jvm.JvmPackageProviderNestedPackageTddTest#depth2_java_util_concurrent_lists_nested_classes_at_full_segment_path
    - interop.jvm.JvmPackageProviderNestedPackageTddTest#mix_of_valid_nested_and_missing_packages_lists_only_valid_paths_without_throw
    - interop.jvm.JvmPackageProviderNestedPackageTddTest#blank_malformed_and_non_wildcard_nested_targets_do_not_throw_or_mount
    - interop.jvm.JvmPackageProviderNestedPackageTddTest#nested_package_paths_never_use_classes_prefix
    - interop.jvm.JvmPackageProviderNestedPackageTddTest#parent_package_alone_does_not_mount_nested_subpackage_providers
    - interop.jvm.JvmPackageProviderNestedPackageTddTest#multi_depth_nested_targets_list_each_subpackage_provider_and_its_classes
    - interop.jvm.JvmPackageProviderNestedPackageTddTest#nested_package_alone_does_not_mount_parent_or_sibling_providers
    - interop.jvm.JvmPackageProviderNestedPackageTddTest#nested_import_target_order_and_duplicates_do_not_change_path_set
  - Observed Windows product defect: package-provider listing returns mixed `__jvm__/packages/...` and `__jvm__/classes/...` class module paths. Package listing must mount only stable `__jvm__/packages/<segments>.lua` virtual paths; class modules stay on class-resolution surfaces, not package-list keys.
  - Empty/missing roots and blank/malformed/non-wildcard targets must not throw and must not mount spurious class paths.
  - Nested depth targets list only configured subpackage package-paths (no parent/sibling leak; no classes-prefix paths).
  - Prefer product fix in JvmClassModuleProvider / package mount path construction; no CURRENTLY_ACCEPTS weakening of green-lock path asserts.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s002: `./gradlew.bat jvmTest --tests interop.jvm.JvmPackageProviderEmptyRootTddTest --tests interop.jvm.JvmPackageProviderListingTddTest --tests interop.jvm.JvmPackageProviderNestedPackageTddTest`
notes:
  - Windows slice s002 run 29175624965: 18 package-provider reds share one product root cause (classes prefix leaking into package listing).
  - Prior corpus tasks TASK-301/353 are done (test-only); this is product path fix.
  - Serialize exclusive claim on JvmClassModuleProvider.kt vs other interop.jvm workers.
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-616.lock
  - locks/files/tasks__TASK-616.md.lock
  - locks/files/src__jvmMain__kotlin__io__github__dingyi222666__luaparser__interop__jvm__JvmClassModuleProvider.kt.lock
related_commits: []
progress:
  - 2026-07-12T materialize WINSLICE-29175624965: created ready product fix for 18 JvmPackageProvider empty/listing/nested reds (packages vs classes virtual path).
  - 2026-07-12T01:49:07Z worker-WINSLICE-29175624965-TASK-616: claimed; fixing packageProvidersFor to packages-only paths (no classes prefix).
  - 2026-07-12T01:51:17Z worker-WINSLICE-29175624965-TASK-616: product fix — packageProvidersFor keys only __jvm__/packages/*; shallow class mounts moved to packageMemberClassProvidersFor; JvmWorkspaceEngine merges both; wildcard-only package list (no classes prefix leak).
