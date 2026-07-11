package io.github.dingyi222666.luaparser.semantic.workspace

import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.parser.ast.node.Range

data class DocumentFacts(
    val path: VirtualPath,
    val fingerprint: String,
    val moduleNameCandidates: List<ModuleNameCandidate> = emptyList(),
    val requires: List<RequireFact> = emptyList(),
    val dynamicRequires: List<DynamicRequireFact> = emptyList(),
    val legacyModuleCalls: List<LegacyModuleCallFact> = emptyList(),
    val sourceImports: List<SourceImportFact> = emptyList(),
    val jvmClassLoads: List<JvmClassLoadFact> = emptyList(),
    val returnHint: ReturnExportShapeHint = ReturnExportShapeHint.none(),
    val environmentSegments: List<EnvironmentSegment> = emptyList(),
    val exportWriteAnchors: List<ExportWriteAnchor> = emptyList()
) {
    data class ModuleNameCandidate(
        val moduleName: String,
        val source: ModuleNameCandidateSource,
        val range: Range? = null
    )

    enum class ModuleNameCandidateSource {
        VIRTUAL_PATH,
        LEGACY_MODULE_CALL
    }

    data class RequireFact(
        val moduleName: String,
        val range: Range
    )

    data class DynamicRequireFact(
        val kind: DynamicRequireKind,
        val range: Range
    )

    enum class DynamicRequireKind {
        MISSING_ARGUMENT,
        NON_STRING_LITERAL
    }

    data class LegacyModuleCallFact(
        val moduleName: String,
        val mode: ModuleEnvironmentMode,
        val range: Range,
        val isTopLevel: Boolean
    )

    data class SourceImportFact(
        val target: String,
        val range: Range
    )

    data class JvmClassLoadFact(
        val target: String,
        val kind: JvmClassLoadKind,
        val range: Range
    )

    enum class JvmClassLoadKind {
        IMPORT_CALL,
        BIND_CLASS_CALL,
        NEW_INSTANCE_CALL,
        CREATE_PROXY_CALL,
        LOAD_LIB_CALL,
        CREATE_ARRAY_CALL
    }

    data class ReturnExportShapeHint(
        val kind: ReturnExportShapeKind,
        val identifierName: String? = null,
        val range: Range? = null
    ) {
        companion object {
            fun none(): ReturnExportShapeHint = ReturnExportShapeHint(ReturnExportShapeKind.NONE)
        }
    }

    enum class ReturnExportShapeKind {
        NONE,
        IDENTIFIER,
        TABLE_LITERAL,
        UNKNOWN,
        MULTI_VALUE
    }

    data class EnvironmentSegment(
        val mode: ModuleEnvironmentMode,
        val start: Position,
        val end: Position,
        val triggerRange: Range? = null
    )

    data class ExportWriteAnchor(
        val rootIdentifier: String,
        val accessPath: List<String>,
        val kind: ExportWriteAnchorKind,
        val range: Range
    )

    enum class ExportWriteAnchorKind {
        BARE_ASSIGNMENT,
        BARE_FUNCTION_DECLARATION,
        MEMBER_ASSIGNMENT,
        INDEX_ASSIGNMENT,
        FUNCTION_DECLARATION
    }
}
