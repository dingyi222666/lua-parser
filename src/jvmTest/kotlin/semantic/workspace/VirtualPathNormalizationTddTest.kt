package semantic.workspace

import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TASK-208 corpus: VirtualPath normalization consistency.
 *
 * Acceptance:
 * - Virtual paths normalize consistently across slash styles.
 * - No host path leakage into provider virtual paths.
 *
 * Test-only; product code is out of scope. Verification is review-owned (no Gradle here).
 *
 * Production formulas mirrored by helpers:
 * - JvmClassModuleProvider: `__jvm__/classes/${clazz.name.replace('.', '/')}.lua`
 * - JvmClassModuleProvider: `__jvm__/packages/${packageName.replace('.', '/')}.lua`
 * - BuiltinOverlayLoader: `__lua_std__/$versionSegment/$moduleName.lua`
 * - LuaLanguageService.encodedUriPath: `__lsp_uri__/$encoded.lua`
 */
class VirtualPathNormalizationTddTest {

    // -------------------------------------------------------------------------
    // Slash-style consistency
    // -------------------------------------------------------------------------

    @Test
    fun forward_and_backslash_inputs_normalize_to_same_value() {
        val cases = listOf(
            "main.lua" to "main.lua",
            "src/lib/mod.lua" to "src/lib/mod.lua",
            "src\\lib\\mod.lua" to "src/lib/mod.lua",
            "src\\lib/mod.lua" to "src/lib/mod.lua",
            "src/lib\\mod.lua" to "src/lib/mod.lua",
            "a\\\\b//c.lua" to "a/b/c.lua",
            "deep\\nested/path\\file.lua" to "deep/nested/path/file.lua"
        )

        for ((input, expected) in cases) {
            val path = VirtualPath.of(input)
            assertEquals(expected, path.value, "normalize($input)")
            assertFalse('\\' in path.value, "normalized value must not retain backslashes: $input -> ${path.value}")
        }
    }

    @Test
    fun mixed_slash_styles_are_equal_as_virtual_paths() {
        val variants = listOf(
            "app/modules/core.lua",
            "app\\modules\\core.lua",
            "app/modules\\core.lua",
            "app\\modules/core.lua",
            "app//modules///core.lua",
            "app\\\\modules\\\\core.lua"
        )

        val normalized = variants.map { VirtualPath.of(it) }.distinct()
        assertEquals(1, normalized.size, "all slash variants must collapse to one VirtualPath")
        assertEquals("app/modules/core.lua", normalized.single().value)
    }

    @Test
    fun dot_segments_are_collapsed_consistently_across_slash_styles() {
        val cases = listOf(
            "src/./module.lua",
            "src\\.\\module.lua",
            "src/././module.lua",
            "./src/module.lua",
            ".\\src\\module.lua",
            "src/foo/../module.lua",
            "src\\foo\\..\\module.lua",
            "src/foo/./bar/../module.lua",
            "src\\foo\\.\\bar\\..\\module.lua"
        )

        val normalized = cases.map { VirtualPath.of(it) }.distinct()
        assertEquals(1, normalized.size)
        assertEquals("src/module.lua", normalized.single().value)
    }

    @Test
    fun trailing_and_duplicate_separators_do_not_change_identity() {
        assertEquals(
            VirtualPath.of("lib/util.lua"),
            VirtualPath.of("lib/util.lua/")
        )
        assertEquals(
            VirtualPath.of("lib/util.lua"),
            VirtualPath.of("lib//util.lua")
        )
        assertEquals(
            VirtualPath.of("lib/util.lua"),
            VirtualPath.of("lib\\\\util.lua\\")
        )
        assertEquals(
            VirtualPath.of("lib/util.lua"),
            VirtualPath.of("lib/util.lua\\")
        )
    }

    @Test
    fun resolve_joins_with_forward_slash_and_renormalizes() {
        val base = VirtualPath.of("src\\pkg")
        assertEquals("src/pkg/child.lua", base.resolve("child.lua").value)
        assertEquals("src/pkg/nested/child.lua", base.resolve("nested\\child.lua").value)
        assertEquals("src/pkg/child.lua", base.resolve("./child.lua").value)
        assertEquals("src/sibling.lua", base.resolve("..\\sibling.lua").value)
        assertEquals("src/pkg", base.resolve("").value)
        assertEquals("src/pkg/a/b.lua", base.resolve("a\\\\b.lua").value)
        assertFailsWith<IllegalArgumentException> {
            base.resolve("../../outside.lua")
        }
    }

    @Test
    fun normalization_preserves_case_and_dollar_inner_class_segments() {
        assertEquals(
            "Feature/Profile.lua",
            VirtualPath.of("Feature\\Profile.lua").value
        )
        assertEquals(
            "__jvm__/classes/android/view/View\$OnClickListener.lua",
            VirtualPath.of("__jvm__\\classes\\android\\view\\View\$OnClickListener.lua").value
        )
        assertEquals(
            jvmClassProviderPath("android.view.View\$OnClickListener"),
            VirtualPath.of("__jvm__/classes/android/view/View\$OnClickListener.lua")
        )
    }

    // -------------------------------------------------------------------------
    // Workspace-relative invariants
    // -------------------------------------------------------------------------

    @Test
    fun leading_slash_is_rejected_as_non_relative() {
        assertFailsWith<IllegalArgumentException> {
            VirtualPath.of("/absolute/path.lua")
        }
        // After separator normalization, a Windows-style root-ish form that
        // still begins with '/' after strip is not produced by of(); empty
        // after collapse is also rejected.
        assertFailsWith<IllegalArgumentException> {
            VirtualPath.of("/")
        }
        assertFailsWith<IllegalArgumentException> {
            VirtualPath.of("///")
        }
    }

    @Test
    fun empty_and_dot_only_paths_are_rejected() {
        assertFailsWith<IllegalArgumentException> {
            VirtualPath.of("")
        }
        assertFailsWith<IllegalArgumentException> {
            VirtualPath.of(".")
        }
        assertFailsWith<IllegalArgumentException> {
            VirtualPath.of("./.")
        }
        assertFailsWith<IllegalArgumentException> {
            VirtualPath.of(".\\.")
        }
        assertFailsWith<IllegalArgumentException> {
            VirtualPath.of("././.")
        }
    }

    @Test
    fun parent_escape_beyond_workspace_is_rejected() {
        assertFailsWith<IllegalArgumentException> {
            VirtualPath.of("..")
        }
        assertFailsWith<IllegalArgumentException> {
            VirtualPath.of("../secret.lua")
        }
        assertFailsWith<IllegalArgumentException> {
            VirtualPath.of("src/../../outside.lua")
        }
        assertFailsWith<IllegalArgumentException> {
            VirtualPath.of("src\\..\\..\\outside.lua")
        }
        assertFailsWith<IllegalArgumentException> {
            VirtualPath.of("a/../b/../../c.lua")
        }
        assertFailsWith<IllegalArgumentException> {
            VirtualPath.of("..\\secret.lua")
        }
    }

    @Test
    fun in_workspace_parent_navigation_is_allowed() {
        assertEquals("b.lua", VirtualPath.of("a/../b.lua").value)
        assertEquals("b.lua", VirtualPath.of("a\\..\\b.lua").value)
        assertEquals("x/y.lua", VirtualPath.of("x/z/../y.lua").value)
        assertEquals("y.lua", VirtualPath.of("a/b/../../y.lua").value)
    }

    @Test
    fun toString_matches_normalized_value() {
        val path = VirtualPath.of("src\\lua\\mod.lua")
        assertEquals(path.value, path.toString())
        assertEquals("src/lua/mod.lua", path.toString())
    }

    // -------------------------------------------------------------------------
    // Provider virtual paths: no host path leakage
    // -------------------------------------------------------------------------

    @Test
    fun jvm_class_provider_paths_use_virtual_prefix_not_host_filesystem() {
        // Mirrors JvmClassModuleProvider.providerForClass path construction:
        // VirtualPath.of("__jvm__/classes/${clazz.name.replace('.', '/')}.lua")
        val classNames = listOf(
            "java.lang.String",
            "java.util.Map\$Entry",
            "android.widget.TextView",
            "com.androlua.LuaActivity",
            "android.view.View\$OnClickListener"
        )

        for (className in classNames) {
            val path = jvmClassProviderPath(className)
            assertTrue(
                path.value.startsWith("__jvm__/classes/"),
                "provider path must use virtual prefix: ${path.value}"
            )
            assertTrue(path.value.endsWith(".lua"), path.value)
            assertFalse(looksLikeHostAbsolutePath(path.value), "host leakage: ${path.value}")
            assertFalse(path.value.contains('\\'), path.value)
            // Class binary name uses '/', never host drive or UNC.
            assertFalse(path.value.contains(':'), "no drive letters: ${path.value}")
            assertFalse(path.value.startsWith('/'), path.value)
        }

        assertEquals(
            "__jvm__/classes/java/util/Map\$Entry.lua",
            jvmClassProviderPath("java.util.Map\$Entry").value
        )
        assertEquals(
            jvmClassProviderPath("java.io.File"),
            VirtualPath.of("__jvm__\\classes\\java\\io\\File.lua")
        )
    }

    @Test
    fun jvm_package_provider_paths_use_virtual_prefix_not_host_filesystem() {
        val packages = listOf("java.io", "android.widget", "com.androlua")
        for (packageName in packages) {
            val path = jvmPackageProviderPath(packageName)
            assertTrue(path.value.startsWith("__jvm__/packages/"), path.value)
            assertTrue(path.value.endsWith(".lua"), path.value)
            assertFalse(looksLikeHostAbsolutePath(path.value), "host leakage: ${path.value}")
            assertFalse(path.value.contains(':'), path.value)
            assertFalse(path.value.contains('\\'), path.value)
        }

        assertEquals(
            "__jvm__/packages/java/io.lua",
            jvmPackageProviderPath("java.io").value
        )
        assertEquals(
            jvmPackageProviderPath("android.view"),
            VirtualPath.of("__jvm__\\packages\\android\\view.lua")
        )
    }

    @Test
    fun lua_std_provider_paths_use_virtual_prefix_not_host_filesystem() {
        // Mirrors BuiltinOverlayLoader std module path construction.
        val versions = listOf("5.3", "5.4", "androlua5.3")
        val modules = listOf("math", "string", "socket.url", "_G", "import")

        for (version in versions) {
            for (module in modules) {
                val path = luaStdProviderPath(version, module)
                assertTrue(
                    path.value.startsWith("__lua_std__/"),
                    "std provider must use virtual prefix: ${path.value}"
                )
                assertFalse(looksLikeHostAbsolutePath(path.value), "host leakage: ${path.value}")
                assertFalse(path.value.contains('\\'), path.value)
                assertFalse(path.value.startsWith('/'), path.value)
                assertFalse(path.value.contains(':'), path.value)
            }
        }

        assertEquals(
            VirtualPath.of("__lua_std__/5.4/math.lua"),
            VirtualPath.of("__lua_std__\\5.4\\math.lua")
        )
        assertEquals(
            "__lua_std__/androlua5.3/socket.url.lua",
            luaStdProviderPath("androlua5.3", "socket.url").value
        )
    }

    @Test
    fun lsp_uri_fallback_provider_paths_stay_virtual_and_workspace_relative() {
        // Mirrors LuaLanguageService.encodedUriPath fallback:
        // VirtualPath.of("__lsp_uri__/$encoded.lua")
        // Production Base64-url-encodes the URI so host material is opaque; the
        // corpus also includes a percent-encoded stand-in to prove the virtual
        // prefix still dominates even when host-looking text is present.
        val opaqueToken = "ZmlsZTovLy9DOi9Vc2Vycy9kaW5neWkvcHJvamVjdC9tYWluLmx1YQ"
        val percentEncoded = "file%3A%2F%2F%2FC%3A%2FUsers%2Fdingyi%2Fproject%2Fmain.lua"

        for (encoded in listOf(opaqueToken, percentEncoded)) {
            val path = VirtualPath.of("__lsp_uri__/$encoded.lua")
            assertTrue(path.value.startsWith("__lsp_uri__/"), path.value)
            assertFalse(looksLikeHostAbsolutePath(path.value), "host leakage: ${path.value}")
            assertFalse(path.value.startsWith("file:"), path.value)
            assertFalse(path.value.startsWith("C:"), path.value)
            assertFalse(path.value.startsWith("/"), path.value)
            assertFalse(path.value.contains('\\'), path.value)
        }

        assertEquals(
            VirtualPath.of("__lsp_uri__/$opaqueToken.lua"),
            VirtualPath.of("__lsp_uri__\\$opaqueToken.lua")
        )
    }

    @Test
    fun raw_host_absolute_style_inputs_are_not_accepted_as_workspace_relative_providers() {
        // Provider construction must never pass host absolute paths into VirtualPath.of.
        // Absolute Unix paths are rejected by the leading-slash invariant.
        assertFailsWith<IllegalArgumentException> {
            VirtualPath.of("/Users/dingyi/projects/app/main.lua")
        }
        assertFailsWith<IllegalArgumentException> {
            VirtualPath.of("/home/user/workspace/mod.lua")
        }

        // UNC-looking inputs lose empty leading segments under normalize(), so they
        // become workspace-relative segment lists — never provider virtual paths.
        val uncStyle = VirtualPath.of("//server/share/mod.lua")
        assertEquals("server/share/mod.lua", uncStyle.value)
        assertFalse(isProviderVirtualPath(uncStyle), uncStyle.value)
        assertFalse(uncStyle.value.startsWith("__jvm__/"), uncStyle.value)

        // If a Windows absolute path is naively fed in, it must not be treated as a
        // provider virtual path under __jvm__/ or __lua_std__/. Even if accepted as a
        // relative segment list (drive letter as first segment), it is not a provider path.
        val windowsStyle = runCatching { VirtualPath.of("C:\\Users\\dingyi\\app\\main.lua") }.getOrNull()
        if (windowsStyle != null) {
            assertFalse(
                windowsStyle.value.startsWith("__jvm__/") ||
                    windowsStyle.value.startsWith("__lua_std__/") ||
                    windowsStyle.value.startsWith("__lsp_uri__/") ||
                    windowsStyle.value.startsWith("__meta__/"),
                "host path must not masquerade as provider virtual path: ${windowsStyle.value}"
            )
            assertTrue(
                looksLikeHostAbsolutePath(windowsStyle.value) || windowsStyle.value.startsWith("C:"),
                "raw host input remains host-shaped when accepted: ${windowsStyle.value}"
            )
            assertFalse(isProviderVirtualPath(windowsStyle))
        }

        // file:// scheme URIs must not be used as VirtualPath.of inputs for providers.
        val fileUri = runCatching { VirtualPath.of("file:///C:/Users/dingyi/app/main.lua") }.getOrNull()
        if (fileUri != null) {
            assertFalse(isProviderVirtualPath(fileUri), "file URI must not be a provider path: ${fileUri.value}")
            assertFalse(fileUri.value.startsWith("__jvm__/"), fileUri.value)
        }

        // Canonical provider factories always produce virtual-prefixed paths.
        assertTrue(isProviderVirtualPath(jvmClassProviderPath("java.lang.String")))
        assertTrue(isProviderVirtualPath(jvmPackageProviderPath("java.lang")))
        assertTrue(isProviderVirtualPath(luaStdProviderPath("5.3", "math")))
        assertTrue(isProviderVirtualPath(VirtualPath.of("__lsp_uri__/opaque.lua")))
        assertTrue(isProviderVirtualPath(VirtualPath.of("__meta__/Mounted.lua")))
    }

    @Test
    fun provider_path_equality_is_stable_across_separator_styles() {
        val classForward = VirtualPath.of("__jvm__/classes/java/lang/String.lua")
        val classBack = VirtualPath.of("__jvm__\\classes\\java\\lang\\String.lua")
        val classMixed = VirtualPath.of("__jvm__/classes\\java/lang\\String.lua")

        assertEquals(classForward, classBack)
        assertEquals(classForward, classMixed)
        assertEquals("__jvm__/classes/java/lang/String.lua", classBack.value)

        val stdForward = VirtualPath.of("__lua_std__/androlua5.3/socket.url.lua")
        val stdBack = VirtualPath.of("__lua_std__\\androlua5.3\\socket.url.lua")
        assertEquals(stdForward, stdBack)

        val metaForward = VirtualPath.of("__meta__/Mounted.lua")
        val metaBack = VirtualPath.of("__meta__\\Mounted.lua")
        assertEquals(metaForward, metaBack)
        assertEquals("__meta__/Mounted.lua", metaBack.value)
    }

    @Test
    fun provider_paths_never_contain_unc_or_drive_host_shapes() {
        val providers = listOf(
            jvmClassProviderPath("java.io.File"),
            jvmClassProviderPath("android.content.Context"),
            jvmClassProviderPath("android.view.View\$OnClickListener"),
            jvmPackageProviderPath("android.app"),
            luaStdProviderPath("5.3", "table"),
            luaStdProviderPath("androlua5.3", "import"),
            VirtualPath.of("__lsp_uri__/opaque-token.lua"),
            VirtualPath.of("__meta__/Mounted.lua")
        )

        for (path in providers) {
            assertTrue(isProviderVirtualPath(path), path.value)
            assertFalse(looksLikeHostAbsolutePath(path.value), path.value)
            assertFalse(path.value.startsWith("//"), "UNC-style: ${path.value}")
            assertFalse(path.value.startsWith("\\\\"), path.value)
            assertFalse(Regex("^[A-Za-z]:").containsMatchIn(path.value), "drive letter: ${path.value}")
            assertEquals(path.value, VirtualPath.of(path.value.replace('/', '\\')).value)
            // Provider values must stay free of host home / Users segments.
            assertFalse(path.value.contains("/Users/"), path.value)
            assertFalse(path.value.contains("/home/"), path.value)
        }
    }

    @Test
    fun host_path_must_not_be_joined_under_provider_prefix() {
        // Even if a host absolute fragment is concatenated under a virtual prefix
        // without normalization discipline, VirtualPath must not yield a path that
        // both claims a provider prefix and embeds a host absolute root.
        val sneaky = runCatching {
            VirtualPath.of("__jvm__/classes//Users/dingyi/evil.lua")
        }.getOrNull()
        if (sneaky != null) {
            // Duplicate separators collapse; leading-empty segments are dropped, so
            // this becomes a relative-looking provider path — but it must still not
            // look like a host absolute path after normalization.
            assertFalse(looksLikeHostAbsolutePath(sneaky.value), sneaky.value)
            assertFalse(sneaky.value.startsWith('/'), sneaky.value)
            assertTrue(sneaky.value.startsWith("__jvm__/"), sneaky.value)
        }

        // Absolute host under resolve must not escape the virtual workspace.
        val base = VirtualPath.of("__jvm__/classes")
        assertFailsWith<IllegalArgumentException> {
            // resolve only accepts relative child; absolute-looking children that
            // begin with '/' are rejected by of() after join+normalize only if they
            // introduce a leading '/'. Construct via of directly instead.
            VirtualPath.of("/Users/dingyi/evil.lua")
        }
        // Parent escape from a provider prefix must still be blocked at workspace root.
        assertFailsWith<IllegalArgumentException> {
            base.resolve("../../../../../../etc/passwd")
        }
    }

    // -------------------------------------------------------------------------
    // Corpus table: slash-style matrix
    // -------------------------------------------------------------------------

    @Test
    fun slash_style_matrix_corpus_all_collapse_to_expected() {
        data class Case(val input: String, val expected: String)

        val corpus = listOf(
            Case("a.lua", "a.lua"),
            Case("a/b.lua", "a/b.lua"),
            Case("a\\b.lua", "a/b.lua"),
            Case("a/b\\c.lua", "a/b/c.lua"),
            Case("a\\b/c.lua", "a/b/c.lua"),
            Case("a//b///c.lua", "a/b/c.lua"),
            Case("a\\\\b\\\\c.lua", "a/b/c.lua"),
            Case("./a.lua", "a.lua"),
            Case(".\\a.lua", "a.lua"),
            Case("a/./b.lua", "a/b.lua"),
            Case("a\\.\\b.lua", "a/b.lua"),
            Case("a/b/../c.lua", "a/c.lua"),
            Case("a\\b\\..\\c.lua", "a/c.lua"),
            Case("a/b/./../c/./d.lua", "a/c/d.lua"),
            Case("a/b/c/../../d.lua", "a/d.lua"),
            Case("__jvm__/classes/java/lang/String.lua", "__jvm__/classes/java/lang/String.lua"),
            Case("__jvm__\\classes\\java\\lang\\String.lua", "__jvm__/classes/java/lang/String.lua"),
            Case("__jvm__/classes/android/view/View\$OnClickListener.lua", "__jvm__/classes/android/view/View\$OnClickListener.lua"),
            Case("__jvm__\\classes\\android\\view\\View\$OnClickListener.lua", "__jvm__/classes/android/view/View\$OnClickListener.lua"),
            Case("__lua_std__/5.3/math.lua", "__lua_std__/5.3/math.lua"),
            Case("__lua_std__\\5.3\\math.lua", "__lua_std__/5.3/math.lua"),
            Case("__lua_std__/androlua5.3/socket.url.lua", "__lua_std__/androlua5.3/socket.url.lua"),
            Case("__lua_std__\\androlua5.3\\socket.url.lua", "__lua_std__/androlua5.3/socket.url.lua"),
            Case("__lsp_uri__/x.lua", "__lsp_uri__/x.lua"),
            Case("__lsp_uri__\\x.lua", "__lsp_uri__/x.lua"),
            Case("__meta__/Mounted.lua", "__meta__/Mounted.lua"),
            Case("__meta__\\Mounted.lua", "__meta__/Mounted.lua")
        )

        for (case in corpus) {
            val actual = VirtualPath.of(case.input).value
            assertEquals(case.expected, actual, "input=${case.input}")
            assertFalse('\\' in actual, actual)
            assertFalse(actual.startsWith('/'), actual)
            // Round-trip: re-normalizing the expected form is identity.
            assertEquals(case.expected, VirtualPath.of(case.expected).value)
            // Separator flip of the expected form still yields the same value.
            assertEquals(case.expected, VirtualPath.of(case.expected.replace('/', '\\')).value)
        }
    }

    @Test
    fun data_class_equality_and_hash_stable_for_slash_variants() {
        val a = VirtualPath.of("pkg/mod.lua")
        val b = VirtualPath.of("pkg\\mod.lua")
        val c = VirtualPath.of("pkg//mod.lua")
        assertEquals(a, b)
        assertEquals(a, c)
        assertEquals(a.hashCode(), b.hashCode())
        assertEquals(a.hashCode(), c.hashCode())
        assertEquals(setOf(a), setOf(a, b, c))
    }

    // -------------------------------------------------------------------------
    // Helpers (mirror production provider path formulas; test-only)
    // -------------------------------------------------------------------------

    private fun jvmClassProviderPath(className: String): VirtualPath =
        VirtualPath.of("__jvm__/classes/${className.replace('.', '/')}.lua")

    private fun jvmPackageProviderPath(packageName: String): VirtualPath =
        VirtualPath.of("__jvm__/packages/${packageName.replace('.', '/')}.lua")

    private fun luaStdProviderPath(versionSegment: String, moduleName: String): VirtualPath =
        VirtualPath.of("__lua_std__/$versionSegment/$moduleName.lua")

    private fun isProviderVirtualPath(path: VirtualPath): Boolean {
        val value = path.value
        return value.startsWith("__jvm__/") ||
            value.startsWith("__lua_std__/") ||
            value.startsWith("__lsp_uri__/") ||
            value.startsWith("__meta__/")
    }

    /**
     * Host absolute shapes that must not appear as provider VirtualPath values:
     * Unix absolute, Windows drive, or UNC.
     */
    private fun looksLikeHostAbsolutePath(value: String): Boolean {
        if (value.startsWith('/')) return true
        if (value.startsWith("//") || value.startsWith("\\\\")) return true
        if (Regex("^[A-Za-z]:[/\\\\]").containsMatchIn(value)) return true
        // Normalized Windows absolute: C:/Users/...
        if (Regex("^[A-Za-z]:/").containsMatchIn(value)) return true
        return false
    }
}
