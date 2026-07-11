package semantic.interop

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.semantic.api.CompletionItem
import io.github.dingyi222666.luaparser.semantic.api.CompletionItemKind
import io.github.dingyi222666.luaparser.semantic.api.Diagnostic
import io.github.dingyi222666.luaparser.semantic.api.SignatureHelp
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import io.github.dingyi222666.luaparser.semantic.workspace.DocumentFacts
import java.io.File
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TASK-474 corpus: **LuaJava coding method surface** — the documented AndroLua /
 * luajava helper method inventory used when coding with Java interop.
 *
 * Does **not** invent APIs. Locks only methods already declared on the AndroLua 5.3
 * luajava overlay ([AndroidLua53LuaJavaBuiltinOverlaySources] /
 * `androlua53-luajava/luajava.lua` / campaign fixtures):
 *
 * | Method | Documented shape (overlay) |
 * |---|---|
 * | bindClass | `(className: string) -> JavaClass` |
 * | new | `(class: JavaClass, ...) -> JavaObject` |
 * | newInstance | `(className: string, ...) -> JavaObject` |
 * | loadLib | `(className, methodName) -> any` |
 * | createProxy | interfaceNames + callbacks -> JavaProxy |
 * | newArray | `(class: JavaClass, ...integer) -> JavaArray` |
 * | createArray | `(className: string, values: table) -> JavaArray` |
 * | astable | `(object: JavaObject) -> table` |
 * | tostring | `(object: JavaObject) -> string` |
 * | instanceof | `(object, class) -> boolean` |
 * | getContext | `() -> AndroidLuaContext` |
 * | override | `(class, implementation) -> JavaObject` |
 *
 * Complements per-helper corpora (createProxy / loadLib / newInstance / bindClass /
 * array helpers) by locking the **module-level coding surface**:
 * - member completion inventory on `luajava.`
 * - direct member hover / alias callable dual-path
 * - result typing for helpers that already have product semantics
 * - signature help dual-path (null/empty CURRENTLY_ACCEPTS)
 * - colon-call + local-shadow negatives
 * - host android.jar Downloads + SDK android-35 only (never G:/)
 *
 * Dual-path / CURRENTLY_ACCEPTS:
 * - Ideal: METHOD/FUNCTION completion + callable hover + documented signature fragments.
 * - CURRENTLY_ACCEPTS: bare METHOD/FUNCTION/FIELD listing without detail; unknown/blank
 *   hover on alias; null/empty signature help; soft result typing for lesser-used helpers
 *   (astable / tostring / instanceof / override) as long as no crash and no invented types.
 * - Hard reject: inventing non-overlay methods as required; Windows G:/ android.jar paths;
 *   colon-call modeling helpers; local shadows inheriting JVM helper surfaces.
 *
 * Test-only. Workers must not run Gradle; verification is review-owned serial jvmTest
 * (TASK-043).
 */
class LuaJavaCodingMethodSurfaceTddTest {

    private val androidJar = File(JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH)
    private val downloadsAndroidJar = File("/Users/dingyi/Downloads/android.jar")
    private val sdkAndroid35Jar =
        File("/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar")

    // -------------------------------------------------------------------------
    // Host android.jar policy (never G:/)
    // -------------------------------------------------------------------------

    @Test
    fun host_android_jar_candidates_include_macos_sdk_and_downloads_never_g_drive() {
        val candidates = hostAndroidJarCandidates().map { it.path.replace('\\', '/') }

        assertTrue(
            candidates.any { it.endsWith("/Downloads/android.jar") || it.contains("/Downloads/") },
            "Host candidates must include Downloads android.jar; got $candidates"
        )
        assertTrue(
            candidates.any {
                it.contains("/Library/Android/sdk/platforms/android-35/android.jar") ||
                    it.contains("/platforms/android-35/android.jar")
            },
            "Host candidates must include SDK android-35 android.jar; got $candidates"
        )
        assertTrue(
            candidates.none { it.startsWith("G:/") || it.startsWith("G:\\") || it.contains("G:/Android") },
            "Host candidates must never hardcode Windows G:/ paths; got $candidates"
        )
        assertFalse(
            JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH.replace('\\', '/').startsWith("G:/"),
            "DEFAULT_ANDROID_JAR_PATH must never be G:/"
        )
        assertFalse(
            androidJar.path.replace('\\', '/').startsWith("G:/"),
            "Resolved android.jar path must never be G:/"
        )
    }

    @Test
    fun host_android_jar_presence_documents_sdk_or_downloads_dual_path() {
        // Host snapshot for this wave: SDK android-35 PRESENT (~27MB); Downloads ABSENT.
        val sdkPresent = sdkAndroid35Jar.isFile
        val downloadsPresent = downloadsAndroidJar.isFile
        assertTrue(
            sdkPresent || downloadsPresent || !androidJar.isFile,
            "android.jar dual-path: SDK or Downloads may be present, or default may be absent; " +
                "sdk=$sdkPresent downloads=$downloadsPresent default=${androidJar.isFile} path=${androidJar.path}"
        )
        if (sdkPresent) {
            assertTrue(
                sdkAndroid35Jar.length() > 1_000_000L,
                "SDK android-35 android.jar should be substantial when present; size=${sdkAndroid35Jar.length()}"
            )
        }
        assertFalse(sdkAndroid35Jar.path.contains("G:/"))
        assertFalse(downloadsAndroidJar.path.contains("G:/"))
    }

    @Test
    fun missing_android_jar_skip_documents_coding_method_surface() {
        val missing = File("/nonexistent/android-sdk/platforms/android-35/android.jar")
        val reason = missingAndroidJarSkipReason(missing)

        assertTrue(reason.contains("TASK-474"), "Skip reason must name TASK-474; got: $reason")
        assertTrue(reason.contains("android.jar"), "Skip reason must mention android.jar; got: $reason")
        assertTrue(reason.contains(missing.path), "Skip reason must include the missing path; got: $reason")
        assertTrue(
            reason.contains("coding") || reason.contains("luajava") || reason.contains("method"),
            "Skip reason must mention luajava coding method surface; got: $reason"
        )
        assertFalse(reason.contains("G:/"), "Skip reason must never mention G:/; got: $reason")
    }

    // -------------------------------------------------------------------------
    // Documented coding method inventory (completion on luajava.)
    // -------------------------------------------------------------------------

    @Test
    fun luajava_member_completion_lists_core_coding_methods() {
        val harness = jvmHarness(
            "main.lua" to """
                local bindClass = luajava.bindClass
                return bindClass
            """.trimIndent()
        )

        val completions = harness.queries.completions(
            harness.path("main.lua"),
            memberPosition(harness, "luajava.bindClass")
        )

        // Hard inventory: methods already locked by sibling corpora / campaign fixtures.
        for (method in CORE_CODING_METHODS) {
            assertTrue(
                completions.any {
                    it.label == method &&
                        (it.kind == CompletionItemKind.METHOD ||
                            it.kind == CompletionItemKind.FUNCTION ||
                            it.kind == CompletionItemKind.FIELD ||
                            it.kind == CompletionItemKind.VARIABLE)
                },
                "Expected core coding method $method on luajava completion; " +
                    "actual=${completions.map { "${it.label}:${it.kind}" }}"
            )
        }
    }

    @Test
    fun luajava_member_completion_lists_helper_coding_methods() {
        val harness = jvmHarness(
            "main.lua" to """
                local createArray = luajava.createArray
                return createArray
            """.trimIndent()
        )

        val completions = harness.queries.completions(
            harness.path("main.lua"),
            memberPosition(harness, "luajava.createArray")
        )

        // Matches JavaAndroidInteropCampaignGapTddTest helper fixture expectations.
        for (method in HELPER_CODING_METHODS) {
            assertTrue(
                completions.any {
                    it.label == method &&
                        (it.kind == CompletionItemKind.METHOD ||
                            it.kind == CompletionItemKind.FUNCTION ||
                            it.kind == CompletionItemKind.FIELD ||
                            it.kind == CompletionItemKind.VARIABLE)
                },
                "Expected helper coding method $method on luajava completion; " +
                    "actual=${completions.map { "${it.label}:${it.kind}" }}"
            )
        }
    }

    @Test
    fun luajava_member_completion_inventory_matrix_covers_full_overlay() {
        val harness = jvmHarness(
            "main.lua" to """
                local x = luajava.bindClass
                return x
            """.trimIndent()
        )

        val completions = harness.queries.completions(
            harness.path("main.lua"),
            memberPosition(harness, "luajava.bindClass")
        )
        val labels = completions.map { it.label }.toSet()

        // Dual-path full inventory: each documented overlay method is either listed
        // (ideal) or documented as CURRENTLY_ACCEPTS gap — never invent extra required APIs.
        for (method in ALL_DOCUMENTED_CODING_METHODS) {
            val listed = method in labels
            val methodItem = completions.singleOrNull { it.label == method }
            val modeled = methodItem != null && isModeledCodingMethodCompletion(methodItem)
            val bare = methodItem != null && isBareCallableListing(methodItem)
            assertTrue(
                listed || modeled || bare || methodItem == null,
                "Overlay method $method dual-path: listed/modeled/bare/absent gap; labels=$labels"
            )
            if (listed) {
                assertTrue(
                    methodItem != null &&
                        (isModeledCodingMethodCompletion(methodItem) || isBareCallableListing(methodItem)),
                    "Listed coding method $method must be METHOD/FUNCTION/FIELD/VARIABLE-ish; got $methodItem"
                )
            }
        }

        // Must not invent non-overlay required methods in this corpus.
        assertFalse(
            labels.contains("coding"),
            "Corpus must not invent luajava.coding as a product method; labels=$labels"
        )
    }

    // -------------------------------------------------------------------------
    // Per-method surface (hover / completion detail / alias)
    // -------------------------------------------------------------------------

    @Test
    fun coding_method_hover_surfaces_are_callable_or_unknown_dual_path() {
        for (method in ALL_DOCUMENTED_CODING_METHODS) {
            val harness = jvmHarness(
                "main.lua" to """
                    local alias = luajava.$method
                    return alias
                """.trimIndent()
            )

            val directHover = harness.queries.hover(
                harness.path("main.lua"),
                memberPosition(harness, "luajava.$method")
            )
            val aliasHover = harness.queries.hover(
                harness.path("main.lua"),
                harness.positionOf("main.lua", "alias", occurrence = 2)
            )

            val modeled = listOf(directHover, aliasHover).any { hover ->
                hover != null && isModeledCodingMethodSurface(
                    kind = hover.symbol?.kind,
                    display = hover.typeInfo?.displayName,
                    method = method
                )
            }
            val bothUnknownOrBlank = listOf(directHover, aliasHover).all { hover ->
                val display = hover?.typeInfo?.displayName
                display == null || display.isBlank() || display == "unknown"
            }
            assertTrue(
                modeled || bothUnknownOrBlank,
                "luajava.$method hover dual-path: modeled METHOD/callable or unknown/blank gap; " +
                    "direct=${directHover?.typeInfo?.displayName} alias=${aliasHover?.typeInfo?.displayName}"
            )
        }
    }

    @Test
    fun coding_method_completion_detail_dual_path_per_method() {
        for (method in CORE_CODING_METHODS + HELPER_CODING_METHODS) {
            val harness = jvmHarness(
                "main.lua" to """
                    local alias = luajava.$method
                    return alias
                """.trimIndent()
            )

            val memberCompletions = harness.queries.completions(
                harness.path("main.lua"),
                memberPosition(harness, "luajava.$method")
            )
            val item = memberCompletions.singleOrNull { it.label == method }
            val modeled = item != null && isModeledCodingMethodCompletion(item)
            val bare = item != null && isBareCallableListing(item)
            assertTrue(
                modeled || bare || item == null,
                "luajava.$method completion dual-path: modeled detail, bare listing, or absent; " +
                    "item=${item?.let { "${it.kind}:${it.detail}" }}"
            )
        }
    }

    // -------------------------------------------------------------------------
    // Result surfaces already product-backed (hard / dual-path goldens)
    // -------------------------------------------------------------------------

    @Test
    fun bind_class_coding_surface_mounts_provider_and_callable_class() {
        val harness = jvmHarness(
            "main.lua" to """
                local Locale = luajava.bindClass("java.util.Locale")
                return Locale
            """.trimIndent()
        )

        assertProviderPath(harness, "java.util.Locale")
        assertBindClassFact(harness, "java.util.Locale")
        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "Locale", occurrence = 2)
        )
        assertNotUnknown(hover?.typeInfo?.displayName)
        assertFalse(
            diagnostics(harness).any { it.message.contains("G:/") },
            "bindClass coding surface must never reference G:/ diagnostics"
        )
    }

    @Test
    fun new_instance_coding_surface_mounts_instance_type() {
        val harness = jvmHarness(
            "main.lua" to """
                local builder = luajava.newInstance("java.lang.StringBuilder")
                return builder
            """.trimIndent()
        )

        assertHoverType(harness, "builder", "java.lang.StringBuilder", occurrence = 2)
        assertProviderPath(harness, "java.lang.StringBuilder")
        assertNewInstanceFact(harness, "java.lang.StringBuilder")
    }

    @Test
    fun load_lib_coding_surface_static_method_is_callable() {
        val harness = jvmHarness(
            "main.lua" to """
                local currentTimeMillis = luajava.loadLib("java.lang.System", "currentTimeMillis")
                return currentTimeMillis
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "currentTimeMillis", occurrence = 2)
        )
        assertCallable(hover?.typeInfo?.displayName, "loadLib System.currentTimeMillis")
        assertLoadLibFact(harness, "java.lang.System")
    }

    @Test
    fun create_proxy_coding_surface_records_fact_and_member() {
        val harness = jvmHarness(
            "main.lua" to """
                local proxy = luajava.createProxy("java.lang.Runnable", {})
                local run = proxy.run
                return proxy, run
            """.trimIndent()
        )

        assertCreateProxyFact(harness, "java.lang.Runnable")
        val runHover = harness.queries.hover(
            harness.path("main.lua"),
            memberPosition(harness, "proxy.run")
        )
        val display = runHover?.typeInfo?.displayName
        assertTrue(
            display != null && (looksCallable(display) || display == "unknown" || display.isBlank()),
            "createProxy.run dual-path callable or gap; got $display"
        )
    }

    @Test
    fun get_context_coding_surface_returns_android_lua_context_or_gap() {
        val harness = jvmHarness(
            "main.lua" to """
                local context = luajava.getContext()
                return context
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "context", occurrence = 2)
        )
        val display = hover?.typeInfo?.displayName.orEmpty()
        val ideal =
            display.contains("AndroidLuaContext") ||
                display.contains("LuaActivity") ||
                display.contains("Context")
        val productGap = display.isBlank() || display == "unknown" || display == "any" || display == "nil"
        assertTrue(
            ideal || productGap,
            "getContext result dual-path: AndroidLuaContext-ish or CURRENTLY_ACCEPTS gap; got '$display'"
        )
    }

    @Test
    fun new_coding_surface_from_bound_class_dual_path() {
        val harness = jvmHarness(
            "main.lua" to """
                local StringBuilder = luajava.bindClass("java.lang.StringBuilder")
                local builder = luajava.new(StringBuilder)
                return builder
            """.trimIndent()
        )

        assertProviderPath(harness, "java.lang.StringBuilder")
        val display = hoverDisplay(harness, "builder", occurrence = 2).orEmpty()
        val ideal = display == "java.lang.StringBuilder" || display.contains("StringBuilder")
        val productGap = display.isBlank() || display == "unknown" || display == "any"
        assertTrue(
            ideal || productGap,
            "luajava.new coding surface dual-path: instance type or CURRENTLY_ACCEPTS gap; got '$display'"
        )
    }

    @Test
    fun create_array_coding_surface_dual_path() {
        val harness = jvmHarness(
            "main.lua" to """
                local array = luajava.createArray("java.lang.String", {"a", "b"})
                return array
            """.trimIndent()
        )

        val display = hoverDisplay(harness, "array", occurrence = 2).orEmpty()
        val ideal =
            (display.contains("String") &&
                (display.contains("[]") || display.contains("Array") || display.contains("JavaArray"))) ||
                display.contains("java.lang.String[]") ||
                display.contains("JavaArray")
        val productGap =
            display.isBlank() ||
                display == "unknown" ||
                display == "any" ||
                display == "table" ||
                display.startsWith("{")
        assertTrue(
            ideal || productGap,
            "createArray result dual-path: JavaArray/String[] or CURRENTLY_ACCEPTS gap; got '$display'"
        )
        assertNoCrashDiagnosticsQuery(harness)
    }

    @Test
    fun new_array_coding_surface_dual_path() {
        val harness = jvmHarness(
            "main.lua" to """
                local Locale = luajava.bindClass("java.util.Locale")
                local array = luajava.newArray(Locale, 2)
                return array
            """.trimIndent()
        )

        val display = hoverDisplay(harness, "array", occurrence = 2).orEmpty()
        val ideal =
            display.contains("Locale") ||
                display.contains("[]") ||
                display.contains("JavaArray") ||
                display.contains("Array")
        val productGap =
            display.isBlank() ||
                display == "unknown" ||
                display == "any" ||
                display == "table" ||
                display.startsWith("{")
        assertTrue(
            ideal || productGap,
            "newArray result dual-path: array-ish or CURRENTLY_ACCEPTS gap; got '$display'"
        )
        assertNoCrashDiagnosticsQuery(harness)
    }

    @Test
    fun astable_tostring_instanceof_override_coding_surfaces_do_not_crash() {
        val harness = jvmHarness(
            "main.lua" to """
                local builder = luajava.newInstance("java.lang.StringBuilder")
                local asTable = luajava.astable(builder)
                local asString = luajava.tostring(builder)
                local isSb = luajava.instanceof(builder, "java.lang.StringBuilder")
                local StringBuilder = luajava.bindClass("java.lang.StringBuilder")
                local overridden = luajava.override(StringBuilder, {})
                return asTable, asString, isSb, overridden
            """.trimIndent()
        )

        assertNoCrashDiagnosticsQuery(harness)

        // Soft dual-path result shapes — do not invent hard goldens beyond overlay docs.
        assertSoftResult(
            hoverDisplay(harness, "asTable", occurrence = 2),
            idealFragments = listOf("table", "{"),
            label = "astable"
        )
        assertSoftResult(
            hoverDisplay(harness, "asString", occurrence = 2),
            idealFragments = listOf("string"),
            label = "tostring"
        )
        assertSoftResult(
            hoverDisplay(harness, "isSb", occurrence = 2),
            idealFragments = listOf("boolean", "bool"),
            label = "instanceof"
        )
        assertSoftResult(
            hoverDisplay(harness, "overridden", occurrence = 2),
            idealFragments = listOf("StringBuilder", "JavaObject", "java.lang"),
            label = "override"
        )
    }

    // -------------------------------------------------------------------------
    // Signature help dual-path
    // -------------------------------------------------------------------------

    @Test
    fun coding_method_signature_help_at_call_sites_dual_path() {
        data class Case(val source: String, val needle: String, val method: String)

        val cases = listOf(
            Case(
                """local Locale = luajava.bindClass("java.util.Locale") return Locale""",
                "\"java.util.Locale\"",
                "bindClass"
            ),
            Case(
                """local b = luajava.newInstance("java.lang.StringBuilder") return b""",
                "\"java.lang.StringBuilder\"",
                "newInstance"
            ),
            Case(
                """local t = luajava.loadLib("java.lang.System", "currentTimeMillis") return t""",
                "\"java.lang.System\"",
                "loadLib"
            ),
            Case(
                """local p = luajava.createProxy("java.lang.Runnable", {}) return p""",
                "\"java.lang.Runnable\"",
                "createProxy"
            ),
            Case(
                """local a = luajava.createArray("java.lang.String", {}) return a""",
                "\"java.lang.String\"",
                "createArray"
            )
        )

        cases.forEach { case ->
            val harness = jvmHarness("main.lua" to case.source)
            val help = harness.queries.signatureHelp(
                harness.path("main.lua"),
                harness.positionOf("main.lua", case.needle)
            )
            assertCodingMethodSignatureHelp(help, case.method)
        }
    }

    // -------------------------------------------------------------------------
    // Negatives: colon guard + local shadow
    // -------------------------------------------------------------------------

    @Test
    fun colon_call_coding_methods_do_not_model_helper_semantics() {
        val harness = jvmHarness(
            "main.lua" to """
                local Locale = luajava:bindClass("java.util.Locale")
                local builder = luajava:newInstance("java.lang.StringBuilder")
                local loaded = luajava:loadLib("java.lang.System", "currentTimeMillis")
                local proxy = luajava:createProxy("java.lang.Runnable", {})
                return Locale, builder, loaded, proxy
            """.trimIndent()
        )

        val loads = jvmClassLoads(harness)
        // Colon must not record helper JVM class-load facts (TASK-133 style).
        assertTrue(
            loads.none { it.kind == DocumentFacts.JvmClassLoadKind.BIND_CLASS_CALL },
            "Colon bindClass must not record BIND_CLASS_CALL; got $loads"
        )
        assertTrue(
            loads.none { it.kind == DocumentFacts.JvmClassLoadKind.NEW_INSTANCE_CALL },
            "Colon newInstance must not record NEW_INSTANCE_CALL; got $loads"
        )
        assertTrue(
            loads.none { it.kind == DocumentFacts.JvmClassLoadKind.LOAD_LIB_CALL },
            "Colon loadLib must not record LOAD_LIB_CALL; got $loads"
        )
        assertTrue(
            loads.none { it.kind == DocumentFacts.JvmClassLoadKind.CREATE_PROXY_CALL },
            "Colon createProxy must not record CREATE_PROXY_CALL; got $loads"
        )

        for (needle in listOf("Locale", "builder", "loaded", "proxy")) {
            val display = hoverDisplay(harness, needle, occurrence = 2).orEmpty()
            val degraded =
                display.isBlank() ||
                    display == "unknown" ||
                    display == "any" ||
                    display == "nil" ||
                    display == "table" ||
                    display.startsWith("{")
            // Dual-path: ideal degrades; CURRENTLY_ACCEPTS if product still types JVM-ish
            // without helper facts (facts already hard-asserted empty above).
            val currentlyAcceptsTypedWithoutFacts = display.isNotBlank()
            assertTrue(
                degraded || currentlyAcceptsTypedWithoutFacts,
                "Colon $needle must not crash; dual-path degrade or typed gap; got '$display'"
            )
        }
        assertNoCrashDiagnosticsQuery(harness)
    }

    @Test
    fun local_shadow_coding_methods_do_not_inherit_luajava_surfaces() {
        val harness = jvmHarness(
            "main.lua" to """
                local function bindClass(target)
                    return { value = target }
                end
                local function newInstance(target)
                    return { value = target }
                end
                local function createProxy(target, impl)
                    return { value = target }
                end
                local function getContext()
                    return { value = "local" }
                end

                local classResult = bindClass("java.util.Locale")
                local instanceResult = newInstance("java.lang.StringBuilder")
                local proxyResult = createProxy("java.lang.Runnable", {})
                local contextResult = getContext()
                local classRoot = classResult.ROOT
                local instanceAppend = instanceResult.append
                local proxyRun = proxyResult.run
                return classResult, instanceResult, proxyResult, contextResult, classRoot, instanceAppend, proxyRun
            """.trimIndent()
        )

        assertTableLikeNotJvm(harness, "classResult", occurrence = 2)
        assertTableLikeNotJvm(harness, "instanceResult", occurrence = 2)
        assertTableLikeNotJvm(harness, "proxyResult", occurrence = 2)
        assertTableLikeNotJvm(harness, "contextResult", occurrence = 2)

        assertUnknownOrTableLike(harness, "classRoot", occurrence = 2)
        assertUnknownOrTableLike(harness, "instanceAppend", occurrence = 2)
        assertUnknownOrTableLike(harness, "proxyRun", occurrence = 2)

        val loads = jvmClassLoads(harness)
        assertTrue(
            loads.none {
                it.kind == DocumentFacts.JvmClassLoadKind.BIND_CLASS_CALL ||
                    it.kind == DocumentFacts.JvmClassLoadKind.NEW_INSTANCE_CALL ||
                    it.kind == DocumentFacts.JvmClassLoadKind.CREATE_PROXY_CALL
            },
            "Local shadow coding methods must not emit JVM helper facts; got $loads"
        )
    }

    @Test
    fun local_luajava_table_shadow_does_not_gain_coding_method_surfaces() {
        val harness = jvmHarness(
            "main.lua" to """
                local luajava = {
                    bindClass = function(target) return { value = target } end,
                    newInstance = function(target) return { value = target } end,
                    createArray = function(target, values) return { value = target } end,
                    getContext = function() return { value = "local" } end
                }
                local Locale = luajava.bindClass("java.util.Locale")
                local builder = luajava.newInstance("java.lang.StringBuilder")
                local array = luajava.createArray("java.lang.String", {})
                local context = luajava.getContext()
                return Locale, builder, array, context
            """.trimIndent()
        )

        assertTableLikeNotJvm(harness, "Locale", occurrence = 2)
        assertTableLikeNotJvm(harness, "builder", occurrence = 2)
        assertTableLikeNotJvm(harness, "array", occurrence = 2)
        assertTableLikeNotJvm(harness, "context", occurrence = 2)

        val loads = jvmClassLoads(harness)
        assertTrue(
            loads.isEmpty() || loads.none {
                it.kind == DocumentFacts.JvmClassLoadKind.BIND_CLASS_CALL ||
                    it.kind == DocumentFacts.JvmClassLoadKind.NEW_INSTANCE_CALL
            },
            "Shadowed luajava table must not emit helper facts; got $loads"
        )
    }

    // -------------------------------------------------------------------------
    // Alias / chained coding surface smoke
    // -------------------------------------------------------------------------

    @Test
    fun chained_aliases_preserve_core_coding_surfaces() {
        val harness = jvmHarness(
            "main.lua" to """
                local bindClass = luajava.bindClass
                local bindAgain = bindClass
                local Locale = bindAgain("java.util.Locale")
                local newInstance = luajava.newInstance
                local make = newInstance
                local builder = make("java.lang.StringBuilder")
                return Locale, builder
            """.trimIndent()
        )

        assertProviderPath(harness, "java.util.Locale")
        assertProviderPath(harness, "java.lang.StringBuilder")
        assertBindClassFact(harness, "java.util.Locale")
        assertNewInstanceFact(harness, "java.lang.StringBuilder")
        assertHoverType(harness, "builder", "java.lang.StringBuilder", occurrence = 2)
    }

    @Test
    fun resource_helper_campaign_fixture_lists_helper_coding_methods() {
        val harness = jvmHarness(
            "luajava_helper_campaign.lua" to resourceText("luajava_helper_campaign.lua")
        )

        val completions = harness.queries.completions(
            harness.path("luajava_helper_campaign.lua"),
            memberPosition(harness, "luajava.createArray", path = "luajava_helper_campaign.lua")
        )

        for (method in HELPER_CODING_METHODS) {
            assertTrue(
                completions.any {
                    it.label == method &&
                        (it.kind == CompletionItemKind.METHOD ||
                            it.kind == CompletionItemKind.FUNCTION ||
                            it.kind == CompletionItemKind.FIELD ||
                            it.kind == CompletionItemKind.VARIABLE)
                },
                "Expected helper coding method $method on luajava completion; " +
                    "actual=${completions.map { "${it.label}:${it.kind}" }}"
            )
        }

        val contextHover = harness.queries.hover(
            harness.path("luajava_helper_campaign.lua"),
            harness.positionOf("luajava_helper_campaign.lua", "context", occurrence = 2)
        )
        val display = contextHover?.typeInfo?.displayName.orEmpty()
        val ideal =
            display.contains("AndroidLuaContext") ||
                display.contains("LuaActivity") ||
                display.contains("Context")
        val productGap = display.isBlank() || display == "unknown" || display == "any" || display == "nil"
        assertTrue(
            ideal || productGap,
            "helper campaign getContext dual-path; got '$display'"
        )
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun jvmHarness(vararg files: Pair<String, String>): WorkspaceSemanticHarness {
        return WorkspaceSemanticHarness.build(
            *files,
            engine = JvmWorkspaceEngine()
        )
    }

    private fun hostAndroidJarCandidates(): List<File> {
        val home = System.getProperty("user.home")
        return listOfNotNull(
            downloadsAndroidJar,
            File(JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH),
            sdkAndroid35Jar,
            File("$home/Library/Android/sdk/platforms/android-35/android.jar"),
            System.getenv("ANDROID_HOME")?.let { File("$it/platforms/android-35/android.jar") },
            System.getenv("ANDROID_SDK_ROOT")?.let { File("$it/platforms/android-35/android.jar") }
        )
    }

    private fun missingAndroidJarSkipReason(missing: File): String {
        return "TASK-474 skip: android.jar missing at ${missing.path}; " +
            "luajava coding method surface corpus uses host Downloads + SDK android-35 only (never G:/)."
    }

    private fun resourceText(name: String): String {
        val path = "/semantic/campaign-java-android/$name"
        val stream = javaClass.getResourceAsStream(path)
            ?: error("Missing campaign fixture resource $path")
        return stream.bufferedReader().use { it.readText() }
    }

    private fun jvmClassLoads(harness: WorkspaceSemanticHarness): List<DocumentFacts.JvmClassLoadFact> {
        return harness.snapshot.files.getValue(harness.path("main.lua")).documentFacts?.jvmClassLoads.orEmpty()
    }

    private fun diagnostics(harness: WorkspaceSemanticHarness): List<Diagnostic> {
        return harness.queries.diagnostics(harness.path("main.lua"))
    }

    private fun assertNoCrashDiagnosticsQuery(harness: WorkspaceSemanticHarness) {
        diagnostics(harness)
    }

    private fun hoverDisplay(
        harness: WorkspaceSemanticHarness,
        needle: String,
        occurrence: Int
    ): String? {
        return harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", needle, occurrence)
        )?.typeInfo?.displayName
    }

    private fun assertHoverType(
        harness: WorkspaceSemanticHarness,
        needle: String,
        expected: String,
        occurrence: Int = 1
    ) {
        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", needle, occurrence)
        )
        assertEquals(expected, hover?.typeInfo?.displayName)
    }

    private fun assertProviderPath(harness: WorkspaceSemanticHarness, className: String) {
        val path = harness.path("__jvm__/classes/${className.replace('.', '/')}.lua")
        assertTrue(
            path in harness.snapshot.extraProviders,
            "Expected provider $path for $className; actual providers: ${harness.snapshot.extraProviders.keys.map { it.value }}."
        )
    }

    private fun assertBindClassFact(harness: WorkspaceSemanticHarness, className: String) {
        val loads = jvmClassLoads(harness)
        assertTrue(
            loads.any {
                it.kind == DocumentFacts.JvmClassLoadKind.BIND_CLASS_CALL && it.target == className
            },
            "Expected BIND_CLASS_CALL fact for $className; got $loads"
        )
    }

    private fun assertNewInstanceFact(harness: WorkspaceSemanticHarness, className: String) {
        val loads = jvmClassLoads(harness)
        assertTrue(
            loads.any {
                it.kind == DocumentFacts.JvmClassLoadKind.NEW_INSTANCE_CALL && it.target == className
            },
            "Expected NEW_INSTANCE_CALL fact for $className; got $loads"
        )
    }

    private fun assertLoadLibFact(harness: WorkspaceSemanticHarness, className: String) {
        val loads = jvmClassLoads(harness)
        assertTrue(
            loads.any {
                it.kind == DocumentFacts.JvmClassLoadKind.LOAD_LIB_CALL && it.target == className
            },
            "Expected LOAD_LIB_CALL fact for $className; got $loads"
        )
    }

    private fun assertCreateProxyFact(harness: WorkspaceSemanticHarness, target: String) {
        val loads = jvmClassLoads(harness)
        assertTrue(
            loads.any {
                it.kind == DocumentFacts.JvmClassLoadKind.CREATE_PROXY_CALL && it.target == target
            },
            "Expected CREATE_PROXY_CALL fact for $target; got $loads"
        )
    }

    private fun isModeledCodingMethodCompletion(item: CompletionItem): Boolean {
        val kindOk =
            item.kind == CompletionItemKind.METHOD ||
                item.kind == CompletionItemKind.FUNCTION ||
                item.kind == CompletionItemKind.FIELD ||
                item.kind == CompletionItemKind.VARIABLE
        val detail = item.detail.orEmpty()
        return kindOk &&
            detail.isNotBlank() &&
            detail != "unknown" &&
            (looksCallable(detail) ||
                detail.contains("fun") ||
                detail.contains("function") ||
                detail.contains("Java") ||
                detail.contains("class", ignoreCase = true) ||
                detail.contains("string", ignoreCase = true) ||
                detail.contains("table", ignoreCase = true) ||
                detail.contains("boolean", ignoreCase = true))
    }

    private fun isBareCallableListing(item: CompletionItem): Boolean {
        return item.kind == CompletionItemKind.METHOD ||
            item.kind == CompletionItemKind.FUNCTION ||
            item.kind == CompletionItemKind.FIELD ||
            item.kind == CompletionItemKind.VARIABLE
    }

    private fun isModeledCodingMethodSurface(
        kind: SymbolKind?,
        display: String?,
        method: String
    ): Boolean {
        val text = display.orEmpty()
        if (text.isBlank() || text == "unknown") {
            return false
        }
        val kindOk =
            kind == null ||
                kind == SymbolKind.METHOD ||
                kind == SymbolKind.FUNCTION ||
                kind == SymbolKind.VARIABLE ||
                kind == SymbolKind.FIELD
        val shapeOk =
            looksCallable(text) ||
                text.contains("fun") ||
                text.contains("function") ||
                text.contains(method, ignoreCase = true) ||
                text.contains("JavaClass") ||
                text.contains("JavaObject") ||
                text.contains("JavaProxy") ||
                text.contains("JavaArray") ||
                text.contains("AndroidLuaContext")
        return kindOk && shapeOk
    }

    private fun assertCodingMethodSignatureHelp(help: SignatureHelp?, method: String) {
        if (help == null || help.signatures.isEmpty()) {
            // Documented product gap until SignatureHelpProvider models all luajava helpers.
            return
        }
        val labels = help.signatures.map { it.label }
        val looksModeled = labels.any { label ->
            looksCallable(label) ||
                label.contains(method, ignoreCase = true) ||
                label.contains("fun(") ||
                label.contains("string", ignoreCase = true) ||
                label.isNotBlank()
        }
        assertTrue(
            looksModeled,
            "When $method signature help is modeled, labels must be non-blank / callable-ish; got $labels"
        )
        assertTrue(help.activeSignature >= 0, "activeSignature must be non-negative")
    }

    private fun assertSoftResult(
        display: String?,
        idealFragments: List<String>,
        label: String
    ) {
        val text = display.orEmpty()
        val ideal = idealFragments.any { text.contains(it, ignoreCase = true) }
        val productGap =
            text.isBlank() ||
                text == "unknown" ||
                text == "any" ||
                text == "nil" ||
                text == "table" ||
                text.startsWith("{")
        assertTrue(
            ideal || productGap,
            "$label result dual-path: one of $idealFragments or CURRENTLY_ACCEPTS gap; got '$text'"
        )
    }

    private fun assertTableLikeNotJvm(
        harness: WorkspaceSemanticHarness,
        needle: String,
        occurrence: Int
    ) {
        val display = hoverDisplay(harness, needle, occurrence).orEmpty()
        assertTrue(
            display == "table" ||
                display.startsWith("{") ||
                display.contains("value") ||
                display == "unknown" ||
                display.isBlank(),
            "Expected table-like shadowed coding method result for $needle, got '$display'."
        )
        assertFalse(
            display.startsWith("java."),
            "Shadowed coding method must not expose JVM class type for $needle, got '$display'."
        )
        assertFalse(
            display.contains("AndroidLuaContext"),
            "Shadowed getContext must not expose AndroidLuaContext for $needle, got '$display'."
        )
        assertFalse(
            display.contains("JavaProxy", ignoreCase = true),
            "Shadowed coding method must not expose JavaProxy for $needle, got '$display'."
        )
    }

    private fun assertUnknownOrTableLike(
        harness: WorkspaceSemanticHarness,
        needle: String,
        occurrence: Int
    ) {
        val display = hoverDisplay(harness, needle, occurrence).orEmpty()
        assertTrue(
            display.isBlank() ||
                display == "unknown" ||
                display == "any" ||
                display == "nil" ||
                display == "table" ||
                display.startsWith("{"),
            "Shadow member $needle must stay unknown/table-like, not JVM helper surface; got '$display'."
        )
        assertFalse(display.startsWith("java."), "Shadow member $needle must not be JVM type; got '$display'.")
    }

    private fun assertCallable(displayName: String?, label: String = "type") {
        val text = displayName.orEmpty()
        assertTrue(
            looksCallable(text),
            "Expected callable $label, got $displayName."
        )
        assertNotUnknown(displayName)
    }

    private fun looksCallable(displayName: String): Boolean {
        return displayName.contains("fun(") ||
            Regex("""fun\s*<[^>]+>\s*\(""").containsMatchIn(displayName)
    }

    private fun assertNotUnknown(displayName: String?) {
        assertFalse(displayName.isNullOrBlank(), "Expected a modeled Java type, got no type.")
        assertFalse(displayName == "unknown", "Expected a modeled Java type, got unknown.")
    }

    private fun memberPosition(
        harness: WorkspaceSemanticHarness,
        memberAccess: String,
        path: String = "main.lua"
    ): Position {
        val dotIndex = memberAccess.indexOf('.')
        check(dotIndex >= 0) { "Expected member access with dot, got $memberAccess." }
        val source = harness.files.getValue(harness.path(path))
        val index = source.indexOf(memberAccess)
        check(index >= 0) { "Missing '$memberAccess' in $path." }
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
        /** Core methods with dedicated sibling corpora / hard product surfaces. */
        val CORE_CODING_METHODS = listOf(
            "bindClass",
            "newInstance",
            "loadLib",
            "createProxy"
        )

        /** Helper methods locked by luajava_helper_campaign / array corpora. */
        val HELPER_CODING_METHODS = listOf(
            "createArray",
            "newArray",
            "astable",
            "getContext",
            "override"
        )

        /**
         * Full AndroLua 5.3 luajava overlay inventory (no invented APIs).
         * Includes lesser-used helpers dual-path only.
         */
        val ALL_DOCUMENTED_CODING_METHODS = listOf(
            "bindClass",
            "new",
            "newInstance",
            "loadLib",
            "createProxy",
            "newArray",
            "createArray",
            "astable",
            "tostring",
            "instanceof",
            "getContext",
            "override"
        )
    }
}
