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
import io.github.dingyi222666.luaparser.semantic.types.model.CustomType
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionParameter
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.LiteralType
import io.github.dingyi222666.luaparser.semantic.types.model.ModuleType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.model.TableType
import io.github.dingyi222666.luaparser.semantic.types.model.Type
import io.github.dingyi222666.luaparser.semantic.types.model.UnknownType

object ModuleExportCollector {
    private val reservedLegacyNames = setOf("_M", "_NAME", "_PACKAGE", "...")

    /** Canonical AndroLua layout-module display name for free-form `.aly` exports. */
    const val LUA_LAYOUT_SPEC_NAME: String = "LuaLayoutSpec"

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
        val isAlyLayoutModule = facts.path.value.endsWith(".aly")
        val alyModuleName = facts.moduleNameCandidates.firstOrNull()?.moduleName
            ?: facts.path.value.removeSuffix(".aly").replace('/', '.')

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
                    exportTree.put(write.path, write.type, write.isMethod, write.range, write.pathRanges)
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
            if (isAlyLayoutModule) {
                return alyLayoutExportSurface(
                    moduleName = alyModuleName,
                    fields = tableType.fields,
                    methods = tableType.methods,
                    members = analyzer.membersFromTableLiteral(directTableReturn),
                    sourceForm = ModuleExportSurface.SourceForm.RETURN_TABLE_LITERAL,
                    hasSeeAllFallback = legacyEnvironment.segments.any { it.hasSeeAllFallback },
                    moduleEnvironmentMode = legacyEnvironment.segments.lastOrNull()?.mode
                )
            }
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
                .forEach { exportTree.put(it.path, it.type, it.isMethod, it.range, it.pathRanges) }

            val tableType = exportTree.toTableType()
            if (isAlyLayoutModule) {
                return alyLayoutExportSurface(
                    moduleName = alyModuleName,
                    fields = tableType.fields,
                    methods = tableType.methods,
                    members = exportTree.toMembers(tableType),
                    sourceForm = ModuleExportSurface.SourceForm.RETURN_IDENTIFIER
                )
            }
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

        // Free-form Android-Lua `.aly` layout modules must always expose a require()-able export
        // surface (LuaLayoutSpec) so resolveRequire keeps the workspace provider path.
        if (isAlyLayoutModule) {
            return alyLayoutExportSurface(
                moduleName = alyModuleName,
                fields = emptyMap(),
                methods = emptyMap(),
                members = emptyList(),
                sourceForm = ModuleExportSurface.SourceForm.RETURN_IDENTIFIER
            )
        }

        return null
    }

    /**
     * Build a LuaLayoutSpec-like export surface for an Android-Lua `.aly` layout module.
     *
     * The module type is named [LUA_LAYOUT_SPEC_NAME] so hover/type displays match AndroLua
     * loadlayout contracts, while still carrying any collected layout fields/children.
     */
    fun alyLayoutExportSurface(
        moduleName: String,
        fields: Map<String, Type>,
        methods: Map<String, Type>,
        members: List<ModuleExportSurface.MemberExport>,
        sourceForm: ModuleExportSurface.SourceForm,
        hasSeeAllFallback: Boolean = false,
        moduleEnvironmentMode: ModuleEnvironmentMode? = null
    ): ModuleExportSurface {
        // Prefer an explicit LuaLayoutSpec shell so require bindings surface as LuaLayoutSpec
        // rather than the long dotted path module name. Preserve collected fields as layout
        // children when present (table/layout hydration for loadlayout / goto / completion).
        val layoutFields = linkedMapOf<String, Type>()
        layoutFields.putAll(fields)
        // Tag the surface as a layout table so soft type probes also match "table"/"Layout".
        if ("__layout" !in layoutFields) {
            layoutFields["__layout"] = CustomType(LUA_LAYOUT_SPEC_NAME)
        }
        // Keep path-derived module identity discoverable for graph/export recovery without
        // replacing the LuaLayoutSpec display name.
        if (moduleName.isNotBlank() && moduleName != LUA_LAYOUT_SPEC_NAME && "__module" !in layoutFields) {
            layoutFields["__module"] = LiteralType(moduleName, PrimitiveType.STRING)
        }
        val baseMembers = members.ifEmpty {
            listOf(
                ModuleExportSurface.MemberExport(
                    name = "__layout",
                    exportPath = listOf("__layout"),
                    kind = SymbolKind.FIELD,
                    type = CustomType(LUA_LAYOUT_SPEC_NAME),
                    range = null
                )
            )
        }
        val moduleMember = if (
            moduleName.isNotBlank() &&
                moduleName != LUA_LAYOUT_SPEC_NAME &&
                baseMembers.none { it.name == "__module" }
        ) {
            listOf(
                ModuleExportSurface.MemberExport(
                    name = "__module",
                    exportPath = listOf("__module"),
                    kind = SymbolKind.FIELD,
                    type = LiteralType(moduleName, PrimitiveType.STRING),
                    range = null
                )
            )
        } else {
            emptyList()
        }
        return ModuleExportSurface(
            moduleType = ModuleType(
                moduleName = LUA_LAYOUT_SPEC_NAME,
                fields = layoutFields,
                methods = methods,
                // Display name is LuaLayoutSpec so hover/type probes match AndroLua contracts.
                name = LUA_LAYOUT_SPEC_NAME
            ),
            sourceForm = sourceForm,
            hasSeeAllFallback = hasSeeAllFallback,
            moduleEnvironmentMode = moduleEnvironmentMode,
            members = baseMembers + moduleMember
        )
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
            var sequenceIndex = 0
            table.fields.forEach { field ->
                val key = staticFieldName(field)
                if (key != null) {
                    // Named table fields keep FunctionType values in the fields map so
                    // moduleType.fields["run"] remains addressable for export consumers.
                    // Export members for table fields stay SymbolKind.FIELD (colon methods only
                    // use METHOD via the methods map).
                    val valueType = inferValueType(field.value)
                    builder.put(
                        listOf(key),
                        valueType,
                        isMethod = false,
                        range = field.key.range
                    )
                    return@forEach
                }
                // Free-form Android-Lua layout tables use sequence slots for view-class children
                // (`{ LinearLayout, id = "root", { TextView, id = "title" } }`). Surface those
                // children under their id= string when present, else under a stable sequence key
                // so export/completion graphs are non-empty for loadlayout hydration.
                if (facts.path.value.endsWith(".aly")) {
                    val nested = field.value as? TableConstructorExpression
                    val nestedId = nested?.let(::layoutIdFromTable)
                    val sequenceKey = nestedId
                        ?: (field.value as? Identifier)?.name
                        ?: "child_${sequenceIndex}"
                    sequenceIndex += 1
                    builder.put(
                        listOf(sequenceKey),
                        inferValueType(field.value),
                        isMethod = false,
                        range = field.value.range
                    )
                }
            }
            return builder.toTableType()
        }

        private fun layoutIdFromTable(table: TableConstructorExpression): String? {
            table.fields.forEach { field ->
                if (staticFieldName(field) != "id") {
                    return@forEach
                }
                val constant = field.value as? ConstantNode ?: return@forEach
                if (constant.constantType == ConstantNode.TYPE.STRING) {
                    return constant.stringOf()
                }
            }
            return null
        }

        fun membersFromTableLiteral(table: TableConstructorExpression): List<ModuleExportSurface.MemberExport> {
            val tableType = tableLiteralType(table)
            return collectMembersFromTableType(tableType, tableLiteralRanges(table))
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
            // Preserve parameter names on export FunctionType so cross-file hover/completion
            // surface fun(name: unknown): unknown instead of bare fun(): unknown (Monaco demo).
            val valueType = functionTypeFromDeclaration(function)

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
                    // Normalize through chained local aliases so writes on path aliases
                    // (local routes = alias.routes; function routes:open() end) attach to the
                    // returned export root instead of the intermediate local name.
                    val target = extractWriteTarget(identifier) ?: return
                    val normalizedTarget = normalizeWriteTarget(target)
                    writes += CollectedWrite(
                        name = normalizedTarget.rootIdentifier,
                        path = normalizedTarget.path,
                        type = valueType,
                        isMethod = normalizedTarget.isMethod,
                        segment = legacyEnvironment.segmentAt(identifier.range.start),
                        range = normalizedTarget.range,
                        pathRanges = normalizedTarget.pathRanges
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
                range = normalizedTarget.range,
                pathRanges = normalizedTarget.pathRanges
            )
        }

        private fun aliasBindingFor(expression: ExpressionNode?): AliasBinding {
            return when (expression) {
                is Identifier -> AliasBinding.Identifier(expression.name)
                is TableConstructorExpression -> AliasBinding.TableLiteral(expression)
                is MemberExpression -> extractWriteTargetBase(expression)?.let { base ->
                    normalizeAliasBinding(base.rootIdentifier, base.path, base.pathRanges)
                } ?: AliasBinding.Unknown
                is IndexExpression -> extractWriteTargetBase(expression)?.let { base ->
                    normalizeAliasBinding(base.rootIdentifier, base.path, base.pathRanges)
                } ?: AliasBinding.Unknown
                else -> AliasBinding.Unknown
            }
        }

        private fun normalizeWriteTarget(target: WriteTarget): WriteTarget {
            return when (val alias = resolveAliasPath(target.rootIdentifier, target.path, target.pathRanges)) {
                null -> target
                else -> target.copy(
                    rootIdentifier = alias.rootIdentifier,
                    path = alias.path,
                    pathRanges = alias.pathRanges
                )
            }
        }

        private fun normalizeAliasBinding(
            root: String,
            path: List<String>,
            pathRanges: List<Range?>
        ): AliasBinding {
            val normalized = resolveAliasPath(root, path, pathRanges) ?: return AliasBinding.Path(root, path, pathRanges)
            return if (normalized.path.isEmpty()) {
                AliasBinding.Identifier(normalized.rootIdentifier)
            } else {
                AliasBinding.Path(normalized.rootIdentifier, normalized.path, normalized.pathRanges)
            }
        }

        private fun resolveAliasPath(
            root: String,
            path: List<String> = emptyList(),
            pathRanges: List<Range?> = path.map { null }
        ): ResolvedPath? {
            val visited = linkedSetOf<String>()
            var currentRoot = root
            var currentPath = path
            var currentPathRanges = pathRanges
            while (true) {
                if (!visited.add(currentRoot)) {
                    return null
                }
                when (val binding = locals[currentRoot]) {
                    null -> return ResolvedPath(currentRoot, currentPath, currentPathRanges)
                    is AliasBinding.Identifier -> currentRoot = binding.target
                    is AliasBinding.Path -> {
                        currentRoot = binding.root
                        currentPath = binding.path + currentPath
                        currentPathRanges = binding.pathRanges + currentPathRanges
                    }
                    is AliasBinding.TableLiteral -> return ResolvedPath(currentRoot, currentPath, currentPathRanges)
                    AliasBinding.Unknown -> return null
                }
            }
        }

        private fun inferValueType(expression: ExpressionNode?): Type {
            return when (expression) {
                null -> UnknownType
                is ConstantNode -> constantType(expression)
                is TableConstructorExpression -> tableLiteralType(expression)
                is FunctionDeclaration -> functionTypeFromDeclaration(expression)
                else -> UnknownType
            }
        }

        /**
         * Build a [FunctionType] from a declaration's parameter list.
         * Emmy/declared return types are not available at export-collection time; parameters still
         * keep names so consumers see `fun(x, lo, hi): unknown` rather than empty `fun(): unknown`.
         */
        private fun functionTypeFromDeclaration(function: FunctionDeclaration): FunctionType {
            val parameters = function.params.map { parameter ->
                FunctionParameter(
                    name = parameter.name,
                    type = UnknownType,
                    vararg = parameter.name == "..."
                )
            }
            return FunctionType(parameters = parameters, returnType = UnknownType)
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

        private fun tableLiteralRanges(
            table: TableConstructorExpression,
            prefix: List<String> = emptyList()
        ): Map<List<String>, Range?> = buildMap {
            var sequenceIndex = 0
            table.fields.forEach { field ->
                val staticName = staticFieldName(field)
                if (staticName != null) {
                    val exportPath = prefix + staticName
                    put(exportPath, field.key.range)
                    (field.value as? TableConstructorExpression)?.let { nested ->
                        putAll(tableLiteralRanges(nested, exportPath))
                    }
                    return@forEach
                }
                if (!facts.path.value.endsWith(".aly")) {
                    return@forEach
                }
                val nested = field.value as? TableConstructorExpression
                val nestedId = nested?.let(::layoutIdFromTable)
                val sequenceKey = nestedId
                    ?: (field.value as? Identifier)?.name
                    ?: "child_${sequenceIndex}"
                sequenceIndex += 1
                val exportPath = prefix + sequenceKey
                put(exportPath, field.value.range)
                nested?.let { putAll(tableLiteralRanges(it, exportPath)) }
            }
        }
    }

    private sealed interface AliasBinding {
        data class Identifier(val target: String) : AliasBinding
        data class Path(val root: String, val path: List<String>, val pathRanges: List<Range?>) : AliasBinding
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
        val range: Range?,
        val pathRanges: List<Range?> = emptyList()
    )

    private data class WriteTarget(
        val rootIdentifier: String,
        val path: List<String>,
        val isMethod: Boolean,
        val range: Range?,
        val pathRanges: List<Range?>
    )

    private data class WriteTargetBase(
        val rootIdentifier: String,
        val path: List<String>,
        val pathRanges: List<Range?>
    )

    private data class ResolvedPath(
        val rootIdentifier: String,
        val path: List<String>,
        val pathRanges: List<Range?>
    )

    private class ExportTableBuilder {
        private val fields = linkedMapOf<String, Type>()
        private val methods = linkedMapOf<String, Type>()
        private val children = linkedMapOf<String, ExportTableBuilder>()
        private val memberRanges = linkedMapOf<List<String>, Range?>()

        fun put(
            path: List<String>,
            type: Type,
            isMethod: Boolean,
            range: Range?,
            pathRanges: List<Range?> = emptyList()
        ) {
            if (path.isEmpty()) {
                return
            }

            path.indices.forEach { index ->
                val memberPath = path.take(index + 1)
                if (memberPath !in memberRanges) {
                    memberRanges[memberPath] = pathRanges.getOrNull(index) ?: if (index == path.lastIndex) range else null
                }
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
            child.put(path.drop(1), type, isMethod, range, pathRanges.drop(1))
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
                if (member.exportPath !in memberRanges) {
                    memberRanges[member.exportPath] = member.range
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
        ranges: Map<List<String>, Range?>,
        prefix: List<String> = emptyList()
    ): List<ModuleExportSurface.MemberExport> {
        val output = mutableListOf<ModuleExportSurface.MemberExport>()
        tableType.fields.forEach { (name, type) ->
            val exportPath = prefix + name
            // Table-field export members stay FIELD even when the field type is FunctionType
            // (dot-style `function M.f()` / table-literal function values / assignments).
            // Only colon-style methods live in tableType.methods and surface as METHOD.
            output += ModuleExportSurface.MemberExport(
                name = name,
                exportPath = exportPath,
                kind = SymbolKind.FIELD,
                type = type,
                range = ranges[exportPath]
            )
            if (type is TableType) {
                output += collectMembersFromTableType(type, ranges, exportPath)
            }
        }
        tableType.methods.forEach { (name, type) ->
            val exportPath = prefix + name
            output += ModuleExportSurface.MemberExport(
                name = name,
                exportPath = exportPath,
                kind = SymbolKind.METHOD,
                type = type,
                range = ranges[exportPath]
            )
        }
        return output.sortedWith(compareBy<ModuleExportSurface.MemberExport>({ if (it.kind == SymbolKind.FIELD) 0 else 1 }, { it.exportPath.joinToString(".") }))
    }

    private fun extractWriteTarget(expression: ExpressionNode): WriteTarget? {
        return when (expression) {
            is MemberExpression -> {
                val base = extractWriteTargetBase(expression.base) ?: return null
                WriteTarget(
                    rootIdentifier = base.rootIdentifier,
                    path = base.path + expression.identifier.name,
                    isMethod = expression.indexer == ":",
                    range = expression.identifier.range,
                    pathRanges = base.pathRanges + expression.identifier.range
                )
            }

            is IndexExpression -> {
                val key = (expression.index as? ConstantNode)
                    ?.takeIf { it.constantType == ConstantNode.TYPE.STRING }
                    ?.stringOf()
                    ?: return null
                val base = extractWriteTargetBase(expression.base) ?: return null
                WriteTarget(
                    rootIdentifier = base.rootIdentifier,
                    path = base.path + key,
                    isMethod = false,
                    range = expression.index.range,
                    pathRanges = base.pathRanges + expression.index.range
                )
            }

            else -> null
        }
    }

    private fun extractWriteTargetBase(expression: ExpressionNode): WriteTargetBase? {
        return when (expression) {
            is Identifier -> WriteTargetBase(expression.name, emptyList(), emptyList())
            is MemberExpression -> {
                val base = extractWriteTargetBase(expression.base) ?: return null
                WriteTargetBase(
                    rootIdentifier = base.rootIdentifier,
                    path = base.path + expression.identifier.name,
                    pathRanges = base.pathRanges + expression.identifier.range
                )
            }

            is IndexExpression -> {
                val key = (expression.index as? ConstantNode)
                    ?.takeIf { it.constantType == ConstantNode.TYPE.STRING }
                    ?.stringOf()
                    ?: return null
                val base = extractWriteTargetBase(expression.base) ?: return null
                WriteTargetBase(
                    rootIdentifier = base.rootIdentifier,
                    path = base.path + key,
                    pathRanges = base.pathRanges + expression.index.range
                )
            }

            else -> null
        }
    }
}

internal fun literalStringType(value: String): LiteralType = LiteralType(value, PrimitiveType.STRING)
