package io.github.dingyi222666.luaparser.semantic.workspace

import io.github.dingyi222666.luaparser.parser.ast.node.AssignmentStatement
import io.github.dingyi222666.luaparser.parser.ast.node.BlockNode
import io.github.dingyi222666.luaparser.parser.ast.node.CallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.CallStatement
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
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.parser.ast.node.Range
import io.github.dingyi222666.luaparser.parser.ast.node.RepeatStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ReturnStatement
import io.github.dingyi222666.luaparser.parser.ast.node.StatementNode
import io.github.dingyi222666.luaparser.parser.ast.node.SwitchStatement
import io.github.dingyi222666.luaparser.parser.ast.node.TableConstructorExpression
import io.github.dingyi222666.luaparser.parser.ast.node.UnaryExpression
import io.github.dingyi222666.luaparser.parser.ast.node.WhenStatement
import io.github.dingyi222666.luaparser.parser.ast.node.WhileStatement
import io.github.dingyi222666.luaparser.parser.ast.node.BinaryExpression
import io.github.dingyi222666.luaparser.parser.ast.node.LambdaDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.ArrayConstructorExpression
import io.github.dingyi222666.luaparser.parser.ast.node.TableKey

object DocumentFactsCollector {
    fun collect(path: VirtualPath, chunk: ChunkNode): DocumentFacts {
        val state = CollectorState(path)
        state.addPathDerivedModuleNameCandidate()
        state.visitBlock(chunk.body, functionDepth = 0)
        val returnHint = state.collectReturnHint(chunk.body.returnStatement)
        val segments = state.buildEnvironmentSegments(chunk.range.start, chunk.range.end)
        val moduleNameCandidates = state.buildModuleNameCandidates()
        val fingerprint = buildFingerprint(
            path = path,
            moduleNameCandidates = moduleNameCandidates,
            requires = state.requires,
            dynamicRequires = state.dynamicRequires,
            legacyModuleCalls = state.legacyModuleCalls,
            returnHint = returnHint,
            environmentSegments = segments,
            exportWriteAnchors = state.exportWriteAnchors
        )

        return DocumentFacts(
            path = path,
            fingerprint = fingerprint,
            moduleNameCandidates = moduleNameCandidates,
            requires = state.requires.toList(),
            dynamicRequires = state.dynamicRequires.toList(),
            legacyModuleCalls = state.legacyModuleCalls.toList(),
            returnHint = returnHint,
            environmentSegments = segments,
            exportWriteAnchors = state.exportWriteAnchors.toList()
        )
    }

    private class CollectorState(private val path: VirtualPath) {
        val requires = mutableListOf<DocumentFacts.RequireFact>()
        val dynamicRequires = mutableListOf<DocumentFacts.DynamicRequireFact>()
        val legacyModuleCalls = mutableListOf<DocumentFacts.LegacyModuleCallFact>()
        val exportWriteAnchors = mutableListOf<DocumentFacts.ExportWriteAnchor>()
        private val moduleNameCandidates = linkedMapOf<ModuleNameCandidateKey, DocumentFacts.ModuleNameCandidate>()
        private val topLevelSegmentTriggers = mutableListOf<DocumentFacts.LegacyModuleCallFact>()

        fun addPathDerivedModuleNameCandidate() {
            deriveModuleNameFromPath(path)?.let { moduleName ->
                addModuleNameCandidate(
                    moduleName = moduleName,
                    source = DocumentFacts.ModuleNameCandidateSource.VIRTUAL_PATH
                )
            }
        }

        fun buildModuleNameCandidates(): List<DocumentFacts.ModuleNameCandidate> = moduleNameCandidates.values.toList()

        fun collectReturnHint(returnStatement: ReturnStatement?): DocumentFacts.ReturnExportShapeHint {
            if (returnStatement == null) {
                return DocumentFacts.ReturnExportShapeHint.none()
            }
            if (returnStatement.arguments.size != 1) {
                return DocumentFacts.ReturnExportShapeHint(
                    kind = DocumentFacts.ReturnExportShapeKind.MULTI_VALUE,
                    range = returnStatement.range
                )
            }

            return when (val argument = returnStatement.arguments.first()) {
                is Identifier -> DocumentFacts.ReturnExportShapeHint(
                    kind = DocumentFacts.ReturnExportShapeKind.IDENTIFIER,
                    identifierName = argument.name,
                    range = argument.range
                )

                is TableConstructorExpression -> DocumentFacts.ReturnExportShapeHint(
                    kind = DocumentFacts.ReturnExportShapeKind.TABLE_LITERAL,
                    range = argument.range
                )

                else -> DocumentFacts.ReturnExportShapeHint(
                    kind = DocumentFacts.ReturnExportShapeKind.UNKNOWN,
                    range = argument.range
                )
            }
        }

        fun buildEnvironmentSegments(documentStart: Position, documentEnd: Position): List<DocumentFacts.EnvironmentSegment> {
            val segments = mutableListOf<DocumentFacts.EnvironmentSegment>()
            var currentMode = ModuleEnvironmentMode.CHUNK
            var currentStart = documentStart
            var currentTrigger: Range? = null

            for (trigger in topLevelSegmentTriggers) {
                segments += DocumentFacts.EnvironmentSegment(
                    mode = currentMode,
                    start = currentStart,
                    end = trigger.range.end,
                    triggerRange = currentTrigger
                )
                currentMode = trigger.mode
                currentStart = trigger.range.end
                currentTrigger = trigger.range
            }

            segments += DocumentFacts.EnvironmentSegment(
                mode = currentMode,
                start = currentStart,
                end = documentEnd,
                triggerRange = currentTrigger
            )

            return segments
        }

        fun visitBlock(block: BlockNode, functionDepth: Int) {
            for (statement in block.statements) {
                visitStatement(statement, functionDepth)
            }
            block.returnStatement?.arguments?.forEach { visitExpression(it, functionDepth) }
        }

        private fun visitStatement(statement: StatementNode, functionDepth: Int) {
            when (statement) {
                is LocalStatement -> {
                    statement.variables.forEach { visitExpression(it, functionDepth) }
                }

                is AssignmentStatement -> {
                    if (functionDepth == 0) {
                        statement.init.forEach { target ->
                            collectAssignmentAnchor(target)
                        }
                    }
                    statement.init.forEach { visitExpression(it, functionDepth) }
                    statement.variables.forEach { visitExpression(it, functionDepth) }
                }

                is CallStatement -> {
                    visitExpression(statement.expression, functionDepth, isTopLevelStatementCall = functionDepth == 0)
                }

                is FunctionDeclaration -> {
                    if (functionDepth == 0) {
                        collectFunctionAnchor(statement)
                    }
                    statement.identifier?.let { visitExpression(it, functionDepth) }
                    statement.body?.let { visitBlock(it, functionDepth + 1) }
                }

                is IfStatement -> statement.causes.forEach { cause ->
                    visitIfClause(cause, functionDepth)
                }

                is DoStatement -> visitBlock(statement.body, functionDepth)
                is WhileStatement -> {
                    visitExpression(statement.condition, functionDepth)
                    visitBlock(statement.body, functionDepth)
                }

                is RepeatStatement -> {
                    visitBlock(statement.body, functionDepth)
                    visitExpression(statement.condition, functionDepth)
                }

                is ForNumericStatement -> {
                    visitExpression(statement.start, functionDepth)
                    visitExpression(statement.end, functionDepth)
                    statement.step?.let { visitExpression(it, functionDepth) }
                    visitBlock(statement.body, functionDepth)
                }

                is ForGenericStatement -> {
                    statement.iterators.forEach { visitExpression(it, functionDepth) }
                    visitBlock(statement.body, functionDepth)
                }

                is WhenStatement -> {
                    visitExpression(statement.condition, functionDepth)
                    visitStatement(statement.ifCause, functionDepth)
                    statement.elseCause?.let { visitStatement(it, functionDepth) }
                }

                is SwitchStatement -> {
                    visitExpression(statement.condition, functionDepth)
                    statement.causes.forEach { cause ->
                        when (cause) {
                            is io.github.dingyi222666.luaparser.parser.ast.node.CaseCause -> {
                                cause.conditions.forEach { visitExpression(it, functionDepth) }
                                visitBlock(cause.body, functionDepth)
                            }

                            is io.github.dingyi222666.luaparser.parser.ast.node.DefaultCause -> {
                                visitBlock(cause.body, functionDepth)
                            }
                        }
                    }
                }
            }
        }

        private fun visitIfClause(clause: IfClause, functionDepth: Int) {
            if (clause !is ElseClause) {
                visitExpression(clause.condition, functionDepth)
            }
            visitBlock(clause.body, functionDepth)
        }

        private fun visitExpression(
            expression: ExpressionNode,
            functionDepth: Int,
            isTopLevelStatementCall: Boolean = false
        ) {
            when (expression) {
                is CallExpression -> {
                    collectCallFacts(expression, isTopLevel = isTopLevelStatementCall)
                    visitExpression(expression.base, functionDepth)
                    expression.arguments.forEach { visitExpression(it, functionDepth) }
                }

                is MemberExpression -> visitExpression(expression.base, functionDepth)
                is IndexExpression -> {
                    visitExpression(expression.base, functionDepth)
                    visitExpression(expression.index, functionDepth)
                }

                is UnaryExpression -> visitExpression(expression.arg, functionDepth)
                is BinaryExpression -> {
                    expression.left?.let { visitExpression(it, functionDepth) }
                    expression.right?.let { visitExpression(it, functionDepth) }
                }

                is TableConstructorExpression -> expression.fields.forEach { visitTableKey(it, functionDepth) }
                is ArrayConstructorExpression -> expression.values.forEach { visitExpression(it, functionDepth) }
                is LambdaDeclaration -> visitExpression(expression.expression, functionDepth)
                is FunctionDeclaration -> expression.body?.let { visitBlock(it, functionDepth + 1) }
            }
        }

        private fun visitTableKey(field: TableKey, functionDepth: Int) {
            visitExpression(field.key, functionDepth)
            visitExpression(field.value, functionDepth)
        }

        private fun collectCallFacts(call: CallExpression, isTopLevel: Boolean) {
            val calleeName = identifierName(call.base) ?: return
            when (calleeName) {
                "require" -> collectRequireFact(call)

                "module" -> extractLegacyModuleCall(call, isTopLevel)?.let { fact ->
                    legacyModuleCalls += fact
                    addModuleNameCandidate(
                        moduleName = fact.moduleName,
                        source = DocumentFacts.ModuleNameCandidateSource.LEGACY_MODULE_CALL,
                        range = fact.range
                    )
                    if (fact.isTopLevel) {
                        topLevelSegmentTriggers += fact
                    }
                }
            }
        }

        private fun collectRequireFact(call: CallExpression) {
            val firstArgument = call.arguments.firstOrNull()
            val stringArgument = firstArgument as? ConstantNode
            if (stringArgument != null && stringArgument.constantType == ConstantNode.TYPE.STRING) {
                requires += DocumentFacts.RequireFact(
                    moduleName = stringArgument.stringOf(),
                    range = call.range
                )
                return
            }

            dynamicRequires += DocumentFacts.DynamicRequireFact(
                kind = if (firstArgument == null) {
                    DocumentFacts.DynamicRequireKind.MISSING_ARGUMENT
                } else {
                    DocumentFacts.DynamicRequireKind.NON_STRING_LITERAL
                },
                range = call.range
            )
        }

        private fun addModuleNameCandidate(
            moduleName: String,
            source: DocumentFacts.ModuleNameCandidateSource,
            range: Range? = null
        ) {
            val key = ModuleNameCandidateKey(moduleName = moduleName, source = source)
            if (key !in moduleNameCandidates) {
                moduleNameCandidates[key] = DocumentFacts.ModuleNameCandidate(
                    moduleName = moduleName,
                    source = source,
                    range = range
                )
            }
        }

        private fun extractLegacyModuleCall(
            call: CallExpression,
            isTopLevel: Boolean
        ): DocumentFacts.LegacyModuleCallFact? {
            val moduleName = extractStringArgument(call) ?: return null
            val mode = when (call.arguments.size) {
                1 -> ModuleEnvironmentMode.LEGACY_MODULE
                2 -> if (isPackageSeeAll(call.arguments[1])) ModuleEnvironmentMode.LEGACY_MODULE_SEEALL else return null
                else -> return null
            }

            return DocumentFacts.LegacyModuleCallFact(
                moduleName = moduleName,
                mode = mode,
                range = call.range,
                isTopLevel = isTopLevel
            )
        }

        private fun collectAssignmentAnchor(target: ExpressionNode) {
            val identifier = target as? Identifier
            if (identifier != null) {
                exportWriteAnchors += DocumentFacts.ExportWriteAnchor(
                    rootIdentifier = identifier.name,
                    accessPath = emptyList(),
                    kind = DocumentFacts.ExportWriteAnchorKind.BARE_ASSIGNMENT,
                    range = target.range
                )
                return
            }

            when (val access = extractAccessPath(target)) {
                is AccessPath.Member -> exportWriteAnchors += DocumentFacts.ExportWriteAnchor(
                    rootIdentifier = access.rootIdentifier,
                    accessPath = access.path,
                    kind = DocumentFacts.ExportWriteAnchorKind.MEMBER_ASSIGNMENT,
                    range = target.range
                )

                is AccessPath.Index -> exportWriteAnchors += DocumentFacts.ExportWriteAnchor(
                    rootIdentifier = access.rootIdentifier,
                    accessPath = access.path,
                    kind = DocumentFacts.ExportWriteAnchorKind.INDEX_ASSIGNMENT,
                    range = target.range
                )

                null -> Unit
            }
        }

        private fun collectFunctionAnchor(function: FunctionDeclaration) {
            val identifier = function.identifier ?: return
            val bareIdentifier = identifier as? Identifier
            if (bareIdentifier != null && !function.isLocal) {
                exportWriteAnchors += DocumentFacts.ExportWriteAnchor(
                    rootIdentifier = bareIdentifier.name,
                    accessPath = emptyList(),
                    kind = DocumentFacts.ExportWriteAnchorKind.BARE_FUNCTION_DECLARATION,
                    range = identifier.range
                )
                return
            }

            val access = extractAccessPath(identifier) ?: return
            exportWriteAnchors += DocumentFacts.ExportWriteAnchor(
                rootIdentifier = access.rootIdentifier,
                accessPath = access.path,
                kind = DocumentFacts.ExportWriteAnchorKind.FUNCTION_DECLARATION,
                range = identifier.range
            )
        }
    }

    private sealed class AccessPath {
        abstract val rootIdentifier: String
        abstract val path: List<String>

        data class Member(
            override val rootIdentifier: String,
            override val path: List<String>
        ) : AccessPath()

        data class Index(
            override val rootIdentifier: String,
            override val path: List<String>
        ) : AccessPath()
    }

    private data class ModuleNameCandidateKey(
        val moduleName: String,
        val source: DocumentFacts.ModuleNameCandidateSource
    )

    private fun identifierName(expression: ExpressionNode): String? =
        (expression as? Identifier)?.name

    private fun extractStringArgument(call: CallExpression): String? {
        val firstArgument = call.arguments.firstOrNull() as? ConstantNode ?: return null
        if (firstArgument.constantType != ConstantNode.TYPE.STRING) {
            return null
        }
        return firstArgument.stringOf()
    }

    private fun isPackageSeeAll(expression: ExpressionNode): Boolean {
        val member = expression as? MemberExpression ?: return false
        val base = member.base as? Identifier ?: return false
        return base.name == "package" && member.identifier.name == "seeall" && member.indexer == "."
    }

    private fun extractAccessPath(expression: ExpressionNode): AccessPath? {
        return when (expression) {
            is MemberExpression -> {
                val base = extractAccessPathBase(expression.base) ?: return null
                AccessPath.Member(base.first, base.second + expression.identifier.name)
            }

            is IndexExpression -> {
                val key = (expression.index as? ConstantNode)
                    ?.takeIf { it.constantType == ConstantNode.TYPE.STRING }
                    ?.stringOf()
                    ?: return null
                val base = extractAccessPathBase(expression.base) ?: return null
                AccessPath.Index(base.first, base.second + key)
            }

            else -> null
        }
    }

    private fun extractAccessPathBase(expression: ExpressionNode): Pair<String, List<String>>? {
        return when (expression) {
            is Identifier -> expression.name to emptyList()
            is MemberExpression -> {
                val base = extractAccessPathBase(expression.base) ?: return null
                base.first to (base.second + expression.identifier.name)
            }

            is IndexExpression -> {
                val key = (expression.index as? ConstantNode)
                    ?.takeIf { it.constantType == ConstantNode.TYPE.STRING }
                    ?.stringOf()
                    ?: return null
                val base = extractAccessPathBase(expression.base) ?: return null
                base.first to (base.second + key)
            }

            else -> null
        }
    }

    private fun deriveModuleNameFromPath(path: VirtualPath): String? {
        val normalized = path.value
        return when {
            normalized.endsWith("/init.lua") -> normalized.removeSuffix("/init.lua").replace('/', '.').ifEmpty { null }
            normalized == "init.lua" -> null
            normalized.endsWith(".lua") -> normalized.removeSuffix(".lua").replace('/', '.')
            else -> normalized.replace('/', '.')
        }
    }

    private fun buildFingerprint(
        path: VirtualPath,
        moduleNameCandidates: List<DocumentFacts.ModuleNameCandidate>,
        requires: List<DocumentFacts.RequireFact>,
        dynamicRequires: List<DocumentFacts.DynamicRequireFact>,
        legacyModuleCalls: List<DocumentFacts.LegacyModuleCallFact>,
        returnHint: DocumentFacts.ReturnExportShapeHint,
        environmentSegments: List<DocumentFacts.EnvironmentSegment>,
        exportWriteAnchors: List<DocumentFacts.ExportWriteAnchor>
    ): String {
        val payload = buildString {
            append("path=")
            append(path.value)
            append('\n')
            append("moduleCandidates=")
            append(moduleNameCandidates.joinToString("|") {
                listOf(it.moduleName, it.source.name).joinToString("#")
            })
            append('\n')
            append("requires=")
            append(requires.joinToString("|") {
                it.moduleName
            })
            append('\n')
            append("dynamicRequires=")
            append(dynamicRequires.joinToString("|") {
                it.kind.name
            })
            append('\n')
            append("legacyModules=")
            append(legacyModuleCalls.joinToString("|") {
                listOf(it.moduleName, it.mode.name, it.isTopLevel.toString()).joinToString("#")
            })
            append('\n')
            append("return=")
            append(listOf(returnHint.kind.name, returnHint.identifierName.orEmpty()).joinToString("#"))
            append('\n')
            append("segments=")
            append(environmentSegments.joinToString("|") {
                it.mode.name
            })
            append('\n')
            append("anchors=")
            append(exportWriteAnchors.joinToString("|") {
                listOf(
                    it.rootIdentifier,
                    it.accessPath.joinToString("."),
                    it.kind.name
                ).joinToString("#")
            })
        }
        return fnv1a64(payload)
    }

    private fun fnv1a64(text: String): String {
        var hash = -3750763034362895579L
        val prime = 1099511628211L
        for (char in text) {
            hash = hash xor char.code.toLong()
            hash *= prime
        }
        return hash.toULong().toString(16).padStart(16, '0')
    }
}
