package semantic.workspace

import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.parser.ast.node.Range
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionParameter
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.ModuleType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.model.TableType
import io.github.dingyi222666.luaparser.semantic.types.model.Type
import io.github.dingyi222666.luaparser.semantic.workspace.DocumentFacts
import io.github.dingyi222666.luaparser.semantic.workspace.ModuleEnvironmentMode
import io.github.dingyi222666.luaparser.semantic.workspace.ModuleExportSurface
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspacePublicFingerprint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * TASK-239 corpus: WorkspacePublicFingerprint stability.
 *
 * Acceptance:
 * - Public fingerprints are stable across reorder-equivalent inputs.
 * - Public fingerprints change when exports change.
 * - Document private-only edit fingerprint policy (test-only docs below).
 *
 * Test-only; product code is out of scope. Verification is review-owned (no Gradle here).
 *
 * ## Private-only edit fingerprint policy (product contract encoded here)
 *
 * [WorkspacePublicFingerprint.from] hashes **only**:
 * 1. **Provided module names** derived from [DocumentFacts] via top-level
 *    [DocumentFacts.LegacyModuleCallFact] entries and
 *    [DocumentFacts.ModuleNameCandidateSource.VIRTUAL_PATH] candidates
 *    (sorted for stability).
 * 2. **Public export surface** serialization of [ModuleExportSurface]:
 *    `sourceForm`, `hasSeeAllFallback`, `moduleEnvironmentMode`, and the
 *    [ModuleType] name / fields / methods / index signature (map keys sorted).
 *
 * Explicitly **not** part of the public fingerprint payload:
 * - [DocumentFacts.fingerprint] (body / full-facts hash)
 * - requires, dynamic requires, source imports, JVM loads, return hints,
 *   environment segments, export write anchors
 * - [ModuleExportSurface.members] (member export list / ranges); the public
 *   API is represented by [ModuleType.fields] / [ModuleType.methods]
 *
 * Therefore a **private-only edit** is any change that mutates private body
 * state (facts fingerprint, cache key, local-only symbols, non-provider
 * facts) while leaving provided module names and the serialized export
 * surface identical. Such edits keep [WorkspacePublicFingerprint.value]
 * stable. Downstream, [io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceDirtySetPlanner]
 * treats public-surface dirtiness as a change in that value only, so
 * private-only edits do not reverse-dirty dependents.
 */
class WorkspacePublicFingerprintStabilityTddTest {

    private val path = VirtualPath.of("src/lib/mod.lua")

    // -------------------------------------------------------------------------
    // Reorder-equivalent stability
    // -------------------------------------------------------------------------

    @Test
    fun field_map_insertion_order_does_not_change_public_fingerprint() {
        val alphaFirst = surface(
            fields = linkedMapOf(
                "alpha" to PrimitiveType.NUMBER,
                "beta" to PrimitiveType.STRING,
                "gamma" to PrimitiveType.BOOLEAN
            )
        )
        val reverseOrder = surface(
            fields = linkedMapOf(
                "gamma" to PrimitiveType.BOOLEAN,
                "beta" to PrimitiveType.STRING,
                "alpha" to PrimitiveType.NUMBER
            )
        )
        val shuffled = surface(
            fields = linkedMapOf(
                "beta" to PrimitiveType.STRING,
                "alpha" to PrimitiveType.NUMBER,
                "gamma" to PrimitiveType.BOOLEAN
            )
        )

        val a = fingerprint(surface = alphaFirst)
        val b = fingerprint(surface = reverseOrder)
        val c = fingerprint(surface = shuffled)

        assertEquals(a.value, b.value, "field map reorder must be fingerprint-stable")
        assertEquals(a.value, c.value, "field map shuffle must be fingerprint-stable")
        assertEquals(a.providedModuleNames, b.providedModuleNames)
    }

    @Test
    fun method_map_insertion_order_does_not_change_public_fingerprint() {
        val methodsForward = linkedMapOf(
            "open" to functionOf(PrimitiveType.STRING, PrimitiveType.NUMBER),
            "close" to functionOf(returnType = PrimitiveType.NIL),
            "flush" to functionOf(returnType = PrimitiveType.BOOLEAN)
        )
        val methodsReverse = linkedMapOf(
            "flush" to functionOf(returnType = PrimitiveType.BOOLEAN),
            "close" to functionOf(returnType = PrimitiveType.NIL),
            "open" to functionOf(PrimitiveType.STRING, PrimitiveType.NUMBER)
        )

        val a = fingerprint(surface = surface(methods = methodsForward))
        val b = fingerprint(surface = surface(methods = methodsReverse))

        assertEquals(a.value, b.value, "method map reorder must be fingerprint-stable")
    }

    @Test
    fun nested_table_field_reorder_is_fingerprint_stable() {
        val nestedA = TableType(
            fields = linkedMapOf(
                "x" to PrimitiveType.NUMBER,
                "y" to PrimitiveType.NUMBER,
                "label" to PrimitiveType.STRING
            )
        )
        val nestedB = TableType(
            fields = linkedMapOf(
                "label" to PrimitiveType.STRING,
                "y" to PrimitiveType.NUMBER,
                "x" to PrimitiveType.NUMBER
            )
        )

        val a = fingerprint(surface = surface(fields = mapOf("point" to nestedA)))
        val b = fingerprint(surface = surface(fields = mapOf("point" to nestedB)))

        assertEquals(a.value, b.value, "nested table field reorder must be fingerprint-stable")
    }

    @Test
    fun provided_module_name_set_is_order_independent() {
        val factsA = facts(
            fingerprint = "body-v1",
            moduleNameCandidates = listOf(
                DocumentFacts.ModuleNameCandidate(
                    moduleName = "src.lib.mod",
                    source = DocumentFacts.ModuleNameCandidateSource.VIRTUAL_PATH
                ),
                DocumentFacts.ModuleNameCandidate(
                    moduleName = "extra.provider",
                    source = DocumentFacts.ModuleNameCandidateSource.VIRTUAL_PATH
                )
            ),
            legacyModuleCalls = listOf(
                legacyCall("legacy.a", isTopLevel = true),
                legacyCall("legacy.b", isTopLevel = true)
            )
        )
        val factsB = facts(
            fingerprint = "body-v1",
            moduleNameCandidates = listOf(
                DocumentFacts.ModuleNameCandidate(
                    moduleName = "extra.provider",
                    source = DocumentFacts.ModuleNameCandidateSource.VIRTUAL_PATH
                ),
                DocumentFacts.ModuleNameCandidate(
                    moduleName = "src.lib.mod",
                    source = DocumentFacts.ModuleNameCandidateSource.VIRTUAL_PATH
                )
            ),
            legacyModuleCalls = listOf(
                legacyCall("legacy.b", isTopLevel = true),
                legacyCall("legacy.a", isTopLevel = true)
            )
        )

        val a = fingerprint(facts = factsA, surface = surface())
        val b = fingerprint(facts = factsB, surface = surface())

        assertEquals(a.value, b.value, "provider list reorder must be fingerprint-stable")
        assertEquals(
            setOf("src.lib.mod", "extra.provider", "legacy.a", "legacy.b"),
            a.providedModuleNames
        )
        assertEquals(a.providedModuleNames, b.providedModuleNames)
    }

    @Test
    fun union_of_identical_fields_and_methods_maps_is_stable_across_construction_styles() {
        // MapOf vs linkedMapOf with different insertion order must still hash equal.
        val surfaceMapOf = surface(
            fields = mapOf(
                "z" to PrimitiveType.NUMBER,
                "a" to PrimitiveType.STRING
            ),
            methods = mapOf(
                "m2" to functionOf(returnType = PrimitiveType.BOOLEAN),
                "m1" to functionOf(returnType = PrimitiveType.NUMBER)
            )
        )
        val surfaceLinked = surface(
            fields = linkedMapOf(
                "a" to PrimitiveType.STRING,
                "z" to PrimitiveType.NUMBER
            ),
            methods = linkedMapOf(
                "m1" to functionOf(returnType = PrimitiveType.NUMBER),
                "m2" to functionOf(returnType = PrimitiveType.BOOLEAN)
            )
        )

        assertEquals(
            fingerprint(surface = surfaceMapOf).value,
            fingerprint(surface = surfaceLinked).value
        )
    }

    // -------------------------------------------------------------------------
    // Export change sensitivity
    // -------------------------------------------------------------------------

    @Test
    fun adding_export_field_changes_public_fingerprint() {
        val before = fingerprint(
            surface = surface(fields = mapOf("alpha" to PrimitiveType.NUMBER))
        )
        val after = fingerprint(
            surface = surface(
                fields = mapOf(
                    "alpha" to PrimitiveType.NUMBER,
                    "beta" to PrimitiveType.STRING
                )
            )
        )

        assertNotEquals(before.value, after.value, "new export field must change public fingerprint")
    }

    @Test
    fun removing_export_field_changes_public_fingerprint() {
        val before = fingerprint(
            surface = surface(
                fields = mapOf(
                    "alpha" to PrimitiveType.NUMBER,
                    "beta" to PrimitiveType.STRING
                )
            )
        )
        val after = fingerprint(
            surface = surface(fields = mapOf("alpha" to PrimitiveType.NUMBER))
        )

        assertNotEquals(before.value, after.value, "removed export field must change public fingerprint")
    }

    @Test
    fun changing_export_field_type_changes_public_fingerprint() {
        val before = fingerprint(
            surface = surface(fields = mapOf("value" to PrimitiveType.NUMBER))
        )
        val after = fingerprint(
            surface = surface(fields = mapOf("value" to PrimitiveType.STRING))
        )

        assertNotEquals(before.value, after.value, "export field type change must change public fingerprint")
    }

    @Test
    fun changing_export_method_signature_changes_public_fingerprint() {
        val before = fingerprint(
            surface = surface(
                methods = mapOf("run" to functionOf(PrimitiveType.NUMBER, returnType = PrimitiveType.BOOLEAN))
            )
        )
        val after = fingerprint(
            surface = surface(
                methods = mapOf("run" to functionOf(PrimitiveType.STRING, returnType = PrimitiveType.BOOLEAN))
            )
        )

        assertNotEquals(before.value, after.value, "method parameter type change must change public fingerprint")
    }

    @Test
    fun changing_source_form_changes_public_fingerprint() {
        val identifier = fingerprint(
            surface = surface(sourceForm = ModuleExportSurface.SourceForm.RETURN_IDENTIFIER)
        )
        val tableLiteral = fingerprint(
            surface = surface(sourceForm = ModuleExportSurface.SourceForm.RETURN_TABLE_LITERAL)
        )
        val legacy = fingerprint(
            surface = surface(sourceForm = ModuleExportSurface.SourceForm.LEGACY_IMPLICIT)
        )

        assertNotEquals(identifier.value, tableLiteral.value)
        assertNotEquals(identifier.value, legacy.value)
        assertNotEquals(tableLiteral.value, legacy.value)
    }

    @Test
    fun changing_module_name_on_export_surface_changes_public_fingerprint() {
        val before = fingerprint(surface = surface(moduleName = "pkg.a"))
        val after = fingerprint(surface = surface(moduleName = "pkg.b"))

        assertNotEquals(before.value, after.value)
    }

    @Test
    fun changing_see_all_fallback_flag_changes_public_fingerprint() {
        val without = fingerprint(surface = surface(hasSeeAllFallback = false))
        val with = fingerprint(surface = surface(hasSeeAllFallback = true))

        assertNotEquals(without.value, with.value)
    }

    @Test
    fun changing_module_environment_mode_changes_public_fingerprint() {
        val chunk = fingerprint(
            surface = surface(moduleEnvironmentMode = ModuleEnvironmentMode.CHUNK)
        )
        val legacyModule = fingerprint(
            surface = surface(moduleEnvironmentMode = ModuleEnvironmentMode.LEGACY_MODULE)
        )
        val seeAll = fingerprint(
            surface = surface(moduleEnvironmentMode = ModuleEnvironmentMode.LEGACY_MODULE_SEEALL)
        )
        val none = fingerprint(surface = surface(moduleEnvironmentMode = null))

        assertNotEquals(chunk.value, legacyModule.value)
        assertNotEquals(legacyModule.value, seeAll.value)
        assertNotEquals(chunk.value, none.value)
    }

    @Test
    fun changing_index_signature_changes_public_fingerprint() {
        val without = fingerprint(surface = surface())
        val withIndex = fingerprint(
            surface = surface(
                indexSignature = ModuleType.IndexSignature(
                    keyType = PrimitiveType.STRING,
                    valueType = PrimitiveType.NUMBER
                )
            )
        )
        val otherIndex = fingerprint(
            surface = surface(
                indexSignature = ModuleType.IndexSignature(
                    keyType = PrimitiveType.STRING,
                    valueType = PrimitiveType.BOOLEAN
                )
            )
        )

        assertNotEquals(without.value, withIndex.value)
        assertNotEquals(withIndex.value, otherIndex.value)
    }

    @Test
    fun adding_provided_module_name_changes_public_fingerprint() {
        val single = fingerprint(
            facts = facts(
                fingerprint = "body",
                moduleNameCandidates = listOf(
                    DocumentFacts.ModuleNameCandidate(
                        moduleName = "src.lib.mod",
                        source = DocumentFacts.ModuleNameCandidateSource.VIRTUAL_PATH
                    )
                )
            ),
            surface = surface()
        )
        val dual = fingerprint(
            facts = facts(
                fingerprint = "body",
                moduleNameCandidates = listOf(
                    DocumentFacts.ModuleNameCandidate(
                        moduleName = "src.lib.mod",
                        source = DocumentFacts.ModuleNameCandidateSource.VIRTUAL_PATH
                    ),
                    DocumentFacts.ModuleNameCandidate(
                        moduleName = "src.lib.extra",
                        source = DocumentFacts.ModuleNameCandidateSource.VIRTUAL_PATH
                    )
                )
            ),
            surface = surface()
        )

        assertNotEquals(single.value, dual.value)
        assertTrue("src.lib.extra" in dual.providedModuleNames)
        assertTrue("src.lib.extra" !in single.providedModuleNames)
    }

    // -------------------------------------------------------------------------
    // Private-only edit policy
    // -------------------------------------------------------------------------

    @Test
    fun private_only_document_facts_fingerprint_change_keeps_public_fingerprint_stable() {
        // Policy: DocumentFacts.fingerprint is intentionally excluded from the
        // public payload. Body / full-facts hash churn must not reverse-dirty
        // dependents when the export surface and providers stay the same.
        val surface = surface(
            fields = mapOf(
                "publicApi" to PrimitiveType.NUMBER,
                "helper" to functionOf(returnType = PrimitiveType.STRING)
            )
        )
        val providers = listOf(
            DocumentFacts.ModuleNameCandidate(
                moduleName = "src.lib.mod",
                source = DocumentFacts.ModuleNameCandidateSource.VIRTUAL_PATH
            )
        )

        val before = fingerprint(
            facts = facts(fingerprint = "facts-body-v1", moduleNameCandidates = providers),
            surface = surface
        )
        val after = fingerprint(
            facts = facts(fingerprint = "facts-body-v2-private-edit", moduleNameCandidates = providers),
            surface = surface
        )

        assertEquals(
            before.value,
            after.value,
            "private-only DocumentFacts.fingerprint change must keep public fingerprint stable"
        )
        assertEquals(before.providedModuleNames, after.providedModuleNames)
    }

    @Test
    fun private_only_non_provider_facts_churn_keeps_public_fingerprint_stable() {
        // Requires, imports, JVM loads, return hints, anchors, and nested
        // (non-top-level) module() calls are private to analysis of this file.
        val surface = surface(fields = mapOf("export" to PrimitiveType.NUMBER))
        val baseProviders = listOf(
            DocumentFacts.ModuleNameCandidate(
                moduleName = "src.lib.mod",
                source = DocumentFacts.ModuleNameCandidateSource.VIRTUAL_PATH
            )
        )

        val lean = fingerprint(
            facts = facts(
                fingerprint = "lean",
                moduleNameCandidates = baseProviders
            ),
            surface = surface
        )
        val noisy = fingerprint(
            facts = facts(
                fingerprint = "noisy-private",
                moduleNameCandidates = baseProviders + listOf(
                    // LEGACY_MODULE_CALL candidates are not providers for the fingerprint.
                    DocumentFacts.ModuleNameCandidate(
                        moduleName = "ignored.legacy.candidate",
                        source = DocumentFacts.ModuleNameCandidateSource.LEGACY_MODULE_CALL
                    )
                ),
                requires = listOf(
                    DocumentFacts.RequireFact(
                        moduleName = "dep.a",
                        range = range(1, 1, 1, 10)
                    )
                ),
                dynamicRequires = listOf(
                    DocumentFacts.DynamicRequireFact(
                        kind = DocumentFacts.DynamicRequireKind.NON_STRING_LITERAL,
                        range = range(2, 1, 2, 12)
                    )
                ),
                legacyModuleCalls = listOf(
                    // Nested / non-top-level module() is not a public provider.
                    legacyCall("nested.only", isTopLevel = false)
                ),
                sourceImports = listOf(
                    DocumentFacts.SourceImportFact(
                        target = "java.util.List",
                        range = range(3, 1, 3, 20)
                    )
                ),
                jvmClassLoads = listOf(
                    DocumentFacts.JvmClassLoadFact(
                        target = "java.lang.String",
                        kind = DocumentFacts.JvmClassLoadKind.IMPORT_CALL,
                        range = range(4, 1, 4, 25)
                    )
                ),
                returnHint = DocumentFacts.ReturnExportShapeHint(
                    kind = DocumentFacts.ReturnExportShapeKind.IDENTIFIER,
                    identifierName = "M",
                    range = range(10, 1, 10, 8)
                ),
                environmentSegments = listOf(
                    DocumentFacts.EnvironmentSegment(
                        mode = ModuleEnvironmentMode.LEGACY_MODULE,
                        start = Position(1, 1),
                        end = Position(20, 1)
                    )
                ),
                exportWriteAnchors = listOf(
                    DocumentFacts.ExportWriteAnchor(
                        rootIdentifier = "M",
                        accessPath = listOf("export"),
                        kind = DocumentFacts.ExportWriteAnchorKind.MEMBER_ASSIGNMENT,
                        range = range(5, 1, 5, 12)
                    )
                )
            ),
            surface = surface
        )

        assertEquals(
            lean.value,
            noisy.value,
            "non-provider DocumentFacts churn must not affect public fingerprint"
        )
        assertEquals(setOf("src.lib.mod"), lean.providedModuleNames)
        assertEquals(setOf("src.lib.mod"), noisy.providedModuleNames)
    }

    @Test
    fun member_export_list_churn_without_module_type_change_keeps_public_fingerprint_stable() {
        // Policy: ModuleExportSurface.members (names/ranges/kinds) are not part
        // of the public fingerprint serialization; ModuleType fields/methods are.
        val moduleType = ModuleType(
            moduleName = "src.lib.mod",
            fields = mapOf("export" to PrimitiveType.NUMBER)
        )
        val withoutMembers = ModuleExportSurface(
            moduleType = moduleType,
            sourceForm = ModuleExportSurface.SourceForm.RETURN_IDENTIFIER,
            members = emptyList()
        )
        val withMembers = ModuleExportSurface(
            moduleType = moduleType,
            sourceForm = ModuleExportSurface.SourceForm.RETURN_IDENTIFIER,
            members = listOf(
                ModuleExportSurface.MemberExport(
                    name = "export",
                    exportPath = listOf("export"),
                    kind = SymbolKind.FIELD,
                    type = PrimitiveType.NUMBER,
                    range = range(1, 1, 1, 6)
                )
            )
        )

        assertEquals(
            fingerprint(surface = withoutMembers).value,
            fingerprint(surface = withMembers).value,
            "members list is private to symbol queries; public fingerprint follows ModuleType only"
        )
    }

    @Test
    fun private_only_edit_then_export_change_still_detects_public_delta() {
        val providers = listOf(
            DocumentFacts.ModuleNameCandidate(
                moduleName = "src.lib.mod",
                source = DocumentFacts.ModuleNameCandidateSource.VIRTUAL_PATH
            )
        )
        val privateV1 = fingerprint(
            facts = facts(fingerprint = "body-1", moduleNameCandidates = providers),
            surface = surface(fields = mapOf("export" to PrimitiveType.NUMBER))
        )
        val privateV2SameExport = fingerprint(
            facts = facts(fingerprint = "body-2", moduleNameCandidates = providers),
            surface = surface(fields = mapOf("export" to PrimitiveType.NUMBER))
        )
        val publicChanged = fingerprint(
            facts = facts(fingerprint = "body-3", moduleNameCandidates = providers),
            surface = surface(fields = mapOf("export" to PrimitiveType.STRING))
        )

        assertEquals(privateV1.value, privateV2SameExport.value)
        assertNotEquals(privateV2SameExport.value, publicChanged.value)
    }

    @Test
    fun null_document_facts_yields_empty_providers_and_surface_only_fingerprint() {
        val withNullFacts = WorkspacePublicFingerprint.from(
            documentFacts = null,
            moduleExportSurface = surface(fields = mapOf("x" to PrimitiveType.NUMBER))
        )
        val withEmptyProviders = fingerprint(
            facts = facts(fingerprint = "anything"),
            surface = surface(fields = mapOf("x" to PrimitiveType.NUMBER))
        )

        assertTrue(withNullFacts.providedModuleNames.isEmpty())
        assertEquals(withNullFacts.value, withEmptyProviders.value)
    }

    @Test
    fun null_export_surface_still_hashes_providers() {
        val factsOnly = WorkspacePublicFingerprint.from(
            documentFacts = facts(
                fingerprint = "body",
                moduleNameCandidates = listOf(
                    DocumentFacts.ModuleNameCandidate(
                        moduleName = "src.lib.mod",
                        source = DocumentFacts.ModuleNameCandidateSource.VIRTUAL_PATH
                    )
                )
            ),
            moduleExportSurface = null
        )
        val emptySurfaceEquivalent = WorkspacePublicFingerprint.from(
            documentFacts = facts(
                fingerprint = "different-private-body",
                moduleNameCandidates = listOf(
                    DocumentFacts.ModuleNameCandidate(
                        moduleName = "src.lib.mod",
                        source = DocumentFacts.ModuleNameCandidateSource.VIRTUAL_PATH
                    )
                )
            ),
            moduleExportSurface = null
        )

        assertEquals(setOf("src.lib.mod"), factsOnly.providedModuleNames)
        assertEquals(factsOnly.value, emptySurfaceEquivalent.value)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun fingerprint(
        facts: DocumentFacts? = facts(fingerprint = "facts-default"),
        surface: ModuleExportSurface?
    ): WorkspacePublicFingerprint = WorkspacePublicFingerprint.from(facts, surface)

    private fun surface(
        moduleName: String = "src.lib.mod",
        fields: Map<String, Type> = emptyMap(),
        methods: Map<String, Type> = emptyMap(),
        indexSignature: ModuleType.IndexSignature? = null,
        sourceForm: ModuleExportSurface.SourceForm = ModuleExportSurface.SourceForm.RETURN_IDENTIFIER,
        hasSeeAllFallback: Boolean = false,
        moduleEnvironmentMode: ModuleEnvironmentMode? = null,
        members: List<ModuleExportSurface.MemberExport> = emptyList()
    ): ModuleExportSurface = ModuleExportSurface(
        moduleType = ModuleType(
            moduleName = moduleName,
            fields = fields,
            methods = methods,
            indexSignature = indexSignature
        ),
        sourceForm = sourceForm,
        hasSeeAllFallback = hasSeeAllFallback,
        moduleEnvironmentMode = moduleEnvironmentMode,
        members = members
    )

    private fun facts(
        fingerprint: String,
        moduleNameCandidates: List<DocumentFacts.ModuleNameCandidate> = emptyList(),
        requires: List<DocumentFacts.RequireFact> = emptyList(),
        dynamicRequires: List<DocumentFacts.DynamicRequireFact> = emptyList(),
        legacyModuleCalls: List<DocumentFacts.LegacyModuleCallFact> = emptyList(),
        sourceImports: List<DocumentFacts.SourceImportFact> = emptyList(),
        jvmClassLoads: List<DocumentFacts.JvmClassLoadFact> = emptyList(),
        returnHint: DocumentFacts.ReturnExportShapeHint = DocumentFacts.ReturnExportShapeHint.none(),
        environmentSegments: List<DocumentFacts.EnvironmentSegment> = emptyList(),
        exportWriteAnchors: List<DocumentFacts.ExportWriteAnchor> = emptyList()
    ): DocumentFacts = DocumentFacts(
        path = path,
        fingerprint = fingerprint,
        moduleNameCandidates = moduleNameCandidates,
        requires = requires,
        dynamicRequires = dynamicRequires,
        legacyModuleCalls = legacyModuleCalls,
        sourceImports = sourceImports,
        jvmClassLoads = jvmClassLoads,
        returnHint = returnHint,
        environmentSegments = environmentSegments,
        exportWriteAnchors = exportWriteAnchors
    )

    private fun legacyCall(
        moduleName: String,
        isTopLevel: Boolean,
        mode: ModuleEnvironmentMode = ModuleEnvironmentMode.LEGACY_MODULE
    ): DocumentFacts.LegacyModuleCallFact = DocumentFacts.LegacyModuleCallFact(
        moduleName = moduleName,
        mode = mode,
        range = range(1, 1, 1, 8),
        isTopLevel = isTopLevel
    )

    private fun functionOf(
        vararg parameterTypes: Type,
        returnType: Type = PrimitiveType.UNKNOWN
    ): FunctionType = FunctionType(
        parameters = parameterTypes.mapIndexed { index, type ->
            FunctionParameter(name = "p$index", type = type)
        },
        returnType = returnType
    )

    private fun range(
        startLine: Int,
        startColumn: Int,
        endLine: Int,
        endColumn: Int
    ): Range = Range(
        start = Position(startLine, startColumn),
        end = Position(endLine, endColumn)
    )
}
