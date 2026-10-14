package io.github.dingyi222666.luaparser.semantic.binder

import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.parser.ast.node.AssignmentStatement
import io.github.dingyi222666.luaparser.parser.ast.node.AttributeIdentifier
import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode
import io.github.dingyi222666.luaparser.parser.ast.node.BlockNode
import io.github.dingyi222666.luaparser.parser.ast.node.CaseCause
import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.CommentStatement
import io.github.dingyi222666.luaparser.parser.ast.node.DefaultCause
import io.github.dingyi222666.luaparser.parser.ast.node.DoStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ElseClause
import io.github.dingyi222666.luaparser.parser.ast.node.ElseIfClause
import io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode
import io.github.dingyi222666.luaparser.parser.ast.node.ForGenericStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ForNumericStatement
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.IfClause
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.parser.ast.node.LambdaDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.MemberExpression
import io.github.dingyi222666.luaparser.parser.ast.node.Range
import io.github.dingyi222666.luaparser.parser.ast.node.RepeatStatement
import io.github.dingyi222666.luaparser.parser.ast.node.StatementNode
import io.github.dingyi222666.luaparser.parser.ast.node.TableConstructorExpression
import io.github.dingyi222666.luaparser.parser.ast.node.SwitchStatement
import io.github.dingyi222666.luaparser.parser.ast.node.WhenStatement
import io.github.dingyi222666.luaparser.parser.ast.node.WhileStatement
import io.github.dingyi222666.luaparser.parser.ast.visitor.ASTVisitor
import io.github.dingyi222666.luaparser.semantic.comments.AliasTagSyntax
import io.github.dingyi222666.luaparser.semantic.comments.ClassTagSyntax
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachment
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachmentIndex
import io.github.dingyi222666.luaparser.semantic.comments.DocCommentSyntax
import io.github.dingyi222666.luaparser.semantic.comments.DocTagSyntax
import io.github.dingyi222666.luaparser.semantic.comments.FieldTagSyntax
import io.github.dingyi222666.luaparser.semantic.comments.GenericTagSyntax
import io.github.dingyi222666.luaparser.semantic.comments.JavaClassTagSyntax
import io.github.dingyi222666.luaparser.semantic.comments.MethodTagSyntax
import io.github.dingyi222666.luaparser.semantic.comments.OverloadTagSyntax
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.syntax.TypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.NamedTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.TypeSyntaxParser
import io.github.dingyi222666.luaparser.semantic.types.syntax.splitTopLevelTypeText

internal class DeclarationBinder(
    private val builder: SymbolTableBuilder,
    private val comments: CommentAttachmentIndex
) : ASTVisitor<Unit> {

    fun bind(chunk: ChunkNode) {
        comments.orphanAttachments.forEach(::bindRootTypeDeclarations)
        visitChunkNode(chunk, Unit)
    }

    override fun visitExpressionNode(node: ExpressionNode, value: Unit) {
        if (node is io.github.dingyi222666.luaparser.parser.ast.node.CallExpression) builder.recordCall(node)
        super<ASTVisitor>.visitExpressionNode(node, value)
    }

    override fun visitStatementNode(node: StatementNode, value: Unit) {
        comments.getAttachment(node)?.let(::bindRootTypeDeclarations)
        super<ASTVisitor>.visitStatementNode(node, value)
    }

    /**
     * Local names become visible only after the whole LocalStatement ends
     * (`visibleFrom` = statement end). In Lua a local's scope begins at the first
     * statement after its declaration, so the initializer still sees the outer
     * binding: `do local x = x + 1 end` must resolve the RHS `x` to the outer `x`,
     * and `local x = 1` must not offer `x` for a completion on its own initializer.
     */
    override fun visitLocalStatement(node: LocalStatement, value: Unit) {
        val attachment = comments.getAttachment(node)
        val declaredTypeSyntaxes = positionalDeclaredTypeSyntaxes(
            declaredNameCount = node.init.size,
            inlineTypeText = attachment?.inlineTypeText
        )

        // A same-named class/table definition links its value and type namespaces. An
        // unrelated attached @class declares a type only; @type is required to type an alias.
        val classValueType = if (node.init.size == 1 && node.variables.singleOrNull() is TableConstructorExpression) {
            attachment?.docComment?.tags?.filterIsInstance<ClassTagSyntax>()?.singleOrNull()
                ?.takeIf { it.name.substringAfterLast('.') == node.init.single().name }
                ?.let { NamedTypeSyntax(it.name) }
        } else null

        node.init.forEachIndexed { index, identifier ->
            builder.addDeclarationWithSymbol(
                localDeclaration(
                    id = builder.nextDeclarationId(), name = identifier.name,
                    owner = DeclarationOwner.Lexical(currentLexicalOwnerNode()), anchorNode = identifier,
                    documentation = attachment?.toDeclarationDocumentation(),
                    declaredTypeSyntax = declaredTypeSyntaxes.getOrNull(index) ?: classValueType
                ).copy(
                    // Anchor at the end of the initializer expression: the new local is
                    // offered from there onward, while the initializer itself still resolves
                    // to the outer binding per Lua scoping. `end.column` is EXCLUSIVE (one
                    // past the last initializer character), so the legacy end-of-statement
                    // caret sits at `end.column - 1` and must still see the new local
                    // (LegacySemanticAnalyzerCompatibilityTest probes exactly that position
                    // for `local inner = outer`).
                    //
                    // Sole exception — a single-character identifier initializer that reads
                    // a name this same statement declares (`local i = i`, `local a, b = a, b`):
                    // its read position (range.start) coincides with that caret, so
                    // anchoring there would self-bind the read to the not-yet-initialized
                    // local (the outer is never marked used and falsely reports
                    // "Unused local"). Anchor strictly after it instead.
                    visibleFrom = node.variables.lastOrNull()?.range?.end
                        ?.let { end ->
                            // A same-name read ending at the initializer's last character
                            // self-binds at the legacy anchor — cover both the bare
                            // identifier and prefix-unary forms (`local i = i`,
                            // `local i = -i`): the whole initializer IS the same-name read.
                            var read: io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode? =
                                node.variables.lastOrNull()
                            while (read is io.github.dingyi222666.luaparser.parser.ast.node.UnaryExpression) {
                                read = read.arg
                            }
                            val selfBindAnchor = read is Identifier &&
                                read.name in node.init.map { it.name } && read.range.end == end
                            Position(end.line, if (selfBindAnchor) end.column else (end.column - 1).coerceAtLeast(1))
                        }
                        ?: node.range.end
                )
            )
        }

        node.variables.forEach { visitExpressionNode(it, value) }
    }

    override fun visitAssignmentStatement(node: AssignmentStatement, value: Unit) {
        // AST quirk: AssignmentStatement.init = LHS targets, .variables = RHS expressions.
        // Each bare free-name LHS invents an AST GLOBAL on first write (identifier-only
        // range), including multi-LHS / unbalanced multi-LHS. Commas invent no ranges.
        // Member writes create MEMBER declarations so table/module surfaces can include
        // fields installed with `owner.name = value`, not only `function owner.name()`.
        val attachment = comments.getAttachment(node)
        val documentation = attachment?.toDeclarationDocumentation()
        val declaredTypeSyntax = node.init.singleOrNull()?.let { parseTypeSyntax(attachment?.inlineTypeText) }
        node.init.forEachIndexed { index, target ->
            recordGlobalWrite(target, node)
            when (target) {
                is Identifier -> bindBareGlobalAssignmentTarget(target, documentation, declaredTypeSyntax)
                is MemberExpression -> {
                    visitExpressionNode(target.base, value)
                    // Function-valued writes are METHOD declarations; other member writes
                    // are FIELD declarations (table/module surfaces must include fields
                    // installed with `owner.name = value`, not only `function owner.name()`).
                    val kind = when (node.variables.getOrNull(index)) {
                        is FunctionDeclaration, is LambdaDeclaration -> DeclarationKind.METHOD
                        else -> DeclarationKind.FIELD
                    }
                    builder.addDeclarationWithSymbol(
                        BinderDeclaration(
                            id = builder.nextDeclarationId(), name = target.identifier.name, kind = kind,
                            origin = DeclarationOrigin.AST, owner = DeclarationOwner.Lexical(currentLexicalOwnerNode()),
                            anchorNode = target.identifier, documentation = documentation,
                            declaredTypeSyntax = declaredTypeSyntax
                        )
                    )
                }
                else -> visitExpressionNode(target, value)
            }
        }
        node.variables.forEach { visitExpressionNode(it, value) }
    }

    override fun visitFunctionDeclaration(node: FunctionDeclaration, value: Unit) {
        if (!node.isLocal) node.identifier?.let { recordGlobalWrite(it, node) }
        val attachment = comments.getAttachment(node)
        val functionDeclaration = createFunctionDeclaration(node, attachment?.toDeclarationDocumentation())

        when (val identifier = node.identifier) {
            is MemberExpression -> visitExpressionNode(identifier.base, value)
            is ExpressionNode -> if (identifier !is Identifier) visitExpressionNode(identifier, value)
            null -> Unit
        }

        val body = node.body ?: return
        val functionScopeId = builder.createScope(
            kind = ScopeKind.FUNCTION,
            range = body.range,
            ownerNode = body,
            ownerDeclarationId = functionDeclaration?.id
        )

        inScope(functionScopeId) {
            bindFunctionTypeParameters(functionDeclaration, attachment)
            val owner = functionDeclaration?.let { DeclarationOwner.Declaration(it.id) }
                ?: DeclarationOwner.Lexical(node.body ?: node)
            node.params.forEach { parameter ->
                bindParameter(parameter, owner, attachment?.toDeclarationDocumentation())
            }
            visitBlockNode(body, value)
        }
    }

    /**
     * Lambda expressions introduce their own FUNCTION scope so parameters resolve inside
     * the body expression, mirroring how function declarations bind parameters.
     */
    override fun visitLambdaDeclaration(node: LambdaDeclaration, value: Unit) {
        val lambdaScopeId = builder.createScope(
            kind = ScopeKind.FUNCTION,
            range = node.expression.range,
            ownerNode = node
        )

        inScope(lambdaScopeId) {
            node.params.forEach { parameter ->
                bindParameter(parameter, DeclarationOwner.Lexical(node))
            }
            visitExpressionNode(node.expression, value)
        }
    }

    override fun visitDoStatement(node: DoStatement, value: Unit) = bindBodyScope(node.body, ScopeKind.BLOCK, value)

    override fun visitIfClause(node: IfClause, value: Unit) {
        visitExpressionNode(node.condition, value)
        bindBodyScope(node.body, ScopeKind.CONDITIONAL, value)
    }

    override fun visitElseClause(node: ElseClause, value: Unit) = bindBodyScope(node.body, ScopeKind.CONDITIONAL, value)

    override fun visitElseIfClause(node: ElseIfClause, value: Unit) = visitIfClause(node, value)

    override fun visitCaseCause(node: CaseCause, value: Unit) {
        node.conditions.forEach { visitExpressionNode(it, value) }
        bindBodyScope(node.body, ScopeKind.CONDITIONAL, value)
    }

    override fun visitDefaultCause(node: DefaultCause, value: Unit) = bindBodyScope(node.body, ScopeKind.CONDITIONAL, value)

    override fun visitWhileStatement(node: WhileStatement, value: Unit) {
        visitExpressionNode(node.condition, value)
        bindBodyScope(node.body, ScopeKind.LOOP, value)
    }

    override fun visitRepeatStatement(node: RepeatStatement, value: Unit) {
        val scopeId = builder.createScope(ScopeKind.LOOP, node.range, node.body)
        inScope(scopeId) {
            visitBlockNode(node.body, value)
            visitExpressionNode(node.condition, value)
        }
    }

    override fun visitForNumericStatement(node: ForNumericStatement, value: Unit) {
        visitExpressionNode(node.start, value)
        visitExpressionNode(node.end, value)
        node.step?.let { visitExpressionNode(it, value) }

        // Lua: the control variable is scoped to the body only, so the header expressions
        // (`for i = 1, #i do`) still evaluate in the ENCLOSING scope. Scope the LOOP scope
        // to the body; the control-var declaration keeps its header anchor (its own range
        // covers the declared name for hover/rename via the declaration-site queries).
        val scopeId = builder.createScope(ScopeKind.LOOP, node.body.range, node.body)
        inScope(scopeId) {
            // Numeric for control var is always number in Lua (for i = start, limit [, step]).
            // Seed declaredType so hover/type-at participate in normal declaredType path.
            builder.addDeclarationWithSymbol(
                localDeclaration(
                    id = builder.nextDeclarationId(), name = node.variable.name,
                    owner = DeclarationOwner.Lexical(node.body), anchorNode = node.variable,
                    declaredType = PrimitiveType.NUMBER
                )
            )
            visitBlockNode(node.body, value)
        }
    }

    override fun visitForGenericStatement(node: ForGenericStatement, value: Unit) {
        node.iterators.forEach { visitExpressionNode(it, value) }

        // Lua: iterator expressions (`for k, v in pairs(k) do`) evaluate in the ENCLOSING
        // scope and the loop variables are scoped to the body only. Scope the LOOP scope
        // to the body; the loop-variable declarations keep their header anchors.
        val scopeId = builder.createScope(ScopeKind.LOOP, node.body.range, node.body)
        inScope(scopeId) {
            node.variables.forEach { identifier ->
                builder.addDeclarationWithSymbol(
                    localDeclaration(
                        id = builder.nextDeclarationId(), name = identifier.name,
                        owner = DeclarationOwner.Lexical(node.body), anchorNode = identifier
                    )
                )
            }
            visitBlockNode(node.body, value)
        }
    }

    override fun visitWhenStatement(node: WhenStatement, value: Unit) {
        visitExpressionNode(node.condition, value)
        bindNestedConditionalStatement(node.ifCause, value)
        node.elseCause?.let { bindNestedConditionalStatement(it, value) }
    }

    override fun visitSwitchStatement(node: SwitchStatement, value: Unit) {
        visitExpressionNode(node.condition, value)
        // Dispatch causes DIRECTLY: the base visitStatementNode when-table has no
        // CaseCause/DefaultCause branches, so routing causes through it silently bound
        // nothing inside case bodies (no scopes, no callback params, no for-in control
        // variables — every `v` inside a switch body resolved as an unresolved global).
        node.causes.forEach { cause ->
            when (cause) {
                is DefaultCause -> visitDefaultCause(cause, value)
                is CaseCause -> visitCaseCause(cause, value)
            }
        }
    }

    override fun visitCommentStatement(commentStatement: CommentStatement, value: Unit) {}

    override fun visitAttributeIdentifier(identifier: AttributeIdentifier, value: Unit) {}

    /**
     * First bare free-name assignment (single or multi-LHS) introduces a chunk-level
     * AST GLOBAL whose range is the identifier token only. Visible locals / parameters /
     * prior VALUE decls (including builtins and prior free-global invents) suppress
     * inventing a peer declaration at later write sites.
     */
    private fun recordGlobalWrite(target: ExpressionNode, write: BaseASTNode) {
        var root = target
        while (true) {
            root = when (root) {
                is MemberExpression -> root.base
                is io.github.dingyi222666.luaparser.parser.ast.node.IndexExpression -> root.base
                else -> break
            }
        }
        val name = (root as? Identifier)?.name ?: return
        val existing = builder.findVisibleValueDeclaration(name)
        if (existing == null || existing.kind == DeclarationKind.GLOBAL || existing.origin == DeclarationOrigin.BUILTIN) {
            builder.recordGlobalWrite(write, name)
        }
    }

    private fun bindBareGlobalAssignmentTarget(
        identifier: Identifier,
        documentation: DeclarationDocumentation?,
        declaredTypeSyntax: io.github.dingyi222666.luaparser.semantic.types.syntax.TypeSyntax?
    ) {
        val existing = builder.findVisibleValueDeclaration(identifier.name)
        if (existing != null) {
            // Local introducers win shadowing; existing GLOBAL/FUNCTION/builtin VALUE
            // symbols are re-used as write references (no forked decl at this site).
            return
        }

        builder.addDeclarationWithSymbol(
            // origin=AST / owner=Root / range=anchor range are the factory defaults.
            globalDeclaration(
                id = builder.nextDeclarationId(),
                name = identifier.name,
                anchorNode = identifier,
                documentation = documentation,
                declaredTypeSyntax = declaredTypeSyntax
            ),
            scopeId = builder.rootScopeId
        )
    }

    private fun createFunctionDeclaration(
        node: FunctionDeclaration,
        documentation: DeclarationDocumentation?
    ): BinderDeclaration? {
        val owner = DeclarationOwner.Lexical(currentLexicalOwnerNode())
        return when (val identifier = node.identifier) {
            is Identifier -> if (node.isLocal) {
                builder.addDeclarationWithSymbol(
                    functionDeclaration(
                        id = builder.nextDeclarationId(), name = identifier.name, owner = owner,
                        anchorNode = identifier, documentation = documentation
                    )
                )
            } else {
                bindNonLocalFunctionName(identifier, owner, documentation)
            }

            is MemberExpression -> builder.addDeclarationWithSymbol(
                // origin=AST is explicit: the methodDeclaration factory defaults to DOC_COMMENT.
                methodDeclaration(
                    id = builder.nextDeclarationId(), name = identifier.identifier.name, origin = DeclarationOrigin.AST,
                    owner = owner, anchorNode = identifier.identifier, documentation = documentation
                )
            )

            else -> null
        }
    }

    /**
     * Non-local `function name()` is itself a GLOBAL introducer rooted at the chunk scope
     * (even when written inside a nested do/if block). When a prior bare free-name write
     * already invented the GLOBAL symbol, attach this function-site decl to that symbol so
     * identity stays unified (later bare writes remain non-declarative).
     *
     * A visible LOCAL/PARAMETER owns the name instead: `function x() end` re-binds that
     * local (Lua name resolution) by recording a FUNCTION-site declaration on the existing
     * symbol — it must not mint a phantom GLOBAL peer.
     */
    private fun bindNonLocalFunctionName(
        identifier: Identifier,
        owner: DeclarationOwner,
        documentation: DeclarationDocumentation?
    ): BinderDeclaration {
        val existing = builder.findVisibleValueDeclaration(identifier.name)
        val existingSymbolId = existing?.symbolId

        if (existingSymbolId != null &&
            (existing.kind == DeclarationKind.LOCAL || existing.kind == DeclarationKind.PARAMETER)
        ) {
            return builder.addDeclarationToExistingSymbol(
                functionDeclaration(
                    id = builder.nextDeclarationId(), name = identifier.name, owner = owner,
                    anchorNode = identifier, documentation = documentation
                ),
                existingSymbolId,
                scopeId = builder.rootScopeId
            )
        }

        val declaration = globalDeclaration(
            id = builder.nextDeclarationId(),
            name = identifier.name,
            owner = owner,
            anchorNode = identifier,
            documentation = documentation
        )
        return if (existingSymbolId != null && existing.kind.namespace == DeclarationNamespace.VALUE) {
            builder.addDeclarationToExistingSymbol(declaration, existingSymbolId, scopeId = builder.rootScopeId)
        } else {
            builder.addDeclarationWithSymbol(declaration, scopeId = builder.rootScopeId)
        }
    }

    /** Shared parameter binding for function declarations and lambda expressions. */
    private fun bindParameter(
        parameter: Identifier,
        owner: DeclarationOwner,
        documentation: DeclarationDocumentation? = null
    ) {
        builder.addDeclarationWithSymbol(
            parameterDeclaration(
                id = builder.nextDeclarationId(),
                name = parameter.name,
                owner = owner,
                anchorNode = parameter,
                documentation = documentation
            )
        )
    }

    /** Shared owner-anchored `@generic`/type-parameter declaration used by all three tag-binding sites. */
    private fun addTypeParameterDeclaration(
        ownerId: DeclarationId,
        parameterName: String,
        attachment: CommentAttachment,
        documentationTags: List<DocTagSyntax>,
        constraintText: String? = null
    ) {
        builder.addDeclarationWithSymbol(
            typeParameterDeclaration(
                id = builder.nextDeclarationId(),
                name = parameterName,
                owner = DeclarationOwner.Declaration(ownerId),
                range = commentRange(attachment),
                documentation = documentationForTags(attachment, documentationTags),
                declaredTypeSyntax = parseTypeSyntax(constraintText)
            ),
            scopeId = null
        )
    }

    private fun bindFunctionTypeParameters(
        functionDeclaration: BinderDeclaration?,
        attachment: CommentAttachment?
    ) {
        val ownerId = functionDeclaration?.id ?: return
        val docComment = attachment?.docComment ?: return
        docComment.tags.filterIsInstance<GenericTagSyntax>().forEach { genericTag ->
            genericTag.parameters.forEach { parameter ->
                addTypeParameterDeclaration(
                    ownerId = ownerId,
                    parameterName = parameter.name,
                    attachment = attachment,
                    documentationTags = listOf(genericTag),
                    constraintText = parameter.constraintText
                )
            }
        }
    }

    private fun bindRootTypeDeclarations(attachment: CommentAttachment) {
        val docComment = attachment.docComment ?: return
        val tags = docComment.tags

        tags.filterIsInstance<AliasTagSyntax>().forEach { aliasTag ->
            val aliasDeclaration = builder.addDeclarationWithSymbol(
                aliasDeclaration(
                    id = builder.nextDeclarationId(), name = aliasTag.name, range = commentRange(attachment),
                    documentation = documentationForTags(attachment, listOf(aliasTag)),
                    declaredTypeSyntax = parseTypeSyntax(aliasTag.targetTypeText)
                ),
                scopeId = builder.rootScopeId
            )

            aliasTag.declaredTypeParameters.forEach { parameterName ->
                addTypeParameterDeclaration(
                    ownerId = aliasDeclaration.id,
                    parameterName = parameterName,
                    attachment = attachment,
                    documentationTags = listOf(aliasTag)
                )
            }
        }

        sliceClassBindings(tags).forEach { binding ->
            val classTag = binding.classTag
            val classDeclaration = builder.addDeclarationWithSymbol(
                classDeclaration(
                    id = builder.nextDeclarationId(), name = classTag.name, range = commentRange(attachment),
                    documentation = documentationForTags(attachment, listOf(classTag) + binding.tags)
                ),
                scopeId = builder.rootScopeId
            )

            bindClassOwnedDeclarations(classDeclaration, attachment, classTag, binding.tags)
        }
    }

    private fun bindClassOwnedDeclarations(
        classDeclaration: BinderDeclaration,
        attachment: CommentAttachment,
        classTag: ClassTagSyntax,
        ownedTags: List<DocTagSyntax>
    ) {
        val genericTags = ownedTags.filterIsInstance<GenericTagSyntax>()
        val fieldTags = ownedTags.filterIsInstance<FieldTagSyntax>()
        val methodBindings = sliceMethodBindings(ownedTags)
        val typeParameterBindings = linkedMapOf<String, TypeParameterBinding>()

        // Shared merge for both binding sources: the @class tag's own declared parameters
        // (no constraint) and @generic parameters (constraint text merged over any prior).
        fun bindTypeParameter(name: String, constraintText: String?, tag: DocTagSyntax) {
            val existing = typeParameterBindings[name]
            typeParameterBindings[name] = TypeParameterBinding(
                name = name,
                constraintText = constraintText ?: existing?.constraintText,
                documentationTags = (existing?.documentationTags.orEmpty() + tag).distinct()
            )
        }
        classTag.declaredTypeParameters.forEach { bindTypeParameter(it, null, classTag) }
        genericTags.forEach { genericTag ->
            genericTag.parameters.forEach { bindTypeParameter(it.name, it.constraintText, genericTag) }
        }

        typeParameterBindings.values.forEach { binding ->
            addTypeParameterDeclaration(
                ownerId = classDeclaration.id,
                parameterName = binding.name,
                attachment = attachment,
                documentationTags = binding.documentationTags,
                constraintText = binding.constraintText
            )
        }

        fieldTags.forEach { fieldTag ->
            builder.addDeclarationWithSymbol(
                fieldDeclaration(
                    id = builder.nextDeclarationId(), name = fieldTag.name,
                    owner = DeclarationOwner.Declaration(classDeclaration.id), range = commentRange(attachment),
                    documentation = documentationForTags(attachment, listOf(fieldTag)),
                    declaredTypeSyntax = parseTypeSyntax(fieldTag.typeText)
                ),
                scopeId = null
            )
        }

        methodBindings.forEach { methodBinding ->
            builder.addDeclarationWithSymbol(
                methodDeclaration(
                    id = builder.nextDeclarationId(), name = methodBinding.methodTag.name,
                    owner = DeclarationOwner.Declaration(classDeclaration.id), range = commentRange(attachment),
                    documentation = documentationForTags(
                        attachment,
                        listOf(methodBinding.methodTag) + methodBinding.overloadTags
                    ),
                    declaredTypeSyntax = parseTypeSyntax(methodBinding.methodTag.signatureText)
                ),
                scopeId = null
            )
        }
    }

    /**
     * Shared doc-tag grouping walk behind [sliceMethodBindings] and [sliceClassBindings]:
     * [startOf] opens a new group (flushing the previous one), [isOwnedTag] accumulates a
     * tag into the currently open group, and a non-owned tag with no open group is dropped.
     */
    private fun <S : DocTagSyntax, T> sliceTagGroups(
        tags: List<DocTagSyntax>,
        startOf: (DocTagSyntax) -> S?,
        isOwnedTag: (DocTagSyntax) -> Boolean,
        build: (S, List<DocTagSyntax>) -> T
    ): List<T> {
        val groups = mutableListOf<T>()
        var currentStart: S? = null
        var currentOwned = mutableListOf<DocTagSyntax>()

        fun flushCurrent() {
            val start = currentStart ?: return
            groups += build(start, currentOwned.toList())
        }

        tags.forEach { tag ->
            val start = startOf(tag)
            when {
                start != null -> {
                    flushCurrent()
                    currentStart = start
                    currentOwned = mutableListOf()
                }

                isOwnedTag(tag) -> if (currentStart != null) {
                    currentOwned += tag
                }
            }
        }

        flushCurrent()
        return groups
    }

    private fun sliceMethodBindings(tags: List<DocTagSyntax>): List<MethodBinding> =
        sliceTagGroups(
            tags,
            startOf = { it as? MethodTagSyntax },
            isOwnedTag = { it is OverloadTagSyntax }
        ) { methodTag, ownedTags ->
            MethodBinding(methodTag, ownedTags.filterIsInstance<OverloadTagSyntax>())
        }

    private fun sliceClassBindings(tags: List<DocTagSyntax>): List<ClassBindingSlice> =
        sliceTagGroups(
            tags,
            startOf = { it as? ClassTagSyntax },
            isOwnedTag = {
                it is GenericTagSyntax || it is JavaClassTagSyntax || it is FieldTagSyntax ||
                    it is MethodTagSyntax || it is OverloadTagSyntax
            }
        ) { classTag, tags -> ClassBindingSlice(classTag, tags) }

    private data class ClassBindingSlice(
        val classTag: ClassTagSyntax,
        val tags: List<DocTagSyntax>
    )

    private data class TypeParameterBinding(
        val name: String,
        val constraintText: String? = null,
        val documentationTags: List<DocTagSyntax> = emptyList()
    )

    private data class MethodBinding(
        val methodTag: MethodTagSyntax,
        val overloadTags: List<OverloadTagSyntax> = emptyList()
    )

    /**
     * Pushes [scopeId] for the duration of [block] and always pops it afterwards — the
     * shared push/try/finally-pop scaffolding behind every nested binding scope below.
     * The lambda is inlined, so binding the critical path allocates nothing extra.
     */
    private inline fun inScope(scopeId: ScopeId, block: () -> Unit) {
        builder.pushScope(scopeId)
        try {
            block()
        } finally {
            builder.popScope()
        }
    }

    private fun bindBodyScope(body: BlockNode, kind: ScopeKind, value: Unit) {
        inScope(builder.createScope(kind, body.range, body)) {
            visitBlockNode(body, value)
        }
    }

    private fun bindNestedConditionalStatement(statement: StatementNode, value: Unit) {
        inScope(builder.createScope(ScopeKind.CONDITIONAL, statement.range, statement)) {
            visitStatementNode(statement, value)
        }
    }

    private fun currentLexicalOwnerNode(): BaseASTNode {
        return requireNotNull(builder.currentScope.ownerNode) { "Current scope is missing an owner node." }
    }

    private fun parseTypeSyntax(text: String?): TypeSyntax? =
        text?.trim()?.takeIf { it.isNotEmpty() }?.let(TypeSyntaxParser::parseOrNull)

    /**
     * `---@type` on a multi-name local is positional: `---@type boolean, string` above
     * `local ok, err = pcall(f)` types `ok` boolean and `err` string. Commas nested inside
     * generics, tables, parens, or strings do not split (depth-aware scan). A single-name
     * local keeps the whole text as before; a multi-name local whose text has no top-level
     * comma stays unresolved (single type, ambiguous owner).
     */
    private fun positionalDeclaredTypeSyntaxes(
        declaredNameCount: Int,
        inlineTypeText: String?
    ): List<TypeSyntax?> {
        val normalized = inlineTypeText?.trim().orEmpty()
        if (normalized.isEmpty()) return emptyList()
        if (declaredNameCount == 1) return listOf(parseTypeSyntax(normalized))

        val positionalTexts = splitTopLevelTypeText(normalized, ',')
        if (positionalTexts.size <= 1) return List(declaredNameCount) { null }

        return List(declaredNameCount) { index -> parseTypeSyntax(positionalTexts.getOrNull(index)) }
    }

    private fun documentationForTags(attachment: CommentAttachment, tags: List<DocTagSyntax>) = DeclarationDocumentation(
        comments = attachment.comments,
        docComment = attachment.docComment?.let { DocCommentSyntax(description = it.description, tags = tags) },
        inlineTypeText = attachment.inlineTypeText
    )

    private fun commentRange(attachment: CommentAttachment): Range? =
        attachment.comments.takeIf { it.isNotEmpty() }?.let { comments ->
            Range(start = comments.first().range.start, end = comments.last().range.end)
        }
}
