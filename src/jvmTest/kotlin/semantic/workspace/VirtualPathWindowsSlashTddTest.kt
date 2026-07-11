package semantic.workspace

import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TASK-295 corpus: VirtualPath Windows slash / drive-letter behavior.
 *
 * Acceptance:
 * - Backslash and mixed separators normalize stably.
 * - Drive-letter paths do not throw on non-Windows.
 *
 * Complements [VirtualPathNormalizationTddTest] (TASK-208) with a focused Windows
 * host-path matrix. Test-only; product code is out of scope. Verification is
 * review-owned (no Gradle here).
 *
 * Product contract encoded here (VirtualPath.of / normalize):
 * - `\` is rewritten to `/` before segment split; mixed and duplicate separators collapse.
 * - Empty / `.` segments drop; `..` pops or rejects workspace escape.
 * - Drive-letter forms (`C:\...`, `D:/...`) are treated as ordinary segment lists after
 *   separator normalization; the drive token (e.g. `C:`) is kept as the first segment.
 *   Construction must not throw merely because the host is not Windows.
 * - Because the drive token is an ordinary segment, a single leading `..` after it
 *   pops the drive and re-roots relatively (no throw). Workspace escape still requires
 *   popping past an empty segment stack.
 * - UNC-looking inputs (`\\server\share\...`) lose empty leading segments and become
 *   workspace-relative segment lists (no host absolute survival).
 */
class VirtualPathWindowsSlashTddTest {

    // -------------------------------------------------------------------------
    // Backslash and mixed separators normalize stably
    // -------------------------------------------------------------------------

    @Test
    fun pure_backslash_paths_normalize_to_forward_slash_values() {
        val cases = listOf(
            "main.lua" to "main.lua",
            "src\\main.lua" to "src/main.lua",
            "src\\lib\\util.lua" to "src/lib/util.lua",
            "deep\\nested\\pkg\\mod.lua" to "deep/nested/pkg/mod.lua",
            "a\\\\b\\\\c.lua" to "a/b/c.lua",
            "trailing\\slash\\" to "trailing/slash",
            "windows\\style\\path\\" to "windows/style/path"
        )

        for ((input, expected) in cases) {
            val path = VirtualPath.of(input)
            assertEquals(expected, path.value, "normalize($input)")
            assertFalse('\\' in path.value, "backslash must not survive: $input -> ${path.value}")
            assertEquals(path.value, path.toString())
        }
    }

    @Test
    fun mixed_separators_collapse_to_one_stable_identity() {
        val variants = listOf(
            "app/modules/core.lua",
            "app\\modules\\core.lua",
            "app/modules\\core.lua",
            "app\\modules/core.lua",
            "app//modules///core.lua",
            "app\\\\modules\\\\core.lua",
            "app\\/modules\\/core.lua",
            "app/\\modules/\\core.lua",
            "app\\modules//core.lua",
            "app//modules\\core.lua"
        )

        val normalized = variants.map { VirtualPath.of(it) }.distinct()
        assertEquals(1, normalized.size, "all mixed separator variants must collapse to one VirtualPath")
        assertEquals("app/modules/core.lua", normalized.single().value)

        // Equality + hash stability across the matrix.
        val first = normalized.single()
        for (variant in variants) {
            val other = VirtualPath.of(variant)
            assertEquals(first, other, variant)
            assertEquals(first.hashCode(), other.hashCode(), variant)
        }
        assertEquals(1, variants.map { VirtualPath.of(it) }.toSet().size)
    }

    @Test
    fun windows_style_dot_and_parent_segments_collapse_stably() {
        assertEquals("src/module.lua", VirtualPath.of("src\\.\\module.lua").value)
        assertEquals("src/module.lua", VirtualPath.of("src\\foo\\..\\module.lua").value)
        assertEquals("src/foo/module.lua", VirtualPath.of("src\\foo\\.\\bar\\..\\module.lua").value)
        assertEquals("b.lua", VirtualPath.of("a\\..\\b.lua").value)
        assertEquals("x/y.lua", VirtualPath.of("x\\z\\..\\y.lua").value)
        assertEquals("y.lua", VirtualPath.of("a\\b\\..\\..\\y.lua").value)

        // Mixed slash + backslash navigation must match pure-forward results.
        assertEquals(
            VirtualPath.of("src/foo/./bar/../module.lua"),
            VirtualPath.of("src\\foo\\.\\bar\\..\\module.lua")
        )
        assertEquals(
            VirtualPath.of("a/b/../c/./d.lua"),
            VirtualPath.of("a\\b\\..\\c\\.\\d.lua")
        )
        assertEquals(
            VirtualPath.of("pkg/mod.lua"),
            VirtualPath.of(".\\pkg\\.\\mod.lua")
        )
    }

    @Test
    fun resolve_accepts_windows_child_separators_and_renormalizes() {
        val base = VirtualPath.of("src\\pkg")
        assertEquals("src/pkg", base.value)

        assertEquals("src/pkg/child.lua", base.resolve("child.lua").value)
        assertEquals("src/pkg/nested/child.lua", base.resolve("nested\\child.lua").value)
        assertEquals("src/pkg/a/b.lua", base.resolve("a\\\\b.lua").value)
        assertEquals("src/pkg/child.lua", base.resolve(".\\child.lua").value)
        assertEquals("src/sibling.lua", base.resolve("..\\sibling.lua").value)
        assertEquals("outside.lua", base.resolve("..\\..\\outside.lua").value)

        // Mixed child separators.
        assertEquals("src/pkg/x/y.lua", base.resolve("x\\y.lua").value)
        assertEquals("src/pkg/x/y.lua", base.resolve("x/y.lua").value)
        assertEquals("src/pkg/x/y.lua", base.resolve("x\\/y.lua").value)

        assertFailsWith<IllegalArgumentException> {
            base.resolve("..\\..\\..\\escape.lua")
        }
    }

    @Test
    fun trailing_leading_and_duplicate_windows_separators_are_stable() {
        assertEquals(
            VirtualPath.of("lib/util.lua"),
            VirtualPath.of("lib\\util.lua\\")
        )
        assertEquals(
            VirtualPath.of("lib/util.lua"),
            VirtualPath.of("lib\\\\util.lua")
        )
        assertEquals(
            VirtualPath.of("lib/util.lua"),
            VirtualPath.of("lib\\//\\util.lua\\/")
        )
        // Leading pure backslash empties drop like leading `/`.
        assertEquals("absolute/path.lua", VirtualPath.of("\\absolute\\path.lua").value)
        assertEquals("absolute/path.lua", VirtualPath.of("\\\\absolute\\\\path.lua").value)
        assertFalse(VirtualPath.of("\\absolute\\path.lua").value.startsWith('/'))
        assertFalse('\\' in VirtualPath.of("\\absolute\\path.lua").value)
    }

    @Test
    fun windows_separator_matrix_corpus_all_collapse_to_expected() {
        data class Case(val input: String, val expected: String)

        val corpus = listOf(
            Case("a.lua", "a.lua"),
            Case("a\\b.lua", "a/b.lua"),
            Case("a\\b\\c.lua", "a/b/c.lua"),
            Case("a/b\\c.lua", "a/b/c.lua"),
            Case("a\\b/c.lua", "a/b/c.lua"),
            Case("a\\\\b\\\\c.lua", "a/b/c.lua"),
            Case("a\\//b\\//c.lua", "a/b/c.lua"),
            Case(".\\a.lua", "a.lua"),
            Case("a\\.\\b.lua", "a/b.lua"),
            Case("a\\b\\..\\c.lua", "a/c.lua"),
            Case("a\\b\\.\\..\\c\\.\\d.lua", "a/c/d.lua"),
            Case("feature\\profile.lua", "feature/profile.lua"),
            Case("lib\\net\\http\\client.lua", "lib/net/http/client.lua"),
            Case("__jvm__\\classes\\java\\lang\\String.lua", "__jvm__/classes/java/lang/String.lua"),
            Case("__jvm__/classes\\android\\view\\View\$OnClickListener.lua",
                "__jvm__/classes/android/view/View\$OnClickListener.lua"),
            Case("__lua_std__\\5.3\\math.lua", "__lua_std__/5.3/math.lua"),
            Case("__lua_std__/androlua5.3\\socket.url.lua", "__lua_std__/androlua5.3/socket.url.lua"),
            Case("__lsp_uri__\\opaque.lua", "__lsp_uri__/opaque.lua"),
            Case("__meta__\\Mounted.lua", "__meta__/Mounted.lua"),
            Case("Windows\\Path\\With Spaces\\file.lua", "Windows/Path/With Spaces/file.lua"),
            Case("mixed\\sep/and\\dots\\.\\file.lua", "mixed/sep/and/dots/file.lua")
        )

        for (case in corpus) {
            val actual = VirtualPath.of(case.input).value
            assertEquals(case.expected, actual, "input=${case.input}")
            assertFalse('\\' in actual, actual)
            assertFalse(actual.startsWith('/'), actual)
            // Round-trip + separator flip stay identity.
            assertEquals(case.expected, VirtualPath.of(case.expected).value)
            assertEquals(case.expected, VirtualPath.of(case.expected.replace('/', '\\')).value)
        }
    }

    // -------------------------------------------------------------------------
    // Drive-letter paths do not throw on non-Windows
    // -------------------------------------------------------------------------

    @Test
    fun drive_letter_backslash_paths_do_not_throw() {
        // Host may be macOS/Linux; drive-letter inputs must still construct.
        val cases = listOf(
            "C:\\Users\\dingyi\\app\\main.lua",
            "c:\\projects\\lua\\mod.lua",
            "D:\\workspace\\src\\lib.lua",
            "Z:\\only\\drive\\root.lua",
            "E:\\\\double\\\\sep.lua"
        )

        for (input in cases) {
            val path = runCatching { VirtualPath.of(input) }.getOrElse { error ->
                throw AssertionError("drive-letter input must not throw on non-Windows: $input", error)
            }
            assertNotNull(path)
            assertTrue(path.value.isNotEmpty(), input)
            assertFalse('\\' in path.value, "normalized must drop backslashes: ${path.value}")
            // Drive token survives as the first segment (e.g. C:/Users/... -> C:/Users/...).
            assertTrue(
                Regex("^[A-Za-z]:/").containsMatchIn(path.value) ||
                    path.value.first().isLetter(),
                "drive-shaped input should retain drive segment: ${path.value}"
            )
        }
    }

    @Test
    fun drive_letter_forward_slash_paths_do_not_throw() {
        val cases = listOf(
            "C:/Users/dingyi/app/main.lua",
            "c:/projects/lua/mod.lua",
            "D:/workspace/src/lib.lua",
            "E://double//sep.lua"
        )

        for (input in cases) {
            val path = runCatching { VirtualPath.of(input) }.getOrElse { error ->
                throw AssertionError("drive-letter forward-slash must not throw: $input", error)
            }
            assertFalse('\\' in path.value, path.value)
            assertFalse(path.value.startsWith('/'), path.value)
        }
    }

    @Test
    fun drive_letter_mixed_separators_normalize_stably_without_throw() {
        val variants = listOf(
            "C:\\Users\\dingyi\\app\\main.lua",
            "C:/Users/dingyi/app/main.lua",
            "C:\\Users/dingyi/app\\main.lua",
            "C:/Users\\dingyi\\app/main.lua",
            "C:\\\\Users//dingyi\\\\app//main.lua"
        )

        val paths = variants.map { input ->
            runCatching { VirtualPath.of(input) }.getOrElse { error ->
                throw AssertionError("mixed drive-letter variant must not throw: $input", error)
            }
        }

        val distinct = paths.distinct()
        assertEquals(1, distinct.size, "drive-letter slash variants must collapse to one value")
        assertEquals("C:/Users/dingyi/app/main.lua", distinct.single().value)
        assertFalse('\\' in distinct.single().value)
    }

    @Test
    fun drive_letter_with_dot_segments_does_not_throw_and_collapses() {
        val path = runCatching {
            VirtualPath.of("C:\\Users\\dingyi\\.\\projects\\..\\projects\\main.lua")
        }.getOrElse { error ->
            throw AssertionError("drive-letter with dots must not throw", error)
        }
        assertEquals("C:/Users/dingyi/projects/main.lua", path.value)

        val mixed = runCatching {
            VirtualPath.of("D:/work\\tmp\\..\\src\\./mod.lua")
        }.getOrElse { error ->
            throw AssertionError("mixed drive-letter dots must not throw", error)
        }
        assertEquals("D:/work/src/mod.lua", mixed.value)
    }

    @Test
    fun bare_drive_root_forms_do_not_throw_when_non_empty_after_normalize() {
        // "C:" alone has one non-empty segment after separator rewrite.
        val bare = runCatching { VirtualPath.of("C:") }.getOrElse { error ->
            throw AssertionError("bare drive letter must not throw: C:", error)
        }
        assertEquals("C:", bare.value)

        val rootSlash = runCatching { VirtualPath.of("C:\\") }.getOrElse { error ->
            throw AssertionError("drive root backslash must not throw: C:\\", error)
        }
        // Trailing separator drops; remaining segment is the drive token.
        assertEquals("C:", rootSlash.value)

        val rootForward = runCatching { VirtualPath.of("C:/") }.getOrElse { error ->
            throw AssertionError("drive root forward slash must not throw: C:/", error)
        }
        assertEquals("C:", rootForward.value)
    }

    @Test
    fun drive_letter_paths_are_not_provider_virtual_paths() {
        val windowsStyle = VirtualPath.of("C:\\Users\\dingyi\\app\\main.lua")
        assertEquals("C:/Users/dingyi/app/main.lua", windowsStyle.value)
        assertFalse(isProviderVirtualPath(windowsStyle), windowsStyle.value)
        assertTrue(
            looksLikeHostAbsolutePath(windowsStyle.value) || windowsStyle.value.startsWith("C:"),
            windowsStyle.value
        )

        // Even if nested under a fake prefix string, raw drive material is host-shaped
        // only when it is the first segment; concatenated drive mid-path is just text.
        val midDrive = VirtualPath.of("workspace\\C:\\not-a-real-drive\\mod.lua")
        assertEquals("workspace/C:/not-a-real-drive/mod.lua", midDrive.value)
        assertFalse(isProviderVirtualPath(midDrive), midDrive.value)
    }

    @Test
    fun unc_style_windows_paths_do_not_throw_and_re_root_relative() {
        // \\server\share\mod.lua -> after \->/ and empty-segment drop: server/share/mod.lua
        val unc = runCatching {
            VirtualPath.of("\\\\server\\share\\mod.lua")
        }.getOrElse { error ->
            throw AssertionError("UNC-style path must not throw on non-Windows", error)
        }
        assertEquals("server/share/mod.lua", unc.value)
        assertFalse(unc.value.startsWith('/'), unc.value)
        assertFalse(unc.value.startsWith("\\\\"), unc.value)
        assertFalse(isProviderVirtualPath(unc), unc.value)

        val uncForward = VirtualPath.of("//server/share/mod.lua")
        assertEquals(unc, uncForward)
        assertEquals("server/share/mod.lua", uncForward.value)
    }

    @Test
    fun file_uri_and_windows_file_scheme_do_not_throw_when_segmented() {
        // Not a supported provider construction path; must still not crash on of().
        val fileUri = runCatching {
            VirtualPath.of("file:///C:/Users/dingyi/app/main.lua")
        }.getOrElse { error ->
            throw AssertionError("file URI input must not throw", error)
        }
        assertFalse(isProviderVirtualPath(fileUri), fileUri.value)
        assertFalse(fileUri.value.startsWith("__jvm__/"), fileUri.value)
        assertFalse('\\' in fileUri.value)

        val fileUriBackslash = runCatching {
            VirtualPath.of("file:\\\\C:\\Users\\dingyi\\app\\main.lua")
        }.getOrElse { error ->
            throw AssertionError("file URI backslash form must not throw", error)
        }
        assertFalse(isProviderVirtualPath(fileUriBackslash), fileUriBackslash.value)
    }

    @Test
    fun drive_letter_parent_escape_still_enforced_from_drive_root() {
        // Drive letter is an ordinary first segment after separator rewrite (product contract).
        // From C:/a, one `..` lands on C:/x; one `..` from C: itself pops the drive token
        // and re-roots relatively (no throw). Workspace escape only throws when `..` would
        // pop an empty segment stack.
        val underDrive = VirtualPath.of("C:\\a\\b.lua")
        assertEquals("C:/a/b.lua", underDrive.value)

        assertEquals("C:/x.lua", VirtualPath.of("C:\\a\\..\\x.lua").value)
        assertEquals("C:/x.lua", VirtualPath.of("C:/a/../x.lua").value)

        // Popping the drive token is allowed — same as popping any other segment.
        assertEquals("outside.lua", VirtualPath.of("C:\\..\\outside.lua").value)
        assertEquals("outside.lua", VirtualPath.of("C:/../outside.lua").value)
        assertEquals("outside.lua", VirtualPath.of("C:\\a\\..\\..\\outside.lua").value)
        assertEquals("outside.lua", VirtualPath.of("C:/a/../../outside.lua").value)

        // True workspace escape still rejected (one more `..` after drive is already gone).
        assertFailsWith<IllegalArgumentException> {
            VirtualPath.of("C:\\..\\..\\outside.lua")
        }
        assertFailsWith<IllegalArgumentException> {
            VirtualPath.of("C:/../../outside.lua")
        }
        assertFailsWith<IllegalArgumentException> {
            VirtualPath.of("C:\\a\\..\\..\\..\\outside.lua")
        }
    }

    @Test
    fun provider_paths_with_windows_separators_stay_virtual_not_drive_shaped() {
        val providers = listOf(
            VirtualPath.of("__jvm__\\classes\\java\\io\\File.lua"),
            VirtualPath.of("__jvm__/packages\\android\\app.lua"),
            VirtualPath.of("__lua_std__\\5.3\\table.lua"),
            VirtualPath.of("__lsp_uri__\\opaque-token.lua"),
            VirtualPath.of("__meta__\\Mounted.lua")
        )

        for (path in providers) {
            assertTrue(isProviderVirtualPath(path), path.value)
            assertFalse(looksLikeHostAbsolutePath(path.value), path.value)
            assertFalse(Regex("^[A-Za-z]:").containsMatchIn(path.value), path.value)
            assertFalse('\\' in path.value, path.value)
            // Separator flip of the normalized value is identity.
            assertEquals(path, VirtualPath.of(path.value.replace('/', '\\')))
        }
    }

    @Test
    fun windows_slash_stability_round_trip_corpus() {
        data class Case(val input: String, val expected: String)

        val corpus = listOf(
            Case("src\\main.lua", "src/main.lua"),
            Case("C:\\Users\\x\\y.lua", "C:/Users/x/y.lua"),
            Case("c:/Users/x/y.lua", "c:/Users/x/y.lua"),
            Case("D:\\a\\b\\..\\c.lua", "D:/a/c.lua"),
            Case("\\\\host\\share\\f.lua", "host/share/f.lua"),
            Case("app\\..\\app\\mod.lua", "app/mod.lua"),
            Case("__jvm__\\classes\\com\\androlua\\LuaActivity.lua",
                "__jvm__/classes/com/androlua/LuaActivity.lua"),
            // Drive token is ordinary: one `..` pops it and re-roots relatively.
            Case("C:\\..\\outside.lua", "outside.lua"),
            Case("C:/../outside.lua", "outside.lua"),
            Case("C:\\a\\..\\..\\outside.lua", "outside.lua")
        )

        for (case in corpus) {
            val first = VirtualPath.of(case.input)
            assertEquals(case.expected, first.value, case.input)
            // Re-normalize expected and backslash form of expected.
            assertEquals(case.expected, VirtualPath.of(case.expected).value)
            assertEquals(case.expected, VirtualPath.of(case.expected.replace('/', '\\')).value)
            // Data-class equality for input vs expected.
            assertEquals(VirtualPath.of(case.expected), first)
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun isProviderVirtualPath(path: VirtualPath): Boolean {
        val value = path.value
        return value.startsWith("__jvm__/") ||
            value.startsWith("__lua_std__/") ||
            value.startsWith("__lsp_uri__/") ||
            value.startsWith("__meta__/")
    }

    /**
     * Host absolute shapes that may appear when raw Windows/Unix paths are fed in:
     * Unix absolute, Windows drive, or UNC.
     *
     * After normalize, leading `/` empty segments are dropped, so a constructed
     * VirtualPath.value never starts with `/` or `//`. Drive-letter forms (C:/...)
     * can still appear if naively fed in.
     */
    private fun looksLikeHostAbsolutePath(value: String): Boolean {
        if (value.startsWith('/')) return true
        if (value.startsWith("//") || value.startsWith("\\\\")) return true
        if (Regex("^[A-Za-z]:[/\\\\]").containsMatchIn(value)) return true
        if (Regex("^[A-Za-z]:/").containsMatchIn(value)) return true
        return false
    }
}
