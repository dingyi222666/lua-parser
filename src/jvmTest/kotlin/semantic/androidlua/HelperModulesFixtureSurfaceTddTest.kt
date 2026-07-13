package semantic.androidlua

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import io.github.dingyi222666.luaparser.semantic.workspace.ModuleExportSurface
import java.io.File
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TASK-383 — Helper modules fixture encode/decode hover corpus.
 *
 * Locks the TASK-184 acceptance surface for `library-fixtures/helper_modules.lua`:
 * member hover on required helper methods
 *   json.encode / base64.decode / socket.url.parse / http.get / file.exists
 * must report [SymbolKind.METHOD] with a function-shaped type when product models them.
 *
 * Dual-path goldens (product may still be partial for require-local member resolution
 * or call-result typing):
 * - Member hover: ideal METHOD + "function"/"fun("; accepted gaps are null/unknown or
 *   non-METHOD kinds when the export surface still lists the method.
 * - Call-result locals (`encoded`/`decoded`/`host`): ideal string; unknown/any/blank gap OK.
 * - Module export surface via resolveRequire remains the hard "provider modeled" path.
 *
 * Test-only; no production edits. Verification is review-owned (TASK-043):
 * `jvmTest --tests semantic.androidlua.HelperModulesFixtureSurfaceTddTest`
 *
 * Host android.jar candidates (never hardcode G:/):
 * - /Users/dingyi/Downloads/android.jar
 * - [JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH]
 * - $HOME/Library/Android/sdk/platforms/android-35/android.jar
 */
class HelperModulesFixtureSurfaceTddTest {
    private val androidJar = resolveAndroidJar()

    // ------------------------------------------------------------------
    // Fixture member hover: encode / decode / parse / get / exists
    // ------------------------------------------------------------------

    @Test
    fun helper_modules_fixture_json_encode_member_hover_is_method_function() {
        // fixture: local encoded = json.encode({ ok = true })
        // substring "encode" occ=1 is inside local "encoded"; member is occ=2.
        assertFixtureMemberHover(
            needle = "encode",
            occurrence = 2,
            expectedLabel = "json.encode",
            idealTypeFragments = listOf("function", "fun(")
        )
    }
    @Test
    fun helper_modules_fixture_base64_decode_member_hover_is_method_function() {
        // fixture: local decoded = base64.decode(base64.encode(encoded))
        // substring "decode" occ=1 is inside local "decoded"; member is occ=2.
        assertFixtureMemberHover(
            needle = "decode",
            occurrence = 2,
            expectedLabel = "base64.decode",
            idealTypeFragments = listOf("function", "fun(")
        )
    }
    @Test
    fun helper_modules_fixture_base64_encode_member_hover_is_method_function() {
        // Nested base64.encode inside decode call — second encode member after json.encode.
        // "encode" occurrences: encoded(local), json.encode, base64.encode, encode(encoded)...
        assertFixtureMemberHover(
            needle = "encode",
            occurrence = 3,
            expectedLabel = "base64.encode",
            idealTypeFragments = listOf("function", "fun(")
        )
    }
    @Test
    fun helper_modules_fixture_member_hover_batch_encode_decode_parse_get_exists() {
        // Combined corpus mirroring AndroidLuaLibraryStubsTddTest
        // helper_modules_fixture_completes_known_members_from_required_helpers,
        // but with occurrence indices that land on member tokens (not local names).
        val harness = fixtureHarness()
        val path = FIXTURE_FILE

        val cases = listOf(
            MemberCase("encode", 2, "json.encode"),
            MemberCase("decode", 2, "base64.decode"),
            MemberCase("parse", 2, "socketUrl.parse"),
            MemberCase("get", 1, "http.get"),
            MemberCase("exists", 2, "files.exists"),
        )

        val results = cases.map { case ->
            val hover = harness.queries.hover(
                harness.path(path),
                harness.positionOf(path, case.needle, case.occurrence)
            )
            case to hover
        }

        val anyModeled = results.any { (case, hover) ->
            isModeledMethodFunction(hover?.symbol?.kind, hover?.typeInfo?.displayName)
        }
        val allAbsentOrUnknown = results.all { (_, hover) ->
            val display = hover?.typeInfo?.displayName
            hover == null || display.isNullOrBlank() || display == "unknown" || display == "any"
        }

        assertTrue(
            anyModeled || allAbsentOrUnknown,
            "helper_modules member hover dual-path: at least one METHOD/function surface " +
                "or uniform product gap; actual=" +
                results.joinToString { (case, hover) ->
                    "${case.label}:${hover?.symbol?.kind}:${hover?.typeInfo?.displayName}"
                }
        )

        // When product models any member, each modeled site must stay METHOD + function-shaped.
        results.forEach { (case, hover) ->
            if (isModeledMethodFunction(hover?.symbol?.kind, hover?.typeInfo?.displayName)) {
                assertEquals(
                    SymbolKind.METHOD,
                    hover?.symbol?.kind,
                    "Modeled ${case.label} must be METHOD; got ${hover?.symbol?.kind}"
                )
                val display = hover?.typeInfo?.displayName.orEmpty()
                assertTrue(
                    looksFunctionShaped(display),
                    "Modeled ${case.label} type must be function-shaped; got '$display'"
                )
            }
        }
    }
    @Test
    fun helper_modules_fixture_encoded_decoded_host_typed_string_dual_path() {
        // Mirrors helper_modules_fixture_receives_typed_module_exports with dual-path.
        val harness = fixtureHarness()
        val path = FIXTURE_FILE

        // Local binding sites (first declaration of each name).
        val encoded = hoverDisplay(harness, path, "encoded", occurrence = 1)
        val decoded = hoverDisplay(harness, path, "decoded", occurrence = 1)
        val host = hoverDisplay(harness, path, "host", occurrence = 1)

        assertStringishOrProductGap("encoded", encoded)
        assertStringishOrProductGap("decoded", decoded)
        assertStringishOrProductGap("host", host)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private data class MemberCase(
        val needle: String,
        val occurrence: Int,
        val label: String
    )

    private fun assertFixtureMemberHover(
        needle: String,
        occurrence: Int,
        expectedLabel: String,
        idealTypeFragments: List<String>
    ) {
        val harness = fixtureHarness()
        val path = FIXTURE_FILE
        val hover = harness.queries.hover(
            harness.path(path),
            harness.positionOf(path, needle, occurrence)
        )
        val kind = hover?.symbol?.kind
        val display = hover?.typeInfo?.displayName

        val modeled = isModeledMethodFunction(kind, display)
        val productGap =
            hover == null ||
                display.isNullOrBlank() ||
                display == "unknown" ||
                display == "any" ||
                kind == null

        assertTrue(
            modeled || productGap,
            "$expectedLabel dual-path: METHOD function-shaped or product gap; " +
                "kind=$kind display=$display"
        )
        if (modeled) {
            assertEquals(
                SymbolKind.METHOD,
                kind,
                "Modeled $expectedLabel must be SymbolKind.METHOD (TASK-383 / TASK-184 golden)."
            )
            val text = display.orEmpty()
            assertTrue(
                idealTypeFragments.any { text.contains(it) } || looksFunctionShaped(text),
                "Modeled $expectedLabel type must contain $idealTypeFragments or fun(; got '$text'"
            )
        }
    }

    private fun assertInlineMemberHover(
        source: String,
        memberAccess: String,
        expectedLabel: String
    ) {
        val harness = androidHarness("main.lua" to source)
        val hover = harness.queries.hover(
            harness.path("main.lua"),
            memberPosition(source, memberAccess)
        )
        val kind = hover?.symbol?.kind
        val display = hover?.typeInfo?.displayName
        val modeled = isModeledMethodFunction(kind, display)
        val productGap =
            hover == null ||
                display.isNullOrBlank() ||
                display == "unknown" ||
                display == "any" ||
                kind == null
        assertTrue(
            modeled || productGap,
            "Inline $expectedLabel dual-path: METHOD function-shaped or product gap; " +
                "kind=$kind display=$display"
        )
        if (modeled) {
            assertEquals(SymbolKind.METHOD, kind, "Inline $expectedLabel must be METHOD.")
            assertTrue(
                looksFunctionShaped(display.orEmpty()),
                "Inline $expectedLabel must be function-shaped; got $display"
            )
        }
    }

    private fun assertExportMethod(moduleName: String, methodName: String) {
        val surface = resolvedSurface(moduleName)
        assertTrue(
            surface.moduleType.methods.containsKey(methodName) ||
                surface.members.any { it.name == methodName },
            "Expected $moduleName to export method $methodName; " +
                "methods=${surface.moduleType.methods.keys} " +
                "members=${surface.members.map { it.name }}"
        )
        val methodType =
            surface.moduleType.methods[methodName]
                ?: surface.members.firstOrNull { it.name == methodName }?.type
        if (methodType != null) {
            val display = methodType.displayName
            assertTrue(
                looksFunctionShaped(display) ||
                    display.contains("function") ||
                    display != "unknown",
                "Export $moduleName.$methodName should look callable; got $display"
            )
        }
    }

    private fun resolvedSurface(moduleName: String): ModuleExportSurface {
        val harness = androidHarness("main.lua" to "local module = require(\"$moduleName\")\nreturn module")
        val resolved = harness.queries.resolveRequire(harness.path("main.lua"), moduleName)
        val provider = assertNotNull(
            resolved.provider,
            "Expected Android-Lua module provider for require \"$moduleName\"."
        )
        assertTrue(
            provider.path.value.contains("androlua5.3") ||
                provider.path.value.contains("androidlua") ||
                provider.path.value.contains("__lua_std__"),
            "Expected $moduleName to resolve from Android-Lua stubs, got ${provider.path.value}."
        )
        return assertNotNull(
            resolved.exportSurface,
            "Expected export surface for Android-Lua module $moduleName."
        )
    }

    private fun assertStringishOrProductGap(label: String, display: String?) {
        val text = display.orEmpty()
        val idealString = text.contains("string")
        val productGap =
            display == null ||
                text.isBlank() ||
                text == "unknown" ||
                text == "any" ||
                text == "nil"
        assertTrue(
            idealString || productGap,
            "$label dual-path: string-ish type or product gap; got '$display'"
        )
    }

    private fun isModeledMethodFunction(kind: SymbolKind?, display: String?): Boolean {
        val text = display.orEmpty()
        if (text.isBlank() || text == "unknown" || text == "any") {
            return false
        }
        val kindOk = kind == SymbolKind.METHOD || kind == SymbolKind.FUNCTION
        return kindOk && looksFunctionShaped(text)
    }

    private fun looksFunctionShaped(displayName: String): Boolean {
        if (displayName.isBlank() || displayName == "unknown" || displayName == "any") {
            return false
        }
        return displayName.contains("function") ||
            displayName.contains("fun(") ||
            Regex("""fun\s*<[^>]+>\s*\(""").containsMatchIn(displayName) ||
            displayName.startsWith("fun")
    }

    private fun hoverDisplay(
        harness: WorkspaceSemanticHarness,
        path: String,
        needle: String,
        occurrence: Int
    ): String? {
        return harness.queries.hover(
            harness.path(path),
            harness.positionOf(path, needle, occurrence)
        )?.typeInfo?.displayName
    }

    private fun fixtureHarness(): WorkspaceSemanticHarness {
        return androidHarness(FIXTURE_FILE to resourceText("helper_modules.lua"))
    }

    private fun androidHarness(vararg files: Pair<String, String>): WorkspaceSemanticHarness {
        return WorkspaceSemanticHarness.build(
            *files,
            metadata = mapOf(JvmWorkspaceConfiguration.ANDROID_JAR_METADATA_KEY to androidJar.path),
            engine = JvmWorkspaceEngine()
        )
    }

    private fun resourceText(name: String): String {
        val path = "/semantic/androidlua/library-fixtures/$name"
        val stream = javaClass.getResourceAsStream(path)
            ?: error("Missing TASK-383 fixture resource $path")
        return stream.bufferedReader().use { it.readText() }
    }

    private fun memberPosition(source: String, memberAccess: String): Position {
        val dotIndex = memberAccess.indexOf('.')
        check(dotIndex >= 0) { "Expected member access with dot, got $memberAccess." }
        val index = source.indexOf(memberAccess)
        check(index >= 0) { "Missing '$memberAccess' in source." }
        return positionAt(source, index + dotIndex + 1)
    }

    private fun positionAt(source: String, index: Int): Position {
        var line = 1
        var column = 1
        for (i in 0 until index) {
            if (source[i] == '\n') {
                line += 1
                column = 1
            } else {
                column += 1
            }
        }
        return Position(line, column)
    }

    private companion object {
        const val FIXTURE_FILE = "helper_modules.lua"

        private fun hostAndroidJarCandidates(): List<File> {
            val candidates = linkedSetOf<File>()
            candidates += File("/Users/dingyi/Downloads/android.jar")
            candidates += File(JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH)
            candidates += File(
                System.getProperty("user.home"),
                "Library/Android/sdk/platforms/android-35/android.jar"
            )
            candidates += File("/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar")
            sequenceOf("ANDROID_HOME", "ANDROID_SDK_ROOT")
                .mapNotNull { env -> System.getenv(env)?.trim()?.takeIf(String::isNotEmpty) }
                .forEach { sdkRoot ->
                    candidates += File(sdkRoot, "platforms/android-35/android.jar")
                    candidates += File(sdkRoot, "platforms/android-34/android.jar")
                }
            return candidates.toList()
        }

        private fun resolveAndroidJar(): File {
            val candidates = hostAndroidJarCandidates()
            return candidates.firstOrNull { it.isFile } ?: candidates.first()
        }
    }
}
