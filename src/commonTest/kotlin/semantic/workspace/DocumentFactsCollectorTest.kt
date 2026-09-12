package semantic.workspace

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.semantic.workspace.DocumentFacts
import io.github.dingyi222666.luaparser.semantic.workspace.DocumentFactsCollector
import io.github.dingyi222666.luaparser.semantic.workspace.ModuleEnvironmentMode
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class DocumentFactsCollectorTest {
    @Test
    fun tracks_static_requires_separately_from_dynamic_require_sites() {
        val facts = collectFacts(
            path = "pkg/runtime.lua",
            source = """
                require "alpha"
                require("beta")
                require(name)
                local dep = require(prefix .. suffix)
                require()
            """.trimIndent()
        )

        assertEquals(listOf("alpha", "beta"), facts.requires.map { it.moduleName })
        assertEquals(
            listOf(
                DocumentFacts.DynamicRequireKind.NON_STRING_LITERAL,
                DocumentFacts.DynamicRequireKind.NON_STRING_LITERAL,
                DocumentFacts.DynamicRequireKind.MISSING_ARGUMENT
            ),
            facts.dynamicRequires.map { it.kind }
        )
    }

    @Test
    fun collects_legacy_module_calls_and_segments_environment() {
        val facts = collectFacts(
            path = "pkg/runtime.lua",
            source = """
                module("pkg.runtime")
                do
                    module("pkg.runtime.seeall", package.seeall)
                end
                local function configure()
                    module("pkg.runtime.inner")
                end
            """.trimIndent()
        )

        assertEquals(
            listOf("pkg.runtime", "pkg.runtime.seeall", "pkg.runtime.inner"),
            facts.legacyModuleCalls.map { it.moduleName }
        )
        assertEquals(
            listOf(true, true, false),
            facts.legacyModuleCalls.map { it.isTopLevel }
        )
        assertEquals(
            listOf(
                ModuleEnvironmentMode.CHUNK,
                ModuleEnvironmentMode.LEGACY_MODULE,
                ModuleEnvironmentMode.LEGACY_MODULE_SEEALL
            ),
            facts.environmentSegments.map { it.mode }
        )
    }

    @Test
    fun records_return_export_hints_for_identifier_and_table() {
        val identifierFacts = collectFacts(
            path = "pkg/id.lua",
            source = """
                local M = {}
                return M
            """.trimIndent()
        )
        val tableFacts = collectFacts(
            path = "pkg/table.lua",
            source = "return { value = 1 }"
        )
        val multiValueFacts = collectFacts(
            path = "pkg/multi.lua",
            source = "return a, b"
        )

        assertEquals(DocumentFacts.ReturnExportShapeKind.IDENTIFIER, identifierFacts.returnHint.kind)
        assertEquals("M", identifierFacts.returnHint.identifierName)
        assertEquals(DocumentFacts.ReturnExportShapeKind.TABLE_LITERAL, tableFacts.returnHint.kind)
        assertEquals(DocumentFacts.ReturnExportShapeKind.MULTI_VALUE, multiValueFacts.returnHint.kind)
    }

    @Test
    fun collects_export_write_anchors_for_member_assignments_and_functions() {
        val facts = collectFacts(
            path = "pkg/runtime.lua",
            source = """
                local M = {}
                M.value = 1
                _M["name"] = 2
                function M.run() end
                do
                    M.nested.value = 3
                end
                local function inner()
                    M.hidden = 4
                end
            """.trimIndent()
        )

        assertEquals(
            listOf(
                Triple("M", "value", DocumentFacts.ExportWriteAnchorKind.MEMBER_ASSIGNMENT),
                Triple("_M", "name", DocumentFacts.ExportWriteAnchorKind.INDEX_ASSIGNMENT),
                Triple("M", "run", DocumentFacts.ExportWriteAnchorKind.FUNCTION_DECLARATION),
                Triple("M", "nested.value", DocumentFacts.ExportWriteAnchorKind.MEMBER_ASSIGNMENT)
            ),
            facts.exportWriteAnchors.map {
                Triple(it.rootIdentifier, it.accessPath.joinToString("."), it.kind)
            }
        )
    }

    @Test
    fun collects_top_level_bare_identifier_assignment_and_function_anchors() {
        val facts = collectFacts(
            path = "pkg/runtime.lua",
            source = """
                value = 1
                function run() end
            """.trimIndent()
        )

        assertEquals(
            listOf(
                Triple("value", "", DocumentFacts.ExportWriteAnchorKind.BARE_ASSIGNMENT),
                Triple("run", "", DocumentFacts.ExportWriteAnchorKind.BARE_FUNCTION_DECLARATION)
            ),
            facts.exportWriteAnchors.map {
                Triple(it.rootIdentifier, it.accessPath.joinToString("."), it.kind)
            }
        )
    }

    @Test
    fun nested_function_bare_writes_do_not_produce_export_anchors() {
        val facts = collectFacts(
            path = "pkg/runtime.lua",
            source = """
                local function inner()
                    value = 1
                    function run() end
                end
            """.trimIndent()
        )

        assertEquals(emptyList(), facts.exportWriteAnchors)
    }


    @Test
    fun collects_source_imports_and_jvm_class_loads_from_direct_and_aliased_import_calls() {
        val facts = collectFacts(
            path = "pkg/runtime.lua",
            source = """
                local import = require("import")
                import("java.util.Locale")
                import "android.content.Context"
            """.trimIndent()
        )

        assertEquals(
            listOf("java.util.Locale", "android.content.Context"),
            facts.sourceImports.map { it.target }
        )
        assertEquals(
            listOf(
                DocumentFacts.JvmClassLoadKind.IMPORT_CALL,
                DocumentFacts.JvmClassLoadKind.IMPORT_CALL
            ),
            facts.jvmClassLoads.map { it.kind }
        )
        assertEquals(
            listOf("java.util.Locale", "android.content.Context"),
            facts.jvmClassLoads.map { it.target }
        )
    }

    @Test
    fun collects_source_imports_and_jvm_class_loads_from_import_table_arguments() {
        val facts = collectFacts(
            path = "pkg/runtime.lua",
            source = """
                local import = require("import")
                import({ "java.util.Locale", "android.content.Context" })
            """.trimIndent()
        )

        assertEquals(
            listOf("java.util.Locale", "android.content.Context"),
            facts.sourceImports.map { it.target }
        )
        assertEquals(
            listOf(
                DocumentFacts.JvmClassLoadKind.IMPORT_CALL,
                DocumentFacts.JvmClassLoadKind.IMPORT_CALL
            ),
            facts.jvmClassLoads.map { it.kind }
        )
        assertEquals(
            listOf("java.util.Locale", "android.content.Context"),
            facts.jvmClassLoads.map { it.target }
        )
    }

    @Test
    fun collects_source_imports_and_jvm_class_loads_from_dex_prefixed_import_calls() {
        val facts = collectFacts(
            path = "pkg/runtime.lua",
            source = """
                local import = require("import")
                import("plugin.dex:android.content.Context")
            """.trimIndent()
        )

        assertEquals(
            listOf("plugin.dex:android.content.Context"),
            facts.sourceImports.map { it.target }
        )
        assertEquals(
            listOf(DocumentFacts.JvmClassLoadKind.IMPORT_CALL),
            facts.jvmClassLoads.map { it.kind }
        )
        assertEquals(
            listOf("plugin.dex:android.content.Context"),
            facts.jvmClassLoads.map { it.target }
        )
    }

    @Test
    fun collects_jvm_bind_class_loads_from_direct_aliased_and_realiased_bind_calls() {
        val facts = collectFacts(
            path = "pkg/runtime.lua",
            source = """
                local bindClass = luajava.bindClass
                local bind = bindClass
                local Context = bind "android.content.Context"
                local Locale = luajava.bindClass("java.util.Locale")
            """.trimIndent()
        )

        assertEquals(emptyList(), facts.sourceImports)
        assertEquals(
            listOf(
                DocumentFacts.JvmClassLoadKind.BIND_CLASS_CALL,
                DocumentFacts.JvmClassLoadKind.BIND_CLASS_CALL
            ),
            facts.jvmClassLoads.map { it.kind }
        )
        assertEquals(
            listOf("android.content.Context", "java.util.Locale"),
            facts.jvmClassLoads.map { it.target }
        )
    }

    @Test
    fun collects_jvm_new_instance_loads_from_direct_aliased_and_realiased_calls() {
        val facts = collectFacts(
            path = "pkg/runtime.lua",
            source = """
                local newInstance = luajava.newInstance
                local create = newInstance
                local first = create("java.lang.StringBuilder")
                local second = luajava.newInstance "java.lang.String"
            """.trimIndent()
        )

        assertEquals(emptyList(), facts.sourceImports)
        assertEquals(
            listOf(
                DocumentFacts.JvmClassLoadKind.NEW_INSTANCE_CALL,
                DocumentFacts.JvmClassLoadKind.NEW_INSTANCE_CALL
            ),
            facts.jvmClassLoads.map { it.kind }
        )
        assertEquals(
            listOf("java.lang.StringBuilder", "java.lang.String"),
            facts.jvmClassLoads.map { it.target }
        )
    }

    @Test
    fun collects_jvm_create_proxy_loads_from_direct_aliased_and_realiased_calls() {
        val facts = collectFacts(
            path = "pkg/runtime.lua",
            source = """
                local createProxy = luajava.createProxy
                local create = createProxy
                local listener = create("java.lang.Runnable", {})
                local proxy = luajava.createProxy("java.util.Comparator", {})
            """.trimIndent()
        )

        assertEquals(emptyList(), facts.sourceImports)
        assertEquals(
            listOf(
                DocumentFacts.JvmClassLoadKind.CREATE_PROXY_CALL,
                DocumentFacts.JvmClassLoadKind.CREATE_PROXY_CALL
            ),
            facts.jvmClassLoads.map { it.kind }
        )
        assertEquals(
            listOf("java.lang.Runnable", "java.util.Comparator"),
            facts.jvmClassLoads.map { it.target }
        )
    }


    @Test
    fun collects_jvm_create_proxy_loads_for_all_string_interface_arguments_before_implementation() {
        val facts = collectFacts(
            path = "pkg/runtime.lua",
            source = """
                local proxy = luajava.createProxy("java.lang.Runnable", "java.util.Comparator", {})
            """.trimIndent()
        )

        assertEquals(emptyList(), facts.sourceImports)
        assertEquals(
            listOf(
                DocumentFacts.JvmClassLoadKind.CREATE_PROXY_CALL,
                DocumentFacts.JvmClassLoadKind.CREATE_PROXY_CALL
            ),
            facts.jvmClassLoads.map { it.kind }
        )
        assertEquals(
            listOf("java.lang.Runnable", "java.util.Comparator"),
            facts.jvmClassLoads.map { it.target }
        )
    }


    @Test
    fun collects_jvm_load_lib_loads_from_short_string_calls_with_trailing_member_name_arguments() {
        val facts = collectFacts(
            path = "pkg/runtime.lua",
            source = """
                local open = luajava.loadLib "java.lang.System", "currentTimeMillis"
            """.trimIndent()
        )

        assertEquals(emptyList(), facts.sourceImports)
        assertEquals(
            listOf(DocumentFacts.JvmClassLoadKind.LOAD_LIB_CALL),
            facts.jvmClassLoads.map { it.kind }
        )
        assertEquals(
            listOf("java.lang.System"),
            facts.jvmClassLoads.map { it.target }
        )
    }

    @Test
    fun derives_module_name_candidates_from_virtual_path_and_module_call() {
        val facts = collectFacts(
            path = "pkg/runtime/init.lua",
            source = "module(\"pkg.runtime.override\")"
        )

        assertEquals(
            listOf(
                "pkg.runtime" to DocumentFacts.ModuleNameCandidateSource.VIRTUAL_PATH,
                // init.lua also claims the explicit "pkg.runtime.init" form so
                // require("pkg.runtime.init") / require("pkg.init") style barrels resolve.
                "pkg.runtime.init" to DocumentFacts.ModuleNameCandidateSource.VIRTUAL_PATH,
                "pkg.runtime.override" to DocumentFacts.ModuleNameCandidateSource.LEGACY_MODULE_CALL
            ),
            facts.moduleNameCandidates.map { it.moduleName to it.source }
        )
    }

    @Test
    fun deduplicates_repeated_module_name_candidates_by_module_name_and_source() {
        val facts = collectFacts(
            path = "pkg/runtime.lua",
            source = """
                module("same.name")
                module("same.name")
            """.trimIndent()
        )

        assertEquals(
            listOf(
                "pkg.runtime" to DocumentFacts.ModuleNameCandidateSource.VIRTUAL_PATH,
                "same.name" to DocumentFacts.ModuleNameCandidateSource.LEGACY_MODULE_CALL
            ),
            facts.moduleNameCandidates.map { it.moduleName to it.source }
        )
    }

    @Test
    fun starts_environment_mode_switch_after_triggering_module_call() {
        val facts = collectFacts(
            path = "pkg/runtime.lua",
            source = """
                module("pkg.runtime")
                local value = 1
            """.trimIndent()
        )

        val trigger = facts.legacyModuleCalls.single().range
        val initialSegment = facts.environmentSegments[0]
        val switchedSegment = facts.environmentSegments[1]

        assertEquals(ModuleEnvironmentMode.CHUNK, initialSegment.mode)
        assertEquals(trigger.end, initialSegment.end)
        assertEquals(ModuleEnvironmentMode.LEGACY_MODULE, switchedSegment.mode)
        assertEquals(trigger.end, switchedSegment.start)
        assertEquals(trigger, switchedSegment.triggerRange)
    }

    @Test
    fun fingerprint_changes_when_fact_payload_changes() {
        val baseline = collectFacts(
            path = "pkg/runtime.lua",
            source = """
                require "alpha"
                return { value = 1 }
            """.trimIndent()
        )
        val whitespaceVariant = collectFacts(
            path = "pkg/runtime.lua",
            source = """
                -- spacing only
                require("alpha")

                return { value = 1 }
            """.trimIndent()
        )
        val payloadVariant = collectFacts(
            path = "pkg/runtime.lua",
            source = """
                require "beta"
                return { value = 1 }
            """.trimIndent()
        )

        assertEquals(baseline.fingerprint, whitespaceVariant.fingerprint)
        assertNotEquals(baseline.fingerprint, payloadVariant.fingerprint)
    }

    private fun collectFacts(path: String, source: String): DocumentFacts {
        val chunk = LuaParser().parse(source)
        return DocumentFactsCollector.collect(VirtualPath.of(path), chunk)
    }

    @Test
    fun require_alias_bound_to_bare_require_collects_require_fact() {
        val facts = collectFacts(
            path = "main.lua",
            source = "local r = require\nlocal m = r(\"mods.util\")\nreturn m"
        )
        assertEquals(listOf("mods.util"), facts.requires.map { it.moduleName })
    }

    @Test
    fun luajava_self_require_keeps_helper_facts() {
        val facts = collectFacts(
            path = "main.lua",
            source = "local luajava = require \'luajava\'\n" +
                "local String = luajava.bindClass(\"java.lang.String\")\nreturn String"
        )
        assertEquals(
            listOf(DocumentFacts.JvmClassLoadKind.BIND_CLASS_CALL),
            facts.jvmClassLoads.map { it.kind }
        )
    }

    @Test
    fun require_alias_shadowing_rebind_retires_the_alias() {
        val facts = collectFacts(
            path = "main.lua",
            source = "local r = require\nlocal r = fake\nr(\"mods.util\")"
        )
        assertEquals(emptyList(), facts.requires.map { it.moduleName })
    }


    @Test
    fun require_alias_retired_through_nested_block_rebind() {
        val facts = collectFacts(
            path = "main.lua",
            source = "local r = require\ndo r = fake end\nr(\"mods.util\")"
        )
        assertEquals(emptyList(), facts.requires.map { it.moduleName })
    }

    @Test
    fun local_require_rebind_stops_fabricating_bare_require_facts() {
        // `local require = print` writes a false tombstone over the "require" name; bare
        // require calls after it go through isRequireAliasCall (no hard-coded name check)
        // and must not fabricate require facts.
        val facts = collectFacts(
            path = "main.lua",
            source = "local require = print\nrequire(\"mods.util\")"
        )
        assertEquals(emptyList(), facts.requires.map { it.moduleName })
        assertEquals(emptyList(), facts.dynamicRequires.map { it.kind })
    }

    @Test
    fun bare_require_before_local_rebind_keeps_its_fact() {
        // The file-scope seed survives bare require calls (calls never write tombstones);
        // only the local re-bind retires it, and only for calls after the re-bind.
        val facts = collectFacts(
            path = "main.lua",
            source = "require(\"mods.alpha\")\nlocal require = print\nrequire(\"mods.beta\")"
        )
        assertEquals(listOf("mods.alpha"), facts.requires.map { it.moduleName })
    }

    @Test
    fun require_alias_reassigned_to_module_result_retires_the_alias() {
        // `local r = require` then `r = require "r"`: the self-require identity exemption
        // keeps the alias-kind skip, but the tombstone must still be retired — the name
        // now holds a module value, so later calls through it are not require facts.
        // The assignment itself stays a real require of module "r".
        val facts = collectFacts(
            path = "main.lua",
            source = "local r = require\nr = require \"r\"\nr(\"mods.util\")"
        )
        assertEquals(listOf("r"), facts.requires.map { it.moduleName })
    }

    @Test
    fun require_alias_module_rebind_inside_nested_block_retires_the_outer_alias() {
        // Same retirement, boundary-targeted: the do-block re-bind of a name whose alias
        // entry lives in the enclosing scope must retire THAT scope's tombstone.
        val facts = collectFacts(
            path = "main.lua",
            source = "local r = require\ndo r = require \"r\" end\nr(\"mods.util\")"
        )
        assertEquals(listOf("r"), facts.requires.map { it.moduleName })
    }

}
