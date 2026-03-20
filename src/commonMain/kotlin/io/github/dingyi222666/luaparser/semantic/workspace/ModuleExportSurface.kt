package io.github.dingyi222666.luaparser.semantic.workspace

import io.github.dingyi222666.luaparser.parser.ast.node.Range
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import io.github.dingyi222666.luaparser.semantic.types.model.ModuleType
import io.github.dingyi222666.luaparser.semantic.types.model.Type

data class ModuleExportSurface(
    val moduleType: ModuleType,
    val sourceForm: SourceForm,
    val hasSeeAllFallback: Boolean = false,
    val moduleEnvironmentMode: ModuleEnvironmentMode? = null,
    val members: List<MemberExport> = emptyList()
) {
    data class MemberExport(
        val name: String,
        val exportPath: List<String>,
        val kind: SymbolKind,
        val type: Type,
        val range: Range?
    )

    enum class SourceForm {
        LEGACY_IMPLICIT,
        RETURN_IDENTIFIER,
        RETURN_TABLE_LITERAL
    }
}
