package semantic.workspace

import io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.semantic.workspace.DocumentFacts
import io.github.dingyi222666.luaparser.semantic.workspace.DocumentFactsCollector
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Negative-path corpus for Android-Lua / LuaJava import fact extraction and
 * JVM import diagnostics (TASK-241).
 *
 * Encodes that malformed `import` / `luajava.*` call shapes and bad configuration
 * import strings produce empty or diagnostic-only results without throwing.
 *
 * Test-only. Does **not** claim TASK-176 completion (production import surface
 * preservation remains owned by that blocked task).
 */
class AndroidLuaImportNegativePathTddTest {

    private val provider = JvmClassModuleProvider()

    // -------------------------------------------------------------------------
    // DocumentFactsCollector: malformed import / luajava call shapes
    // -------------------------------------------------------------------------

    @Test
    fun empty_string_import_records_empty_target_fact_without_throw() {
        val facts = collectFactsWithoutThrow("""import("")""")

        assertEquals(listOf(""), facts.sourceImports.map { it.target })
        assertEquals(
            listOf(LoadShape(DocumentFacts.JvmClassLoadKind.IMPORT_CALL, "")),
            facts.jvmClassLoads.map { LoadShape(it.kind, it.target) }
        )
        assertTrue(facts.sourceImports.single().range.start.line >= 0)
    }

    @Test
    fun blank_string_import_records_whitespace_target_fact_without_throw() {
        val facts = collectFactsWithoutThrow("""import("   ")""")

        assertEquals(listOf("   "), facts.sourceImports.map { it.target })
        assertEquals(
            listOf(LoadShape(DocumentFacts.JvmClassLoadKind.IMPORT_CALL, "   ")),
            facts.jvmClassLoads.map { LoadShape(it.kind, it.target) }
        )
    }

    @Test
    fun short_string_empty_import_records_empty_target_without_throw() {
        val facts = collectFactsWithoutThrow("""import "" """)

        assertEquals(listOf(""), facts.sourceImports.map { it.target })
        assertEquals(
            listOf(LoadShape(DocumentFacts.JvmClassLoadKind.IMPORT_CALL, "")),
            facts.jvmClassLoads.map { LoadShape(it.kind, it.target) }
        )
    }

    @Test
    fun missing_argument_import_produces_no_import_facts_without_throw() {
        assertEmptyImportShape(collectFactsWithoutThrow("import()"))
    }

    @Test
    fun dynamic_identifier_import_produces_no_import_facts_without_throw() {
        assertEmptyImportShape(
            collectFactsWithoutThrow(
                """
                    local className = "java.io.File"
                    import(className)
                """
            )
        )
    }

    @Test
    fun concatenated_import_target_produces_no_import_facts_without_throw() {
        assertEmptyImportShape(
            collectFactsWithoutThrow("""import("java.io." .. "File")""")
        )
    }

    @Test
    fun table_import_with_only_named_and_non_string_entries_is_empty_without_throw() {
        assertEmptyImportShape(
            collectFactsWithoutThrow(
                """import({ ignored = "java.io.File", 42, true, dynamicName })"""
            )
        )
    }

    @Test
    fun empty_table_import_produces_no_import_facts_without_throw() {
        assertEmptyImportShape(collectFactsWithoutThrow("""import({})"""))
    }

    @Test
    fun empty_array_import_produces_no_import_facts_without_throw() {
        assertEmptyImportShape(collectFactsWithoutThrow("""import([])"""))
    }

    @Test
    fun nested_table_only_import_produces_no_import_facts_without_throw() {
        assertEmptyImportShape(
            collectFactsWithoutThrow("""import({ { "java.io.File" } })""")
        )
    }

    @Test
    fun malformed_mixed_table_import_keeps_only_valid_string_entries_without_throw() {
        val facts = collectFactsWithoutThrow(
            """import({ "java.io.File", {}, dynamicName, 1, "java.util.Locale", ignored = "skip" })"""
        )

        assertEquals(listOf("java.io.File", "java.util.Locale"), facts.sourceImports.map { it.target })
        assertEquals(
            listOf(
                LoadShape(DocumentFacts.JvmClassLoadKind.IMPORT_CALL, "java.io.File"),
                LoadShape(DocumentFacts.JvmClassLoadKind.IMPORT_CALL, "java.util.Locale")
            ),
            facts.jvmClassLoads.map { LoadShape(it.kind, it.target) }
        )
    }

    @Test
    fun bare_wildcard_and_dot_star_import_targets_are_source_only_without_throw() {
        val facts = collectFactsWithoutThrow(
            """
                import(".*")
                import("*")
                import("java.io.*")
            """
        )

        // Targets ending with ".*" stay source-import only (no concrete JVM class load).
        // A bare "*" is not treated as a package wildcard by DocumentFactsCollector, so it
        // still appears as an IMPORT_CALL load target while remaining non-throwing.
        assertEquals(listOf(".*", "*", "java.io.*"), facts.sourceImports.map { it.target })
        assertEquals(
            listOf(LoadShape(DocumentFacts.JvmClassLoadKind.IMPORT_CALL, "*")),
            facts.jvmClassLoads.map { LoadShape(it.kind, it.target) }
        )
    }

    @Test
    fun bind_class_missing_and_dynamic_targets_produce_no_loads_without_throw() {
        assertEmptyImportShape(
            collectFactsWithoutThrow(
                """
                    luajava.bindClass()
                    luajava.bindClass(className)
                    luajava.bindClass("java.lang." .. "String")
                """
            )
        )
    }

    @Test
    fun bind_class_empty_string_records_empty_load_without_throw() {
        val facts = collectFactsWithoutThrow("""luajava.bindClass("")""")

        assertEquals(emptyList(), facts.sourceImports)
        assertEquals(
            listOf(LoadShape(DocumentFacts.JvmClassLoadKind.BIND_CLASS_CALL, "")),
            facts.jvmClassLoads.map { LoadShape(it.kind, it.target) }
        )
    }

    @Test
    fun new_instance_missing_and_dynamic_targets_produce_no_loads_without_throw() {
        assertEmptyImportShape(
            collectFactsWithoutThrow(
                """
                    luajava.newInstance()
                    luajava.newInstance(className)
                """
            )
        )
    }

    @Test
    fun create_proxy_missing_dynamic_and_empty_list_produce_no_loads_without_throw() {
        assertEmptyImportShape(
            collectFactsWithoutThrow(
                """
                    luajava.createProxy()
                    luajava.createProxy(interfaceName, {})
                    luajava.createProxy("", {})
                    luajava.createProxy("  ,  , ", {})
                """
            )
        )
    }

    @Test
    fun create_proxy_empty_segments_are_filtered_while_valid_segment_is_kept() {
        val facts = collectFactsWithoutThrow(
            """luajava.createProxy(" , java.lang.Runnable ,  ", {})"""
        )

        assertEquals(emptyList(), facts.sourceImports)
        assertEquals(
            listOf(LoadShape(DocumentFacts.JvmClassLoadKind.CREATE_PROXY_CALL, "java.lang.Runnable")),
            facts.jvmClassLoads.map { LoadShape(it.kind, it.target) }
        )
    }

    @Test
    fun load_lib_missing_and_dynamic_targets_produce_no_loads_without_throw() {
        assertEmptyImportShape(
            collectFactsWithoutThrow(
                """
                    luajava.loadLib()
                    luajava.loadLib(className, methodName)
                    luajava.loadLib("java.lang." .. "System", "currentTimeMillis")
                """
            )
        )
    }

    @Test
    fun load_lib_empty_class_name_records_empty_load_without_throw() {
        val facts = collectFactsWithoutThrow("""luajava.loadLib("", "currentTimeMillis")""")

        assertEquals(emptyList(), facts.sourceImports)
        assertEquals(
            listOf(LoadShape(DocumentFacts.JvmClassLoadKind.LOAD_LIB_CALL, "")),
            facts.jvmClassLoads.map { LoadShape(it.kind, it.target) }
        )
    }

    @Test
    fun import_alias_used_before_registration_stays_empty_without_throw() {
        assertEmptyImportShape(
            collectFactsWithoutThrow(
                """
                    androidImport("java.lang.Integer")
                    local androidImport = import
                    androidImport("java.lang.String")
                """
            ),
            // After registration the second call is a valid import fact.
            expectedSourceImports = listOf("java.lang.String"),
            expectedLoads = listOf(LoadShape(DocumentFacts.JvmClassLoadKind.IMPORT_CALL, "java.lang.String"))
        )
    }

    @Test
    fun mixed_malformed_and_valid_import_luajava_corpus_never_throws() {
        val facts = collectFactsWithoutThrow(
            """
                import()
                import(className)
                import("")
                import("java.io.File")
                import({ dynamicName, "java.util.Locale", 9 })
                luajava.bindClass()
                luajava.bindClass("java.lang.String")
                luajava.newInstance(className)
                luajava.createProxy("  , ", {})
                luajava.createProxy("java.lang.Runnable", {})
                luajava.loadLib(className, methodName)
                luajava.loadLib("java.lang.System", "currentTimeMillis")
            """
        )

        assertEquals(
            listOf("", "java.io.File", "java.util.Locale"),
            facts.sourceImports.map { it.target }
        )
        assertEquals(
            listOf(
                LoadShape(DocumentFacts.JvmClassLoadKind.IMPORT_CALL, ""),
                LoadShape(DocumentFacts.JvmClassLoadKind.IMPORT_CALL, "java.io.File"),
                LoadShape(DocumentFacts.JvmClassLoadKind.IMPORT_CALL, "java.util.Locale"),
                LoadShape(DocumentFacts.JvmClassLoadKind.BIND_CLASS_CALL, "java.lang.String"),
                LoadShape(DocumentFacts.JvmClassLoadKind.CREATE_PROXY_CALL, "java.lang.Runnable"),
                LoadShape(DocumentFacts.JvmClassLoadKind.LOAD_LIB_CALL, "java.lang.System")
            ),
            facts.jvmClassLoads.map { LoadShape(it.kind, it.target) }
        )
    }

    // -------------------------------------------------------------------------
    // JvmClassModuleProvider: malformed configuration imports / diagnostics
    // -------------------------------------------------------------------------

    @Test
    fun blank_and_empty_configuration_imports_do_not_throw_or_request_classes() {
        val configuration = JvmWorkspaceConfiguration(
            androluaImports = listOf("", "   ", "\t", "import ", "import")
        )

        val requested = runCatching { provider.requestedClasses(configuration) }
            .getOrElse { error ->
                fail("Blank configuration imports must not throw; got ${error::class.simpleName}: ${error.message}")
            }
        val diagnostics = runCatching { provider.importDiagnostics(configuration) }
            .getOrElse { error ->
                fail("Blank configuration import diagnostics must not throw; got ${error::class.simpleName}: ${error.message}")
            }
        val providers = runCatching { provider.providersFor(configuration) }
            .getOrElse { error ->
                fail("Blank configuration providers must not throw; got ${error::class.simpleName}: ${error.message}")
            }

        // Normalization drops blank entries; "import" alone may parse as a simple name that fails to resolve.
        assertTrue(requested.none { it.isBlank() }, "Requested classes must not include blank names: $requested")
        assertTrue(providers.keys.none { it.value.isBlank() })
        assertTrue(diagnostics.none { it.className.isBlank() && it.pathPrefix.isBlank() })
    }

    @Test
    fun unsupported_dex_and_non_classpath_prefix_imports_emit_diagnostics_without_throw() {
        val configuration = JvmWorkspaceConfiguration(
            androluaImports = listOf(
                "dexPath:java.io.File",
                "plugin.dex:android.content.Context",
                "missing/prefix.jar:java.util.Locale",
                "java.io.File"
            )
        )

        val diagnostics = runCatching { provider.importDiagnostics(configuration) }
            .getOrElse { error ->
                fail("Prefixed import diagnostics must not throw; got ${error::class.simpleName}: ${error.message}")
            }
        val requested = runCatching { provider.requestedClasses(configuration) }
            .getOrElse { error ->
                fail("Prefixed import requestedClasses must not throw; got ${error::class.simpleName}: ${error.message}")
            }

        assertEquals(3, diagnostics.size)
        assertTrue(diagnostics.all { it.code == JvmClassModuleProvider.UNSUPPORTED_PREFIXED_IMPORT_CODE })
        assertEquals(
            setOf("dexPath", "plugin.dex", "missing/prefix.jar"),
            diagnostics.map { it.pathPrefix }.toSet()
        )
        assertEquals(
            setOf("java.io.File", "android.content.Context", "java.util.Locale"),
            diagnostics.map { it.className }.toSet()
        )
        diagnostics.forEach { diagnostic ->
            assertTrue(diagnostic.message.isNotBlank())
            assertTrue(diagnostic.importText.isNotBlank())
            assertTrue(diagnostic.message.contains(diagnostic.className))
        }
        // Concrete ordinary import still resolves; unsupported prefixes keep class names for resolution attempts.
        assertTrue("java.io.File" in requested)
    }

    @Test
    fun ordinary_valid_import_does_not_emit_prefixed_diagnostic() {
        val configuration = JvmWorkspaceConfiguration(
            androluaImports = listOf("java.io.File", "java.util.Locale")
        )

        val diagnostics = runCatching { provider.importDiagnostics(configuration) }
            .getOrElse { error ->
                fail("Ordinary imports must not throw diagnostics; got ${error::class.simpleName}: ${error.message}")
            }

        assertEquals(emptyList(), diagnostics)
        assertEquals(linkedSetOf("java.io.File", "java.util.Locale"), provider.requestedClasses(configuration))
    }

    @Test
    fun import_diagnostics_overload_for_explicit_targets_handles_malformed_without_throw() {
        val diagnostics = runCatching {
            provider.importDiagnostics(
                importTargets = listOf(
                    "",
                    "   ",
                    "dexPath:java.lang.String",
                    "not-a-prefix-only",
                    "plugin.apk:android.app.Activity"
                ),
                configuration = JvmWorkspaceConfiguration()
            )
        }.getOrElse { error ->
            fail("Explicit-target importDiagnostics must not throw; got ${error::class.simpleName}: ${error.message}")
        }

        assertEquals(2, diagnostics.size)
        assertEquals(
            setOf("dexPath", "plugin.apk"),
            diagnostics.map { it.pathPrefix }.toSet()
        )
        assertEquals(
            setOf("java.lang.String", "android.app.Activity"),
            diagnostics.map { it.className }.toSet()
        )
        assertTrue(diagnostics.all { it.code == JvmClassModuleProvider.UNSUPPORTED_PREFIXED_IMPORT_CODE })
    }

    @Test
    fun malformed_package_provider_targets_return_empty_without_throw() {
        // Truly malformed / non-wildcard targets must not mount package providers.
        val malformedOnly = runCatching {
            provider.packageProvidersFor(
                importTargets = listOf("", "   ", ".*", "*", "not-a-package", "import "),
                configuration = JvmWorkspaceConfiguration()
            )
        }.getOrElse { error ->
            fail("Malformed package provider targets must not throw; got ${error::class.simpleName}: ${error.message}")
        }

        assertTrue(
            malformedOnly.isEmpty(),
            "Malformed package targets must not mount providers; actual: ${malformedOnly.keys.map { it.value }}"
        )

        // Unsupported path-prefix wildcards still parse the className side (java.io.*) and fall back to
        // the default classloader for package enumeration — non-throwing, mounts the JDK package.
        // REVIEW24 golden alignment: dexPath:java.io.* → __jvm__/packages/java/io.lua.
        val prefixedWildcard = runCatching {
            provider.packageProvidersFor(
                importTargets = listOf(
                    "",
                    "   ",
                    ".*",
                    "*",
                    "not-a-package",
                    "import ",
                    "dexPath:java.io.*"
                ),
                configuration = JvmWorkspaceConfiguration()
            )
        }.getOrElse { error ->
            fail(
                "Prefixed wildcard package provider targets must not throw; " +
                    "got ${error::class.simpleName}: ${error.message}"
            )
        }

        assertEquals(
            setOf(VirtualPath.of("__jvm__/packages/java/io.lua")),
            prefixedWildcard.keys,
            "Unsupported prefix + resolvable package wildcard mounts fallback package only; " +
                "actual: ${prefixedWildcard.keys.map { it.value }}"
        )
        assertNotNull(prefixedWildcard[VirtualPath.of("__jvm__/packages/java/io.lua")])
    }

    @Test
    fun resolve_import_for_blank_and_missing_classes_returns_null_without_throw() {
        val configuration = JvmWorkspaceConfiguration()

        val blank = runCatching { provider.resolveImport("", configuration) }
            .getOrElse { error ->
                fail("Blank resolveImport must not throw; got ${error::class.simpleName}: ${error.message}")
            }
        val missing = runCatching { provider.resolveImport("com.missing.NoSuchClass", configuration) }
            .getOrElse { error ->
                fail("Missing class resolveImport must not throw; got ${error::class.simpleName}: ${error.message}")
            }
        val emptyWildcard = runCatching { provider.resolveImport(".*", configuration) }
            .getOrElse { error ->
                fail("Bare wildcard resolveImport must not throw; got ${error::class.simpleName}: ${error.message}")
            }

        assertEquals(null, blank)
        assertEquals(null, missing)
        assertEquals(null, emptyWildcard)
    }

    @Test
    fun document_facts_fingerprint_is_stable_for_repeated_malformed_corpus() {
        val source = """
            import()
            import("")
            luajava.bindClass(className)
            luajava.createProxy(" , ", {})
        """.trimIndent()

        val first = collectFactsWithoutThrow(source)
        val second = collectFactsWithoutThrow(source)

        assertEquals(first.fingerprint, second.fingerprint)
        assertNotNull(first.fingerprint)
        assertTrue(first.fingerprint.isNotBlank())
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun collectFactsWithoutThrow(source: String): DocumentFacts {
        return runCatching {
            val chunk = LuaParser().parse(source.trimIndent())
            DocumentFactsCollector.collect(VirtualPath.of("app/negative_import.lua"), chunk)
        }.getOrElse { error ->
            fail(
                "Malformed import/luajava fact collection must not throw; " +
                    "got ${error::class.simpleName}: ${error.message}"
            )
        }
    }

    private fun assertEmptyImportShape(
        facts: DocumentFacts,
        expectedSourceImports: List<String> = emptyList(),
        expectedLoads: List<LoadShape> = emptyList()
    ) {
        assertEquals(expectedSourceImports, facts.sourceImports.map { it.target })
        assertEquals(expectedLoads, facts.jvmClassLoads.map { LoadShape(it.kind, it.target) })
    }

    private data class LoadShape(
        val kind: DocumentFacts.JvmClassLoadKind,
        val target: String
    )
}
