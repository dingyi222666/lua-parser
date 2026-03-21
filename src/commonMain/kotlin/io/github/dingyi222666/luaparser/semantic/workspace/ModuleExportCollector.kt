package io.github.dingyi222666.luaparser.semantic.workspace

import io.github.dingyi222666.luaparser.parser.ast.node.AssignmentStatement
import io.github.dingyi222666.luaparser.parser.ast.node.BlockNode
import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.ConstantNode
import io.github.dingyi222666.luaparser.parser.ast.node.DoStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ElseClause
import io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode
import io.github.dingyi222666.luaparser.parser.ast.node.ForGenericStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ForNumericStatement
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.IfClause
import io.github.dingyi222666.luaparser.parser.ast.node.IfStatement
import io.github.dingyi222666.luaparser.parser.ast.node.IndexExpression
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.parser.ast.node.MemberExpression
import io.github.dingyi222666.luaparser.parser.ast.node.Range
import io.github.dingyi222666.luaparser.parser.ast.node.RepeatStatement
import io.github.dingyi222666.luaparser.parser.ast.node.StatementNode
import io.github.dingyi222666.luaparser.parser.ast.node.SwitchStatement
import io.github.dingyi222666.luaparser.parser.ast.node.TableConstructorExpression
import io.github.dingyi222666.luaparser.parser.ast.node.TableKey
import io.github.dingyi222666.luaparser.parser.ast.node.WhenStatement
import io.github.dingyi222666.luaparser.parser.ast.node.WhileStatement
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.LiteralType
import io.github.dingyi222666.luaparser.semantic.types.model.ModuleType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.model.TableType
import io.github.dingyi222666.luaparser.semantic.types.model.Type
import io.github.dingyi222666.luaparser.semantic.types.model.UnknownType

object ModuleExportCollector {
    private val reservedLegacyNames = setOf("_M", "_NAME", "_PACKAGE", "...")

    fun collect(
        chunk: ChunkNode,
        facts: DocumentFacts,
        legacyEnvironment: LegacyModuleEnvironment
    ): ModuleExportSurface? {
        val analyzer = Analyzer(facts, legacyEnvironment)
        analyzer.visitBlock(chunk.body)

        val exportRoot = facts.returnHint.identifierName?.let(analyzer::resolveAlias)
        val explicitRootNames = buildSet {
            exportRoot?.aliases?.forEach(::add)
        }
        val exportTree = ExportTableBuilder()

        val legacySegment = legacyEnvironment.segments.lastOrNull()
        if (legacySegment != null) {
            analyzer.writes.forEach { write ->
                if (write.path.isEmpty()) {
                    if (write.segment != null && write.name !in reservedLegacyNames) {
                        exportTree.put(listOf(write.name), write.type, write.isMethod, write.range)
                    }
                    return@forEach
                }

                if (write.segment != null && (write.name == "_M" || write.name in explicitRootNames)) {
                    exportTree.put(write.path, write.type, write.isMethod, write.range)
                }
            }

            val tableType = exportTree.toTableType()
            return ModuleExportSurface(
                moduleType = ModuleType(
                    moduleName = legacySegment.moduleName,
                    fields = tableType.fields,
                    methods = tableType.methods
                ),
                sourceForm = ModuleExportSurface.SourceForm.LEGACY_IMPLICIT,
                hasSeeAllFallback = legacySegment.hasSeeAllFallback,
                moduleEnvironmentMode = legacySegment.mode,
                members = exportTree.toMembers(tableType)
            )
        }

        val directTableReturn = when (val returned = chunk.body.returnStatement?.arguments?.singleOrNull()) {
            is TableConstructorExpression -> returned
            else -> null
        }
        if (directTableReturn != null) {
            val tableType = analyzer.tableLiteralType(directTableReturn)
            return ModuleExportSurface(
                moduleType = ModuleType(
                    moduleName = facts.moduleNameCandidates.firstOrNull()?.moduleName ?: facts.path.value,
                    fields = tableType.fields,
                    methods = tableType.methods
                ),
                sourceForm = ModuleExportSurface.SourceForm.RETURN_TABLE_LITERAL,
                hasSeeAllFallback = legacyEnvironment.segments.any { it.hasSeeAllFallback },
                moduleEnvironmentMode = legacyEnvironment.segments.lastOrNull()?.mode,
                members = analyzer.membersFromTableLiteral(directTableReturn)
            )
        }

        if (exportRoot != null) {
            exportRoot.tableLiteral?.let {
                exportTree.putTable(analyzer.tableLiteralType(it), analyzer.membersFromTableLiteral(it))
            }
            analyzer.writes
                .filter { it.path.isNotEmpty() && it.name in explicitRootNames }
                .forEach { exportTree.put(it.path, it.type, it.isMethod, it.range) }

            val tableType = exportTree.toTableType()
            return ModuleExportSurface(
                moduleType = ModuleType(
                    moduleName = facts.moduleNameCandidates.firstOrNull()?.moduleName ?: exportRoot.identifier,
                    fields = tableType.fields,
                    methods = tableType.methods
                ),
                sourceForm = ModuleExportSurface.SourceForm.RETURN_IDENTIFIER,
                members = exportTree.toMembers(tableType)
            )
        }

        return null
    }

    private class Analyzer(
        private val facts: DocumentFacts,
        private val legacyEnvironment: LegacyModuleEnvironment
    ) {
        val writes = mutableListOf<CollectedWrite>()
        private val locals = linkedMapOf<String, AliasBinding>()

        fun visitBlock(block: BlockNode) {
            block.statements.forEach(::visitStatement)
        }

        fun resolveAlias(name: String): ResolvedRoot? {
            val aliases = linkedSetOf<String>()
            var current = name

            while (true) {
                if (!aliases.add(current)) {
                    return null
                }
                when (val binding = locals[current]) {
                    null -> return ResolvedRoot(identifier = current, aliases = aliases)
                    is AliasBinding.Identifier -> current = binding.target
                    is AliasBinding.Path -> {
                        if (binding.path.isNotEmpty()) {
                            return null
                        }
                        current = binding.root
                    }
                    is AliasBinding.TableLiteral -> {
                        return ResolvedRoot(
                            identifier = current,
                            aliases = aliases,
                            tableLiteral = binding.table
                        )
                    }

                    AliasBinding.Unknown -> return null
                }
            }
        }

        fun tableLiteralType(table: TableConstructorExpression): TableType {
            val builder = ExportTableBuilder()
            table.fields.forEach { field ->
                val key = staticFieldName(field) ?: return@forEach
                builder.put(listOf(key), inferValueType(field.value), isMethod = false, range = field.key.range)
            }
            return builder.toTableType()
        }

        fun membersFromTableLiteral(table: TableConstructorExpression): List<ModuleExportSurface.MemberExport> {
            val tableType = tableLiteralType(table)
            val ranges = buildMap<String, Range?> {
                table.fields.forEach { field ->
                    val name = staticFieldName(field) ?: return@forEach
                    put(name, field.key.range)
                }
            }
            return collectMembersFromTableType(tableType, ranges)
        }

        private fun visitStatement(statement: StatementNode) {
            when (statement) {
                is LocalStatement -> visitLocalStatement(statement)
                is AssignmentStatement -> visitAssignmentStatement(statement)
                is FunctionDeclaration -> visitFunctionDeclaration(statement)
                is DoStatement -> visitBlock(statement.body)
                is IfStatement -> statement.causes.forEach(::visitIfClause)
                is WhileStatement -> visitBlock(statement.body)
                is RepeatStatement -> visitBlock(statement.body)
                is ForNumericStatement -> visitBlock(statement.body)
                is ForGenericStatement -> visitBlock(statement.body)
                is WhenStatement -> {
                    visitStatement(statement.ifCause)
                    statement.elseCause?.let(::visitStatement)
                }

                is SwitchStatement -> statement.causes.forEach { cause ->
                    when (cause) {
                        is io.github.dingyi222666.luaparser.parser.ast.node.CaseCause -> visitBlock(cause.body)
                        is io.github.dingyi222666.luaparser.parser.ast.node.DefaultCause -> visitBlock(cause.body)
                    }
                }
            }
        }

        private fun visitIfClause(clause: IfClause) {
            visitBlock(clause.body)
        }

        private fun visitLocalStatement(statement: LocalStatement) {
            statement.init.forEachIndexed { index, variable ->
                locals[variable.name] = aliasBindingFor(statement.variables.getOrNull(index))
            }
        }

        private fun visitAssignmentStatement(statement: AssignmentStatement) {
            statement.init.forEachIndexed { index, target ->
                val value = statement.variables.getOrNull(index)
                collectWrite(target, value)
                (target as? Identifier)?.let { locals[it.name] = AliasBinding.Unknown }
            }
        }

        private fun visitFunctionDeclaration(function: FunctionDeclaration) {
            val identifier = function.identifier ?: return
            val valueType = FunctionType()

            when (identifier) {
                is Identifier -> {
                    if (function.isLocal) {
                        return
                    }
                    writes += CollectedWrite(
                        name = identifier.name,
                        path = emptyList(),
                        type = valueType,
                        isMethod = false,
                        segment = legacyEnvironment.segmentAt(identifier.range.start),
                        range = identifier.range
                    )
                }

                else -> {
                    val target = extractWriteTarget(identifier) ?: return
                    writes += CollectedWrite(
                        name = target.rootIdentifier,
                        path = target.path,
                        type = valueType,
                        isMethod = target.isMethod,
                        segment = legacyEnvironment.segmentAt(identifier.range.start),
                        range = target.range
                    )
                }
            }
        }

        private fun collectWrite(target: ExpressionNode, value: ExpressionNode?) {
            val bareIdentifier = target as? Identifier
            if (bareIdentifier != null) {
                writes += CollectedWrite(
                    name = bareIdentifier.name,
                    path = emptyList(),
                    type = inferValueType(value),
                    isMethod = false,
                    segment = legacyEnvironment.segmentAt(target.range.start),
                    range = bareIdentifier.range
                )
                return
            }

            val writeTarget = extractWriteTarget(target) ?: return
            val normalizedTarget = normalizeWriteTarget(writeTarget)
            writes += CollectedWrite(
                name = normalizedTarget.rootIdentifier,
                path = normalizedTarget.path,
                type = inferValueType(value),
                isMethod = normalizedTarget.isMethod,
                segment = legacyEnvironment.segmentAt(target.range.start),
                range = normalizedTarget.range
            )
        }

        private fun aliasBindingFor(expression: ExpressionNode?): AliasBinding {
            return when (expression) {
                is Identifier -> AliasBinding.Identifier(expression.name)
                is TableConstructorExpression -> AliasBinding.TableLiteral(expression)
                is MemberExpression -> extractWriteTargetBase(expression)?.let { (root, path) ->
                    normalizeAliasBinding(root, path)
                } ?: AliasBinding.Unknown
                is IndexExpression -> extractWriteTargetBase(expression)?.let { (root, path) ->
                    normalizeAliasBinding(root, path)
                } ?: AliasBinding.Unknown
                else -> AliasBinding.Unknown
            }
        }

        private fun normalizeWriteTarget(target: WriteTarget): WriteTarget {
            return when (val alias = resolveAliasPath(target.rootIdentifier, target.path)) {
                null -> target
                else -> target.copy(rootIdentifier = alias.first, path = alias.second)
            }
        }

        private fun normalizeAliasBinding(root: String, path: List<String>): AliasBinding {
            val normalized = resolveAliasPath(root, path) ?: return AliasBinding.Path(root, path)
            return if (normalized.second.isEmpty()) {
                AliasBinding.Identifier(normalized.first)
            } else {
                AliasBinding.Path(normalized.first, normalized.second)
            }
        }

        private fun resolveAliasPath(root: String, path: List<String> = emptyList()): Pair<String, List<String>>? {
            val visited = linkedSetOf<String>()
            var currentRoot = root
            var currentPath = path
            while (true) {
                if (!visited.add(currentRoot)) {
                    return null
                }
                when (val binding = locals[currentRoot]) {
                    null -> return currentRoot to currentPath
                    is AliasBinding.Identifier -> currentRoot = binding.target
                    is AliasBinding.Path -> {
                        currentRoot = binding.root
                        currentPath = binding.path + currentPath
                    }
                    is AliasBinding.TableLiteral -> return currentRoot to currentPath
                    AliasBinding.Unknown -> return null
                }
            }
        }

        private fun inferValueType(expression: ExpressionNode?): Type {
            return when (expression) {
                null -> UnknownType
                is ConstantNode -> constantType(expression)
                is TableConstructorExpression -> tableLiteralType(expression)
                is FunctionDeclaration -> FunctionType()
                else -> UnknownType
            }
        }

        private fun constantType(node: ConstantNode): Type {
            return when (node.constantType) {
                ConstantNode.TYPE.INTERGER -> LiteralType(node.intOf(), PrimitiveType.NUMBER)
                ConstantNode.TYPE.FLOAT -> LiteralType(node.floatOf(), PrimitiveType.NUMBER)
                ConstantNode.TYPE.BOOLEAN -> LiteralType(node.rawValue.toString().toBooleanStrict(), PrimitiveType.BOOLEAN)
                ConstantNode.TYPE.STRING -> LiteralType(node.stringOf(), PrimitiveType.STRING)
                ConstantNode.TYPE.NIL -> LiteralType(null, PrimitiveType.NIL)
                ConstantNode.TYPE.UNKNOWN -> UnknownType
            }
        }

        private fun staticFieldName(field: TableKey): String? {
            val key = field.key
            return when (key) {
                is Identifier -> key.name
                is ConstantNode -> if (key.constantType == ConstantNode.TYPE.STRING) key.stringOf() else null
                else -> null
            }
        }
    }

    private sealed interface AliasBinding {
        data class Identifier(val target: String) : AliasBinding
        data class Path(val root: String, val path: List<String>) : AliasBinding
        data class TableLiteral(val table: TableConstructorExpression) : AliasBinding
        data object Unknown : AliasBinding
    }

    private data class ResolvedRoot(
        val identifier: String,
        val aliases: Set<String>,
        val tableLiteral: TableConstructorExpression? = null
    )

    private data class CollectedWrite(
        val name: String,
        val path: List<String>,
        val type: Type,
        val isMethod: Boolean,
        val segment: LegacyModuleEnvironment.Segment?,
        val range: Range?
    )

    private data class WriteTarget(
        val rootIdentifier: String,
        val path: List<String>,
        val isMethod: Boolean,
        val range: Range?
    )

    private class ExportTableBuilder {
        private val fields = linkedMapOf<String, Type>()
        private val methods = linkedMapOf<String, Type>()
        private val children = linkedMapOf<String, ExportTableBuilder>()
        private val memberRanges = linkedMapOf<String, Range?>()

        fun put(path: List<String>, type: Type, isMethod: Boolean, range: Range?) {
            if (path.isEmpty()) {
                return
            }

            if (path.first() !in memberRanges) {
                memberRanges[path.first()] = range
            }

            if (path.size == 1) {
                val name = path.first()
                if (isMethod) {
                    methods[name] = type
                    fields.remove(name)
                } else if (name !in methods) {
                    fields[name] = type
                }
                return
            }

            val head = path.first()
            val child = children.getOrPut(head) { ExportTableBuilder() }
            fields.remove(head)
            methods.remove(head)
            child.put(path.drop(1), type, isMethod, range)
        }

        fun toTableType(): TableType {
            val resolvedFields = fields.toMutableMap()
            children.forEach { (name, child) ->
                resolvedFields[name] = child.toTableType()
            }
            return TableType(fields = resolvedFields, methods = methods.toMap())
        }

        fun putTable(table: TableType, members: List<ModuleExportSurface.MemberExport> = emptyList()) {
            members.forEach { member ->
                if (member.name !in memberRanges) {
                    memberRanges[member.name] = member.range
                }
            }
            table.fields.forEach { (name, type) ->
                when (type) {
                    is TableType -> {
                        val child = children.getOrPut(name) { ExportTableBuilder() }
                        fields.remove(name)
                        methods.remove(name)
                        child.putTable(type)
                    }

                    else -> if (name !in methods) {
                        fields[name] = type
                    }
                }
            }

            table.methods.forEach { (name, type) ->
                methods[name] = type
                fields.remove(name)
                children.remove(name)
            }
        }

        fun toMembers(tableType: TableType): List<ModuleExportSurface.MemberExport> {
            return collectMembersFromTableType(tableType, memberRanges)
        }
    }

    private fun collectMembersFromTableType(
        tableType: TableType,
        ranges: Map<String, Range?>,
        prefix: List<String> = emptyList()
    ): List<ModuleExportSurface.MemberExport> {
        val output = mutableListOf<ModuleExportSurface.MemberExport>()
        tableType.fields.forEach { (name, type) ->
            val exportPath = prefix + name
            output += ModuleExportSurface.MemberExport(
                name = name,
                exportPath = exportPath,
                kind = SymbolKind.FIELD,
                type = type,
                range = ranges[name]
            )
            if (type is TableType) {
                output += collectMembersFromTableType(type, emptyMap(), exportPath)
            }
        }
        tableType.methods.forEach { (name, type) ->
            output += ModuleExportSurface.MemberExport(
                name = name,
                exportPath = prefix + name,
                kind = SymbolKind.METHOD,
                type = type,
                range = ranges[name]
            )
        }
        return output.sortedWith(compareBy<ModuleExportSurface.MemberExport>({ if (it.kind == SymbolKind.FIELD) 0 else 1 }, { it.exportPath.joinToString(".") }))
    }

    private fun extractWriteTarget(expression: ExpressionNode): WriteTarget? {
        return when (expression) {
            is MemberExpression -> {
                val base = extractWriteTargetBase(expression.base) ?: return null
                WriteTarget(
                    rootIdentifier = base.first,
                    path = base.second + expression.identifier.name,
                    isMethod = expression.indexer == ":",
                    range = expression.identifier.range
                )
            }

            is IndexExpression -> {
                val key = (expression.index as? ConstantNode)
                    ?.takeIf { it.constantType == ConstantNode.TYPE.STRING }
                    ?.stringOf()
                    ?: return null
                val base = extractWriteTargetBase(expression.base) ?: return null
                WriteTarget(
                    rootIdentifier = base.first,
                    path = base.second + key,
                    isMethod = false,
                    range = expression.index.range
                )
            }

            else -> null
        }
    }

    private fun extractWriteTargetBase(expression: ExpressionNode): Pair<String, List<String>>? {
        return when (expression) {
            is Identifier -> expression.name to emptyList()
            is MemberExpression -> {
                val base = extractWriteTargetBase(expression.base) ?: return null
                base.first to (base.second + expression.identifier.name)
            }

            is IndexExpression -> {
                val key = (expression.index as? ConstantNode)
                    ?.takeIf { it.constantType == ConstantNode.TYPE.STRING }
                    ?.stringOf()
                    ?: return null
                val base = extractWriteTargetBase(expression.base) ?: return null
                base.first to (base.second + key)
            }

            else -> null
        }
    }
}

internal fun literalStringType(value: String): LiteralType = LiteralType(value, PrimitiveType.STRING)
