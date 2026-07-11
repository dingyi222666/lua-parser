package semantic.interop

import io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.api.CompletionItem
import io.github.dingyi222666.luaparser.semantic.api.CompletionItemKind
import io.github.dingyi222666.luaparser.semantic.api.Diagnostic
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import java.io.File
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JavaChainedCallTddTest {
    private val androidJar = File(JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH)

    /**
     * TASK-589 product hard-lock: resource_reflection_fixture_chained_static_and_instance_calls_are_typed
     * style corpus. Intermediate static factory / instance returns must stay reflection-backed
     * (never invent chain types without reflected signatures).
     */
    @Test
    fun resource_reflection_fixture_chained_static_and_instance_calls_are_typed() {
        val harness = jvmHarness(
            "main.lua" to """
                local Arrays = luajava.bindClass("java.util.Arrays")
                local Locale = luajava.bindClass("java.util.Locale")
                local Integer = luajava.bindClass("java.lang.Integer")

                local values = Arrays.asList("alpha", "beta")
                local count = values.size()
                local locales = Locale.getAvailableLocales()
                local parsed = Integer.parseInt("42")
                local firstTag = Locale.forLanguageTag("en-US").toLanguageTag()

                return values, count, locales, parsed, firstTag
            """.trimIndent(),
            classes = setOf("java.util.List")
        )

        assertHoverType(harness, "count", "number", occurrence = 2)
        val localesHover = assertNotNull(
            harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "locales", 2))
        )
        val localesType = localesHover.typeInfo?.displayName.orEmpty()
        assertTrue(
            "java.util.Locale[]" in localesType || localesType.contains("Locale"),
            "Expected Locale[] (or Locale array surface) for getAvailableLocales chain; got $localesType"
        )
        assertNotUnknown(localesType)
        assertHoverType(harness, "parsed", "number", occurrence = 2)
        assertHoverType(harness, "firstTag", "string", occurrence = 2)
        // Intermediate static factory result used by instance chain must remain typed.
        val tagHover = assertNotNull(
            harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "toLanguageTag"))
        )
        assertEquals(SymbolKind.METHOD, tagHover.symbol?.kind)
        assertCallable(tagHover.typeInfo?.displayName)
    }

    @Test
    fun file_parent_file_name_chain_returns_string() {
        val harness = jvmHarness(
            "main.lua" to """
                local File = luajava.bindClass("java.io.File")
                local name = File("src/main/kotlin").getParentFile().getName()
                return name
            """.trimIndent()
        )

        assertHoverType(harness, "name", "string", occurrence = 2)
        assertNoDiagnostics(harness)
    }

    @Test
    fun file_parent_file_name_properties_return_string() {
        val harness = jvmHarness(
            "main.lua" to """
                local File = luajava.bindClass("java.io.File")
                local name = File("src/main/kotlin").parentFile.name
                return name
            """.trimIndent()
        )

        assertHoverType(harness, "name", "string", occurrence = 2)
        assertNoDiagnostics(harness)
    }

    @Test
    fun java_bean_properties_do_not_hide_direct_methods() {
        val harness = jvmHarness(
            "main.lua" to """
                local File = luajava.bindClass("java.io.File")
                local parent = File("src/main/kotlin").parentFile
                local name = parent.name
                local methodParent = File("src/main/kotlin").getParentFile()
                local methodName = methodParent.getName()
                return name, methodName
            """.trimIndent()
        )

        assertHoverType(harness, "name", "string", occurrence = 2)
        assertHoverType(harness, "methodName", "string", occurrence = 2)
        val hover = assertNotNull(harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "getName")))
        assertEquals(SymbolKind.METHOD, hover.symbol?.kind)
        assertCallable(hover.typeInfo?.displayName)
        assertNoDiagnostics(harness)
    }

    @Test
    fun boolean_is_getter_surfaces_as_lua_property() {
        val harness = jvmHarness(
            "main.lua" to """
                local ArrayList = luajava.bindClass("java.util.ArrayList")
                local list = ArrayList()
                local empty = list.empty
                local methodEmpty = list.isEmpty()
                return empty, methodEmpty
            """.trimIndent()
        )

        assertHoverType(harness, "empty", "boolean", occurrence = 2)
        assertHoverType(harness, "methodEmpty", "boolean", occurrence = 2)
        assertNoDiagnostics(harness)
    }

    @Test
    fun getter_setter_pair_surfaces_as_lua_readable_property() {
        val harness = jvmHarness(
            "main.lua" to """
                local Bean = luajava.bindClass("semantic.interop.JavaChainedCallTddTest${'$'}MutableJavaBean")
                local bean = Bean()
                local title = bean.title
                local methodTitle = bean.getTitle()
                bean.title = "assigned"
                bean.setTitle("updated")
                return title, methodTitle
            """.trimIndent()
        )

        assertHoverType(harness, "title", "string", occurrence = 2)
        assertHoverType(harness, "methodTitle", "string", occurrence = 2)
        assertNoDiagnostics(harness)
    }

    @Test
    fun listener_setter_accepts_lua_function_callback() {
        val harness = jvmHarness(
            "main.lua" to """
                local Holder = luajava.bindClass("semantic.interop.JavaChainedCallTddTest${'$'}ListenerHolder")
                local holder = Holder()
                local assigned = holder.setValueListener(function(value)
                    return nil
                end)
                return assigned
            """.trimIndent()
        )

        assertHoverType(harness, "assigned", "nil", occurrence = 2)
        assertNoDiagnostics(harness)
    }

    @Test
    fun listener_setter_accepts_lua_table_with_matching_callback_method() {
        val harness = jvmHarness(
            "main.lua" to """
                local Holder = luajava.bindClass("semantic.interop.JavaChainedCallTddTest${'$'}ListenerHolder")
                local holder = Holder()
                local assigned = holder.setValueListener({
                    onValue = function(value)
                        return nil
                    end
                })
                return assigned
            """.trimIndent()
        )

        assertHoverType(harness, "assigned", "nil", occurrence = 2)
        assertNoDiagnostics(harness)
    }

    @Test
    fun non_listener_interface_setter_callback_remains_unknown() {
        val harness = jvmHarness(
            "main.lua" to """
                local Holder = luajava.bindClass("semantic.interop.JavaChainedCallTddTest${'$'}ListenerHolder")
                local holder = Holder()
                local assigned = holder.setAction(function()
                    return nil
                end)
                return assigned
            """.trimIndent()
        )

        assertHoverTypeAllowingUnknown(harness, "assigned", "unknown", occurrence = 2)
    }

    @Test
    fun string_array_parameter_accepts_compatible_lua_table_literal() {
        val harness = jvmHarness(
            "main.lua" to """
                local Holder = luajava.bindClass("semantic.interop.JavaChainedCallTddTest${'$'}ContainerHolder")
                local holder = Holder()
                local count = holder.setStringArray({ "a", "b" })
                return count
            """.trimIndent()
        )

        assertHoverType(harness, "count", "number", occurrence = 2)
        assertNoDiagnostics(harness)
    }

    @Test
    fun string_list_parameter_accepts_compatible_lua_table_literal() {
        val harness = jvmHarness(
            "main.lua" to """
                local Holder = luajava.bindClass("semantic.interop.JavaChainedCallTddTest${'$'}ContainerHolder")
                local holder = Holder()
                local size = holder.setStringList({ "a", "b" })
                return size
            """.trimIndent()
        )

        assertHoverType(harness, "size", "number", occurrence = 2)
        assertNoDiagnostics(harness)
    }

    @Test
    fun string_map_parameter_accepts_compatible_lua_table_literal() {
        val harness = jvmHarness(
            "main.lua" to """
                local Holder = luajava.bindClass("semantic.interop.JavaChainedCallTddTest${'$'}ContainerHolder")
                local holder = Holder()
                local size = holder.setStringMap({ a = "one", b = "two" })
                return size
            """.trimIndent()
        )

        assertHoverType(harness, "size", "number", occurrence = 2)
        assertNoDiagnostics(harness)
    }

    @Test
    fun string_array_parameter_rejects_mixed_or_incompatible_lua_table_literal() {
        val harness = jvmHarness(
            "main.lua" to """
                local Holder = luajava.bindClass("semantic.interop.JavaChainedCallTddTest${'$'}ContainerHolder")
                local holder = Holder()
                local count = holder.setStringArray({ a = "one", b = "two" })
                return count
            """.trimIndent()
        )

        assertHoverTypeAllowingUnknown(harness, "count", "unknown", occurrence = 2)
    }

    @Test
    fun raw_object_list_parameter_does_not_claim_precise_table_conversion() {
        val harness = jvmHarness(
            "main.lua" to """
                local Holder = luajava.bindClass("semantic.interop.JavaChainedCallTddTest${'$'}ContainerHolder")
                local holder = Holder()
                local size = holder.setRawList({ "a", "b" })
                return size
            """.trimIndent()
        )

        assertHoverTypeAllowingUnknown(harness, "size", "unknown", occurrence = 2)
    }

    @Test
    fun overloaded_getter_does_not_create_property_alias() {
        val harness = jvmHarness(
            "main.lua" to """
                local Bean = luajava.bindClass("semantic.interop.JavaChainedCallTddTest${'$'}OverloadedGetterBean")
                local bean = Bean()
                local direct = bean.getCode()
                local property = bean.code
                return direct, property
            """.trimIndent()
        )

        assertHoverType(harness, "direct", "string", occurrence = 2)
        assertInvalidMemberDiagnostic(harness, "code")
    }

    @Test
    fun write_only_setter_does_not_create_readable_property_alias() {
        val harness = jvmHarness(
            "main.lua" to """
                local Bean = luajava.bindClass("semantic.interop.JavaChainedCallTddTest${'$'}WriteOnlyBean")
                local bean = Bean()
                bean.setToken("secret")
                local token = bean.token
                return token
            """.trimIndent()
        )

        assertInvalidMemberDiagnostic(harness, "token")
    }

    @Test
    fun mixed_boolean_getter_names_do_not_create_property_alias() {
        val harness = jvmHarness(
            "main.lua" to """
                local Bean = luajava.bindClass("semantic.interop.JavaChainedCallTddTest${'$'}MixedBooleanGetterBean")
                local bean = Bean()
                local getterValue = bean.getActive()
                local isValue = bean.isActive()
                local property = bean.active
                return getterValue, isValue, property
            """.trimIndent()
        )

        assertHoverType(harness, "getterValue", "boolean", occurrence = 2)
        assertHoverType(harness, "isValue", "string", occurrence = 2)
        assertInvalidMemberDiagnostic(harness, "active")
    }

    @Test
    fun file_parent_file_chain_intermediate_hover_reports_file() {
        val harness = jvmHarness(
            "main.lua" to """
                local File = luajava.bindClass("java.io.File")
                local parent = File("src/main/kotlin").getParentFile()
                return parent
            """.trimIndent()
        )

        assertHoverType(harness, "parent", "java.io.File", occurrence = 2)
    }

    @Test
    fun file_absolute_file_parent_name_chain_returns_string() {
        val harness = jvmHarness(
            "main.lua" to """
                local File = luajava.bindClass("java.io.File")
                local name = File("src/main/kotlin/Main.kt").getAbsoluteFile().getParentFile().getName()
                return name
            """.trimIndent()
        )

        assertHoverType(harness, "name", "string", occurrence = 2)
        assertNoDiagnostics(harness)
    }

    @Test
    fun file_to_path_to_absolute_path_chain_reports_path() {
        val harness = jvmHarness(
            "main.lua" to """
                local File = luajava.bindClass("java.io.File")
                local absolute = File("build.gradle.kts").toPath().toAbsolutePath()
                return absolute
            """.trimIndent(),
            classes = setOf("java.nio.file.Path")
        )

        assertHoverType(harness, "absolute", "java.nio.file.Path", occurrence = 2)
    }

    @Test
    fun file_to_path_file_name_chain_returns_path() {
        val harness = jvmHarness(
            "main.lua" to """
                local File = luajava.bindClass("java.io.File")
                local fileName = File("build.gradle.kts").toPath().getFileName()
                return fileName
            """.trimIndent(),
            classes = setOf("java.nio.file.Path")
        )

        assertHoverType(harness, "fileName", "java.nio.file.Path", occurrence = 2)
    }

    @Test
    fun paths_get_static_factory_to_file_get_name_returns_string() {
        val harness = jvmHarness(
            "main.lua" to """
                local Paths = luajava.bindClass("java.nio.file.Paths")
                local name = Paths.get("build.gradle.kts").toFile().getName()
                return name
            """.trimIndent(),
            classes = setOf("java.nio.file.Path", "java.io.File")
        )

        assertHoverType(harness, "name", "string", occurrence = 2)
        assertNoDiagnostics(harness)
    }

    @Test
    fun paths_get_static_factory_parent_chain_returns_path() {
        val harness = jvmHarness(
            "main.lua" to """
                local Paths = luajava.bindClass("java.nio.file.Paths")
                local parent = Paths.get("src", "jvmTest").toAbsolutePath().getParent()
                return parent
            """.trimIndent(),
            classes = setOf("java.nio.file.Path")
        )

        assertHoverType(harness, "parent", "java.nio.file.Path", occurrence = 2)
    }

    @Test
    fun path_of_static_factory_to_uri_chain_reports_uri() {
        val harness = jvmHarness(
            "main.lua" to """
                local Path = luajava.bindClass("java.nio.file.Path")
                local uri = Path.of("build.gradle.kts").toUri()
                return uri
            """.trimIndent(),
            classes = setOf("java.net.URI")
        )

        assertHoverType(harness, "uri", "java.net.URI", occurrence = 2)
    }

    @Test
    fun locale_for_language_tag_static_factory_chain_returns_string() {
        val harness = jvmHarness(
            "main.lua" to """
                local Locale = luajava.bindClass("java.util.Locale")
                local displayName = Locale.forLanguageTag("en-US").getDisplayName()
                return displayName
            """.trimIndent()
        )

        assertHoverType(harness, "displayName", "string", occurrence = 2)
    }

    @Test
    fun big_decimal_value_of_static_factory_chain_returns_string() {
        val harness = jvmHarness(
            "main.lua" to """
                local BigDecimal = luajava.bindClass("java.math.BigDecimal")
                local plain = BigDecimal.valueOf(10).stripTrailingZeros().toPlainString()
                return plain
            """.trimIndent()
        )

        assertHoverType(harness, "plain", "string", occurrence = 2)
    }

    @Test
    fun uuid_from_string_static_factory_to_string_returns_string() {
        val harness = jvmHarness(
            "main.lua" to """
                local UUID = luajava.bindClass("java.util.UUID")
                local text = UUID.fromString("00000000-0000-0000-0000-000000000000").toString()
                return text
            """.trimIndent()
        )

        assertHoverType(harness, "text", "string", occurrence = 2)
    }

    @Test
    fun calendar_get_instance_static_factory_chain_returns_number() {
        val harness = jvmHarness(
            "main.lua" to """
                local Calendar = luajava.bindClass("java.util.Calendar")
                local millis = Calendar.getInstance().getTimeInMillis()
                return millis
            """.trimIndent()
        )

        assertHoverType(harness, "millis", "number", occurrence = 2)
    }

    @Test
    fun system_get_properties_static_to_instance_chain_returns_number() {
        val harness = jvmHarness(
            "main.lua" to """
                local System = luajava.bindClass("java.lang.System")
                local size = System.getProperties().size()
                return size
            """.trimIndent(),
            classes = setOf("java.util.Properties")
        )

        // occurrence 2 is the zero-arg method identifier `.size()` (callable surface);
        // occurrence 3 is the return local that carries the chain call result type.
        assertHoverType(harness, "size", "number", occurrence = 3)
    }

    @Test
    fun static_java_bean_property_chain_returns_number() {
        val harness = jvmHarness(
            "main.lua" to """
                local System = luajava.bindClass("java.lang.System")
                local size = System.properties.size()
                return size
            """.trimIndent(),
            classes = setOf("java.util.Properties")
        )

        assertHoverType(harness, "properties", "java.util.Properties")
        // Avoid the member identifier collision on `.size()`; hover the return local.
        assertHoverType(harness, "size", "number", occurrence = 3)
        assertNoDiagnostics(harness)
    }

    @Test
    fun thread_current_thread_static_to_instance_chain_returns_string() {
        val harness = jvmHarness(
            "main.lua" to """
                local Thread = luajava.bindClass("java.lang.Thread")
                local name = Thread.currentThread().getName()
                return name
            """.trimIndent()
        )

        assertHoverType(harness, "name", "string", occurrence = 2)
    }

    @Test
    fun time_zone_get_default_static_to_instance_chain_returns_string() {
        val harness = jvmHarness(
            "main.lua" to """
                local TimeZone = luajava.bindClass("java.util.TimeZone")
                local id = TimeZone.getDefault().getID()
                return id
            """.trimIndent()
        )

        assertHoverType(harness, "id", "string", occurrence = 2)
    }

    @Test
    fun runtime_get_runtime_static_to_instance_chain_returns_number() {
        val harness = jvmHarness(
            "main.lua" to """
                local Runtime = luajava.bindClass("java.lang.Runtime")
                local processors = Runtime.getRuntime().availableProcessors()
                return processors
            """.trimIndent()
        )

        assertHoverType(harness, "processors", "number", occurrence = 2)
    }

    @Test
    fun charset_default_charset_static_to_instance_chain_returns_string() {
        val harness = jvmHarness(
            "main.lua" to """
                local Charset = luajava.bindClass("java.nio.charset.Charset")
                local name = Charset.defaultCharset().name()
                return name
            """.trimIndent()
        )

        // occurrence 2 is Charset.name() method; occurrence 3 is the return local result.
        assertHoverType(harness, "name", "string", occurrence = 3)
    }

    @Test
    fun string_builder_append_string_overload_return_allows_to_string_chain() {
        val harness = jvmHarness(
            "main.lua" to """
                local StringBuilder = luajava.bindClass("java.lang.StringBuilder")
                local text = StringBuilder().append("prefix").append("suffix").toString()
                return text
            """.trimIndent()
        )

        assertHoverType(harness, "text", "string", occurrence = 2)
    }

    @Test
    fun string_builder_append_number_overload_return_allows_to_string_chain() {
        val harness = jvmHarness(
            "main.lua" to """
                local StringBuilder = luajava.bindClass("java.lang.StringBuilder")
                local text = StringBuilder().append("count=").append(42).toString()
                return text
            """.trimIndent()
        )

        assertHoverType(harness, "text", "string", occurrence = 2)
    }

    @Test
    fun string_builder_insert_overload_return_allows_reverse_chain() {
        val harness = jvmHarness(
            "main.lua" to """
                local StringBuilder = luajava.bindClass("java.lang.StringBuilder")
                local text = StringBuilder("abc").insert(1, "x").reverse().toString()
                return text
            """.trimIndent()
        )

        assertHoverType(harness, "text", "string", occurrence = 2)
    }

    @Test
    fun big_decimal_add_overload_return_allows_scale_chain() {
        val harness = jvmHarness(
            "main.lua" to """
                local BigDecimal = luajava.bindClass("java.math.BigDecimal")
                local scale = BigDecimal.valueOf(1).add(BigDecimal.TEN).scale()
                return scale
            """.trimIndent()
        )

        // occurrence 2 is BigDecimal.scale() method; occurrence 3 is the return local result.
        assertHoverType(harness, "scale", "number", occurrence = 3)
    }

    @Test
    fun completion_after_file_instance_includes_javabean_property_aliases() {
        // Keep local names distinct from member needles so completion is requested on the
        // Java member expression (TASK-177), not the lexical local binding.
        val harness = jvmHarness(
            "main.lua" to """
                local File = luajava.bindClass("java.io.File")
                local result = File("src/main/kotlin").parentFile
                return result
            """.trimIndent()
        )

        // Readable JavaBean aliases are exported as fields without hiding direct getters.
        assertCompletionAt(harness, "parentFile", "parentFile", CompletionItemKind.FIELD)
        assertCompletionAt(harness, "parentFile", "name", CompletionItemKind.FIELD)
        assertCompletionAt(harness, "parentFile", "getParentFile", CompletionItemKind.METHOD)
        assertCompletionAt(harness, "parentFile", "getName", CompletionItemKind.METHOD)
    }

    @Test
    fun completion_after_array_list_includes_boolean_is_getter_alias() {
        val harness = jvmHarness(
            "main.lua" to """
                local ArrayList = luajava.bindClass("java.util.ArrayList")
                local list = ArrayList()
                local result = list.empty
                return result
            """.trimIndent()
        )

        assertCompletionAt(harness, "empty", "empty", CompletionItemKind.FIELD)
        assertCompletionAt(harness, "empty", "isEmpty", CompletionItemKind.METHOD)
    }

    @Test
    fun completion_after_mutable_bean_includes_title_alias_and_direct_methods() {
        val harness = jvmHarness(
            "main.lua" to """
                local Bean = luajava.bindClass("semantic.interop.JavaChainedCallTddTest${'$'}MutableJavaBean")
                local bean = Bean()
                local result = bean.title
                return result
            """.trimIndent()
        )

        assertCompletionAt(harness, "title", "title", CompletionItemKind.FIELD)
        assertCompletionAt(harness, "title", "getTitle", CompletionItemKind.METHOD)
        assertCompletionAt(harness, "title", "setTitle", CompletionItemKind.METHOD)
    }

    @Test
    fun completion_after_static_system_includes_properties_alias() {
        val harness = jvmHarness(
            "main.lua" to """
                local System = luajava.bindClass("java.lang.System")
                local result = System.properties
                return result
            """.trimIndent(),
            classes = setOf("java.util.Properties")
        )

        assertCompletionAt(harness, "properties", "properties", CompletionItemKind.FIELD)
        assertCompletionAt(harness, "properties", "getProperties", CompletionItemKind.METHOD)
    }

    @Test
    fun completion_excludes_overloaded_and_write_only_javabean_aliases() {
        val overloaded = jvmHarness(
            "main.lua" to """
                local Bean = luajava.bindClass("semantic.interop.JavaChainedCallTddTest${'$'}OverloadedGetterBean")
                local bean = Bean()
                local result = bean.code
                return result
            """.trimIndent()
        )
        val overloadedCompletions = overloaded.queries.completions(
            overloaded.path("main.lua"),
            overloaded.positionOf("main.lua", "code")
        )
        assertFalse(
            overloadedCompletions.any { it.label == "code" && it.kind == CompletionItemKind.FIELD },
            "Overloaded getter must not export a readable JavaBean alias; actual: " +
                overloadedCompletions.map { "${it.label}:${it.kind}" }
        )
        assertCompletion(overloadedCompletions, "getCode", CompletionItemKind.METHOD)

        val writeOnly = jvmHarness(
            "main.lua" to """
                local Bean = luajava.bindClass("semantic.interop.JavaChainedCallTddTest${'$'}WriteOnlyBean")
                local bean = Bean()
                local result = bean.token
                return result
            """.trimIndent()
        )
        val writeOnlyCompletions = writeOnly.queries.completions(
            writeOnly.path("main.lua"),
            writeOnly.positionOf("main.lua", "token")
        )
        assertFalse(
            writeOnlyCompletions.any { it.label == "token" && it.kind == CompletionItemKind.FIELD },
            "Write-only setter must not export a readable JavaBean alias; actual: " +
                writeOnlyCompletions.map { "${it.label}:${it.kind}" }
        )
        assertCompletion(writeOnlyCompletions, "setToken", CompletionItemKind.METHOD)
    }

    // TASK-177 note: assignable JavaBean setter write-through remains deferred (TASK-151 read-alias-only).
    // Completions expose readable aliases only; bean.title = value is not modeled as write semantics here.

    @Test
    fun completion_after_file_constructor_chain_includes_get_parent_file() {
        val harness = jvmHarness(
            "main.lua" to """
                local File = luajava.bindClass("java.io.File")
                local parent = File("src/main/kotlin").getParentFile
                return parent
            """.trimIndent()
        )

        assertCompletionAt(harness, "getParentFile", "getParentFile", CompletionItemKind.METHOD)
        assertCompletionAt(harness, "getParentFile", "getName", CompletionItemKind.METHOD)
    }

    @Test
    fun completion_after_get_parent_file_chain_includes_get_name() {
        val harness = jvmHarness(
            "main.lua" to """
                local File = luajava.bindClass("java.io.File")
                local name = File("src/main/kotlin").getParentFile().getName
                return name
            """.trimIndent()
        )

        assertCompletionAt(harness, "getName", "getName", CompletionItemKind.METHOD)
        assertCompletionAt(harness, "getName", "getAbsolutePath", CompletionItemKind.METHOD)
    }

    @Test
    fun completion_after_paths_get_chain_includes_to_file() {
        val harness = jvmHarness(
            "main.lua" to """
                local Paths = luajava.bindClass("java.nio.file.Paths")
                local file = Paths.get("build.gradle.kts").toFile
                return file
            """.trimIndent(),
            classes = setOf("java.nio.file.Path", "java.io.File")
        )

        assertCompletionAt(harness, "toFile", "toFile", CompletionItemKind.METHOD)
        assertCompletionAt(harness, "toFile", "toAbsolutePath", CompletionItemKind.METHOD)
    }

    @Test
    fun completion_after_big_decimal_value_of_chain_includes_to_plain_string() {
        val harness = jvmHarness(
            "main.lua" to """
                local BigDecimal = luajava.bindClass("java.math.BigDecimal")
                local plain = BigDecimal.valueOf(10).toPlainString
                return plain
            """.trimIndent()
        )

        assertCompletionAt(harness, "toPlainString", "toPlainString", CompletionItemKind.METHOD)
        assertCompletionAt(harness, "toPlainString", "stripTrailingZeros", CompletionItemKind.METHOD)
    }

    @Test
    fun completion_after_string_builder_append_chain_includes_reverse() {
        val harness = jvmHarness(
            "main.lua" to """
                local StringBuilder = luajava.bindClass("java.lang.StringBuilder")
                local candidate = StringBuilder().append("abc").reverse
                return candidate
            """.trimIndent()
        )

        assertCompletionAt(harness, "reverse", "reverse", CompletionItemKind.METHOD)
        assertCompletionAt(harness, "reverse", "toString", CompletionItemKind.METHOD)
    }

    @Test
    fun hover_on_chain_method_reports_callable() {
        val harness = jvmHarness(
            "main.lua" to """
                local File = luajava.bindClass("java.io.File")
                local parent = File("src/main/kotlin").getParentFile()
                return parent
            """.trimIndent()
        )

        val hover = assertNotNull(harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "getParentFile")))
        assertEquals(SymbolKind.METHOD, hover.symbol?.kind)
        assertCallable(hover.typeInfo?.displayName)
    }

    @Test
    fun hover_on_static_factory_method_reports_callable() {
        val harness = jvmHarness(
            "main.lua" to """
                local Locale = luajava.bindClass("java.util.Locale")
                local locale = Locale.forLanguageTag("en-US")
                return locale
            """.trimIndent()
        )

        val hover = assertNotNull(harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "forLanguageTag")))
        assertEquals(SymbolKind.METHOD, hover.symbol?.kind)
        assertCallable(hover.typeInfo?.displayName)
    }

    @Test
    fun hover_on_chained_static_to_instance_result_reports_class() {
        val harness = jvmHarness(
            "main.lua" to """
                local Locale = luajava.bindClass("java.util.Locale")
                local locale = Locale.forLanguageTag("en-US").stripExtensions()
                return locale
            """.trimIndent()
        )

        assertHoverType(harness, "locale", "java.util.Locale", occurrence = 2)
    }

    @Test
    fun hover_on_chained_member_reports_declaring_provider_symbol() {
        val harness = jvmHarness(
            "main.lua" to """
                local File = luajava.bindClass("java.io.File")
                local name = File("src/main/kotlin").getParentFile().getName()
                return name
            """.trimIndent()
        )

        val hover = assertNotNull(harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "getName")))
        assertEquals(SymbolKind.METHOD, hover.symbol?.kind)
        assertCallable(hover.typeInfo?.displayName)
    }

    @Test
    fun invalid_member_after_file_constructor_reports_diagnostic() {
        val harness = jvmHarness(
            "main.lua" to """
                local File = luajava.bindClass("java.io.File")
                local value = File("src/main/kotlin").definitelyMissing()
                return value
            """.trimIndent()
        )

        assertInvalidMemberDiagnostic(harness, "definitelyMissing")
    }

    @Test
    fun invalid_member_after_chain_segment_reports_diagnostic() {
        val harness = jvmHarness(
            "main.lua" to """
                local File = luajava.bindClass("java.io.File")
                local value = File("src/main/kotlin").getParentFile().missingName()
                return value
            """.trimIndent()
        )

        assertInvalidMemberDiagnostic(harness, "missingName")
    }

    @Test
    fun invalid_static_factory_chain_member_reports_diagnostic() {
        val harness = jvmHarness(
            "main.lua" to """
                local Paths = luajava.bindClass("java.nio.file.Paths")
                local value = Paths.get("build.gradle.kts").notAPathMethod()
                return value
            """.trimIndent(),
            classes = setOf("java.nio.file.Path")
        )

        assertInvalidMemberDiagnostic(harness, "notAPathMethod")
    }

    @Test
    fun invalid_static_member_before_chain_reports_diagnostic() {
        val harness = jvmHarness(
            "main.lua" to """
                local Locale = luajava.bindClass("java.util.Locale")
                local value = Locale.notAFactory().getDisplayName()
                return value
            """.trimIndent()
        )

        assertInvalidMemberDiagnostic(harness, "notAFactory")
    }

    @Test
    fun invalid_member_after_overloaded_return_reports_diagnostic() {
        val harness = jvmHarness(
            "main.lua" to """
                local StringBuilder = luajava.bindClass("java.lang.StringBuilder")
                local value = StringBuilder().append("abc").notBuilderMember()
                return value
            """.trimIndent()
        )

        assertInvalidMemberDiagnostic(harness, "notBuilderMember")
    }

    @Test
    fun android_uri_parse_builder_chain_returns_string_when_android_jar_available() {
        withAndroidHarness(
            "main.lua" to """
                local Uri = luajava.bindClass("android.net.Uri")
                local path = Uri.parse("content://example/root").buildUpon().path("child").build().getPath()
                return path
            """.trimIndent(),
            classes = setOf("android.net.Uri", "android.net.Uri\$Builder")
        ) { harness ->
            // occurrence 2 is Uri.Builder.path(...); occurrence 3 is the return local result.
            assertHoverType(harness, "path", "string", occurrence = 3)
            assertNoDiagnostics(harness)
        }
    }

    @Test
    fun android_uri_parse_chain_completion_includes_build_upon_when_android_jar_available() {
        withAndroidHarness(
            "main.lua" to """
                local Uri = luajava.bindClass("android.net.Uri")
                local builder = Uri.parse("content://example/root").buildUpon
                return builder
            """.trimIndent(),
            classes = setOf("android.net.Uri", "android.net.Uri\$Builder")
        ) { harness ->
            assertCompletionAt(harness, "buildUpon", "buildUpon", CompletionItemKind.METHOD)
            assertCompletionAt(harness, "buildUpon", "getPath", CompletionItemKind.METHOD)
        }
    }

    @Test
    fun android_uri_builder_chained_hover_reports_builder_when_android_jar_available() {
        withAndroidHarness(
            "main.lua" to """
                local Uri = luajava.bindClass("android.net.Uri")
                local builder = Uri.parse("content://example/root").buildUpon().path("child")
                return builder
            """.trimIndent(),
            classes = setOf("android.net.Uri", "android.net.Uri\$Builder")
        ) { harness ->
            assertHoverType(harness, "builder", "android.net.Uri\$Builder", occurrence = 2)
        }
    }

    private fun jvmHarness(
        vararg files: Pair<String, String>,
        classes: Set<String> = emptySet(),
        metadata: Map<String, String> = emptyMap()
    ): WorkspaceSemanticHarness {
        return WorkspaceSemanticHarness.build(
            *files,
            metadata = metadataWithClasses(metadata, classes),
            engine = JvmWorkspaceEngine()
        )
    }

    private fun withAndroidHarness(
        vararg files: Pair<String, String>,
        classes: Set<String> = emptySet(),
        block: (WorkspaceSemanticHarness) -> Unit
    ) {
        if (!androidJar.isFile) {
            return
        }
        block(
            jvmHarness(
                *files,
                classes = classes,
                metadata = mapOf(JvmWorkspaceConfiguration.ANDROID_JAR_METADATA_KEY to androidJar.path)
            )
        )
    }

    private fun metadataWithClasses(metadata: Map<String, String>, classes: Set<String>): Map<String, String> {
        if (classes.isEmpty()) {
            return metadata
        }
        val mergedClasses = (
            metadata[JvmClassModuleProvider.CLASSES_METADATA_KEY]
                .orEmpty()
                .split(',', ';', '\n')
                .asSequence()
                .map(String::trim)
                .filter(String::isNotEmpty) +
                classes.asSequence()
        ).toCollection(linkedSetOf())
        return metadata + (JvmClassModuleProvider.CLASSES_METADATA_KEY to mergedClasses.joinToString("\n"))
    }

    private fun assertHoverType(
        harness: WorkspaceSemanticHarness,
        needle: String,
        expected: String,
        occurrence: Int = 1
    ) {
        val hover = assertNotNull(harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", needle, occurrence)))
        assertEquals(expected, hover.typeInfo?.displayName)
        assertNotUnknown(hover.typeInfo?.displayName)
    }

    private fun assertHoverTypeAllowingUnknown(
        harness: WorkspaceSemanticHarness,
        needle: String,
        expected: String,
        occurrence: Int = 1
    ) {
        val hover = assertNotNull(harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", needle, occurrence)))
        assertEquals(expected, hover.typeInfo?.displayName)
    }

    private fun assertCompletionAt(
        harness: WorkspaceSemanticHarness,
        memberNeedle: String,
        label: String,
        kind: CompletionItemKind,
        occurrence: Int = 1
    ) {
        val completions = harness.queries.completions(
            harness.path("main.lua"),
            harness.positionOf("main.lua", memberNeedle, occurrence)
        )
        assertCompletion(completions, label, kind)
    }

    private fun assertCompletion(completions: List<CompletionItem>, label: String, kind: CompletionItemKind) {
        assertTrue(
            completions.any { it.label == label && it.kind == kind },
            "Expected completion $label of kind $kind; actual: ${completions.map { "${it.label}:${it.kind}" }}."
        )
    }

    private fun assertCallable(displayName: String?) {
        assertTrue(
            displayName.orEmpty().contains("fun("),
            "Expected callable type, got $displayName."
        )
        assertNotUnknown(displayName)
    }

    private fun assertNotUnknown(displayName: String?) {
        assertFalse(displayName.isNullOrBlank(), "Expected a modeled Java chain type, got no type.")
        assertFalse(displayName == "unknown", "Expected a modeled Java chain type, got unknown.")
    }

    private fun assertNoDiagnostics(harness: WorkspaceSemanticHarness) {
        val diagnostics = harness.queries.diagnostics(harness.path("main.lua"))
        assertTrue(
            diagnostics.isEmpty(),
            "Expected no diagnostics for valid Java chain; actual diagnostics: ${diagnostics.map { it.message }}."
        )
    }

    private fun assertInvalidMemberDiagnostic(harness: WorkspaceSemanticHarness, member: String) {
        val diagnostics = harness.queries.diagnostics(harness.path("main.lua"))
        assertTrue(
            diagnostics.containsInvalidMember(member),
            "Expected invalid Java member diagnostic for $member; actual diagnostics: ${diagnostics.map { it.message }}."
        )
    }

    private fun List<Diagnostic>.containsInvalidMember(member: String): Boolean {
        return any { diagnostic ->
            diagnostic.message.contains(member) &&
                (diagnostic.message.contains("member", ignoreCase = true) ||
                    diagnostic.message.contains("method", ignoreCase = true) ||
                    diagnostic.message.contains("field", ignoreCase = true) ||
                    diagnostic.message.contains("unknown", ignoreCase = true) ||
                    diagnostic.message.contains("unresolved", ignoreCase = true) ||
                    diagnostic.message.contains("not found", ignoreCase = true))
        }
    }

    class MutableJavaBean {
        fun getTitle(): String = "initial"

        fun setTitle(value: String) {
            storedTitle = value
        }

        private var storedTitle: String = "initial"
    }

    class ListenerHolder {
        fun setValueListener(listener: ValueListener) {
            this.listener = listener
        }

        fun setAction(action: Action) {
            this.action = action
        }

        private var listener: ValueListener? = null
        private var action: Action? = null
    }

    interface ValueListener {
        fun onValue(value: String)
    }

    interface Action {
        fun run()
    }

    class OverloadedGetterBean {
        fun getCode(): String = "zero"

        fun getCode(index: Int): String = index.toString()
    }

    class WriteOnlyBean {
        fun setToken(value: String) {
            storedToken = value
        }

        private var storedToken: String = ""
    }

    class MixedBooleanGetterBean {
        fun getActive(): Boolean = true

        fun isActive(): String = "not-a-boolean-bean-getter"
    }

    class ContainerHolder {
        fun setStringArray(values: Array<String>): Int = values.size

        fun setStringList(values: List<String>): Int = values.size

        fun setStringMap(values: Map<String, String>): Int = values.size

        fun setRawList(values: List<*>): Int = values.size
    }
}
