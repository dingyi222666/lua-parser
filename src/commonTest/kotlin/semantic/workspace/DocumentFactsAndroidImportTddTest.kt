package semantic.workspace

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.semantic.workspace.DocumentFacts
import io.github.dingyi222666.luaparser.semantic.workspace.DocumentFactsCollector
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import kotlin.test.Test
import kotlin.test.assertEquals

class DocumentFactsAndroidImportTddTest {
    @Test
    fun require_import_short_string_records_require_without_java_loads() {
        val facts = collectFacts("require \"import\"")

        assertEquals(listOf("import"), facts.requires.map { it.moduleName })
        assertShape(facts)
    }

    @Test
    fun require_import_parenthesized_records_require_without_java_loads() {
        val facts = collectFacts("""require("import")""")

        assertEquals(listOf("import"), facts.requires.map { it.moduleName })
        assertShape(facts)
    }

    @Test
    fun local_import_from_require_enables_alias_call() = assertShape(
        source = """
            local androidImport = require("import")
            androidImport("java.io.File")
        """,
        sourceImports = listOf("java.io.File"),
        jvmClassLoads = listOf(importLoad("java.io.File"))
    )

    @Test
    fun direct_parenthesized_import_records_source_import_and_jvm_load() = assertShape(
        source = """import("java.util.Locale")""",
        sourceImports = listOf("java.util.Locale"),
        jvmClassLoads = listOf(importLoad("java.util.Locale"))
    )

    @Test
    fun direct_short_string_import_records_source_import_and_jvm_load() = assertShape(
        source = "import \"android.content.Context\"",
        sourceImports = listOf("android.content.Context"),
        jvmClassLoads = listOf(importLoad("android.content.Context"))
    )

    @Test
    fun import_used_as_local_initializer_records_import_target() = assertShape(
        source = """local File = import("java.io.File")""",
        sourceImports = listOf("java.io.File"),
        jvmClassLoads = listOf(importLoad("java.io.File"))
    )

    @Test
    fun nested_function_import_records_import_target() = assertShape(
        source = """
            local function open()
                return import("java.io.InputStream")
            end
        """,
        sourceImports = listOf("java.io.InputStream"),
        jvmClassLoads = listOf(importLoad("java.io.InputStream"))
    )

    @Test
    fun repeated_import_calls_preserve_source_order_and_duplicates() = assertShape(
        source = """
            import("java.io.File")
            import("java.io.File")
            import("java.util.Locale")
        """,
        sourceImports = listOf("java.io.File", "java.io.File", "java.util.Locale"),
        jvmClassLoads = listOf(
            importLoad("java.io.File"),
            importLoad("java.io.File"),
            importLoad("java.util.Locale")
        )
    )

    @Test
    fun dex_prefixed_import_records_original_target_text() = assertShape(
        source = """import("plugin.dex:android.content.Context")""",
        sourceImports = listOf("plugin.dex:android.content.Context"),
        jvmClassLoads = listOf(importLoad("plugin.dex:android.content.Context"))
    )

    @Test
    fun assignment_import_alias_from_require_records_import_call() = assertShape(
        source = """
            androidImport = require("import")
            androidImport("java.lang.String")
        """,
        sourceImports = listOf("java.lang.String"),
        jvmClassLoads = listOf(importLoad("java.lang.String"))
    )

    @Test
    fun local_alias_from_import_identifier_records_import_call() = assertShape(
        source = """
            local androidImport = import
            androidImport("java.lang.Thread")
        """,
        sourceImports = listOf("java.lang.Thread"),
        jvmClassLoads = listOf(importLoad("java.lang.Thread"))
    )

    @Test
    fun reassigned_import_alias_records_import_call() = assertShape(
        source = """
            local first = import
            local second = first
            second("java.lang.Boolean")
        """,
        sourceImports = listOf("java.lang.Boolean"),
        jvmClassLoads = listOf(importLoad("java.lang.Boolean"))
    )

    @Test
    fun import_alias_used_before_registration_is_ignored() = assertShape(
        source = """
            androidImport("java.lang.Integer")
            local androidImport = import
        """
    )

    @Test
    fun parenthesized_table_import_records_sequential_string_entries() = assertShape(
        source = """import({ "java.io.File", "java.util.Locale" })""",
        sourceImports = listOf("java.io.File", "java.util.Locale"),
        jvmClassLoads = listOf(importLoad("java.io.File"), importLoad("java.util.Locale"))
    )

    @Test
    fun table_call_import_records_sequential_string_entries() = assertShape(
        source = """import { "java.io.File", "java.util.Locale" }""",
        sourceImports = listOf("java.io.File", "java.util.Locale"),
        jvmClassLoads = listOf(importLoad("java.io.File"), importLoad("java.util.Locale"))
    )

    @Test
    fun table_call_import_accepts_semicolon_separated_entries() = assertShape(
        source = """import { "java.lang.String"; "java.lang.Thread" }""",
        sourceImports = listOf("java.lang.String", "java.lang.Thread"),
        jvmClassLoads = listOf(importLoad("java.lang.String"), importLoad("java.lang.Thread"))
    )

    @Test
    fun table_import_ignores_named_fields() = assertShape(
        source = """import({ "java.io.File", ignored = "java.util.Locale" })""",
        sourceImports = listOf("java.io.File"),
        jvmClassLoads = listOf(importLoad("java.io.File"))
    )

    @Test
    fun table_import_ignores_explicit_sparse_numeric_fields() = assertShape(
        source = """import({ "java.io.File", [3] = "java.util.Locale" })""",
        sourceImports = listOf("java.io.File"),
        jvmClassLoads = listOf(importLoad("java.io.File"))
    )

    @Test
    fun table_import_ignores_nested_tables_and_keeps_string_entries() = assertShape(
        source = """import({ { "java.io.File" }, "java.util.Locale" })""",
        sourceImports = listOf("java.util.Locale"),
        jvmClassLoads = listOf(importLoad("java.util.Locale"))
    )

    @Test
    fun table_import_ignores_non_string_entries() = assertShape(
        source = """import({ "java.io.File", 42, true, dynamicName })""",
        sourceImports = listOf("java.io.File"),
        jvmClassLoads = listOf(importLoad("java.io.File"))
    )

    @Test
    fun parenthesized_array_import_records_string_entries() = assertShape(
        source = """import([ "java.io.File", "java.util.Locale" ])""",
        sourceImports = listOf("java.io.File", "java.util.Locale"),
        jvmClassLoads = listOf(importLoad("java.io.File"), importLoad("java.util.Locale"))
    )

    @Test
    fun array_import_ignores_non_string_entries() = assertShape(
        source = """import([ "java.io.File", dynamicName, 42 ])""",
        sourceImports = listOf("java.io.File"),
        jvmClassLoads = listOf(importLoad("java.io.File"))
    )

    @Test
    fun direct_wildcard_import_records_source_import_without_concrete_jvm_load() = assertShape(
        source = """import("java.io.*")""",
        sourceImports = listOf("java.io.*")
    )

    @Test
    fun table_wildcard_import_records_prefix_without_concrete_jvm_load() = assertShape(
        source = """import({ "java.io.*" })""",
        sourceImports = listOf("java.io.*")
    )

    @Test
    fun array_wildcard_import_records_prefix_without_concrete_jvm_load() = assertShape(
        source = """import([ "android.widget.*" ])""",
        sourceImports = listOf("android.widget.*")
    )

    @Test
    fun mixed_wildcard_and_explicit_import_only_loads_explicit_class() = assertShape(
        source = """import({ "java.io.*", "java.util.Locale" })""",
        sourceImports = listOf("java.io.*", "java.util.Locale"),
        jvmClassLoads = listOf(importLoad("java.util.Locale"))
    )

    @Test
    fun dex_wildcard_import_records_source_import_without_concrete_jvm_load() = assertShape(
        source = """import("plugin.dex:android.widget.*")""",
        sourceImports = listOf("plugin.dex:android.widget.*")
    )

    @Test
    fun bind_class_direct_call_records_jvm_load_only() = assertShape(
        source = """luajava.bindClass("java.lang.String")""",
        jvmClassLoads = listOf(bindClassLoad("java.lang.String"))
    )

    @Test
    fun bind_class_local_alias_records_jvm_load_only() = assertShape(
        source = """
            local bindClass = luajava.bindClass
            bindClass("java.io.File")
        """,
        jvmClassLoads = listOf(bindClassLoad("java.io.File"))
    )

    @Test
    fun bind_class_realias_records_jvm_load_only() = assertShape(
        source = """
            local bindClass = luajava.bindClass
            local bind = bindClass
            bind("java.util.Locale")
        """,
        jvmClassLoads = listOf(bindClassLoad("java.util.Locale"))
    )

    @Test
    fun bind_class_dynamic_target_is_ignored() = assertShape(
        source = """luajava.bindClass(className)"""
    )

    @Test
    fun new_instance_direct_call_records_jvm_load_only() = assertShape(
        source = """luajava.newInstance("java.lang.StringBuilder")""",
        jvmClassLoads = listOf(newInstanceLoad("java.lang.StringBuilder"))
    )

    @Test
    fun new_instance_alias_records_jvm_load_only() = assertShape(
        source = """
            local newInstance = luajava.newInstance
            local create = newInstance
            create("java.lang.String")
        """,
        jvmClassLoads = listOf(newInstanceLoad("java.lang.String"))
    )

    @Test
    fun new_instance_dynamic_target_is_ignored() = assertShape(
        source = """luajava.newInstance(className)"""
    )

    @Test
    fun create_proxy_direct_call_records_interface_load() = assertShape(
        source = """luajava.createProxy("java.lang.Runnable", {})""",
        jvmClassLoads = listOf(createProxyLoad("java.lang.Runnable"))
    )

    @Test
    fun create_proxy_alias_records_interface_load() = assertShape(
        source = """
            local createProxy = luajava.createProxy
            local proxy = createProxy
            proxy("java.util.Comparator", {})
        """,
        jvmClassLoads = listOf(createProxyLoad("java.util.Comparator"))
    )

    @Test
    fun create_proxy_comma_separated_interfaces_record_separate_targets() = assertShape(
        source = """luajava.createProxy("java.lang.Runnable,java.util.Comparator", {})""",
        jvmClassLoads = listOf(
            createProxyLoad("java.lang.Runnable"),
            createProxyLoad("java.util.Comparator")
        )
    )

    @Test
    fun create_proxy_dynamic_target_is_ignored() = assertShape(
        source = """luajava.createProxy(interfaceName, {})"""
    )

    @Test
    fun load_lib_direct_call_records_class_name_only() = assertShape(
        source = """luajava.loadLib("java.lang.System", "currentTimeMillis")""",
        jvmClassLoads = listOf(loadLibLoad("java.lang.System"))
    )

    @Test
    fun load_lib_alias_records_class_name_only() = assertShape(
        source = """
            local loadLib = luajava.loadLib
            local load = loadLib
            load("java.lang.Runtime", "getRuntime")
        """,
        jvmClassLoads = listOf(loadLibLoad("java.lang.Runtime"))
    )

    @Test
    fun load_lib_dynamic_target_is_ignored() = assertShape(
        source = """luajava.loadLib(className, methodName)"""
    )

    private fun collectFacts(source: String): DocumentFacts {
        val chunk = LuaParser().parse(source.trimIndent())
        return DocumentFactsCollector.collect(VirtualPath.of("app/main.lua"), chunk)
    }

    private fun assertShape(
        source: String,
        sourceImports: List<String> = emptyList(),
        jvmClassLoads: List<LoadShape> = emptyList()
    ) {
        assertShape(collectFacts(source), sourceImports, jvmClassLoads)
    }

    private fun assertShape(
        facts: DocumentFacts,
        sourceImports: List<String> = emptyList(),
        jvmClassLoads: List<LoadShape> = emptyList()
    ) {
        assertEquals(sourceImports, facts.sourceImports.map { it.target })
        assertEquals(jvmClassLoads, facts.jvmClassLoads.map { LoadShape(it.kind, it.target) })
    }

    private data class LoadShape(
        val kind: DocumentFacts.JvmClassLoadKind,
        val target: String
    )

    private fun importLoad(target: String) = LoadShape(DocumentFacts.JvmClassLoadKind.IMPORT_CALL, target)
    private fun bindClassLoad(target: String) = LoadShape(DocumentFacts.JvmClassLoadKind.BIND_CLASS_CALL, target)
    private fun newInstanceLoad(target: String) = LoadShape(DocumentFacts.JvmClassLoadKind.NEW_INSTANCE_CALL, target)
    private fun createProxyLoad(target: String) = LoadShape(DocumentFacts.JvmClassLoadKind.CREATE_PROXY_CALL, target)
    private fun loadLibLoad(target: String) = LoadShape(DocumentFacts.JvmClassLoadKind.LOAD_LIB_CALL, target)
}
