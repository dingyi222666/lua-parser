package io.github.dingyi222666.luaparser.semantic.workspace

import io.github.dingyi222666.luaparser.parser.ast.node.ArrayConstructorExpression
import io.github.dingyi222666.luaparser.parser.ast.node.AssignmentStatement
import io.github.dingyi222666.luaparser.parser.ast.node.BinaryExpression
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
import io.github.dingyi222666.luaparser.parser.ast.node.LambdaDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.parser.ast.node.MemberExpression
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.parser.ast.node.Range
import io.github.dingyi222666.luaparser.parser.ast.node.RepeatStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ReturnStatement
import io.github.dingyi222666.luaparser.parser.ast.node.StatementNode
import io.github.dingyi222666.luaparser.parser.ast.node.StringCallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.SwitchStatement
import io.github.dingyi222666.luaparser.parser.ast.node.TableCallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.TableConstructorExpression
import io.github.dingyi222666.luaparser.parser.ast.node.TableKey
import io.github.dingyi222666.luaparser.parser.ast.node.TableKeyString
import io.github.dingyi222666.luaparser.parser.ast.node.UnaryExpression
import io.github.dingyi222666.luaparser.parser.ast.node.WhenStatement
import io.github.dingyi222666.luaparser.parser.ast.node.WhileStatement

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
            sourceImports = state.sourceImports,
            jvmClassLoads = state.jvmClassLoads,
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
            sourceImports = state.sourceImports.toList(),
            jvmClassLoads = state.jvmClassLoads.toList(),
            returnHint = returnHint,
            environmentSegments = segments,
            exportWriteAnchors = state.exportWriteAnchors.toList()
        )
    }

    private class CollectorState(private val path: VirtualPath) {
        val requires = mutableListOf<DocumentFacts.RequireFact>()
        val dynamicRequires = mutableListOf<DocumentFacts.DynamicRequireFact>()
        val legacyModuleCalls = mutableListOf<DocumentFacts.LegacyModuleCallFact>()
        val sourceImports = mutableListOf<DocumentFacts.SourceImportFact>()
        val jvmClassLoads = mutableListOf<DocumentFacts.JvmClassLoadFact>()
        val exportWriteAnchors = mutableListOf<DocumentFacts.ExportWriteAnchor>()
        private val moduleNameCandidates = linkedMapOf<ModuleNameCandidateKey, DocumentFacts.ModuleNameCandidate>()
        private val topLevelSegmentTriggers = mutableListOf<DocumentFacts.LegacyModuleCallFact>()
        private val aliasScopes = mutableListOf(mutableMapOf<String, DocumentFacts.JvmClassLoadKind?>())
        private val functionAliasBoundaries = mutableListOf<Int>()
        private val luaJavaHelperKinds = mapOf(
            "bindClass" to DocumentFacts.JvmClassLoadKind.BIND_CLASS_CALL,
            "newInstance" to DocumentFacts.JvmClassLoadKind.NEW_INSTANCE_CALL,
            "createProxy" to DocumentFacts.JvmClassLoadKind.CREATE_PROXY_CALL,
            "loadLib" to DocumentFacts.JvmClassLoadKind.LOAD_LIB_CALL,
            "createArray" to DocumentFacts.JvmClassLoadKind.CREATE_ARRAY_CALL
        )

        fun addPathDerivedModuleNameCandidate() {
            // Primary: full virtual-path dotted name (`resources/lua/import.lua` → `resources.lua.import`).
            // Secondary: basename-only aliases used by Android-Lua package.path (`import`, `loadlayout`,
            // `layout` for `.aly`). Nested dotted basenames (e.g. `socket/url.lua` → `socket.url`) stay.
            for (moduleName in pathDerivedModuleNameCandidates(path)) {
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
                    collectLocalAliases(statement)
                }

                is AssignmentStatement -> {
                    statement.init.forEach { target ->
                        if (functionDepth == 0) {
                            collectAssignmentAnchor(target)
                        }
                    }
                    statement.init.forEach { visitExpression(it, functionDepth) }
                    statement.variables.forEach { visitExpression(it, functionDepth) }
                    collectAssignmentAliases(statement)
                }

                is CallStatement -> {
                    visitExpression(statement.expression, functionDepth, isTopLevelStatementCall = functionDepth == 0)
                }

                is FunctionDeclaration -> {
                    if (functionDepth == 0) {
                        collectFunctionAnchor(statement)
                    }
                    collectFunctionAliasShadow(statement)
                    statement.identifier?.let { visitExpression(it, functionDepth) }
                    statement.body?.let { body ->
                        withAliasScope(isFunctionBoundary = true) {
                            statement.params.forEach { declareLocalAlias(it.name, null) }
                            visitBlock(body, functionDepth + 1)
                        }
                    }
                }

                is IfStatement -> statement.causes.forEach { cause ->
                    visitIfClause(cause, functionDepth)
                }

                is DoStatement -> withAliasScope { visitBlock(statement.body, functionDepth) }
                is WhileStatement -> {
                    visitExpression(statement.condition, functionDepth)
                    withAliasScope { visitBlock(statement.body, functionDepth) }
                }

                is RepeatStatement -> {
                    withAliasScope { visitBlock(statement.body, functionDepth) }
                    visitExpression(statement.condition, functionDepth)
                }

                is ForNumericStatement -> {
                    visitExpression(statement.start, functionDepth)
                    visitExpression(statement.end, functionDepth)
                    statement.step?.let { visitExpression(it, functionDepth) }
                    withAliasScope {
                        declareLocalAlias(statement.variable.name, null)
                        visitBlock(statement.body, functionDepth)
                    }
                }

                is ForGenericStatement -> {
                    statement.iterators.forEach { visitExpression(it, functionDepth) }
                    withAliasScope {
                        statement.variables.forEach { declareLocalAlias(it.name, null) }
                        visitBlock(statement.body, functionDepth)
                    }
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
                                withAliasScope { visitBlock(cause.body, functionDepth) }
                            }

                            is io.github.dingyi222666.luaparser.parser.ast.node.DefaultCause -> {
                                withAliasScope { visitBlock(cause.body, functionDepth) }
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
            withAliasScope { visitBlock(clause.body, functionDepth) }
        }

        private fun visitExpression(
            expression: ExpressionNode,
            functionDepth: Int,
            isTopLevelStatementCall: Boolean = false
        ) {
            when (expression) {
                is StringCallExpression, is TableCallExpression -> {
                    // Compact short-call nodes are CallExpression subclasses. Collect once using the
                    // specialized node, then walk only the semantic base/arguments.
                    collectCallFacts(expression, isTopLevel = isTopLevelStatementCall)
                    visitExpression(effectiveCallBase(expression), functionDepth)
                    callArguments(expression).forEach { visitExpression(it, functionDepth) }
                }

                is CallExpression -> {
                    collectCallFacts(expression, isTopLevel = isTopLevelStatementCall)
                    val compactBase = expression.base
                    if (compactBase is StringCallExpression || compactBase is TableCallExpression) {
                        // Nested compact wrapper already contributed its arguments to this call.
                        // Walk only the semantic callee and the merged argument list so nested
                        // compact CallExpression subclasses are not collected a second time.
                        visitExpression(effectiveCallBase(expression), functionDepth)
                        callArguments(expression).forEach { visitExpression(it, functionDepth) }
                    } else {
                        visitExpression(expression.base, functionDepth)
                        expression.arguments.forEach { visitExpression(it, functionDepth) }
                    }
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
                is LambdaDeclaration -> withAliasScope {
                    expression.params.forEach { declareLocalAlias(it.name, null) }
                    visitExpression(expression.expression, functionDepth + 1)
                }
                is FunctionDeclaration -> expression.body?.let { body ->
                    withAliasScope(isFunctionBoundary = true) {
                        expression.params.forEach { declareLocalAlias(it.name, null) }
                        visitBlock(body, functionDepth + 1)
                    }
                }
            }
        }

        private fun visitTableKey(field: TableKey, functionDepth: Int) {
            visitExpression(field.key, functionDepth)
            visitExpression(field.value, functionDepth)
        }

        private fun collectCallFacts(call: CallExpression, isTopLevel: Boolean) {
            if (isLuaJavaHelperColonCall(call)) {
                return
            }
            val calleeName = calleeName(effectiveCallBase(call)) ?: return
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
                else -> jvmClassLoadKindForCallee(calleeName)?.let { kind ->
                    collectJvmClassLoadFact(call, calleeName, kind)
                }
            }
        }

        private fun isLuaJavaHelperColonCall(call: CallExpression): Boolean {
            val member = effectiveCallBase(call) as? MemberExpression ?: return false
            return member.indexer == ":" &&
                member.identifier.name in luaJavaHelperKinds &&
                identifierName(member.base) == "luajava"
        }

        private fun collectRequireFact(call: CallExpression) {
            val firstArgument = callArguments(call).firstOrNull()
            val stringArgument = firstArgument as? ConstantNode
            if (stringArgument != null && stringArgument.constantType == ConstantNode.TYPE.STRING) {
                val moduleName = stringArgument.stringOf()
                requires += DocumentFacts.RequireFact(
                    moduleName = moduleName,
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

        private fun collectLocalAliases(statement: LocalStatement) {
            // LocalStatement: .init = names/LHS, .variables = RHS (AST quirk).
            statement.init.forEachIndexed { index, identifier ->
                val value = statement.variables.getOrNull(index)
                // Android-Lua helpers (loadlayout/loadmenu) capture globals with identity
                // rebinds such as `local luajava = luajava` then `local bindClass = luajava.bindClass`.
                // Registering a null-kind shadow for that identity would blank every `luajava.*`
                // helper via isAliasDeclared and yield empty jvmClassLoads (ViewGroup got []).
                if (isIdentityAliasRebind(identifier.name, value)) {
                    return@forEachIndexed
                }
                declareLocalAlias(identifier.name, value?.let(::jvmClassLoadKindForAliasExpression))
            }
        }

        private fun collectAssignmentAliases(statement: AssignmentStatement) {
            // AssignmentStatement: .init = LHS, .variables = RHS (AST quirk).
            statement.init.forEachIndexed { index, target ->
                val identifier = target as? Identifier ?: return@forEachIndexed
                val value = statement.variables.getOrNull(index)
                if (isIdentityAliasRebind(identifier.name, value)) {
                    return@forEachIndexed
                }
                assignAlias(identifier.name, value?.let(::jvmClassLoadKindForAliasExpression))
            }
        }

        /**
         * True for `local x = x` / `x = x` identity captures of the outer binding.
         * These must not register a null-kind local shadow for LuaJava helper resolution.
         */
        private fun isIdentityAliasRebind(aliasName: String, value: ExpressionNode?): Boolean {
            val identifier = value as? Identifier ?: return false
            return identifier.name == aliasName
        }

        private fun collectFunctionAliasShadow(function: FunctionDeclaration) {
            val identifier = function.identifier as? Identifier ?: return
            if (function.isLocal) {
                declareLocalAlias(identifier.name, null)
            } else {
                assignAlias(identifier.name, null)
            }
        }

        private fun declareLocalAlias(aliasName: String, kind: DocumentFacts.JvmClassLoadKind?) {
            aliasScopes.last()[aliasName] = kind
        }

        private fun assignAlias(aliasName: String, kind: DocumentFacts.JvmClassLoadKind?) {
            val boundaryIndex = functionAliasBoundaries.lastOrNull() ?: 0
            val targetScope = (aliasScopes.lastIndex downTo boundaryIndex)
                .firstOrNull { aliasName in aliasScopes[it] }
                ?.let { aliasScopes[it] }
                ?: aliasScopes[boundaryIndex]
            targetScope[aliasName] = kind
        }

        private inline fun <T> withAliasScope(
            isFunctionBoundary: Boolean = false,
            block: () -> T
        ): T {
            aliasScopes += mutableMapOf()
            if (isFunctionBoundary) {
                functionAliasBoundaries += aliasScopes.lastIndex
            }
            return try {
                block()
            } finally {
                if (isFunctionBoundary) {
                    functionAliasBoundaries.removeAt(functionAliasBoundaries.lastIndex)
                }
                aliasScopes.removeAt(aliasScopes.lastIndex)
            }
        }

        private fun jvmClassLoadKindForAliasExpression(expression: ExpressionNode): DocumentFacts.JvmClassLoadKind? {
            if (extractRequireString(expression) == "import") {
                return DocumentFacts.JvmClassLoadKind.IMPORT_CALL
            }
            if (isLuaJavaHelperColonMember(expression)) {
                return null
            }
            val resolvedName = calleeName(expression) ?: return null
            return jvmClassLoadKindForCallee(resolvedName)
        }

        private fun isLuaJavaHelperColonMember(expression: ExpressionNode): Boolean {
            val member = expression as? MemberExpression ?: return false
            return member.indexer == ":" &&
                member.identifier.name in luaJavaHelperKinds &&
                identifierName(member.base) == "luajava"
        }

        private fun jvmClassLoadKindForCallee(calleeName: String): DocumentFacts.JvmClassLoadKind? {
            for (scope in aliasScopes.asReversed()) {
                if (calleeName in scope) {
                    return scope[calleeName]
                }
            }
            if (calleeName.startsWith("luajava.") && isAliasDeclared("luajava")) {
                return null
            }
            return when (calleeName) {
                "import" -> DocumentFacts.JvmClassLoadKind.IMPORT_CALL
                else -> calleeName
                    .removePrefix("luajava.")
                    .takeIf { calleeName.startsWith("luajava.") }
                    ?.let(luaJavaHelperKinds::get)
                    ?: null
            }
        }

        private fun isAliasDeclared(aliasName: String): Boolean {
            return aliasScopes.asReversed().any { aliasName in it }
        }

        private fun collectJvmClassLoadFact(
            call: CallExpression,
            calleeName: String,
            kind: DocumentFacts.JvmClassLoadKind
        ) {
            val targets = when (kind) {
                DocumentFacts.JvmClassLoadKind.IMPORT_CALL -> extractImportTargets(call)
                DocumentFacts.JvmClassLoadKind.BIND_CLASS_CALL,
                DocumentFacts.JvmClassLoadKind.NEW_INSTANCE_CALL,
                DocumentFacts.JvmClassLoadKind.LOAD_LIB_CALL,
                DocumentFacts.JvmClassLoadKind.CREATE_ARRAY_CALL -> extractStringTargets(call)
                DocumentFacts.JvmClassLoadKind.CREATE_PROXY_CALL -> extractCreateProxyTargets(call)
            }
            if (targets.isEmpty()) {
                return
            }
            if (kind == DocumentFacts.JvmClassLoadKind.IMPORT_CALL) {
                targets.forEach { target ->
                    sourceImports += DocumentFacts.SourceImportFact(
                        target = target,
                        range = call.range
                    )
                }
            }
            val classLoadTargets = if (kind == DocumentFacts.JvmClassLoadKind.IMPORT_CALL) {
                targets.filterNot(::isWildcardImportTarget)
            } else {
                targets
            }
            classLoadTargets.forEach { target ->
                jvmClassLoads += DocumentFacts.JvmClassLoadFact(
                    target = target,
                    kind = kind,
                    range = call.range
                )
            }
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

    private fun calleeName(expression: ExpressionNode): String? {
        return when (expression) {
            is Identifier -> expression.name
            is MemberExpression -> {
                if (expression.indexer != ".") {
                    return null
                }
                val base = calleeName(expression.base) ?: return null
                "$base.${expression.identifier.name}"
            }
            else -> null
        }
    }

    private fun effectiveCallBase(call: CallExpression): ExpressionNode {
        return unwrapCompactCallBase(call.base)
    }

    private fun unwrapCompactCallBase(expression: ExpressionNode): ExpressionNode {
        var current = expression
        while (true) {
            current = when (current) {
                is StringCallExpression -> current.base
                is TableCallExpression -> current.base
                else -> return current
            }
        }
    }

    private fun callArguments(call: CallExpression): List<ExpressionNode> {
        return buildList {
            fun appendFrom(expression: ExpressionNode) {
                when (expression) {
                    is StringCallExpression -> {
                        appendFrom(expression.base)
                        addAll(expression.arguments)
                    }
                    is TableCallExpression -> {
                        appendFrom(expression.base)
                        addAll(expression.arguments)
                    }
                    is CallExpression -> {
                        val nestedBase = expression.base
                        if (nestedBase is StringCallExpression || nestedBase is TableCallExpression) {
                            appendFrom(nestedBase)
                        }
                        addAll(expression.arguments)
                    }
                }
            }
            appendFrom(call)
        }
    }

    private fun extractStringTargets(call: CallExpression): List<String> {
        return extractStringTargets(callArguments(call).firstOrNull())
    }

    private fun extractCreateProxyTargets(call: CallExpression): List<String> {
        val arguments = callArguments(call)
        if (arguments.isEmpty()) {
            return emptyList()
        }
        return arguments.flatMap { argument ->
            extractStringTargets(argument).flatMap(::splitCreateProxyTargetList)
        }
    }

    private fun splitCreateProxyTargetList(targetList: String): List<String> {
        return targetList.split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
    }

    private fun extractImportTargets(call: CallExpression): List<String> {
        return when (val firstArgument = callArguments(call).firstOrNull()) {
            is ArrayConstructorExpression -> firstArgument.values.flatMap(::extractStringTargets)
            is TableConstructorExpression -> firstArgument.fields
                .filter(::isImplicitTableSequenceField)
                .flatMap { extractStringTargets(it.value) }
            else -> extractStringTargets(firstArgument)
        }
    }

    /**
     * True only for list/array fields written as bare values (`"java.io.File"`), not
     * named keys (`ignored = ...`) or explicit sparse numeric keys (`[3] = ...`).
     *
     * The parser synthesizes ConstantNode.INTERGER keys for implicit array slots and
     * marks those keys with a zero-width range (start == end). Explicit bracket keys
     * keep a non-degenerate source range over the key expression and must not be
     * collected as import targets.
     */
    private fun isImplicitTableSequenceField(field: TableKey): Boolean {
        if (field is TableKeyString) {
            return false
        }
        val key = field.key as? ConstantNode ?: return false
        if (key.constantType != ConstantNode.TYPE.INTERGER) {
            return false
        }
        // Synthetic implicit array keys are zero-width; explicit [n] keys span source text.
        return key.range.start == key.range.end
    }

    private fun extractStringTargets(expression: ExpressionNode?): List<String> {
        val constant = expression as? ConstantNode ?: return emptyList()
        if (constant.constantType != ConstantNode.TYPE.STRING) {
            return emptyList()
        }
        return listOf(constant.stringOf())
    }

    private fun isWildcardImportTarget(target: String): Boolean {
        return target.endsWith(".*")
    }

    private fun extractStringArgument(call: CallExpression): String? {
        return extractStringTargets(call).singleOrNull()
    }

    private fun extractRequireString(expression: ExpressionNode): String? {
        val call = expression as? CallExpression ?: return null
        if (calleeName(effectiveCallBase(call)) != "require") {
            return null
        }
        return extractStringArgument(call)
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

    /**
     * Path-derived module names for workspace providers.
     *
     * Always includes the full slash→dot path module name when present. For nested
     * Android-Lua trees (`resources/lua/…`, `assets/…`), also claims leaf / relative
     * basename module names that runtime `package.path` searchers resolve (`import`,
     * `loadlayout`, `socket.url`, `layout` for `.aly`).
     */
    private fun pathDerivedModuleNameCandidates(path: VirtualPath): List<String> {
        val primary = deriveModuleNameFromPath(path) ?: return emptyList()
        val names = linkedSetOf(primary)
        val normalized = path.value.replace('\\', '/')
        // Real-project / Lua package.path style: require("pkg.init") must resolve to
        // pkg/init.lua even when the primary claim collapses init.lua → package root "pkg".
        // Claim both forms so barrel re-export modules stay require()-able by either name.
        if (normalized.endsWith("/init.lua") || normalized == "init.lua") {
            val withInit = when {
                normalized == "init.lua" -> "init"
                else -> normalized.removeSuffix(".lua").replace('/', '.')
            }
            if (withInit.isNotEmpty()) {
                names += withInit
            }
        }
        // Basename / package-relative aliases only for Android-Lua full-tree layouts so
        // simple harness paths (`pkg/runtime.lua`, `import.lua`) keep a single candidate.
        val isAndroidLuaTree =
            normalized.startsWith("resources/") ||
                normalized.startsWith("assets/") ||
                normalized.startsWith("lua/")
        if (!isAndroidLuaTree) {
            return names.toList()
        }
        val withoutExt = when {
            normalized.endsWith("/init.lua") -> normalized.removeSuffix("/init.lua")
            normalized == "init.lua" -> ""
            normalized.endsWith(".lua") -> normalized.removeSuffix(".lua")
            normalized.endsWith(".aly") -> normalized.removeSuffix(".aly")
            else -> normalized
        }
        if (withoutExt.isEmpty()) {
            return names.toList()
        }
        val segments = withoutExt.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) {
            return names.toList()
        }
        // Leaf basename (import.lua → import; layout.aly → layout).
        names += segments.last()
        // Nested package under any directory (socket/url.lua → socket.url).
        if (segments.size >= 2) {
            names += segments.takeLast(2).joinToString(".")
        }
        // Package-relative names under common Android-Lua roots so require("import")
        // maps to resources/lua/import.lua rather than only resources.lua.import.
        for (rootPrefix in listOf("resources/lua", "resources", "assets", "lua")) {
            val prefix = "$rootPrefix/"
            if (withoutExt.startsWith(prefix)) {
                val relative = withoutExt.removePrefix(prefix)
                if (relative.isNotEmpty()) {
                    names += relative.replace('/', '.')
                }
            }
        }
        return names.filter { it.isNotEmpty() }.toList()
    }

    private fun deriveModuleNameFromPath(path: VirtualPath): String? {
        val normalized = path.value
        return when {
            normalized.endsWith("/init.lua") -> normalized.removeSuffix("/init.lua").replace('/', '.').ifEmpty { null }
            normalized == "init.lua" -> null
            normalized.endsWith(".lua") -> normalized.removeSuffix(".lua").replace('/', '.')
            // Android-Lua layout modules (.aly) are require()-able without a .lua suffix.
            normalized.endsWith(".aly") -> normalized.removeSuffix(".aly").replace('/', '.')
            else -> normalized.replace('/', '.')
        }
    }

    private fun buildFingerprint(
        path: VirtualPath,
        moduleNameCandidates: List<DocumentFacts.ModuleNameCandidate>,
        requires: List<DocumentFacts.RequireFact>,
        dynamicRequires: List<DocumentFacts.DynamicRequireFact>,
        legacyModuleCalls: List<DocumentFacts.LegacyModuleCallFact>,
        sourceImports: List<DocumentFacts.SourceImportFact>,
        jvmClassLoads: List<DocumentFacts.JvmClassLoadFact>,
        returnHint: DocumentFacts.ReturnExportShapeHint,
        environmentSegments: List<DocumentFacts.EnvironmentSegment>,
        exportWriteAnchors: List<DocumentFacts.ExportWriteAnchor>
    ): String {
        val payload = buildString {
            append("path=")
            append(path.value)
            append('\n')
            append("moduleNameCandidates=")
            append(moduleNameCandidates.joinToString("|") { "${it.source}:${it.moduleName}" })
            append('\n')
            append("requires=")
            append(requires.joinToString("|") { it.moduleName })
            append('\n')
            append("dynamicRequires=")
            append(dynamicRequires.joinToString("|") { it.kind.name })
            append('\n')
            append("legacyModuleCalls=")
            append(legacyModuleCalls.joinToString("|") { "${it.moduleName}:${it.mode}:${it.isTopLevel}" })
            append('\n')
            append("sourceImports=")
            append(sourceImports.joinToString("|") { it.target })
            append('\n')
            append("jvmClassLoads=")
            append(jvmClassLoads.joinToString("|") { "${it.kind}:${it.target}" })
            append('\n')
            append("returnHint=")
            append(returnHint.kind)
            append(':')
            append(returnHint.identifierName.orEmpty())
            append('\n')
            append("environmentSegments=")
            append(environmentSegments.joinToString("|") { it.mode.name })
            append('\n')
            append("exportWriteAnchors=")
            append(exportWriteAnchors.joinToString("|") { "${it.kind}:${it.rootIdentifier}:${it.accessPath.joinToString(".")}" })
        }
        return workspaceFingerprintHash(payload)
    }

}
