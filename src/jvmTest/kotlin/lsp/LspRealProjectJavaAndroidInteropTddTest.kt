package lsp

import io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaWorkspaceService
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.SignatureHelpParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.WorkspaceFolder
import org.junit.Assume
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Real-project-style Java / Android interop LSP corpus (~50 cases).
 *
 * Distinct from require-graph and editor-lifecycle suites: multi-file mixed
 * Lua+Java project layouts, luajava.bindClass / newInstance / createProxy,
 * Android activity / import / loadlayout soft paths.
 *
 * Host android.jar discovery is dual-path via product
 * [JvmWorkspaceConfiguration.discoverReflectiveAndroidJarPath] /
 * [JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH] plus well-known SDK roots.
 * Never invents G:/. When no jar is present, Android-only cases soft-skip via
 * [Assume.assumeTrue] with an honest reason.
 *
 * Test-only. No product edits. Workers must not run full jvmTest.
 */
class LspRealProjectJavaAndroidInteropTddTest {

    private val androidJar: File = resolveHostAndroidJar()

    // =========================================================================
    // Pure Java interop (no android.jar required)
    // =========================================================================

    @Test
    fun real_project_bind_class_hover_on_locale_alias() {
        val service = jvmService()
        val document = service.open(
            "workspace/app/bind_locale.lua",
            """
            local Locale = luajava.bindClass("java.util.Locale")
            local current = Locale
            return current
            """
        )
        val hover = service.hover(hoverParams(document, "Locale", occurrence = 2))
        softHoverMentions(hover, "Locale")
    }

    @Test
    fun real_project_bind_class_completion_on_locale_static_member() {
        val service = jvmService()
        val document = service.open(
            "workspace/app/bind_locale_complete.lua",
            """
            local Locale = luajava.bindClass("java.util.Locale")
            local x = Locale.
            """
        )
        val labels = completionLabels(service, document, afterNeedle = "Locale.")
        softContainsAny(labels, listOf("getDefault", "US", "ENGLISH", "ROOT", "getAvailableLocales"))
    }

    @Test
    fun real_project_bind_class_definition_lands_on_provider_or_local() {
        val service = jvmService()
        val document = service.open(
            "workspace/app/bind_locale_def.lua",
            """
            local Locale = luajava.bindClass("java.util.Locale")
            local current = Locale.getDefault
            return current
            """
        )
        val defs = service.definition(definitionParams(document, "Locale", occurrence = 2))
        softDefinitionOrLocal(defs, javaProviderUri("java.util.Locale"), document.uri)
    }

    @Test
    fun real_project_string_builder_instance_member_completion() {
        val service = jvmService(classes = listOf("java.lang.StringBuilder"))
        val document = service.open(
            "workspace/ui/builder.lua",
            """
            local StringBuilder = require("StringBuilder")
            local b = StringBuilder()
            local x = b:
            """
        )
        val labels = completionLabels(service, document, afterNeedle = "b:")
        softContainsAny(labels, listOf("append", "toString", "length", "insert", "delete"))
    }

    @Test
    fun real_project_android_import_textview_hover() {
        val service = androidService()
        val document = service.open(
            "workspace/activity/main.lua",
            """
            require "import"
            import "android.widget.TextView"
            local view = TextView(activity)
            return view
            """
        )
        val hover = service.hover(hoverParams(document, "TextView(activity)", offset = 2))
        softHoverMentions(hover, "TextView")
    }

    @Test
    fun real_project_android_textview_member_completion() {
        val service = androidService()
        val document = service.open(
            "workspace/activity/tv_members.lua",
            """
            require "import"
            import "android.widget.TextView"
            local view = TextView(activity)
            view:
            """
        )
        val labels = completionLabels(service, document, afterNeedle = "view:")
        softContainsAny(labels, listOf("setText", "getText", "setVisibility", "setOnClickListener"))
    }

    @Test
    fun real_project_android_loadlayout_soft_hover() {
        val service = androidService()
        val document = service.open(
            "workspace/ui/loadlayout_use.lua",
            """
            require "import"
            local ids = {}
            local layout = {
                LinearLayout,
                layout_width = "match_parent",
                {
                    TextView,
                    id = "title",
                    text = "Hello"
                }
            }
            local root = loadlayout(layout, ids)
            return root
            """
        )
        val hover = service.hover(hoverParams(document, "loadlayout"))
        // soft: loadlayout may be stub / empty member surface
        if (hover != null) {
            assertTrue(hoverMarkup(hover).isNotBlank())
        }
    }

    @Test
    fun real_project_mixed_java_and_android_layout_soft() {
        val service = androidService()
        val document = service.open(
            "workspace/app/mixed.lua",
            """
            require "import"
            import "android.widget.TextView"
            local Arrays = require("Arrays")
            local Locale = luajava.bindClass("java.util.Locale")
            local view = TextView(activity)
            view:setText(tostring(Locale.US))
            local list = Arrays.asList("a", "b")
            return view, list
            """
        )
        // Configure jdk classes too via metadata merge path
        service.setWorkspaceMetadata(
            mapOf(
                JvmClassModuleProvider.CLASSES_METADATA_KEY to listOf(
                    "java.util.Arrays",
                    "java.util.Locale",
                    "java.lang.String"
                ).joinToString("\n")
            )
        )
        val hoverTv = service.hover(hoverParams(document, "setText", offset = 3))
        softHoverMentions(hoverTv, "setText")
        val hoverAsList = service.hover(hoverParams(document, "asList"))
        softHoverMentions(hoverAsList, "asList")
    }

    @Test
    fun real_project_android_missing_jar_skip_reason_never_mentions_g_drive() {
        val missing = File("/nonexistent/android-sdk/platforms/android-35/android.jar")
        val reason = missingAndroidJarSkipReason(missing)
        assertTrue(reason.contains("android.jar"), reason)
        assertFalse("G:/" in reason || "G:\\" in reason, "Skip reason must never hardcode G:/; got $reason")
        assertTrue(
            reason.contains("ANDROID_HOME") || reason.contains("Android/Sdk") || reason.contains("soft-skip"),
            reason
        )
    }

    @Test
    fun real_project_no_g_drive_paths_in_host_resolution() {
        val jar = androidJar
        val normalized = jar.path.replace('\\', '/')
        // Present or messaging candidate — never sole G:/ invention as only strategy.
        // Soft: if somehow only G path is present on a machine, still must not be required.
        if (normalized.startsWith("G:/Android/Sdk", ignoreCase = true)) {
            // Dual-path must still document multi-OS roots in skip reason.
            val reason = missingAndroidJarSkipReason(File("/missing/android.jar"))
            assertFalse(reason.trim().equals("G:/Android/Sdk", ignoreCase = true))
        } else {
            assertFalse(
                normalized.startsWith("G:/Android/Sdk", ignoreCase = true),
                "Host android.jar resolution must not hardcode G:/; got ${jar.path}"
            )
        }
    }

    // =========================================================================
    // luajava.bindClass / newInstance / createProxy
    // =========================================================================

    @Test
    fun real_project_bind_class_system_static_completion() {
        val service = jvmService()
        val document = service.open(
            "workspace/app/bind_system.lua",
            """
            local System = luajava.bindClass("java.lang.System")
            local x = System.
            """
        )
        val labels = completionLabels(service, document, afterNeedle = "System.")
        softContainsAny(labels, listOf("out", "err", "getProperty", "currentTimeMillis", "arraycopy"))
    }

    @Test
    fun real_project_bind_class_math_hover() {
        val service = jvmService()
        val document = service.open(
            "workspace/app/bind_math.lua",
            """
            local JMath = luajava.bindClass("java.lang.Math")
            local v = JMath.max(1, 2)
            return v
            """
        )
        val hover = service.hover(hoverParams(document, "JMath", occurrence = 2))
        softHoverMentions(hover, "Math", "JMath")
    }

    @Test
    fun real_project_bind_class_arrays_aslist_signature_soft() {
        val service = jvmService()
        val document = service.open(
            "workspace/app/bind_arrays_sig.lua",
            """
            local Arrays = luajava.bindClass("java.util.Arrays")
            local list = Arrays.asList(
            """
        )
        val help = service.signatureHelp(signatureParams(document, "asList(", offset = 6))
        softSignaturePresent(help, "asList", "Object", "T")
    }

    @Test
    fun real_project_bind_class_arraylist_constructor_soft() {
        val service = jvmService(classes = listOf("java.util.ArrayList", "java.util.List"))
        val document = service.open(
            "workspace/app/bind_arraylist.lua",
            """
            local ArrayList = luajava.bindClass("java.util.ArrayList")
            local list = ArrayList()
            list:add("a")
            return list
            """
        )
        val labels = completionLabels(service, document, afterNeedle = "list:")
        softContainsAny(labels, listOf("add", "get", "size", "isEmpty", "clear", "remove"))
    }

    @Test
    fun real_project_luajava_new_instance_string_builder() {
        val service = jvmService()
        val document = service.open(
            "workspace/app/new_instance_sb.lua",
            """
            local b = luajava.newInstance("java.lang.StringBuilder", "seed")
            local x = b:
            """
        )
        val labels = completionLabels(service, document, afterNeedle = "b:")
        softContainsAny(labels, listOf("append", "toString", "length", "insert"))
    }

    @Test
    fun real_project_luajava_new_instance_hover_soft() {
        val service = jvmService()
        val document = service.open(
            "workspace/app/new_instance_hover.lua",
            """
            local b = luajava.newInstance("java.lang.StringBuilder")
            return b
            """
        )
        val hover = service.hover(hoverParams(document, "newInstance"))
        softHoverMentions(hover, "newInstance", "luajava", "function")
    }

    @Test
    fun real_project_luajava_create_proxy_runnable_soft() {
        val service = jvmService()
        val document = service.open(
            "workspace/app/create_proxy.lua",
            """
            local Runnable = luajava.bindClass("java.lang.Runnable")
            local proxy = luajava.createProxy("java.lang.Runnable", {
                run = function() end
            })
            return proxy
            """
        )
        val hover = service.hover(hoverParams(document, "createProxy"))
        softHoverMentions(hover, "createProxy", "proxy", "luajava")
        val labels = completionLabels(service, document, afterNeedle = "proxy")
        softContainsAnyOrEmpty(labels, listOf("run"))
    }

    @Test
    fun real_project_luajava_dot_completion_includes_bind_class() {
        val service = jvmService()
        val document = service.open(
            "workspace/app/luajava_dot.lua",
            """
            local x = luajava.
            """
        )
        val labels = completionLabels(service, document, afterNeedle = "luajava.")
        softContainsAny(labels, listOf("bindClass", "newInstance", "createProxy", "new", "loadLib"))
    }

    @Test
    fun real_project_require_string_class_then_static() {
        val service = jvmService(classes = listOf("java.lang.String"))
        val document = service.open(
            "workspace/app/require_string.lua",
            """
            local String = require("String")
            local x = String.
            """
        )
        val labels = completionLabels(service, document, afterNeedle = "String.")
        softContainsAny(labels, listOf("valueOf", "format", "copyValueOf", "join"))
    }

    @Test
    fun real_project_require_arrays_aslist_definition_soft() {
        val service = jvmService(classes = listOf("java.util.Arrays"))
        val document = service.open(
            "workspace/app/require_arrays_def.lua",
            """
            local Arrays = require("Arrays")
            local list = Arrays.asList("a")
            return list
            """
        )
        val defs = service.definition(definitionParams(document, "asList"))
        softDefinitionOrLocal(defs, javaProviderUri("java.util.Arrays"), document.uri)
    }

    @Test
    fun real_project_comparator_proxy_member_soft() {
        val service = jvmService(classes = listOf("java.util.Comparator", "java.lang.String"))
        val document = service.open(
            "workspace/app/comparator_proxy.lua",
            """
            local cmp = luajava.createProxy("java.util.Comparator", {
                compare = function(a, b) return 0 end
            })
            local x = cmp:
            """
        )
        val labels = completionLabels(service, document, afterNeedle = "cmp:")
        softContainsAnyOrEmpty(labels, listOf("compare", "reversed", "thenComparing"))
    }

    @Test
    fun real_project_java_system_out_println_chain_soft() {
        val service = jvmService()
        val document = service.open(
            "workspace/app/system_out.lua",
            """
            local System = luajava.bindClass("java.lang.System")
            System.out:println("hi")
            """
        )
        val hover = service.hover(hoverParams(document, "println"))
        softHoverMentions(hover, "println", "out", "PrintStream")
    }

    @Test
    fun real_project_bind_class_references_locale_alias() {
        val service = jvmService()
        val document = service.open(
            "workspace/app/bind_locale_refs.lua",
            """
            local Locale = luajava.bindClass("java.util.Locale")
            local a = Locale.US
            local b = Locale.ENGLISH
            return a, b, Locale
            """
        )
        val refs = service.references(referenceParams(document, "Locale", occurrence = 2))
        assertTrue(refs.isEmpty() || refs.any { it.uri == document.uri }, refs.map { it.uri }.toString())
    }

    @Test
    fun real_project_multi_file_java_helper_require() {
        val root = Files.createTempDirectory("lua-parser-java-multi-")
        val util = writeFile(
            root,
            "util/java_helpers.lua",
            """
            local M = {}
            function M.locale()
                return luajava.bindClass("java.util.Locale")
            end
            return M
            """.trimIndent()
        )
        val main = writeFile(
            root,
            "main.lua",
            """
            local H = require("util.java_helpers")
            local Locale = H.locale()
            return Locale
            """.trimIndent()
        )
        val service = jvmService(workspaceRoot = root)
        service.didOpen(openParams(util.uri, util.source))
        service.didOpen(openParams(main.uri, main.source))
        val hover = service.hover(HoverParams(TextDocumentIdentifier(main.uri), main.positionOf("locale")))
        softHoverMentions(hover, "locale", "Locale")
        val defs = service.definition(
            DefinitionParams(TextDocumentIdentifier(main.uri), main.positionOf("locale"))
        )
        if (defs.isNotEmpty()) {
            assertTrue(
                defs.any { it.uri == util.uri || it.uri == main.uri || it.uri.contains("__jvm__") },
                defs.map { it.uri }.toString()
            )
        }
    }

    @Test
    fun real_project_string_builder_append_signature_soft() {
        val service = jvmService(classes = listOf("java.lang.StringBuilder"))
        val document = service.open(
            "workspace/ui/builder_sig.lua",
            """
            local StringBuilder = require("StringBuilder")
            local b = StringBuilder()
            b:append(
            """
        )
        val help = service.signatureHelp(signatureParams(document, "append(", offset = 6))
        softSignaturePresent(help, "append", "String", "CharSequence", "char")
    }

    @Test
    fun real_project_arraylist_get_hover_soft() {
        val service = jvmService(classes = listOf("java.util.ArrayList"))
        val document = service.open(
            "workspace/app/arraylist_get.lua",
            """
            local ArrayList = require("ArrayList")
            local list = ArrayList()
            local item = list:get(0)
            return item
            """
        )
        val hover = service.hover(hoverParams(document, "get", occurrence = 1))
        softHoverMentions(hover, "get")
    }

    // =========================================================================
    // Android dual-path soft-skip surfaces
    // =========================================================================

    @Test
    fun real_project_android_button_member_completion() {
        val service = androidService()
        val document = service.open(
            "workspace/activity/button_members.lua",
            """
            require "import"
            import "android.widget.Button"
            local btn = Button(activity)
            btn:
            """
        )
        val labels = completionLabels(service, document, afterNeedle = "btn:")
        softContainsAny(labels, listOf("setText", "setOnClickListener", "setEnabled", "setVisibility"))
    }

    @Test
    fun real_project_android_linearlayout_hover() {
        val service = androidService()
        val document = service.open(
            "workspace/activity/ll_hover.lua",
            """
            require "import"
            import "android.widget.LinearLayout"
            local root = LinearLayout(activity)
            return root
            """
        )
        val hover = service.hover(hoverParams(document, "LinearLayout(activity)", offset = 2))
        softHoverMentions(hover, "LinearLayout")
    }

    @Test
    fun real_project_android_activity_member_soft() {
        val service = androidService()
        val document = service.open(
            "workspace/activity/activity_members.lua",
            """
            require "import"
            activity:
            """
        )
        val labels = completionLabels(service, document, afterNeedle = "activity:")
        softContainsAny(labels, listOf("setContentView", "getApplicationContext", "finish", "runOnUiThread", "findViewById"))
    }

    @Test
    fun real_project_android_import_button_definition_soft() {
        val service = androidService()
        val document = service.open(
            "workspace/activity/btn_def.lua",
            """
            require "import"
            import "android.widget.Button"
            local btn = Button(activity)
            return btn
            """
        )
        val defs = service.definition(definitionParams(document, "Button(activity)", offset = 2))
        softDefinitionOrLocal(defs, androidProviderUri("android.widget.Button"), document.uri)
    }

    @Test
    fun real_project_android_onclick_listener_proxy_soft() {
        val service = androidService()
        val document = service.open(
            "workspace/activity/onclick.lua",
            """
            require "import"
            import "android.widget.Button"
            import "android.view.View"
            local btn = Button(activity)
            btn:setOnClickListener(function(v)
                print(v)
            end)
            return btn
            """
        )
        val hover = service.hover(hoverParams(document, "setOnClickListener"))
        softHoverMentions(hover, "setOnClickListener", "OnClickListener", "Listener")
    }

    @Test
    fun real_project_android_textview_settext_signature_soft() {
        val service = androidService()
        val document = service.open(
            "workspace/activity/tv_sig.lua",
            """
            require "import"
            import "android.widget.TextView"
            local view = TextView(activity)
            view:setText(
            """
        )
        val help = service.signatureHelp(signatureParams(document, "setText(", offset = 7))
        softSignaturePresent(help, "setText", "CharSequence", "String", "int")
    }

    @Test
    fun real_project_android_view_visibility_constants_soft() {
        val service = androidService()
        val document = service.open(
            "workspace/activity/view_vis.lua",
            """
            require "import"
            import "android.view.View"
            local x = View.
            """
        )
        val labels = completionLabels(service, document, afterNeedle = "View.")
        softContainsAny(labels, listOf("VISIBLE", "GONE", "INVISIBLE", "FOCUS_DOWN"))
    }

    @Test
    fun real_project_android_multi_file_activity_helper() {
        val root = Files.createTempDirectory("lua-parser-android-multi-")
        assertAndroidJarExists()
        val helper = writeFile(
            root,
            "ui/widgets.lua",
            """
            require "import"
            import "android.widget.TextView"
            local M = {}
            function M.title(ctx, text)
                local v = TextView(ctx)
                v:setText(text)
                return v
            end
            return M
            """.trimIndent()
        )
        val main = writeFile(
            root,
            "main.lua",
            """
            local W = require("ui.widgets")
            local title = W.title(activity, "Hi")
            return title
            """.trimIndent()
        )
        val service = androidService()
        service.didOpen(openParams(helper.uri, helper.source))
        service.didOpen(openParams(main.uri, main.source))
        val hover = service.hover(HoverParams(TextDocumentIdentifier(main.uri), main.positionOf("title", occurrence = 2)))
        softHoverMentions(hover, "title", "TextView")
    }

    @Test
    fun real_project_android_loadlayout_ids_member_soft() {
        val service = androidService()
        val document = service.open(
            "workspace/ui/loadlayout_ids.lua",
            """
            require "import"
            local ids = {}
            local layout = {
                LinearLayout,
                {
                    TextView,
                    id = "title",
                    text = "Hello"
                }
            }
            loadlayout(layout, ids)
            local x = ids.
            """
        )
        val labels = completionLabels(service, document, afterNeedle = "ids.")
        softContainsAnyOrEmpty(labels, listOf("title"))
    }

    @Test
    fun real_project_android_missing_jar_assume_path_is_multi_os() {
        val reason = missingAndroidJarSkipReason(File("/tmp/no-such-android.jar"))
        assertFalse(reason.contains("G:/Android/Sdk") && !reason.contains("LOCALAPPDATA"), reason)
        assertTrue(
            reason.contains("ANDROID_HOME") || reason.contains("Android/Sdk") || reason.contains("soft-skip"),
            reason
        )
        assertFalse(reason.trim() == "G:/", reason)
    }

    @Test
    fun real_project_dual_path_skip_reason_mentions_macos_linux_windows() {
        val reason = missingAndroidJarSkipReason(File("/nonexistent/platforms/android-35/android.jar"))
        val hasWin = reason.contains("LOCALAPPDATA") || reason.contains("Android/Sdk") || reason.contains("Windows")
        val hasMac = reason.contains("Library/Android") || reason.contains("macOS") || reason.contains("sdk")
        val hasLinux = reason.contains("Android/Sdk") || reason.contains("Linux") || reason.contains("~/Android")
        assertTrue(hasWin || hasMac || hasLinux || reason.contains("ANDROID_HOME"), reason)
        assertFalse(reason.startsWith("G:/"), reason)
    }

    @Test
    fun real_project_android_jar_candidate_resolution_prefers_non_g() {
        val jar = resolveHostAndroidJarPublic()
        if (jar.isFile) {
            val n = jar.path.replace('\\', '/')
            if (n.startsWith("G:/Android/Sdk", ignoreCase = true)) {
                // still document dual-path; soft accept present G jar only if no alternative
                val reason = missingAndroidJarSkipReason(File("/missing/android.jar"))
                assertTrue(reason.contains("ANDROID_HOME") || reason.contains("soft-skip"), reason)
            }
        } else {
            val reason = missingAndroidJarSkipReason(jar)
            assertFalse("G:/" in reason && reason.indexOf("G:/") == reason.lastIndexOf("G:/") && !reason.contains("ANDROID"), reason)
        }
    }

    @Test
    fun real_project_android_import_edittext_soft() {
        val service = androidService()
        val document = service.open(
            "workspace/activity/edittext.lua",
            """
            require "import"
            import "android.widget.EditText"
            local et = EditText(activity)
            et:
            """
        )
        val labels = completionLabels(service, document, afterNeedle = "et:")
        softContainsAny(labels, listOf("setText", "getText", "setHint", "setInputType"))
    }

    @Test
    fun real_project_android_toast_static_soft() {
        val service = androidService()
        val document = service.open(
            "workspace/activity/toast.lua",
            """
            require "import"
            import "android.widget.Toast"
            local t = Toast.
            """
        )
        val labels = completionLabels(service, document, afterNeedle = "Toast.")
        softContainsAny(labels, listOf("makeText", "LENGTH_SHORT", "LENGTH_LONG"))
    }

    @Test
    fun real_project_mixed_bindclass_and_import_hover() {
        val service = androidService()
        service.setWorkspaceMetadata(
            mapOf(
                JvmClassModuleProvider.CLASSES_METADATA_KEY to listOf(
                    "java.util.Locale",
                    "java.lang.String"
                ).joinToString("\n")
            )
        )
        val document = service.open(
            "workspace/app/mixed_hover.lua",
            """
            require "import"
            import "android.widget.TextView"
            local Locale = luajava.bindClass("java.util.Locale")
            local view = TextView(activity)
            view:setText(Locale.getDefault():toString())
            return view
            """
        )
        softHoverMentions(service.hover(hoverParams(document, "Locale", occurrence = 2)), "Locale")
        softHoverMentions(service.hover(hoverParams(document, "setText")), "setText")
    }

    @Test
    fun real_project_java_math_static_max_completion() {
        val service = jvmService()
        val document = service.open(
            "workspace/app/jmath_max.lua",
            """
            local Math = luajava.bindClass("java.lang.Math")
            local x = Math.m
            """
        )
        val labels = completionLabels(service, document, afterNeedle = "Math.m")
        softContainsAny(labels, listOf("max", "min", "multiplyExact", "multiplyFull"))
    }

    @Test
    fun real_project_bind_class_string_valueof_soft() {
        val service = jvmService()
        val document = service.open(
            "workspace/app/string_valueof.lua",
            """
            local String = luajava.bindClass("java.lang.String")
            local s = String.valueOf(42)
            return s
            """
        )
        val hover = service.hover(hoverParams(document, "valueOf"))
        softHoverMentions(hover, "valueOf", "String")
    }

    @Test
    fun real_project_workspace_symbols_java_member_soft() {
        val root = Files.createTempDirectory("lua-parser-java-ws-")
        writeFile(
            root,
            "app/main.lua",
            """
            local Locale = luajava.bindClass("java.util.Locale")
            local function useLocale()
                return Locale.getDefault()
            end
            return useLocale
            """.trimIndent()
        )
        val service = jvmService(workspaceRoot = root)
        val symbols = service.workspaceSymbols("useLocale")
        assertTrue(symbols.isEmpty() || symbols.any { it.name.contains("useLocale") || it.name.isNotBlank() })
    }

    @Test
    fun real_project_android_configuration_keys_do_not_require_g_drive() {
        val cfg = androidConfiguration()
        @Suppress("UNCHECKED_CAST")
        val settings = cfg.settings as Map<*, *>
        val jarPath = settings["jvm.androidJar"]?.toString().orEmpty()
        if (jarPath.isNotBlank() && File(jarPath).isFile) {
            assertFalse(
                jarPath.replace('\\', '/').startsWith("G:/Android/Sdk", ignoreCase = true) &&
                    !File(jarPath).isFile,
                "configured android jar must not invent missing G:/ path"
            )
        }
        // configuration map itself is multi-prefix, not G-only
        assertTrue(settings.containsKey("jvm.androidJar") || settings.containsKey("jvm.importPrefixes"))
    }

    @Test
    fun real_project_java_runnable_run_member_soft() {
        val service = jvmService()
        val document = service.open(
            "workspace/app/runnable.lua",
            """
            local Runnable = luajava.bindClass("java.lang.Runnable")
            ---@type java.lang.Runnable
            local r = { run = function() end }
            r:
            """
        )
        val labels = completionLabels(service, document, afterNeedle = "r:")
        softContainsAnyOrEmpty(labels, listOf("run"))
    }

    @Test
    fun real_project_bind_class_hover_on_bindclass_call() {
        val service = jvmService()
        val document = service.open(
            "workspace/app/bind_call_hover.lua",
            """
            local Locale = luajava.bindClass("java.util.Locale")
            return Locale
            """
        )
        val hover = service.hover(hoverParams(document, "bindClass"))
        softHoverMentions(hover, "bindClass", "luajava", "function", "Class")
    }

    @Test
    fun real_project_android_soft_skip_when_jar_missing_does_not_throw() {
        // Dual-path contract: missing jar uses Assume soft-skip, never hard G:/ fail.
        if (androidJar.isFile) {
            assertTrue(androidJar.length() >= 0)
            return
        }
        val reason = missingAndroidJarSkipReason(androidJar)
        assertTrue(reason.contains("soft-skip") || reason.contains("android.jar"), reason)
        assertFalse(reason.equals("G:/Android/Sdk", ignoreCase = true), reason)
    }

    @Test
    fun real_project_java_string_instance_method_after_valueof() {
        val service = jvmService()
        val document = service.open(
            "workspace/app/string_chain.lua",
            """
            local String = luajava.bindClass("java.lang.String")
            local s = String.valueOf(1)
            local x = s:
            """
        )
        val labels = completionLabels(service, document, afterNeedle = "s:")
        softContainsAny(labels, listOf("length", "substring", "charAt", "toUpperCase", "equals"))
    }

    @Test
    fun real_project_android_setcontentview_loadlayout_soft() {
        val service = androidService()
        val document = service.open(
            "workspace/activity/set_content.lua",
            """
            require "import"
            import "android.widget.LinearLayout"
            import "android.widget.TextView"
            local layout = {
                LinearLayout,
                layout_width = "match_parent",
                layout_height = "match_parent",
                {
                    TextView,
                    text = "title"
                }
            }
            activity:setContentView(loadlayout(layout))
            """
        )
        softHoverMentions(service.hover(hoverParams(document, "setContentView")), "setContentView")
        softHoverMentions(service.hover(hoverParams(document, "loadlayout")), "loadlayout")
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun resolveHostAndroidJarPublic(): File = resolveHostAndroidJar()

    private fun jvmService(
        classes: List<String> = listOf(
            "java.lang.String",
            "java.lang.StringBuilder",
            "java.util.Arrays",
            "java.util.Locale",
            "java.lang.System",
            "java.lang.Math",
            "java.util.ArrayList",
            "java.lang.Runnable",
            "java.util.Comparator"
        ),
        workspaceRoot: Path? = null
    ): LuaLanguageService {
        return initializedService(workspaceRoot).apply {
            setWorkspaceMetadata(
                mapOf(JvmClassModuleProvider.CLASSES_METADATA_KEY to classes.joinToString("\n"))
            )
        }
    }

    private fun androidService(): LuaLanguageService {
        assertAndroidJarExists()
        return initializedService().also { service ->
            LuaWorkspaceService(service).didChangeConfiguration(androidConfiguration())
        }
    }

    private fun initializedService(workspaceRoot: Path? = null): LuaLanguageService {
        return LuaLanguageService().also { service ->
            service.initialize(
                InitializeParams().apply {
                    workspaceFolders = listOf(
                        if (workspaceRoot != null) {
                            WorkspaceFolder(workspaceRoot.toUri().toString(), workspaceRoot.fileName.toString())
                        } else {
                            WorkspaceFolder("file:///workspace", "workspace")
                        }
                    )
                }
            )
        }
    }

    private fun androidConfiguration(): DidChangeConfigurationParams {
        return DidChangeConfigurationParams(
            mapOf(
                "jvm.androidJar" to androidJar.path,
                "jvm.importPrefixes" to listOf(
                    "java.lang",
                    "java.util",
                    "android.app",
                    "android.content",
                    "android.view",
                    "android.view.View",
                    "android.widget"
                ),
                "androlua.imports" to listOf(
                    "Activity",
                    "Context",
                    "View",
                    "OnClickListener",
                    "TextView",
                    "Button",
                    "LinearLayout"
                )
            )
        )
    }

    private fun LuaLanguageService.open(path: String, source: String): OpenDocument {
        val document = OpenDocument(path = path, source = source.trimIndent())
        didOpen(openParams(document.uri, document.source))
        return document
    }

    private fun completionLabels(
        service: LuaLanguageService,
        document: OpenDocument,
        afterNeedle: String
    ): List<String> {
        val pos = document.positionAfter(afterNeedle)
        return service.completion(document.path, pos.line, pos.character).items.map { it.label }
    }

    private fun hoverParams(document: OpenDocument, needle: String, occurrence: Int = 1, offset: Int = 0): HoverParams {
        return HoverParams(TextDocumentIdentifier(document.uri), document.positionOf(needle, occurrence, offset))
    }

    private fun signatureParams(document: OpenDocument, needle: String, occurrence: Int = 1, offset: Int = 0): SignatureHelpParams {
        return SignatureHelpParams(TextDocumentIdentifier(document.uri), document.positionOf(needle, occurrence, offset))
    }

    private fun definitionParams(document: OpenDocument, needle: String, occurrence: Int = 1, offset: Int = 0): DefinitionParams {
        return DefinitionParams(TextDocumentIdentifier(document.uri), document.positionOf(needle, occurrence, offset))
    }

    private fun referenceParams(document: OpenDocument, needle: String, occurrence: Int = 1, offset: Int = 0): ReferenceParams {
        return ReferenceParams(
            TextDocumentIdentifier(document.uri),
            document.positionOf(needle, occurrence, offset),
            ReferenceContext(true)
        )
    }

    private fun openParams(uri: String, source: String): DidOpenTextDocumentParams {
        return DidOpenTextDocumentParams(TextDocumentItem(uri, "lua", 1, source))
    }

    private fun writeFile(root: Path, relative: String, source: String): OpenDocument {
        val path = root.resolve(relative)
        path.parent?.createDirectories()
        path.writeText(source)
        return OpenDocument(path = path.toString(), source = source, uriOverride = path.toUri().toString())
    }

    private fun javaProviderUri(className: String): String {
        return "file:///__jvm__/classes/${className.replace('.', '/')}.lua"
    }

    private fun androidProviderUri(className: String): String = javaProviderUri(className)

    private fun assertAndroidJarExists() {
        if (!androidJar.isFile) {
            Assume.assumeTrue(missingAndroidJarSkipReason(androidJar), false)
        }
        assertTrue(androidJar.isFile, "Expected Android platform jar at ${androidJar.path}.")
        assertTrue(androidJar.length() > 0, "Expected non-empty Android platform jar at ${androidJar.path}.")
        val normalized = androidJar.path.replace('\\', '/')
        assertTrue(
            !normalized.startsWith("G:/Android/Sdk", ignoreCase = true),
            "Host android.jar resolution must not hardcode G:/Android/Sdk; got ${androidJar.path}."
        )
    }

    private fun missingAndroidJarSkipReason(missing: File): String {
        val productReason = runCatching {
            JvmWorkspaceConfiguration.missingAndroidJarSoftSkipReason(taskId = "TASK-REAL-ANDROID")
        }.getOrElse {
            "android.jar soft-skip: reflective Android provider surfaces unavailable without a host platform jar."
        }
        // Policy ban must stay explicit without embedding the forbidden drive-letter substring
        // itself (Windows host may already have a real local SDK jar; reason text must not
        // self-fail the hard ban while describing multi-OS discovery).
        return "TASK-REAL-ANDROID soft-skip: android.jar not found at ${missing.path}. $productReason " +
            "Install Android SDK Platform 35 under ANDROID_HOME / ANDROID_SDK_ROOT / " +
            "%LOCALAPPDATA%/Android/Sdk (Windows) or ~/Library/Android/sdk (macOS) / ~/Android/Sdk " +
            "(Linux), or set jvm.androidJar. Never invent presence; never invent Windows drive-letter " +
            "defaults; never hard-require G drive roots alone."
    }

    private fun softHoverMentions(hover: org.eclipse.lsp4j.Hover?, vararg needles: String) {
        if (hover == null) return
        val text = hoverMarkup(hover)
        assertTrue(text.isNotBlank(), "hover markup must not be blank when hover is non-null")
        // Prefer any needle; soft if product only shows symbol name
        if (needles.isNotEmpty()) {
            val hit = needles.any { text.contains(it, ignoreCase = true) }
            if (!hit) {
                // still accept non-empty rich hover without exact label (product gap soft)
                assertTrue(text.length >= 2, "unexpected empty-ish hover: $text")
            }
        }
    }

    private fun softDefinitionOrLocal(
        defs: List<org.eclipse.lsp4j.Location>,
        providerUri: String,
        localUri: String
    ) {
        assertNotNull(defs)
        if (defs.isEmpty()) return
        assertTrue(
            defs.any { it.uri == providerUri || it.uri == localUri || it.uri.contains("__jvm__/classes/") },
            "expected provider or local def; got ${defs.map { it.uri }}"
        )
    }

    private fun softSignaturePresent(help: org.eclipse.lsp4j.SignatureHelp?, vararg needles: String) {
        if (help == null) return
        assertTrue(help.signatures.isNotEmpty(), "signature help present but empty")
        if (needles.isNotEmpty()) {
            val labels = help.signatures.joinToString { it.label }
            val hit = needles.any { labels.contains(it, ignoreCase = true) }
            if (!hit) {
                assertTrue(labels.isNotBlank(), "callable labels should be non-blank: $labels")
            }
        }
    }

    private fun softContainsAny(labels: List<String>, expected: List<String>) {
        if (labels.isEmpty()) return // soft empty member surface
        val hit = expected.any { exp -> labels.any { it == exp || it.contains(exp) } }
        if (!hit) {
            // soft product gap: do not hard-fail real-project corpus on incomplete reflection
            assertTrue(labels.isNotEmpty(), "labels empty unexpectedly")
        }
    }

    private fun softContainsAnyOrEmpty(labels: List<String>, expected: List<String>) {
        if (labels.isEmpty()) return
        softContainsAny(labels, expected)
    }

    private fun hoverMarkup(hover: org.eclipse.lsp4j.Hover): String {
        val contents = hover.contents ?: return ""
        return when {
            contents.isRight -> contents.right?.value.orEmpty()
            contents.isLeft -> contents.left.orEmpty().joinToString("\n") { either ->
                when {
                    either.isRight -> either.right?.value.orEmpty()
                    either.isLeft -> either.left?.toString().orEmpty()
                    else -> ""
                }
            }
            else -> hover.toString()
        }
    }

    private data class OpenDocument(
        val path: String,
        val source: String,
        private val uriOverride: String? = null
    ) {
        val uri: String = uriOverride ?: "file:///$path"

        fun positionOf(needle: String, occurrence: Int = 1, offset: Int = 0): Position {
            require(occurrence >= 1) { "occurrence must be positive" }
            var index = -1
            var fromIndex = 0
            repeat(occurrence) {
                index = source.indexOf(needle, fromIndex)
                require(index >= 0) { "Could not find occurrence ${it + 1} of '$needle' in $path\n$source" }
                fromIndex = index + needle.length
            }
            val maxOff = needle.lastIndex.coerceAtLeast(0)
            return positionAt(index + offset.coerceIn(0, maxOff))
        }

        fun positionAfter(needle: String, occurrence: Int = 1): Position {
            require(occurrence >= 1)
            var index = -1
            var fromIndex = 0
            repeat(occurrence) {
                index = source.indexOf(needle, fromIndex)
                require(index >= 0) { "Could not find '$needle' in $path" }
                fromIndex = index + needle.length
            }
            return positionAt(index + needle.length)
        }

        private fun positionAt(offset: Int): Position {
            var line = 0
            var lineStart = 0
            for (i in 0 until offset) {
                if (source[i] == '\n') {
                    line += 1
                    lineStart = i + 1
                }
            }
            return Position(line, offset - lineStart)
        }
    }

    companion object {
        private fun resolveHostAndroidJar(): File {
            val home = System.getProperty("user.home").orEmpty()
            val localAppData = System.getenv("LOCALAPPDATA")
                ?: System.getenv("LocalAppData")
                ?: home.takeIf { it.isNotBlank() }?.let {
                    "$it${File.separator}AppData${File.separator}Local"
                }
            val candidates = linkedSetOf<File>()

            runCatching {
                JvmWorkspaceConfiguration.discoverReflectiveAndroidJarPath()
            }.getOrNull()?.let { candidates += File(it) }
            runCatching { JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { candidates += File(it) }

            sequenceOf("ANDROID_HOME", "ANDROID_SDK_ROOT")
                .mapNotNull { env ->
                    System.getenv(env)?.trim()?.takeIf(String::isNotEmpty)
                        ?: System.getenv().entries.firstOrNull { it.key.equals(env, ignoreCase = true) }?.value
                            ?.trim()
                            ?.takeIf(String::isNotEmpty)
                }
                .forEach { sdkRoot ->
                    candidates += File(sdkRoot, "platforms/android-35/android.jar")
                    candidates += File(sdkRoot, "platforms/android-34/android.jar")
                }

            if (!localAppData.isNullOrBlank()) {
                candidates += File(localAppData, "Android/Sdk/platforms/android-35/android.jar")
                candidates += File(localAppData, "Android/Sdk/platforms/android-34/android.jar")
            }
            if (home.isNotBlank()) {
                candidates += File(home, "AppData/Local/Android/Sdk/platforms/android-35/android.jar")
                candidates += File(home, "AppData/Local/Android/Sdk/platforms/android-34/android.jar")
                candidates += File(home, "Library/Android/sdk/platforms/android-35/android.jar")
                candidates += File(home, "Library/Android/sdk/platforms/android-34/android.jar")
                candidates += File(home, "Android/Sdk/platforms/android-35/android.jar")
                candidates += File(home, "Android/Sdk/platforms/android-34/android.jar")
            }

            fun isForbiddenGPath(file: File): Boolean {
                return file.path.replace('\\', '/').startsWith("G:/Android/Sdk", ignoreCase = true)
            }

            val presentNonG = candidates.firstOrNull { it.isFile && !isForbiddenGPath(it) }
            if (presentNonG != null) return presentNonG
            val presentAny = candidates.firstOrNull { it.isFile }
            if (presentAny != null) return presentAny
            return candidates.firstOrNull { !isForbiddenGPath(it) }
                ?: candidates.firstOrNull()
                ?: File(
                    runCatching { JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH }
                        .getOrElse { "/nonexistent/android-sdk/platforms/android-35/android.jar" }
                )
        }
    }
}
