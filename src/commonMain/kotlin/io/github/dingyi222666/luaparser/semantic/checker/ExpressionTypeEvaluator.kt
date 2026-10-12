package io.github.dingyi222666.luaparser.semantic.checker

import io.github.dingyi222666.luaparser.parser.compactCallBase
import io.github.dingyi222666.luaparser.parser.compactCallArguments

import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode
import io.github.dingyi222666.luaparser.parser.ast.node.BinaryExpression
import io.github.dingyi222666.luaparser.parser.ast.node.BlockNode
import io.github.dingyi222666.luaparser.parser.ast.node.CallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.CaseCause
import io.github.dingyi222666.luaparser.parser.ast.node.ConstantNode
import io.github.dingyi222666.luaparser.parser.ast.node.DefaultCause
import io.github.dingyi222666.luaparser.parser.ast.node.DoStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode
import io.github.dingyi222666.luaparser.parser.ast.node.ExpressionOperator
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
import io.github.dingyi222666.luaparser.parser.ast.node.RepeatStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ReturnStatement
import io.github.dingyi222666.luaparser.parser.ast.node.StatementNode
import io.github.dingyi222666.luaparser.parser.ast.node.StringCallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.SwitchStatement
import io.github.dingyi222666.luaparser.parser.ast.node.TableCallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.TableConstructorExpression
import io.github.dingyi222666.luaparser.parser.ast.node.TableKey
import io.github.dingyi222666.luaparser.parser.ast.node.TableKeyString
import io.github.dingyi222666.luaparser.parser.ast.node.CallStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.UnaryExpression
import io.github.dingyi222666.luaparser.parser.ast.node.VarargLiteral
import io.github.dingyi222666.luaparser.parser.ast.node.WhenStatement
import io.github.dingyi222666.luaparser.parser.ast.node.WhileStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ArrayConstructorExpression
import io.github.dingyi222666.luaparser.parser.ast.node.AssignmentStatement
import io.github.dingyi222666.luaparser.semantic.SemanticWorkspaceContext
import io.github.dingyi222666.luaparser.semantic.mergeWorkspaceGlobalExtension
import io.github.dingyi222666.luaparser.semantic.binder.BinderDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationId
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationNamespace
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOwner
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin
import io.github.dingyi222666.luaparser.semantic.binder.Scope
import io.github.dingyi222666.luaparser.semantic.binder.ScopeId
import io.github.dingyi222666.luaparser.semantic.binder.isChunkGlobalFunctionDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.comparePositions
import io.github.dingyi222666.luaparser.semantic.workspace.splitCreateProxyTargetList
import io.github.dingyi222666.luaparser.semantic.types.model.AppliedType
import io.github.dingyi222666.luaparser.semantic.types.model.ArrayType
import io.github.dingyi222666.luaparser.semantic.types.model.CallableType
import io.github.dingyi222666.luaparser.semantic.types.model.ClassType
import io.github.dingyi222666.luaparser.semantic.types.model.CustomType
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionParameter
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaArrayType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaConstructorType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaClassType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaInstanceMemberType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaInstanceType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaMemberKind
import io.github.dingyi222666.luaparser.semantic.types.model.JavaOverloadType
import io.github.dingyi222666.luaparser.semantic.types.model.IntersectionType
import io.github.dingyi222666.luaparser.semantic.types.model.LiteralType
import io.github.dingyi222666.luaparser.semantic.types.model.ModuleType
import io.github.dingyi222666.luaparser.semantic.types.model.MultiReturnType
import io.github.dingyi222666.luaparser.semantic.types.model.OverloadedFunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.model.TableType
import io.github.dingyi222666.luaparser.semantic.types.model.TupleType
import io.github.dingyi222666.luaparser.semantic.types.model.Type
import io.github.dingyi222666.luaparser.semantic.types.model.TypeParameterType
import io.github.dingyi222666.luaparser.semantic.types.model.UnionType
import io.github.dingyi222666.luaparser.semantic.types.model.UnknownType
import io.github.dingyi222666.luaparser.semantic.types.model.VarargType
import io.github.dingyi222666.luaparser.semantic.types.resolve.TypeResolutionContext
import io.github.dingyi222666.luaparser.semantic.types.resolve.intersectionTypeOf
import io.github.dingyi222666.luaparser.semantic.types.resolve.unionTypeOf
import io.github.dingyi222666.luaparser.semantic.types.resolve.isAssignableFrom

class ExpressionTypeEvaluator internal constructor(
    private val binder: BinderPassResult,
    private val workspaceContext: SemanticWorkspaceContext
) {

    constructor(binder: BinderPassResult) : this(binder, SemanticWorkspaceContext())

    data class ReturnSite(
        val statement: ReturnStatement?,
        val values: ValueSequence
    )

    data class Context(
        val lexicalScopeId: ScopeId,
        val localOverrides: Map<String, Type> = emptyMap(),
        val excludedDeclarations: Set<DeclarationId> = emptySet(),
        val varargType: Type = VarargType(UnknownType)
    )

    private val expressionTypeCache = mutableMapOf<ExpressionNode, Type>()
    private val contextualExpressionTypeCache = mutableMapOf<ExpressionNode, MutableMap<Context, Type>>()
    private val declarationValueTypeCache = mutableMapOf<DeclarationId, MutableMap<Context, Type>>()
    // A recursion break contaminates every active computation which observed it. Those
    // provisional results must not enter the completed type cache for another entry point.
    private var recursionEpoch = 0L
    private val activeDeclarationIds = mutableSetOf<DeclarationId>()
    private val memberResolver = MemberResolver(binder) { workspaceContext.analysisCheckpoint() }
    private val callChecker = CallChecker(binder)
    private val luaJavaArrayHelperNames = setOf("newArray", "createArray")
    // getContext is an AndroLua helper surface (TASK-575); include it so colon-call guards and
    // helper-owner checks stay consistent with bind/newInstance/createProxy/loadLib.
    private val luaJavaHelperNames =
        setOf("bindClass", "newInstance", "createProxy", "loadLib", "getContext") + luaJavaArrayHelperNames
    // Context-dependent results belong to this document's semantic model. Provider-owned
    // class metadata is cached separately by classpath identity.
    private val androidLuaHydratedSurfaceCache = mutableMapOf<String, Type>()
    private val layoutViewClassTypeCache = mutableMapOf<String, Type>()
    private val layoutSuggestionsCache = mutableMapOf<String, List<LuaLayoutPropertySuggestion>>()

    private val loadlayoutIdsTableTypeCache = mutableMapOf<DeclarationId, Type>()
    // Root-name -> member assignments, built once per evaluator. Replaces the per-value
    // full-declaration scans in visibleMemberDeclarationsForValue / nested path collection
    // (O(declarations) per typed value was quadratic across a document).
    private var memberAssignmentsByRoot: Map<String, List<MemberAssignmentAnchor>>? = null
    // One-shot loadlayout(...) usage index for the whole binder root: each call records
    // where its layout spec table comes from and where the runtime registers the ids.
    private var loadlayoutUsages: List<LoadlayoutUsage>? = null
    private val loadlayoutSinkFieldsCache = mutableMapOf<LoadlayoutIdsSink, Map<String, Type>>()
    private var loadlayoutGlobalIdNames: Set<String>? = null

    fun evaluate(node: ExpressionNode): Type {
        expressionTypeCache[node]?.let { return it }
        val scopeId = binder.positionQueries.getScopeAt(node.range.start)?.id ?: binder.scopeGraph.rootScope.id
        val epoch = recursionEpoch
        val type = evaluate(node, Context(lexicalScopeId = scopeId))
        if (recursionEpoch == epoch) expressionTypeCache[node] = type
        return type
    }

    internal fun inferDeclarationType(declaration: BinderDeclaration): Type {
        val scope = binder.scopeGraph.getDeclarationScope(declaration.id)?.id ?: binder.scopeGraph.rootScope.id
        return deriveDeclarationValueType(declaration, Context(scope), preferDeclaredReturn = false)
    }

    internal fun callableDeclarationAt(node: ExpressionNode): BinderDeclaration? {
        val scope = binder.positionQueries.getScopeAt(node.range.start)?.id ?: binder.scopeGraph.rootScope.id
        return callableDeclaration(node, Context(scope))
    }

    internal fun callArgumentValues(node: CallExpression, lexicalScopeId: ScopeId): List<ValueSequence> =
        buildCallArgumentSequences(node, Context(lexicalScopeId))

    internal fun declarationValueType(declaration: BinderDeclaration): Type {
        val scope = binder.scopeGraph.getDeclarationScope(declaration.id)?.id ?: binder.scopeGraph.rootScope.id
        return typeOfDeclaration(declaration, Context(scope))
    }

    internal fun isLuaJavaHelperCall(node: CallExpression, name: String): Boolean {
        val scope = binder.positionQueries.getScopeAt(node.range.start)?.id ?: binder.scopeGraph.rootScope.id
        return isLuaJavaCallBase(effectiveCallBase(node), Context(scope), name)
    }

    internal fun isImportCall(node: CallExpression): Boolean {
        val scope = binder.positionQueries.getScopeAt(node.range.start)?.id ?: binder.scopeGraph.rootScope.id
        return isImportCallBase(effectiveCallBase(node), Context(scope), mutableSetOf())
    }

    private fun isImportCallBase(base: ExpressionNode, context: Context, visited: MutableSet<DeclarationId>): Boolean {
        val identifier = base as? Identifier ?: return false
        val declaration = findVisibleValueDeclaration(identifier.name, identifier.range.start, context)
        if (identifier.name == "import" &&
            (declaration == null || declaration.origin == DeclarationOrigin.BUILTIN)) return true
        declaration ?: return false
        if (!visited.add(declaration.id)) return false
        val initializer = localDeclarationInitializer(declaration) ?: return false
        val initializerContext = localInitializerContext(declaration, initializer, context)
        return when (initializer) {
            is CallExpression -> isBuiltinRequireImportCall(initializer, initializerContext)
            is Identifier -> isImportCallBase(initializer, initializerContext, visited)
            else -> false
        }
    }

    fun evaluate(node: ExpressionNode, context: Context): Type {
        workspaceContext.analysisCheckpoint()
        contextualExpressionTypeCache[node]?.get(context)?.let { return it }
        val epoch = recursionEpoch
        workspaceContext.onExpressionEvaluation?.invoke(node)
        val type = when (node) {
            is ConstantNode -> evaluateConstant(node, context)
            is Identifier -> evaluateIdentifier(node, context)
            is VarargLiteral -> context.varargType
            is UnaryExpression -> evaluateUnary(node)
            is BinaryExpression -> evaluateBinary(node, context)
            is ArrayConstructorExpression -> evaluateArrayConstructor(node, context)
            is TableConstructorExpression -> evaluateTableConstructor(node, context)
            is MemberExpression -> evaluateMemberExpression(node, context)
            is IndexExpression -> evaluateIndexExpression(node, context)
            is StringCallExpression -> evaluateCallExpression(node, context)
            is TableCallExpression -> evaluateCallExpression(node, context)
            is CallExpression -> evaluateCallExpression(node, context)
            is FunctionDeclaration -> evaluateFunctionDeclaration(node, context, preferDeclaredReturn = true)
            is LambdaDeclaration -> evaluateLambdaDeclaration(node, context)
            else -> UnknownType
        }
        if (recursionEpoch == epoch) contextualExpressionTypeCache.getOrPut(node) { mutableMapOf() }[context] = type
        return type
    }

    private fun evaluateConstant(node: ConstantNode, context: Context): Type {
        resolveBindClassTargetArgumentType(node, context)?.let { return it }
        resolveLoadLibMemberArgumentType(node, context)?.let { return it }
        return evaluateLiteralConstant(node)
    }

    private fun evaluateLiteralConstant(node: ConstantNode): Type {
        return when (node.constantType) {
            ConstantNode.TYPE.NIL -> PrimitiveType.NIL
            ConstantNode.TYPE.BOOLEAN -> LiteralType(node.rawValue.toString() == "true", PrimitiveType.BOOLEAN)
            ConstantNode.TYPE.STRING -> LiteralType(node.stringOf(), PrimitiveType.STRING)
            ConstantNode.TYPE.INTERGER -> LiteralType(node.rawValue.toString().toIntOrNull() ?: node.rawValue, PrimitiveType.NUMBER)
            ConstantNode.TYPE.FLOAT -> LiteralType(node.rawValue.toString().toDoubleOrNull() ?: node.rawValue, PrimitiveType.NUMBER)
            ConstantNode.TYPE.UNKNOWN -> UnknownType
        }
    }

    private fun evaluateIdentifier(node: Identifier, context: Context): Type {
        context.localOverrides[node.name]?.let { return it }
        val declaration = findVisibleValueDeclaration(node.name, node.range.start, context)
        // A source declaration shadows imports. Resolve it before consulting
        // workspace globals, which can evaluate other documents on demand.
        if (declaration?.origin == DeclarationOrigin.AST) {
            return luaJavaLocalInitializerType(declaration, context)
                ?: typeOfDeclaration(declaration, context)
        }
        val imported = workspaceContext.importedSymbols[node.name]
            ?: workspaceContext.resolveImportedSymbol?.invoke(node.name)
        if (declaration != null) {
            luaJavaLocalInitializerType(declaration, context)?.let { return it }
            val declarationType = typeOfDeclaration(declaration, context)
            return if (declaration.origin != DeclarationOrigin.AST && imported?.extendsExistingGlobal == true) {
                mergeWorkspaceGlobalExtension(declarationType, imported.valueType)
            } else {
                declarationType
            }
        }
        imported?.let { return it.valueType }
        // AndroLua loadlayout registers `id="..."` views into _G when no ids table is
        // passed (`loadlayout("layout/main")`); type those reads from the layout row class.
        loadlayoutInjectedGlobalType(node.name, context)?.let { return it }
        if (node.name == "self") {
            implicitSelfType(node)?.let { return it }
        }
        return UnknownType
    }

    /**
     * The implicit `self` inside a colon-method body is not bound as a parameter, so plain
     * identifier resolution falls through to UnknownType and every `self.` member query
     * (completion / hover / member diagnostics) collapses to an empty surface. The method's
     * binder declaration already carries the resolved `---@param self T` type
     * (TypeResolver synthesizes the implicit first parameter for colon methods), so consult
     * it before giving up. Explicit `self` parameters (`function f(self)`) are bound
     * normally and never reach this path.
     */
    private fun implicitSelfType(node: Identifier): Type? {
        val functionNode = findAncestorFunction(node) ?: return null
        val anchor = when (val identifier = functionNode.identifier) {
            is MemberExpression -> identifier.identifier
            is Identifier -> identifier
            else -> null
        } ?: return null
        return binder.declarationIndex.getDeclarations(anchor)
            .mapNotNull { declaration -> declaration.documentation?.resolvedParameterTypes?.get("self") }
            .firstOrNull()
    }

    private fun evaluateUnary(node: UnaryExpression): Type {
        return when (node.operator) {
            ExpressionOperator.MINUS,
            ExpressionOperator.BIT_TILDE,
            ExpressionOperator.GETLEN -> PrimitiveType.NUMBER

            ExpressionOperator.NOT -> PrimitiveType.BOOLEAN
            else -> UnknownType
        }
    }

    private fun evaluateBinary(node: BinaryExpression, context: Context): Type {
        val leftType = node.left?.let { evaluate(it, context) } ?: UnknownType
        val rightType = node.right?.let { evaluate(it, context) } ?: UnknownType

        return when (node.operator) {
            ExpressionOperator.ADD,
            ExpressionOperator.MINUS,
            ExpressionOperator.MULT,
            ExpressionOperator.DIV,
            ExpressionOperator.MOD,
            ExpressionOperator.DOUBLE_DIV,
            ExpressionOperator.BIT_EXP,
            ExpressionOperator.BIT_AND,
            ExpressionOperator.BIT_OR,
            ExpressionOperator.BIT_LT,
            ExpressionOperator.BIT_GT -> PrimitiveType.NUMBER

            ExpressionOperator.CONCAT -> PrimitiveType.STRING

            ExpressionOperator.LT,
            ExpressionOperator.GT,
            ExpressionOperator.LE,
            ExpressionOperator.GE,
            ExpressionOperator.EQ,
            ExpressionOperator.NE -> PrimitiveType.BOOLEAN

            ExpressionOperator.AND,
            ExpressionOperator.OR -> unionTypeOf(leftType, rightType)

            else -> UnknownType
        }
    }

    private fun evaluateArrayConstructor(node: ArrayConstructorExpression, context: Context): Type {
        val elementTypes = node.values.map { evaluate(it, context) }
        return ArrayType(
            elementType = if (elementTypes.isEmpty()) UnknownType else unionTypeOf(elementTypes)
        )
    }

    private fun evaluateTableConstructor(node: TableConstructorExpression, context: Context): Type {
        val fields = linkedMapOf<String, Type>()
        val methods = linkedMapOf<String, Type>()

        node.fields.forEach { field ->
            val keyName = staticTableKeyName(field)
            if (keyName != null) {
                val valueType = evaluate(field.value, context)
                fields[keyName] = valueType
                if (callChecker.resolveCallable(valueType, context.lexicalScopeId).isSuccess) {
                    methods[keyName] = valueType
                }
            }
        }

        return TableType(fields = fields, methods = methods)
    }

    private fun isLikelyAndroidViewClassName(name: String): Boolean {
        if (name.isEmpty() || !name.first().isUpperCase()) {
            return false
        }
        // Common AndroLua layout class identifiers (not exhaustive; just a cheap filter).
        return name.endsWith("Layout") ||
            name.endsWith("View") ||
            name.endsWith("Button") ||
            name.endsWith("EditText") ||
            name.endsWith("TextView") ||
            name.endsWith("ImageView") ||
            name.endsWith("ListView") ||
            name.endsWith("RecyclerView") ||
            name.endsWith("ScrollView") ||
            name.endsWith("WebView") ||
            name.endsWith("CheckBox") ||
            name.endsWith("RadioButton") ||
            name.endsWith("Switch") ||
            name.endsWith("ProgressBar") ||
            name.endsWith("SeekBar") ||
            name.endsWith("Spinner") ||
            name in KNOWN_ANDROID_VIEW_SIMPLE_NAMES
    }

    private fun evaluateMemberExpression(node: MemberExpression, context: Context): Type {
        val baseType = evaluateReferenceBaseType(node.base, context)
            .hydrateJavaProviderType(workspaceContext.resolveImportTarget)
        return memberResolver.resolveMember(baseType, node.identifier.name, node.indexer == ":", context.lexicalScopeId).type
            ?.hydrateJavaProviderType(workspaceContext.resolveImportTarget)
            ?: UnknownType
    }

    private fun evaluateIndexExpression(node: IndexExpression, context: Context): Type {
        // `loadlayout(src, _pageids[i])` registers ids onto the BASE table's elements, so
        // every `_pageids[k]` read surfaces the ids table (recy/progress/...) — checked
        // before plain index resolution, which on an empty `{}` table yields nothing.
        (node.base as? Identifier)?.let { base ->
            loadlayoutSinkIdFields(LoadlayoutIdsSink.Elements(base.name), context).takeIf { it.isNotEmpty() }
                ?.let { return loadlayoutIdsTableSurface(it) }
        }
        val baseType = evaluateReferenceBaseType(node.base, context)
            .hydrateJavaProviderType(workspaceContext.resolveImportTarget)
        val indexType = evaluate(node.index, context)
        return memberResolver.resolveIndex(baseType, node.index, indexType, context.lexicalScopeId).type
            ?.hydrateJavaProviderType(workspaceContext.resolveImportTarget)
            ?: UnknownType
    }

    private fun evaluateCallExpression(node: CallExpression, context: Context): Type {
        // `f{table}` sugar: the parser emits a bare CallExpression whose base is the
        // TableCallExpression carrying the real member and its table argument. Evaluating
        // the wrapper as-is would call the inner call's RESULT with zero arguments and
        // dead-end every fluent chain through a table argument
        // (`animator.addListener{...}.start()`). Delegate to the inner call instead.
        if (node.arguments.isEmpty() && !node.bad) {
            val tableCallBase = node.base as? TableCallExpression
            if (tableCallBase != null) {
                return evaluateCallExpression(tableCallBase, context)
            }
        }
        resolveBuiltinRequire(node, context)?.let { return it }
        resolveDynamicImportCall(node, context)?.let { return it }
        resolveLuaJavaHelperColonCall(node, context)?.let { return it }
        resolveBindClassCall(node, context)?.let { return it }
        resolveNewInstanceCall(node, context)?.let { return it }
        resolveCreateProxyCall(node, context)?.let { return it }
        resolveLoadLibCall(node, context)?.let { return it }
        resolveLuaJavaArrayCall(node, context)?.let { return it }
        resolveJvmConstructorCall(node, context)?.let { return it }
        // The runtime host context retains its complete documented helper surface.
        resolveGetContextCall(node, context)?.let { return it }
        val declaration = callableDeclaration(node.base, context)
        val callableType = evaluateReferenceBaseType(node.base, context)
            .hydrateJavaProviderType(workspaceContext.resolveImportTarget)
        val argumentSequences = buildCallArgumentSequences(node, context)
        val resolution = callChecker.checkCallValues(
            callableType,
            argumentSequences,
            context.lexicalScopeId,
            declaration
        )
        resolution.returnType
            ?.hydrateJavaProviderType(workspaceContext.resolveImportTarget)
            ?.let { luaClassSelfReturnType(node, context, resolution.selectedSignature, it) }
            ?.let { return aluaJavaFluentReturnType(node, context, it) }

        return UnknownType
    }

    /** Preserve the concrete receiver surface for Lua methods declared to return their own class. */
    private fun luaClassSelfReturnType(
        node: CallExpression,
        context: Context,
        signature: FunctionType?,
        returnType: Type
    ): Type {
        val member = effectiveCallBase(node) as? MemberExpression ?: return returnType
        if (member.indexer != ":") {
            return returnType
        }
        val selfType = signature?.parameters?.firstOrNull { it.name == "self" }?.type ?: return returnType
        val selfClassName = luaClassName(selfType) ?: return returnType
        if (luaClassName(returnType) != selfClassName) {
            return returnType
        }
        return evaluateReferenceBaseType(member.base, context)
            .hydrateJavaProviderType(workspaceContext.resolveImportTarget)
    }

    private fun luaClassName(type: Type): String? = when (type) {
        is ClassType -> type.name
        is AppliedType -> type.baseName
        is ModuleType -> type.moduleName
            .substringAfterLast('/')
            .removeSuffix(".lua")
            .substringAfterLast('.')
        else -> null
    }

    /**
     * ALua keeps the Java receiver when an instance method returns Java void/null, enabling
     * `JavaType().setA(...).setB(...)`. Lua callables and Java static calls keep their declared
     * return types.
     */
    private fun aluaJavaFluentReturnType(
        node: CallExpression,
        context: Context,
        returnType: Type
    ): Type {
        if (returnType != PrimitiveType.VOID && returnType != PrimitiveType.NIL &&
            (returnType !is UnionType || PrimitiveType.NIL !in returnType.types)) {
            return returnType
        }
        val member = effectiveCallBase(node) as? MemberExpression ?: return returnType
        val receiverType = evaluateReferenceBaseType(member.base, context)
            .hydrateJavaProviderType(workspaceContext.resolveImportTarget)
        if (receiverType !is JavaInstanceType && receiverType !is JavaArrayType) {
            return returnType
        }
        return when (returnType) {
            PrimitiveType.VOID, PrimitiveType.NIL -> receiverType
            is UnionType -> {
                if (PrimitiveType.NIL !in returnType.types) {
                    returnType
                } else {
                    unionTypeOf(returnType.types.map { type ->
                        if (type == PrimitiveType.NIL) receiverType else type
                    })
                }
            }
            else -> returnType
        }
    }

    private fun resolveDynamicImportCall(node: CallExpression, context: Context): Type? {
        if (!isImportCallBase(effectiveCallBase(node), context, mutableSetOf())) return null
        val importedTargets = importTargets(node)
        if (importedTargets.isEmpty()) {
            return null
        }
        val importedTypes = importedTargets.mapNotNull { target ->
            workspaceContext.resolveImportTarget?.invoke(target)?.moduleType
        }
        if (importedTypes.isEmpty()) {
            return null
        }
        return if (importedTargets.size == 1 && importedTypes.size == 1) {
            importedTypes.single()
        } else {
            ArrayType(elementType = unionTypeOf(importedTypes))
        }
    }

    private fun resolveBindClassCall(node: CallExpression, context: Context): ModuleType? {
        val target = stringCallTarget(node) ?: return null
        if (!isLuaJavaCallBase(effectiveCallBase(node), context, "bindClass")) return null
        return luaJavaHelperCallReturn("bindClass", node, target, context) as? ModuleType
    }

    private fun resolveLuaJavaHelperColonCall(node: CallExpression, context: Context): Type? {
        val base = effectiveCallBase(node) as? MemberExpression ?: return null
        if (base.indexer != ":" || base.identifier.name !in luaJavaHelperNames) {
            return null
        }
        val owner = base.base as? Identifier ?: return null
        if (!isLuaJavaHelperOwner(owner, context)) {
            return null
        }
        return PrimitiveType.UNKNOWN
    }

    private fun resolveNewInstanceCall(node: CallExpression, context: Context): Type? {
        if (!isLuaJavaCallBase(effectiveCallBase(node), context, "newInstance")) {
            return null
        }
        // Dynamic / non-literal class names must not inherit the JavaObject stub return from
        // luajava.newInstance overlays (TASK-682). Keep unknown without inventing providers.
        val target = stringCallTarget(node) ?: return UnknownType
        val moduleType = resolveLuaJavaImportTarget(target)?.moduleType ?: return UnknownType
        val instanceType = moduleType.javaInstanceSurface()
            ?.hydrateLuaJavaProviderType()
            ?: return UnknownType
        // Rank constructor overloads against the reflected `__call` surface (same CallChecker
        // path used for direct Java class construction). Wrong arity/type-shape degrades to
        // unknown instead of blindly keeping the instance surface.
        return if (newInstanceConstructorShapeMatches(moduleType, node, context)) {
            instanceType
        } else {
            UnknownType
        }
    }

    /**
     * Consult constructor/arity ranking for `luajava.newInstance(className, ...)` by reusing
     * the module `__call` surface (typically the reflected [JavaClassType]) and [CallChecker].
     * A class without a constructor surface cannot be instantiated.
     */
    private fun newInstanceConstructorShapeMatches(
        moduleType: ModuleType,
        node: CallExpression,
        context: Context
    ): Boolean {
        val callField = moduleType.fields["__call"] ?: return false
        val constructorType = callField.withJavaCallableSurface(
            resolveImportTarget = workspaceContext.resolveImportTarget
        )
        return callChecker.checkCallValues(
            constructorType,
            newInstanceConstructorArgumentSequences(node, context),
            context.lexicalScopeId
        ).isSuccess
    }

    /**
     * Constructor arguments for LuaJava newInstance are everything after the class-name string.
     * The helper call itself is not a colon-method, so no implicit receiver is injected.
     */
    private fun newInstanceConstructorArgumentSequences(
        node: CallExpression,
        context: Context
    ): List<ValueSequence> {
        val constructorArguments = callArguments(node).drop(1)
        if (constructorArguments.isEmpty()) {
            return emptyList()
        }
        return constructorArguments.mapIndexed { index, argument ->
            val sequence = ValueSequence.of(callArgumentType(argument, context))
            if (index == constructorArguments.lastIndex) {
                sequence
            } else {
                sequence.collapseToSingle()
            }
        }
    }

    private fun resolveCreateProxyCall(node: CallExpression, context: Context): Type? {
        val interfaceTargets = createProxyTargets(node)
        if (interfaceTargets.isEmpty()) {
            return null
        }
        if (!isLuaJavaCallBase(effectiveCallBase(node), context, "createProxy")) {
            return null
        }
        val interfaceTypes = interfaceTargets.mapNotNull { target ->
            resolveLuaJavaImportTarget(target)?.moduleType?.javaInstanceSurface()
                ?.hydrateLuaJavaProviderType()
        }
        if (interfaceTypes.isEmpty()) {
            return UnknownType
        }
        return intersectionTypeOf(interfaceTypes)
    }

    private fun resolveLoadLibCall(node: CallExpression, context: Context): Type? {
        if (!isLuaJavaCallBase(effectiveCallBase(node), context, "loadLib")) {
            return null
        }
        // TASK-593: recognized loadLib with missing/invalid args must not keep a silent
        // static-member surface — degrade to unknown and let checker emit structured diagnostics.
        if (!hasValidLoadLibArguments(node)) {
            return UnknownType
        }
        val target = stringCallTarget(node) ?: return UnknownType
        val memberName = stringCallTarget(node, argumentIndex = 1) ?: return UnknownType
        return resolveLoadLibMemberType(target, memberName, context)
    }


    private fun resolveLuaJavaArrayCall(node: CallExpression, context: Context): Type? {
        val helperName = luaJavaArrayHelperName(node, context) ?: return null

        return when (helperName) {
            "createArray" -> {
                // createArray(className, values) is always a single-rank JVM array.
                // Element display: java.lang.String/char/string map to PrimitiveType.STRING so
                // hover is honest `string[]` (LuaJavaArrayHelpersTddTest hard lock). Object
                // classes stay reflected instance surfaces (e.g. java.util.Locale[]).
                val elementType = stringCallTarget(node)?.let(::javaArrayElementTypeForTarget) ?: UnknownType
                JavaArrayType(
                    elementType = elementType.hydrateJavaProviderType(workspaceContext.resolveImportTarget)
                )
            }
            "newArray" -> resolveLuaJavaNewArrayCall(node, context)
            else -> null
        }
    }

    /**
     * TASK-246 / TASK-525 / TASK-592 / TASK-588: LuaJava `newArray(class, dim1 [, dim2, ...])` typing.
     *
     * Valid multi-dimensional allocations return nested single-rank [JavaArrayType] wrappers
     * equal to the dimension-argument count (`T[][]` for two dims). Index peeling then yields
     * intermediate array surfaces (`T[]`) before the component root. Display uses repeated
     * "[]" only (never "[[]]"). Invalid/missing dimensions still degrade to `unknown[]`
     * without inventing a component class (TASK-525 diagnostics).
     */
    private fun resolveLuaJavaNewArrayCall(node: CallExpression, context: Context): Type {
        // TASK-246 / TASK-525: Invalid/missing dimensions must not keep a known Class[]
        // surface. Degrade to unknown[] so hover/index typing stays conservative.
        if (!hasValidNewArrayDimensions(node, context)) {
            return JavaArrayType(elementType = UnknownType)
        }

        val arguments = callArguments(node)
        val componentType = arguments.firstOrNull()
            ?.let { evaluate(it, context) }
            ?.javaClassElementType()
            ?: UnknownType
        val hydratedComponent = componentType.hydrateJavaProviderType(workspaceContext.resolveImportTarget)
        val rank = (arguments.size - 1).coerceAtLeast(1)
        return nestedJavaArrayType(hydratedComponent, rank)
    }

    /**
     * Build nested single-rank [JavaArrayType] wrappers of [rank] around [componentType].
     * Nested ranks (rather than only `dimensions=N` on a flat component) keep
     * [MemberResolver] index peeling working: each index returns the next inner array,
     * and [JavaArrayType.displayName] stays honest nested ranks (`T[][]`, never "[[]]")
     * via buildJavaArrayName (TASK-588 / TASK-663).
     */
    private fun nestedJavaArrayType(componentType: Type, rank: Int): Type {
        var current = componentType
        // Always dimensions=1 wrappers so elementType is the previous rank surface.
        // Flat multi-dim (dimensions=N) would also display as T[]...[] but would not peel
        // intermediate arrays on index without extra MemberResolver logic.
        val safeRank = rank.coerceAtLeast(1)
        var i = 0
        while (i < safeRank) {
            current = JavaArrayType(elementType = current, dimensions = 1)
            i++
        }
        return current
    }

    /**
     * TASK-525: Public-facing helper for checker diagnostics. True when [node] is a LuaJava
     * `newArray` call whose dimensions are missing/zero/negative/nil/non-numeric/mixed-invalid.
     */
    internal fun isInvalidNewArrayDimensionCall(node: CallExpression): Boolean {
        val scopeId = binder.positionQueries.getScopeAt(node.range.start)?.id ?: binder.scopeGraph.rootScope.id
        val context = Context(lexicalScopeId = scopeId)
        if (luaJavaArrayHelperName(node, context) != "newArray") {
            return false
        }
        return !hasValidNewArrayDimensions(node, context)
    }

    /**
     * TASK-593: Public-facing helper for checker diagnostics. True when [node] is a LuaJava
     * `loadLib` / loadLib-alias call whose arguments are missing, non-string, empty-string,
     * dynamic, or otherwise not the documented `(className: string, methodName: string)` surface.
     *
     * Colon calls (`luajava:loadLib`) and shadowed locals are not loadLib helpers and return false.
     */
    internal fun isInvalidLoadLibArgumentCall(node: CallExpression): Boolean {
        val scopeId = binder.positionQueries.getScopeAt(node.range.start)?.id ?: binder.scopeGraph.rootScope.id
        val context = Context(lexicalScopeId = scopeId)
        if (!isLuaJavaCallBase(effectiveCallBase(node), context, "loadLib")) {
            return false
        }
        return !hasValidLoadLibArguments(node)
    }

    /**
     * Documented loadLib arity/shape: exactly two non-empty string literals.
     * Dynamic expressions, nil, numbers, empty strings, and wrong arity are invalid.
     */
    private fun hasValidLoadLibArguments(node: CallExpression): Boolean {
        val arguments = callArguments(node)
        if (arguments.size != 2) {
            return false
        }
        val className = stringLiteralOf(arguments[0])
        val memberName = stringLiteralOf(arguments[1])
        return !className.isNullOrEmpty() && !memberName.isNullOrEmpty()
    }


    private fun luaJavaArrayHelperName(node: CallExpression, context: Context): String? {
        val base = effectiveCallBase(node)
        return when (base) {
            is MemberExpression -> {
                if (base.identifier.name in luaJavaArrayHelperNames &&
                    isLuaJavaHelperMember(base, context, base.identifier.name)
                ) {
                    base.identifier.name
                } else {
                    null
                }
            }

            is Identifier -> {
                val declaration = callableDeclaration(base, context)
                when {
                    declaration != null && declarationResolvesToLuaJavaHelper(declaration, context, "createArray") -> "createArray"
                    declaration != null && declarationResolvesToLuaJavaHelper(declaration, context, "newArray") -> "newArray"
                    isUnshadowedBareLuaJavaHelper(base, declaration, context, "newArray") -> "newArray"
                    isUnshadowedBareLuaJavaHelper(base, declaration, context, "createArray") -> "createArray"
                    else -> null
                }
            }

            else -> null
        }
    }

    /**
     * TASK-246 / TASK-525: LuaJava `newArray(class, dim1 [, dim2, ...])` requires at least one
     * positive numeric dimension. Missing, zero, negative, nil, or non-numeric
     * dimensions are treated as invalid for typing purposes and force unknown[].
     */
    private fun hasValidNewArrayDimensions(node: CallExpression, context: Context): Boolean {
        val arguments = callArguments(node)
        // class + at least one dimension
        if (arguments.size < 2) {
            return false
        }
        return arguments.drop(1).all { isValidNewArrayDimension(it, context) }
    }

    private fun isValidNewArrayDimension(expression: ExpressionNode, context: Context): Boolean {
        return when (expression) {
            is ConstantNode -> isPositiveNumericConstantDimension(expression)
            is UnaryExpression -> {
                // `-n` is always non-positive for array allocation; other unaries
                // are not valid dimension expressions either.
                false
            }
            else -> isPositiveNumberLikeDimensionType(evaluate(expression, context))
        }
    }

    private fun isPositiveNumericConstantDimension(node: ConstantNode): Boolean {
        return when (node.constantType) {
            ConstantNode.TYPE.INTERGER -> {
                // Prefer typed intOf() so lexeme/rawValue dual-path rewrites still see 0/-n.
                // intOf() falls back to 0 for unparseable lexemes, so only trust a strictly
                // positive typed value as definitive; otherwise re-parse rawValue and reject
                // zero/negative/non-numeric.
                val typed = runCatching { node.intOf() }.getOrNull()
                if (typed != null && typed > 0) {
                    return true
                }
                val text = node.rawValue.toString().trim()
                val value = text.toLongOrNull()
                    ?: text.toDoubleOrNull()?.takeIf { it == it.toLong().toDouble() && !it.isNaN() }?.toLong()
                value != null && value > 0L
            }
            ConstantNode.TYPE.FLOAT -> {
                val floatValue = runCatching { node.floatOf() }.getOrNull()?.toDouble()
                val value = when {
                    floatValue != null && !floatValue.isNaN() -> floatValue
                    else -> node.rawValue.toString().trim().toDoubleOrNull()
                }
                value != null && value > 0.0 && value == value.toLong().toDouble() && !value.isNaN()
            }
            // nil / string / boolean / unknown are never valid array dimensions
            else -> false
        }
    }

    private fun isPositiveNumberLikeDimensionType(type: Type): Boolean {
        return when (type) {
            is LiteralType -> type.baseType == PrimitiveType.NUMBER && isPositiveNumberLiteral(type.value)
            // Bare `number` (non-literal) is accepted for dynamic dimensions (e.g. local n = 2).
            PrimitiveType.NUMBER -> true
            is UnionType -> type.types.isNotEmpty() && type.types.all(::isPositiveNumberLikeDimensionType)
            // unknown/nil/any/boolean/string must not keep Class[] for invalid dimensions.
            else -> false
        }
    }

    private fun isPositiveNumberLiteral(value: Any?): Boolean {
        return when (value) {
            is Int -> value > 0
            is Long -> value > 0L
            is Short -> value > 0
            is Byte -> value > 0
            is Double -> value > 0.0 && !value.isNaN() && value == value.toLong().toDouble()
            is Float -> value > 0f && !value.isNaN() && value == value.toLong().toFloat()
            is Number -> {
                val d = value.toDouble()
                d > 0.0 && !d.isNaN()
            }
            is String -> {
                value.trim().toLongOrNull()?.let { it > 0L }
                    ?: value.trim().toDoubleOrNull()?.let { it > 0.0 && !it.isNaN() && it == it.toLong().toDouble() }
                    ?: false
            }
            else -> false
        }
    }

    private fun resolveJvmConstructorCall(node: CallExpression, context: Context): Type? {
        // Only class modules can become constructor modules. Hydrating ordinary
        // Lua function signatures here repeats the regular call path's work.
        val baseModule = evaluateReferenceBaseType(node.base, context) as? ModuleType ?: return null
        val moduleType = baseModule.hydrateJavaProviderType(workspaceContext.resolveImportTarget) as? ModuleType
            ?: return null
        val instanceType = moduleType.javaInstanceSurface() ?: return null
        val constructorType = moduleType.fields["__call"]
            ?.withJavaCallableSurface(resolveImportTarget = workspaceContext.resolveImportTarget)
            ?: return UnknownType
        val result = callChecker.checkCallValues(
            constructorType,
            buildCallArgumentSequences(node, context),
            context.lexicalScopeId
        )
        return if (result.isSuccess) instanceType.hydrateJavaProviderType(workspaceContext.resolveImportTarget)
            else UnknownType
    }

    private fun isLuaJavaHelperMember(member: MemberExpression, context: Context, helperName: String): Boolean {
        val owner = member.base as? Identifier ?: return false
        return member.indexer == "." &&
            member.identifier.name == helperName &&
            isLuaJavaHelperOwner(owner, context)
    }

    private fun declarationResolvesToLuaJavaHelper(
        declaration: BinderDeclaration?,
        context: Context,
        helperName: String,
        visited: MutableSet<DeclarationId> = linkedSetOf()
    ): Boolean {
        declaration ?: return false
        if (declaration.kind != DeclarationKind.LOCAL) {
            return false
        }
        if (!visited.add(declaration.id)) {
            return false
        }
        val initializer = localDeclarationInitializer(declaration) ?: return false
        val initializerContext = localInitializerContext(declaration, initializer, context)
        return expressionResolvesToLuaJavaHelper(initializer, initializerContext, helperName, visited)
    }

    private fun expressionResolvesToLuaJavaHelper(
        expression: ExpressionNode,
        context: Context,
        helperName: String,
        visited: MutableSet<DeclarationId>
    ): Boolean {
        return when (expression) {
            is MemberExpression -> isLuaJavaHelperMember(expression, context, helperName)
            is Identifier -> {
                val declaration = findVisibleValueDeclaration(expression.name, expression.range.start, context)
                declarationResolvesToLuaJavaHelper(declaration, context, helperName, visited)
            }
            else -> false
        }
    }

    private fun isLuaJavaHelperOwner(owner: Identifier?, context: Context): Boolean {
        if (owner?.name != "luajava" || context.localOverrides.containsKey(owner.name)) {
            return false
        }
        val declaration = findVisibleValueDeclaration(owner.name, owner.range.start, context)
        // TASK-572: Unshadowed `luajava` remains the helper owner so real/realiased helpers
        // and transitive local alias chains keep working when the builtin is not position-visible.
        return isUnshadowedBuiltinHelperDeclaration(declaration)
    }

    /**
     * TASK-572: Any visible non-builtin binding (local function, local, parameter, free global
     * invent) shadows LuaJava helper surfaces; only true builtin free helpers keep them, so
     * local `function bindClass/createProxy/...` stays ordinary Lua.
     */
    private fun isUnshadowedBuiltinHelperDeclaration(declaration: BinderDeclaration?): Boolean {
        if (declaration == null) {
            return true
        }
        if (declaration.origin != DeclarationOrigin.BUILTIN) {
            return false
        }
        // Builtin helpers are never LOCAL/FUNCTION/PARAMETER; reject those kinds defensively
        // so a mis-originated local never re-enters helper typing.
        return declaration.kind != DeclarationKind.LOCAL &&
            declaration.kind != DeclarationKind.FUNCTION &&
            declaration.kind != DeclarationKind.PARAMETER
    }

    private fun isUnshadowedBareLuaJavaHelper(
        base: Identifier,
        declaration: BinderDeclaration?,
        context: Context,
        helperName: String
    ): Boolean {
        if (base.name != helperName || context.localOverrides.containsKey(base.name)) {
            return false
        }
        return isUnshadowedBuiltinHelperDeclaration(declaration)
    }

    private fun resolveLuaJavaImportTarget(target: String) =
        workspaceContext.resolveImportTarget?.invoke(target)
            ?: workspaceContext.workspaceResolver?.importTargetSymbol(target)

    private fun Type.hydrateLuaJavaProviderType(): Type =
        hydrateJavaProviderType { target -> resolveLuaJavaImportTarget(target) }

    private fun localDeclarationInitializer(declaration: BinderDeclaration): ExpressionNode? {
        val localStatement = declaration.anchorNode?.parent as? LocalStatement ?: return null
        val initializerIndex = localStatement.init.indexOf(declaration.anchorNode)
        if (initializerIndex < 0) {
            return null
        }
        return localStatement.variables.getOrNull(initializerIndex)
    }

    private fun localInitializerContext(
        declaration: BinderDeclaration,
        initializer: ExpressionNode,
        fallback: Context
    ): Context {
        val scopeId = binder.positionQueries.getScopeAt(initializer.range.start)?.id ?: fallback.lexicalScopeId
        val localStatement = declaration.anchorNode?.parent as? LocalStatement ?: return fallback.copy(lexicalScopeId = scopeId)
        val statementDeclarationIds = localStatement.init.mapNotNull { identifier ->
            binder.declarationIndex.getDeclarations(identifier)
                .firstOrNull { it.kind == DeclarationKind.LOCAL }
                ?.id
        }
        return fallback.copy(
            lexicalScopeId = scopeId,
            excludedDeclarations = fallback.excludedDeclarations.filterTo(linkedSetOf()) { id ->
                binder.declarationIndex.getDeclaration(id)?.let { isVisibleAt(it, initializer.range.start) } == true
            } + statementDeclarationIds
        )
    }

    private fun resolveBuiltinRequire(node: CallExpression, context: Context): Type? {
        val moduleName = builtinRequireModuleName(node, context) ?: return null
        val currentPath = workspaceContext.currentPath ?: return null
        val resolver = workspaceContext.workspaceResolver ?: return null
        val resolved = resolver.resolveRequire(currentPath, moduleName, workspaceContext.moduleResolutionPath) ?: return null
        val providerPath = resolved.provider?.path?.value.orEmpty()
        if (providerPath.endsWith(".aly")) {
            return CustomType("LuaLayoutSpec")
        }
        return resolved.moduleType
    }

    /**
     * TASK-575: `luajava.getContext()` returns the Android-Lua host context surface.
     *
     * Prefer the documented [ClassType] named `AndroidLuaContext` (and its members from
     * the AndroLua `_G` overlay) so hover is non-unknown and completion can list
     * expected context helpers. Do not invent android.jar-only members here.
     */
    private fun resolveGetContextCall(node: CallExpression, context: Context): Type? {
        // Identical recognition surface to every other LuaJava helper: member form via
        // isLuaJavaHelperMember, bare/alias/chain form via isLuaJavaCallBase.
        if (!isLuaJavaCallBase(effectiveCallBase(node), context, "getContext")) {
            return null
        }
        return androidLuaContextSurface()
    }

    private fun androidLuaContextSurface(): Type =
        androidLuaHydratedSurfaceCache.getOrPut("AndroidLuaContext") {
            resolveDocumentedAndroidLuaContextType() ?: UnknownType
        }

    private fun resolveDocumentedAndroidLuaContextType(): Type? {
        val preferredNames = listOf("activity", "this", "service", "context")
        for (name in preferredNames) {
            val declaration = binder.declarationIndex.declarations.firstOrNull { candidate ->
                candidate.origin == DeclarationOrigin.BUILTIN &&
                    candidate.name == name &&
                    candidate.kind.namespace == DeclarationNamespace.VALUE
            } ?: continue
            extractAndroidLuaContextType(declaration.declaredType)?.let { return it }
        }
        // Also accept a direct documented ClassType/CustomType from any builtin if present.
        binder.declarationIndex.declarations.asSequence()
            .filter { it.origin == DeclarationOrigin.BUILTIN }
            .mapNotNull { extractAndroidLuaContextType(it.declaredType) }
            .firstOrNull()
            ?.let { return it }
        return null
    }

    private fun extractAndroidLuaContextType(type: Type?): Type? {
        type ?: return null
        return when (type) {
            is ClassType -> when (type.name) {
                "AndroidLuaContext" -> type
                "LuaActivity", "LuaService" -> {
                    type.superClass?.let(::extractAndroidLuaContextType)
                        ?: extractAndroidLuaContextType(type.superType)
                }
                else -> null
            }
            is UnionType -> type.types.asSequence().mapNotNull(::extractAndroidLuaContextType).firstOrNull()
            is IntersectionType -> type.types.asSequence().mapNotNull(::extractAndroidLuaContextType).firstOrNull()
            else -> null
        }
    }

    /** Resolve native load* return classes through the configured provider. */
    private fun androidLuaHydratedSurface(alias: String): Type =
        androidLuaHydratedSurfaceCache.getOrPut(alias) {
            val fqcn = when (alias) {
                "AndroidView" -> "android.view.View"
                "AndroidMenu" -> "android.view.Menu"
                "AndroidMenuItem" -> "android.view.MenuItem"
                "Bitmap" -> "android.graphics.Bitmap"
                "Drawable" -> "android.graphics.drawable.Drawable"
                else -> return@getOrPut UnknownType
            }
            val imported = workspaceContext.resolveImportTarget?.invoke(fqcn)
                ?: workspaceContext.workspaceResolver?.importTargetSymbol(fqcn)
            imported?.moduleType?.javaInstanceSurface() ?: UnknownType
        }

    private fun builtinRequireModuleName(node: CallExpression, context: Context): String? {
        val identifier = effectiveCallBase(node) as? Identifier ?: return null
        if (identifier.name != "require") {
            return null
        }
        val declaration = findVisibleValueDeclaration(identifier.name, identifier.range.start, context) ?: return null
        if (declaration.origin != DeclarationOrigin.BUILTIN || declaration.name != "require") {
            return null
        }
        return stringLiteralOf(callArguments(node).singleOrNull() ?: return null)
    }

    private fun isBuiltinRequireImportCall(node: CallExpression, context: Context): Boolean {
        return builtinRequireModuleName(node, context) == "import"
    }

    private fun effectiveCallBase(node: CallExpression): ExpressionNode = node.compactCallBase()

    private fun importTargets(node: CallExpression): List<String> {
        val arguments = callArguments(node)
        val firstArgument = arguments.singleOrNull()
        return when (firstArgument) {
            is ConstantNode -> {
                if (firstArgument.constantType == ConstantNode.TYPE.STRING) listOf(firstArgument.stringOf()) else emptyList()
            }
            is ArrayConstructorExpression -> firstArgument.values.mapNotNull(::stringLiteralOf)
            is TableConstructorExpression -> firstArgument.fields.mapNotNull { stringLiteralOf(it.value) }
            else -> emptyList()
        }
    }

    private fun createProxyTargets(node: CallExpression): List<String> {
        val arguments = callArguments(node)
        if (arguments.isEmpty()) {
            return emptyList()
        }
        // Interface list is the leading string args (comma-lists allowed). Stop at the first
        // non-string argument (implementation table/function) so trailing junk never becomes a target.
        val targets = mutableListOf<String>()
        for (argument in arguments) {
            val literal = stringLiteralOf(argument) ?: break
            targets += splitCreateProxyTargetList(literal)
        }
        return targets
    }

    private fun stringLiteralOf(expression: ExpressionNode): String? {
        val constant = expression as? ConstantNode ?: return null
        return constant.takeIf { it.constantType == ConstantNode.TYPE.STRING }?.stringOf()
    }

    private fun callArguments(node: CallExpression): List<ExpressionNode> = node.compactCallArguments()

    private fun stringCallTarget(node: CallExpression, argumentIndex: Int = 0): String? {
        return stringLiteralOf(callArguments(node).getOrNull(argumentIndex) ?: return null)
    }

    private fun resolveLoadLibMemberArgumentType(node: ConstantNode, context: Context): Type? {
        if (node.constantType != ConstantNode.TYPE.STRING) {
            return null
        }
        val call = enclosingCallExpression(node) ?: return null
        if (callArguments(call).getOrNull(1) !== node) {
            return null
        }
        val target = stringCallTarget(call) ?: return null
        if (!isLuaJavaCallBase(effectiveCallBase(call), context, "loadLib")) {
            return null
        }
        return resolveLoadLibMemberType(target, node.stringOf(), context).takeIf { it != UnknownType }
    }

    private fun resolveBindClassTargetArgumentType(node: ConstantNode, context: Context): Type? {
        if (node.constantType != ConstantNode.TYPE.STRING) {
            return null
        }
        val call = enclosingCallExpression(node) ?: return null
        if (callArguments(call).firstOrNull() !== node) {
            return null
        }
        if (!isLuaJavaCallBase(effectiveCallBase(call), context, "bindClass")) {
            return null
        }
        return resolveLuaJavaImportTarget(node.stringOf())?.moduleType
    }

    private fun enclosingCallExpression(node: BaseASTNode): CallExpression? {
        var current: BaseASTNode? = node
        while (current != null) {
            if (current is CallExpression) {
                return current
            }
            current = runCatching { current.parent }.getOrNull()
        }
        return null
    }

    private fun isLuaJavaCallBase(base: ExpressionNode, context: Context, helperName: String): Boolean {
        return when (base) {
            is MemberExpression -> isLuaJavaHelperMember(base, context, helperName)
            is Identifier -> {
                val declaration = callableDeclaration(base, context)
                    ?: findVisibleValueDeclarationIgnoringScope(
                        base.name,
                        base.range.start,
                        context.excludedDeclarations
                    )
                when {
                    isUnshadowedBareLuaJavaHelper(base, declaration, context, helperName) -> true
                    declaration != null && declarationResolvesToLuaJavaHelper(declaration, context, helperName) -> true
                    declaration != null && declarationResolvesToLuaJavaHelperByDeclarationChain(
                        declaration,
                        helperName,
                        context.excludedDeclarations,
                        linkedSetOf()
                    ) -> true
                    else -> false
                }
            }
            else -> false
        }
    }

    private fun resolveLoadLibMemberType(target: String, memberName: String, context: Context): Type {
        val moduleType = resolveLuaJavaImportTarget(target)?.moduleType ?: return UnknownType
        val memberResolution = memberResolver.resolveMember(
            baseType = moduleType.hydrateLuaJavaProviderType(),
            memberName = memberName,
            preferMethod = true,
            lexicalScopeId = context.lexicalScopeId
        )
        return memberResolution.type
            ?.hydrateLuaJavaProviderType()
            ?: UnknownType
    }

    private fun javaArrayElementTypeForTarget(target: String): Type {
        primitiveArrayElementTypeFor(target)?.let { return it }
        val moduleType = resolveLuaJavaImportTarget(target)?.moduleType ?: return UnknownType
        val surface = moduleType.javaInstanceSurface()
            ?.hydrateLuaJavaProviderType()
            ?: return UnknownType
        return primitiveArrayElementTypeFor(surface.displayName) ?: surface
    }

    private fun Type.javaClassElementType(): Type? {
        val hydrated = hydrateJavaProviderType(workspaceContext.resolveImportTarget)
        return when (hydrated) {
            is ModuleType -> hydrated.javaInstanceSurface()
                ?.hydrateJavaProviderType(workspaceContext.resolveImportTarget)
                ?.let { surface -> primitiveArrayElementTypeFor(surface.displayName) ?: surface }
            is JavaClassType -> JavaInstanceType(hydrated)

            else -> null
        }
    }

    internal fun inferImplementationFunctionType(node: FunctionDeclaration): CallableType? {
        val scopeId = node.body
            ?.let(binder.scopeGraph::getScope)
            ?.id
            ?: binder.positionQueries.getScopeAt(node.range.start)?.id
            ?: binder.scopeGraph.rootScope.id
        return evaluateFunctionDeclaration(
            node = node,
            context = Context(lexicalScopeId = scopeId),
            preferDeclaredReturn = false
        ) as? CallableType
    }

    private fun evaluateFunctionDeclaration(
        node: FunctionDeclaration,
        context: Context,
        preferDeclaredReturn: Boolean
    ): Type {
        val ownedDeclaration = node.body
            ?.let(binder.scopeGraph::getScope)
            ?.ownerDeclarationId
            ?.let(binder.declarationIndex::getDeclaration)
        val declaredSignature = (ownedDeclaration?.declaredType as? CallableType)?.callSignatures?.firstOrNull()
        val declaredParameterOffset = if (ownedDeclaration?.let { isColonMethodDeclaration(binder, it) } == true) 1 else 0
        val documentedParameterTypes = ownedDeclaration?.documentation?.resolvedParameterTypes.orEmpty()
        val documentedReturnType = ownedDeclaration?.documentation?.resolvedReturnTypes
            ?.takeIf { it.isNotEmpty() }
            ?.let { returnTypes ->
                if (returnTypes.size == 1) {
                    returnTypes.single()
                } else {
                    MultiReturnType(returnTypes)
                }
            }

        val body = node.body
        val functionScopeId = body?.let(binder.scopeGraph::getScope)?.id ?: context.lexicalScopeId
        val parameterDeclarations = body
            ?.let(binder.scopeGraph::getScope)
            ?.declarationIds
            .orEmpty()
            .mapNotNull(binder.declarationIndex::getDeclaration)
            .filter { it.kind == DeclarationKind.PARAMETER }

        val parameters = node.params.mapIndexed { index, parameterNode ->
            val declaration = parameterDeclarations.getOrNull(index)
            val declaredParameterType = declaredSignature?.parameters?.getOrNull(index + declaredParameterOffset)?.type
            val parameterType = listOf<Type?>(
                declaration?.declaredType,
                declaredParameterType,
                documentedParameterTypes[parameterNode.name],
                context.localOverrides[parameterNode.name]
            ).firstOrNull { it != null && it != UnknownType } ?: UnknownType
            FunctionParameter(
                name = parameterNode.name,
                type = parameterType,
                vararg = parameterNode.name == "..." || parameterType is VarargType
            )
        }

        val varargType = parameters.lastOrNull { it.vararg }?.type ?: VarargType(UnknownType)
        val childContext = buildFunctionBodyContext(
            function = node,
            parameters = parameters,
            fallbackScopeId = functionScopeId,
            fallbackVarargType = varargType
        )
        val knownReturnType = if (preferDeclaredReturn) {
            declaredSignature?.returnType?.takeIf { it != UnknownType }
                ?: documentedReturnType?.takeIf { it != UnknownType }
        } else null
        val returnType = knownReturnType
            ?: body?.let { inferFunctionReturnType(it, childContext) }
            ?: PrimitiveType.VOID
        // Prefer declared/owned @generic type parameters so value types keep fun<T> labels
        // even when body inference only rebuilds parameters/return (TASK-670).
        val typeParameters = declaredSignature?.typeParameters.orEmpty().ifEmpty {
            ownedDeclaration?.let { declaration ->
                binder.declarationIndex
                    .getOwnedDeclarations(DeclarationOwner.Declaration(declaration.id))
                    .filter { it.kind == DeclarationKind.TYPE_PARAMETER }
                    .map { parameterDeclaration ->
                        (parameterDeclaration.declaredType as? TypeParameterType)
                            ?: TypeParameterType(name = parameterDeclaration.name)
                    }
            }.orEmpty()
        }
        return FunctionType(
            parameters = parameters,
            returnType = returnType,
            typeParameters = typeParameters
        )
    }

    private fun evaluateLambdaDeclaration(node: LambdaDeclaration, context: Context): Type {
        val parameters = node.params.map { parameter ->
            val parameterType = context.localOverrides[parameter.name] ?: UnknownType
            FunctionParameter(
                name = parameter.name,
                type = parameterType,
                vararg = parameter.name == "..." || parameterType is VarargType
            )
        }
        val childContext = context.copy(
            localOverrides = context.localOverrides + parameters.associate { it.name to it.type },
            varargType = parameters.lastOrNull { it.vararg }?.type ?: context.varargType
        )
        return FunctionType(
            parameters = parameters,
            returnType = evaluate(node.expression, childContext)
        )
    }

    private fun typeOfDeclaration(declaration: BinderDeclaration, context: Context): Type {
        if (declaration.id in context.excludedDeclarations) {
            return UnknownType
        }

        if (declaration.kind == DeclarationKind.PARAMETER) {
            return parameterTypeOfDeclaration(declaration, context)
        }

        if (declaration.id in activeDeclarationIds) {
            recursionEpoch++
            return UnknownType
        }
        // The derivation resolves initializers and members at the DECLARATION's own
        // scope (localInitializerContext re-derives from the initializer position),
        // so keying by the caller's lexical scope forced one full member-surface
        // assembly per referencing scope. Normalizing the cache key + computation to
        // the declaration scope types each declaration once per (overrides,
        // exclusions, vararg) shape and reuses it for every referencing scope.
        // Exception: loadlayout ids sinks bind the layout call visible from the
        // CALLER's scope, so they keep the caller-scoped derivation.
        val usesCallerScope = isPotentialLoadlayoutIdsLocal(declaration)
        val derivationScope = if (usesCallerScope) {
            context.lexicalScopeId
        } else {
            binder.scopeGraph.getDeclarationScope(declaration.id)?.id ?: binder.scopeGraph.rootScope.id
        }
        val derivationContext = context.copy(lexicalScopeId = derivationScope)
        declarationValueTypeCache[declaration.id]?.get(derivationContext)?.let { return it }
        activeDeclarationIds.add(declaration.id)
        val epoch = recursionEpoch
        return try {
            val declared = declaration.declaredType.takeIf {
                declaration.kind !in setOf(DeclarationKind.FUNCTION, DeclarationKind.GLOBAL, DeclarationKind.METHOD, DeclarationKind.FIELD)
            }
            val type = attachVisibleAstMembersToType(
                declaration,
                declared ?: deriveDeclarationValueType(declaration, derivationContext),
                derivationContext
            )
            if (recursionEpoch == epoch) declarationValueTypeCache.getOrPut(declaration.id) { mutableMapOf() }[derivationContext] = type
            type
        } finally {
            activeDeclarationIds.remove(declaration.id)
        }
    }

    private fun deriveDeclarationValueType(declaration: BinderDeclaration, context: Context, preferDeclaredReturn: Boolean = true): Type {
        return when (declaration.kind) {
            DeclarationKind.LOCAL -> deriveLocalDeclarationValueType(declaration, context)
            // Builtin/overlay function declarations (loadlayout/print/etc.) often have no AST
            // body. Preserve declaredType instead of collapsing to unknown (handled in the
            // shared declaredOrInferredFunctionValueType tail).
            DeclarationKind.FUNCTION -> declaredOrInferredFunctionValueType(declaration, context, preferDeclaredReturn)
            DeclarationKind.PARAMETER -> parameterTypeOfDeclaration(declaration, context)
            DeclarationKind.GLOBAL -> deriveGlobalDeclarationValueType(declaration, context, preferDeclaredReturn)
            DeclarationKind.METHOD -> deriveMethodDeclarationValueType(declaration, context, preferDeclaredReturn)
            DeclarationKind.FIELD -> deriveFieldDeclarationValueType(declaration, context)
            DeclarationKind.MODULE -> declaration.declaredType ?: UnknownType
            else -> UnknownType
        }
    }

    private fun deriveLocalDeclarationValueType(declaration: BinderDeclaration, context: Context): Type {
        luaJavaLocalInitializerType(declaration, context)?.let { return it }
        loadlayoutIdsTableType(declaration, context)?.let { return it }
        val localStatement = declaration.anchorNode?.parent as? LocalStatement ?: return UnknownType
        val initializerIndex = localStatement.init.indexOf(declaration.anchorNode)
        if (initializerIndex < 0) {
            return UnknownType
        }

        val initializer = localStatement.variables.getOrNull(initializerIndex)
            ?: localStatement.variables.lastOrNull() ?: return PrimitiveType.NIL
        val initializerContext = localInitializerContext(declaration, initializer, context)
        val baseType = resolveAssignedValueType(localStatement.variables, initializerIndex, initializerContext)
        return baseType
    }

    private fun loadlayoutIdsTableType(declaration: BinderDeclaration, context: Context): Type? {
        // Only empty/table locals can be AndroLua ids sinks (local ids = {}). Do not scan the
        // AST for every unrelated local — that re-walk was a primary OOM source under android.jar.
        if (!isPotentialLoadlayoutIdsLocal(declaration)) {
            return null
        }
        loadlayoutIdsTableTypeCache[declaration.id]?.let { return it }
        val fields = loadlayoutSinkIdFields(LoadlayoutIdsSink.Named(declaration.name), context)
        if (fields.isEmpty()) {
            return null
        }
        val result = loadlayoutIdsTableSurface(fields)
        loadlayoutIdsTableTypeCache[declaration.id] = result
        return result
    }

    /** The synthesized surface for a table holding loadlayout-registered view ids. */
    private fun loadlayoutIdsTableSurface(fields: Map<String, Type>): Type =
        ModuleType(
            moduleName = "LuaLayoutIds",
            fields = fields,
            indexSignature = ModuleType.IndexSignature(
                keyType = PrimitiveType.STRING,
                valueType = androidLuaHydratedSurface("AndroidView")
            )
        )

    private fun isPotentialLoadlayoutIdsLocal(declaration: BinderDeclaration): Boolean {
        if (declaration.kind != DeclarationKind.LOCAL) {
            return false
        }
        val initializer = localDeclarationInitializer(declaration) ?: return true
        return initializer is TableConstructorExpression
    }

    /**
     * Merged id fields for one [LoadlayoutIdsSink]: every `loadlayout(layout, sink)` call's
     * layout spec contributes its `id="..."` rows (view-class typed). Built once per sink
     * from the one-shot usage index; layout sources resolve without re-entering evaluate().
     */
    private fun loadlayoutSinkIdFields(sink: LoadlayoutIdsSink, context: Context): Map<String, Type> {
        loadlayoutSinkFieldsCache[sink]?.let { return it }
        val fields = linkedMapOf<String, Type>()
        loadlayoutUsages().forEach { usage ->
            if (usage.sink != sink) {
                return@forEach
            }
            layoutTableFor(usage.source, context)?.let { table ->
                fields.putAll(layoutIdFields(table))
            }
        }
        val frozen = fields.toMap()
        loadlayoutSinkFieldsCache[sink] = frozen
        return frozen
    }

    private fun layoutTableFor(source: LoadlayoutLayoutSource, context: Context): TableConstructorExpression? = when (source) {
        is LoadlayoutLayoutSource.Inline -> source.table
        is LoadlayoutLayoutSource.LocalTable -> localTableInitializerFor(source.name, source.from, context)
        is LoadlayoutLayoutSource.WorkspaceLayout -> loadlayoutPathLayoutTable(source.path)
        is LoadlayoutLayoutSource.CalleeReturn -> calleeReturnLayoutTable(source.callee, source.from, context)
    }

    /**
     * Layout spec variable (`loadlayout(parentLayout, ids)`): resolve through the binder's
     * scope-aware declaration lookup at the call site — AST `parent` pointers are not
     * reliably set on call arguments, so the historical parent-chain block walk silently
     * returned null (Delegates.notNull parent throws on unset access).
     */
    private fun localTableInitializerFor(name: String, from: BaseASTNode, context: Context): TableConstructorExpression? {
        val declaration = findVisibleValueDeclaration(name, from.range.start, context)
        return declaration?.let { localDeclarationInitializer(it) } as? TableConstructorExpression
    }

    /**
     * `loadlayout("layout/main")` — resolve the workspace `.aly` file alyloader would load
     * for the path. The chunk is already parsed by the engine; this only reads its wrapped
     * `return <table>`.
     */
    private fun loadlayoutPathLayoutTable(path: String): TableConstructorExpression? {
        val resolver = workspaceContext.workspaceResolver ?: return null
        return resolver.workspaceLayoutChunk(path)?.let(::topLevelLayoutTable)
    }

    /** `.aly` layout chunks parse as `return <table>` (alyloader's bare-table wrap). */
    private fun topLevelLayoutTable(chunk: ChunkNode): TableConstructorExpression? {
        val returnStatement = chunk.body.returnStatement
            ?: chunk.body.statements.filterIsInstance<ReturnStatement>().firstOrNull()
            ?: return null
        return returnStatement.arguments.filterIsInstance<TableConstructorExpression>().firstOrNull()
    }

    /**
     * `loadlayout(addTab(...), nil, ...)` — the layout spec is the callee's first returned
     * table constructor (`local function addTab() return { ... } end`). The callee resolves
     * through the scope-aware declaration lookup at the call site, not AST parent walking.
     */
    private fun calleeReturnLayoutTable(callee: String, from: BaseASTNode, context: Context): TableConstructorExpression? {
        val declaration = findVisibleValueDeclaration(callee, from.range.start, context) ?: return null
        val function = functionNodeForDeclaration(declaration) ?: return null
        val body = function.body ?: return null
        return body.statements.asSequence()
            .filterIsInstance<ReturnStatement>()
            .firstNotNullOfOrNull { it.arguments.filterIsInstance<TableConstructorExpression>().firstOrNull() }
    }

    /**
     * Structural id names loadlayout registers into `_G` at runtime (`loadlayout(t)` /
     * `loadlayout(t, nil)` — no ids table passed). Names only: descends into call rows
     * (`MyTabLayout { id = "parent" }`) whose view-class typing we deliberately skip, so a
     * runtime-injected id never flags `checker.global.unresolved` even when its typed
     * surface stays unknown.
     */
    fun loadlayoutInjectedGlobalNames(): Set<String> {
        loadlayoutGlobalIdNames?.let { return it }
        val names = linkedSetOf<String>()
        // Name collection is purely structural: a root-scoped Context satisfies the shared
        // layout-table resolver, which never deep-evaluates through this path.
        val unusedNamesContext = Context(lexicalScopeId = binder.scopeGraph.rootScope.id)
        loadlayoutUsages().forEach { usage ->
            if (usage.sink == LoadlayoutIdsSink.Globals) {
                layoutTableFor(usage.source, unusedNamesContext)?.let { collectLayoutIdNames(it, names) }
            }
        }
        loadlayoutGlobalIdNames = names
        return names
    }

    fun isLoadlayoutInjectedGlobal(name: String): Boolean = name in loadlayoutInjectedGlobalNames()

    /** The typed `_G` surface for a loadlayout-injected id, when its row class resolves. */
    fun loadlayoutInjectedGlobalType(name: String, context: Context): Type? =
        loadlayoutInjectedGlobals(context)[name]

    /**
     * All typed ids loadlayout registers into `_G` for this document (no ids table
     * passed). Powers identifier typing and completion enumeration; ids whose row class
     * could not be resolved are absent (they stay unknown rather than mis-typed).
     */
    fun loadlayoutInjectedGlobals(context: Context): Map<String, Type> =
        loadlayoutSinkIdFields(LoadlayoutIdsSink.Globals, context)

    private data class LayoutRow(val table: TableConstructorExpression, val className: String?)
    private val layoutRowsCache = mutableMapOf<TableConstructorExpression, List<LayoutRow>>()

    /** Complete iterative layout index shared by IDs, runtime globals and property queries. */
    private fun layoutRows(root: TableConstructorExpression): List<LayoutRow> =
        layoutRowsCache.getOrPut(root) {
            val rows = mutableListOf<LayoutRow>()
            val pending = ArrayDeque<LayoutRow>()
            val visited = mutableSetOf<TableConstructorExpression>()
            pending.addLast(LayoutRow(root, null))
            while (pending.isNotEmpty()) {
                workspaceContext.analysisCheckpoint()
                val (table, inherited) = pending.removeLast()
                if (!visited.add(table)) continue
                val className = table.fields.firstNotNullOfOrNull { field ->
                    if (isLayoutArrayField(field, staticTableKeyName(field))) {
                        (field.value as? Identifier)?.name?.takeIf(::isLayoutClassIdentifier)
                    } else null
                } ?: inherited
                rows += LayoutRow(table, className)
                table.fields.asReversed().forEach { field ->
                    when (val value = field.value) {
                        is TableConstructorExpression -> pending.addLast(LayoutRow(value, className))
                        is CallExpression -> {
                            val rowClass = (effectiveCallBase(value) as? Identifier)?.name ?: className
                            callArguments(value).filterIsInstance<TableConstructorExpression>().asReversed()
                                .forEach { pending.addLast(LayoutRow(it, rowClass)) }
                        }
                        else -> Unit
                    }
                }
            }
            rows
        }

    private fun collectLayoutIdNames(table: TableConstructorExpression, output: MutableSet<String>) {
        layoutRows(table).forEach { row ->
            row.table.fields.forEach { field ->
                if (staticTableKeyName(field) == "id") stringLiteralOf(field.value)?.let(output::add)
            }
        }
    }

    /** The binder's one document walk owns call discovery; evaluators only select usages. */
    private fun loadlayoutUsages(): List<LoadlayoutUsage> {
        loadlayoutUsages?.let { return it }
        val collected = mutableListOf<LoadlayoutUsage>()
        binder.scopeGraph.calls.forEach { call ->
            val parent = call.parent as? CallExpression
            // Compact syntax contributes one logical call, with merged arguments.
            if (parent?.base === call && (call is StringCallExpression || call is TableCallExpression)) return@forEach
            workspaceContext.analysisCheckpoint()
            recordLoadlayoutUsage(call, collected)
        }
        loadlayoutUsages = collected
        return collected
    }

    private fun recordLoadlayoutUsage(node: CallExpression, output: MutableList<LoadlayoutUsage>) {
        val base = effectiveCallBase(node) as? Identifier ?: return
        if (base.name !in LOADLAYOUT_FAMILY_NAMES) {
            return
        }
        val args = callArguments(node)
        // Sink: `loadlayout(layout, ids)` names an ids table; `_pageids[i]` targets the
        // elements of its base table; a missing/nil second arg registers ids into _G.
        val sink = when (val second = args.getOrNull(1)) {
            is Identifier -> LoadlayoutIdsSink.Named(second.name)
            is IndexExpression -> (second.base as? Identifier)
                ?.let { LoadlayoutIdsSink.Elements(it.name) }
                ?: LoadlayoutIdsSink.Globals
            else -> LoadlayoutIdsSink.Globals
        }
        val source = when (val first = args.getOrNull(0)) {
            is TableConstructorExpression -> LoadlayoutLayoutSource.Inline(first)
            is Identifier -> LoadlayoutLayoutSource.LocalTable(first.name, first)
            is ConstantNode -> stringLiteralOf(first)
                ?.let { LoadlayoutLayoutSource.WorkspaceLayout(it) }
                ?: return
            is CallExpression -> (first.base as? Identifier)
                ?.let { LoadlayoutLayoutSource.CalleeReturn(it.name, first) }
                ?: return
            else -> return
        }
        output.add(LoadlayoutUsage(sink, source))
    }

    private fun layoutIdFields(table: TableConstructorExpression): Map<String, Type> = buildMap {
        layoutRows(table).forEach { row ->
            row.table.fields.forEach { field ->
                if (staticTableKeyName(field) == "id") {
                    val id = stringLiteralOf(field.value) ?: return@forEach
                    put(id, layoutIdFieldType(row.className?.let(::resolveLayoutViewClassType), row.table))
                }
            }
        }
    }

    /**
     * The synthesized type for a layout id: the row's Android view class enriched with
     * data-bearing property values (`adapter = LuaMultiAdapter(activity, {...})`). At
     * runtime loadlayout assigns the constructed value through the matching setter and
     * reading the property back (`tab.poplist.adapter`) returns it, so the field type
     * carries the constructor class's instance surface. Resolution mounts the class by
     * name through the JVM interop target resolver (bundled AndroLua runtime jar) —
     * bounded, no listener-body descent, everything else keeps the plain view class.
     */
    private fun layoutIdFieldType(viewClassType: Type?, entry: TableConstructorExpression): Type {
        var fieldType: Type = viewClassType ?: androidLuaHydratedSurface("AndroidView")
        entry.fields.forEach { field ->
            val keyName = staticTableKeyName(field) ?: return@forEach
            if (keyName != "adapter" && keyName != "list") {
                return@forEach
            }
            val call = field.value as? CallExpression ?: return@forEach
            val constructorName = (call.base as? Identifier)?.name ?: return@forEach
            if (!constructorName.startsWith("Lua") || !constructorName.endsWith("Adapter")) {
                return@forEach
            }
            // The constructor identifier resolves to the overlay global's class surface:
            // a ModuleType carrying a __class Java surface, or a ClassType whose
            // @java-class names the runtime class. Mount the reflected instance surface
            // (add/addAll/clear/...) from either shape.
            val baseEval = runCatching { evaluate(call.base) }.getOrNull()
            val instanceSurface = when (baseEval) {
                is ModuleType -> baseEval.javaInstanceSurface()
                is ClassType -> baseEval.javaClassName?.let { fqcn ->
                    runCatching {
                        workspaceContext.resolveImportTarget?.invoke(fqcn)
                            ?.moduleType
                            ?.javaInstanceSurface()
                    }.getOrNull()
                }
                else -> null
            } ?: return@forEach
            fieldType = when (val current = fieldType) {
                is JavaInstanceType -> current.copy(
                    classType = current.classType.copy(
                        // Direct instance-member hit: wins over the getAdapter() bean
                        // alias, so `.adapter` surfaces the assigned Lua adapter.
                        instanceMembers = current.classType.instanceMembers + (
                            keyName to JavaInstanceMemberType(
                                owner = current.classType.javaName,
                                memberName = keyName,
                                valueType = instanceSurface,
                                memberKind = JavaMemberKind.FIELD
                            )
                        )
                    )
                )
                is ClassType -> current.copy(fields = current.fields + (keyName to instanceSurface))
                else -> current
            }
        }
        return fieldType
    }

    /**
     * True for Lua array-table fields (`{ TextView, ... }`, nested child tables).
     * Parser assigns ConstantNode.INTERGER keys for implicit array positions; named
     * `id =` / `text =` fields use TableKeyString / Identifier keys and are not array fields.
     */
    private fun isLayoutArrayField(field: TableKey, keyName: String?): Boolean {
        if (field is TableKeyString) {
            return false
        }
        if (keyName == null) {
            return true
        }
        val key = field.key
        return key is ConstantNode && key.constantType == ConstantNode.TYPE.INTERGER
    }

    /**
     * Resolve the enclosing Android view class for [target] by walking the layout table tree
     * from [root], mirroring [layoutIdFields] class tracking: each table's first positional
     * identifier that looks like a view class becomes the current class for the table and its
     * nested tables. Returns null when [target] is unreachable from [root].
     */
    fun layoutPropertyClassAt(root: TableConstructorExpression, target: TableConstructorExpression): LuaLayoutClassMatch? {
        val row = layoutRows(root).firstOrNull { it.table === target } ?: return null
        val name = row.className ?: return null
        return LuaLayoutClassMatch(name, resolveLayoutViewClassType(name))
    }

    /** A view-class identifier that resolves for layout completions: known view names or embedder-extended classes. */
    private fun isLayoutClassIdentifier(name: String): Boolean {
        return isLikelyAndroidViewClassName(name) || name in workspaceContext.layoutPropertyExtensions
    }

    /**
     * Lua property names usable inside an AndroLua layout table for [match]
     * (loadlayout semantics: property `k` applies `view.setCap(k)(value)`).
     *
     * Sources, grounded in the Android-Lua `loadlayout.lua` runtime:
     * - Bean properties derived from the reflected `set*` instance methods of the class
     *   (setTextColor -> textColor, setAdapter -> adapter, setRadius -> radius).
     * - Runtime-special keys loadlayout handles explicitly: id, style, onClick, src
     *   (image-bearing views), items (adapter views).
     * - Embedder-provided extensions keyed by the class name written in the layout table
     *   (workspace metadata `lua.layout.properties`), matched by source or Java simple name.
     * - LayoutParams keys applied before setters: layout_width/height, margins,
     *   layout_weight, layout_gravity, layout_x/y.
     */
    fun layoutPropertySuggestions(match: LuaLayoutClassMatch): List<LuaLayoutPropertySuggestion> {
        layoutSuggestionsCache[match.sourceName]?.let { return it }
        val members = (match.type as? JavaInstanceType)?.allInstanceMembers().orEmpty()
        val suggestions = linkedMapOf<String, String>()
        members.values.forEach { member ->
            if (member.memberKind != JavaMemberKind.METHOD || !member.memberName.startsWith("set")) {
                return@forEach
            }
            val property = member.memberName.decapitalizeLuaProperty()
            if (property.isEmpty() || property in suggestions) {
                return@forEach
            }
            val signatures = (member.valueType as? CallableType)?.callSignatures.orEmpty()
            val first = signatures.firstOrNull { it.parameters.isNotEmpty() } ?: return@forEach
            suggestions[property] = first.parameters.joinToString(", ") { it.type.displayName }
        }
        // Never suggest internal plumbing keys loadlayout manages itself.
        suggestions.remove("layoutParams")
        suggestions.remove("id")

        val result = mutableListOf(LuaLayoutPropertySuggestion("id", LAYOUT_ID_DETAIL))
        val memberNames = members.keys
        if ("setImageBitmap" in memberNames || "setImageResource" in memberNames) {
            result += LuaLayoutPropertySuggestion("src", LAYOUT_SRC_DETAIL)
        }
        if ("setAdapter" in memberNames) {
            result += LuaLayoutPropertySuggestion("items", LAYOUT_ITEMS_DETAIL)
        }
        result += LuaLayoutPropertySuggestion("style", LAYOUT_STYLE_DETAIL)
        result += LuaLayoutPropertySuggestion("onClick", LAYOUT_ON_CLICK_DETAIL)
        suggestions.forEach { (label, detail) ->
            result += LuaLayoutPropertySuggestion(label, detail)
        }
        val known = result.mapTo(hashSetOf()) { it.label }
        layoutPropertyExtensionEntries(match).forEach { extension ->
            if (extension.label !in known) {
                result += extension
            }
        }
        LAYOUT_PARAM_SUGGESTIONS.forEach { (label, detail) ->
            result += LuaLayoutPropertySuggestion(label, detail)
        }
        // allInstanceMembers() walks the full reflected hierarchy; cache the derived list
        // per class so repeated keystrokes do not re-enumerate it.
        layoutSuggestionsCache[match.sourceName] = result
        return result
    }

    private fun layoutPropertyExtensionEntries(match: LuaLayoutClassMatch): List<LuaLayoutPropertySuggestion> {
        val extensions = workspaceContext.layoutPropertyExtensions
        return extensions[match.sourceName]
            ?: (match.type as? JavaInstanceType)?.javaName?.simpleNames?.lastOrNull()
                ?.let(extensions::get)
            ?: emptyList()
    }

    /**
     * Valid string-literal values for a layout-table property key, grounded in the
     * Android-Lua `loadlayout.lua` runtime converters (`toint` keyword maps, `checkint`
     * pipe flags, `checkType` units, `checkPercent` %w/%h, boolean strings). Empty when the
     * key has no modeled value domain (free-form keys like id/text/src).
     */
    fun layoutValueSuggestionsForKey(key: String): List<String> {
        return LuaLayoutValueDomains.forKey(key)
    }

    /** Workspace module names usable as require / import string arguments. */
    fun workspaceModuleCompletionNames(): List<String> {
        return workspaceContext.workspaceResolver?.completionModuleNames().orEmpty()
    }

    private fun String.decapitalizeLuaProperty(): String {
        if (length < 4) {
            return ""
        }
        val body = substring(3)
        if (body.isEmpty() || !body[0].isUpperCase()) {
            // setup()/setts() are not bean setters; require SetXxx shape.
            return ""
        }
        if (body.length > 1 && body[1].isUpperCase()) {
            return body
        }
        return body.replaceFirstChar { it.lowercaseChar() }
    }

    private fun resolveLayoutViewClassType(className: String): Type {
        layoutViewClassTypeCache[className]?.let { return it }
        // The written identifier binds to the document's active imports first (AndroLua
        // semantics); stock android.widget/android.view packages are the fallback, so a
        // project class shadowing a platform simple name is not silently replaced.
        val candidates = listOf(
            className,
            "android.widget.$className",
            "android.view.$className"
        )
        for (candidate in candidates) {
            val imported = workspaceContext.resolveImportTarget?.invoke(candidate)
                ?: workspaceContext.workspaceResolver?.importTargetSymbol(candidate)
            // Reuse cached instance surface; never deep-hydrate member signatures.
            val surface = imported?.moduleType?.javaInstanceSurface()
            if (surface != null && surface != UnknownType) {
                layoutViewClassTypeCache[className] = surface
                return surface
            }
        }
        return UnknownType.also { layoutViewClassTypeCache[className] = it }
    }

    private fun functionNodeForDeclaration(declaration: BinderDeclaration): FunctionDeclaration? {
        return resolveOwningFunctionDeclaration(binder, declaration)
            ?: declaration.anchorNode?.parent as? FunctionDeclaration
    }

    /**
     * Shared declared-vs-inferred callable derivation for FUNCTION/GLOBAL function values:
     * resolve the owning function node, infer the body type, then merge with the declared
     * signature preferring the declared return. Globals keep their extra non-callable
     * declaredType short-circuit and assignment-value check before entering here.
     */
    private fun declaredOrInferredFunctionValueType(declaration: BinderDeclaration, context: Context, preferDeclaredReturn: Boolean = true): Type {
        val functionNode = functionNodeForDeclaration(declaration)
            ?: return declaration.declaredType ?: UnknownType
        val inferred = inferImplementationFunctionType(functionNode)
        val declared = declaration.declaredType as? CallableType
            ?: return inferred
                ?: evaluateFunctionDeclaration(functionNode, context, preferDeclaredReturn = preferDeclaredReturn)
        val inferredCallable = inferred as? CallableType ?: return declared
        return mergeDeclaredAndInferredCallableType(declaration, declared, inferredCallable, preferDeclaredReturn = preferDeclaredReturn)
    }

    private fun deriveGlobalDeclarationValueType(declaration: BinderDeclaration, context: Context, preferDeclaredReturn: Boolean = true): Type {
        // Builtin GLOBAL values such as activity/service/this/context are non-callable
        // ClassType/CustomType/ModuleType/UnionType surfaces from BuiltinSymbolSeeder with no
        // function body. Returning UnknownType discarded declaredType and broke member hover
        // (getLuaDir/getLuaPath). Prefer non-callable declaredType; keep callable body inference
        // for true function globals (print/import/load*).
        val declaredType = declaration.declaredType
        if (declaredType != null && declaredType !is CallableType) {
            return declaredType
        }
        globalAssignmentValueType(declaration, context)?.let { return it }
        return declaredOrInferredFunctionValueType(declaration, context, preferDeclaredReturn)
    }

    private fun globalAssignmentValueType(declaration: BinderDeclaration, context: Context): Type? {
        val anchor = declaration.anchorNode ?: return null
        val assignment = anchor.parent as? AssignmentStatement ?: return null
        val targetIndex = assignment.init.indexOf(anchor)
        if (targetIndex < 0) {
            return null
        }
        return resolveAssignedValueType(
            assignment.variables,
            targetIndex,
            context.copy(excludedDeclarations = context.excludedDeclarations + declaration.id)
        )
    }

    private fun deriveMethodDeclarationValueType(declaration: BinderDeclaration, context: Context, preferDeclaredReturn: Boolean = true): Type {
        assignedMemberValueType(declaration, context)?.let { inferred ->
            val declared = declaration.declaredType as? CallableType ?: return inferred
            val inferredCallable = inferred as? CallableType ?: return declared
            return mergeDeclaredAndInferredCallableType(declaration, declared, inferredCallable, preferDeclaredReturn = preferDeclaredReturn)
        }
        val functionNode = resolveOwningFunctionDeclaration(binder, declaration) ?: return declaration.declaredType ?: UnknownType
        val inferred = inferImplementationFunctionType(functionNode)
        val declared = declaration.declaredType as? CallableType ?: return inferred ?: UnknownType
        val inferredCallable = inferred as? CallableType ?: return enrichMethodCallableType(binder, declaration, declared)
        return mergeDeclaredAndInferredCallableType(declaration, declared, inferredCallable, preferDeclaredReturn = preferDeclaredReturn)
    }

    private fun deriveFieldDeclarationValueType(declaration: BinderDeclaration, context: Context): Type {
        return declaration.declaredType ?: assignedMemberValueType(declaration, context) ?: UnknownType
    }

    private fun assignedMemberValueType(declaration: BinderDeclaration, context: Context): Type? {
        val member = declaration.anchorNode?.parent as? MemberExpression ?: return null
        val assignment = member.parent as? AssignmentStatement ?: return null
        val targetIndex = assignment.init.indexOf(member)
        if (targetIndex < 0) {
            return null
        }
        val scopeId = binder.positionQueries.getScopeAt(assignment.range.start)?.id ?: context.lexicalScopeId
        return resolveAssignedValueType(
            assignment.variables,
            targetIndex,
            context.copy(lexicalScopeId = scopeId)
        )
    }

    private fun findVisibleValueDeclaration(name: String, position: Position, context: Context): BinderDeclaration? {
        var scope: Scope? = binder.scopeGraph.getScope(context.lexicalScopeId)
        while (scope != null) {
            val declaration = binder.positionQueries.valueDeclarationsInScope(scope.id, name)
                .firstOrNull { candidate ->
                    candidate.id !in context.excludedDeclarations &&
                        isVisibleAt(candidate, position)
                }
            if (declaration != null) {
                return declaration
            }
            scope = scope.parentId?.let(binder.scopeGraph::getScope)
        }
        return null
    }

    private fun evaluateReferenceBaseType(node: ExpressionNode, context: Context): Type {
        val identifier = node as? Identifier ?: return evaluate(node, context)
        context.localOverrides[identifier.name]?.let { return it }
        val declaration = findVisibleValueDeclaration(identifier.name, identifier.range.start, context)
        declaration?.let { luaJavaLocalInitializerType(it, context) }?.let { return it }
        // Prefer full value derivation over a raw declaredType short-circuit.
        // TypeResolver materializes bare local functions as `function(...): unknown`; that
        // incomplete signature must not suppress ordinary body return inference for helpers
        // that happen to share LuaJava names (e.g. local function createProxy → { value = target }).
        return declaration?.let { typeOfDeclaration(it, context) } ?: evaluate(node, context)
    }

    private fun luaJavaLocalInitializerType(declaration: BinderDeclaration, context: Context): Type? {
        if (declaration.kind != DeclarationKind.LOCAL) {
            return null
        }
        val initializer = localDeclarationInitializer(declaration) as? CallExpression ?: return null
        val initializerContext = localInitializerContext(declaration, initializer, context)
        val target = stringCallTarget(initializer)
        if (target == null) {
            // Dynamic class-name newInstance/bindClass/loadLib must stay unknown rather than
            // falling through to the JavaObject stub declaredType (TASK-682).
            val base = effectiveCallBase(initializer)
            if (isLuaJavaCallBase(base, initializerContext, "newInstance") ||
                isLuaJavaCallBase(base, initializerContext, "bindClass") ||
                isLuaJavaCallBase(base, initializerContext, "loadLib")
            ) {
                return UnknownType
            }
            return null
        }
        return luaJavaHelperCallType(initializer, target, initializerContext)
            ?: luaJavaHelperCallTypeFromDeclarationChain(initializer, target, declaration)
    }

    private fun luaJavaHelperCallType(call: CallExpression, target: String, context: Context): Type? {
        return when {
            isLuaJavaCallBase(effectiveCallBase(call), context, "bindClass") ->
                luaJavaHelperCallReturn("bindClass", call, target, context)

            isLuaJavaCallBase(effectiveCallBase(call), context, "newInstance") ->
                luaJavaHelperCallReturn("newInstance", call, target, context)

            isLuaJavaCallBase(effectiveCallBase(call), context, "createProxy") ->
                luaJavaHelperCallReturn("createProxy", call, target, context)

            isLuaJavaCallBase(effectiveCallBase(call), context, "loadLib") ->
                luaJavaHelperCallReturn("loadLib", call, target, context)

            else -> null
        }
    }

    /**
     * Shared LuaJava helper return typing for a recognized helper call
     * (bindClass / newInstance / createProxy / loadLib). Single source of truth for the
     * direct-initializer path ([luaJavaHelperCallType]) and the transitive
     * declaration-chain path ([luaJavaHelperCallTypeFromDeclarationChain]); null means
     * "helper not applicable here", letting the caller fall through.
     */
    private fun luaJavaHelperCallReturn(
        helperName: String,
        call: CallExpression,
        target: String,
        context: Context
    ): Type? {
        return when (helperName) {
            "bindClass" -> resolveLuaJavaImportTarget(target)?.moduleType

            "newInstance" -> {
                val moduleType = resolveLuaJavaImportTarget(target)?.moduleType ?: return null
                if (!newInstanceConstructorShapeMatches(moduleType, call, context)) {
                    UnknownType
                } else {
                    moduleType.javaInstanceSurface()
                        ?.hydrateLuaJavaProviderType()
                        ?: UnknownType
                }
            }

            "createProxy" -> {
                val interfaceTypes = createProxyTargets(call).mapNotNull { interfaceTarget ->
                    resolveLuaJavaImportTarget(interfaceTarget)?.moduleType?.javaInstanceSurface()
                        ?.hydrateLuaJavaProviderType()
                }
                when {
                    interfaceTypes.isEmpty() -> UnknownType
                    else -> intersectionTypeOf(interfaceTypes)
                }
            }

            "loadLib" -> {
                if (!hasValidLoadLibArguments(call)) {
                    UnknownType
                } else {
                    val memberName = stringCallTarget(call, argumentIndex = 1) ?: return UnknownType
                    resolveLoadLibMemberType(target, memberName, context)
                }
            }

            else -> null
        }
    }

    private fun luaJavaHelperCallTypeFromDeclarationChain(
        call: CallExpression,
        target: String,
        assignedDeclaration: BinderDeclaration
    ): Type? {
        val base = effectiveCallBase(call) as? Identifier ?: return null
        val excluded = localStatementDeclarationIds(assignedDeclaration)
        val baseDeclaration = findVisibleValueDeclarationIgnoringScope(base.name, base.range.start, excluded) ?: return null
        val helperName = when {
            declarationResolvesToLuaJavaHelperByDeclarationChain(baseDeclaration, "bindClass", excluded, linkedSetOf()) -> "bindClass"
            declarationResolvesToLuaJavaHelperByDeclarationChain(baseDeclaration, "newInstance", excluded, linkedSetOf()) -> "newInstance"
            declarationResolvesToLuaJavaHelperByDeclarationChain(baseDeclaration, "createProxy", excluded, linkedSetOf()) -> "createProxy"
            declarationResolvesToLuaJavaHelperByDeclarationChain(baseDeclaration, "loadLib", excluded, linkedSetOf()) -> "loadLib"
            else -> return null
        }
        val chainContext = Context(
            lexicalScopeId = binder.positionQueries.getScopeAt(call.range.start)?.id
                ?: binder.scopeGraph.rootScope.id,
            excludedDeclarations = excluded
        )
        return luaJavaHelperCallReturn(helperName, call, target, chainContext)
    }

    private fun declarationResolvesToLuaJavaHelperByDeclarationChain(
        declaration: BinderDeclaration,
        helperName: String,
        excludedDeclarations: Set<DeclarationId>,
        visited: MutableSet<DeclarationId>
    ): Boolean {
        if (
            declaration.kind != DeclarationKind.LOCAL ||
            declaration.id in excludedDeclarations ||
            !visited.add(declaration.id)
        ) {
            return false
        }
        // Exclude only the current alias hop's same-statement locals. Do not accumulate prior hops:
        // transitive chains like `bindClass -> bind -> again` must still see earlier alias decls.
        val hopExclusions = localStatementDeclarationIds(declaration)
        return when (val initializer = localDeclarationInitializer(declaration)) {
            is MemberExpression -> isLuaJavaHelperMemberByDeclarationChain(
                initializer,
                helperName,
                hopExclusions
            )
            is Identifier -> {
                val next = findVisibleValueDeclarationIgnoringScope(
                    name = initializer.name,
                    position = initializer.range.start,
                    excludedDeclarations = hopExclusions
                ) ?: return false
                declarationResolvesToLuaJavaHelperByDeclarationChain(
                    next,
                    helperName,
                    hopExclusions,
                    visited
                )
            }
            else -> false
        }
    }

    private fun isLuaJavaHelperMemberByDeclarationChain(
        member: MemberExpression,
        helperName: String,
        excludedDeclarations: Set<DeclarationId>
    ): Boolean {
        val owner = member.base as? Identifier ?: return false
        if (member.indexer != "." || member.identifier.name != helperName || owner.name != "luajava") {
            return false
        }
        val ownerDeclaration = findVisibleValueDeclarationIgnoringScope(
            name = owner.name,
            position = owner.range.start,
            excludedDeclarations = excludedDeclarations
        )
        // TASK-572: mirror isLuaJavaHelperOwner — local/non-builtin `luajava` shadows helpers.
        if (ownerDeclaration == null) {
            return true
        }
        if (ownerDeclaration.origin != DeclarationOrigin.BUILTIN) {
            return false
        }
        return ownerDeclaration.kind != DeclarationKind.LOCAL &&
            ownerDeclaration.kind != DeclarationKind.FUNCTION &&
            ownerDeclaration.kind != DeclarationKind.PARAMETER
    }

    private fun localStatementDeclarationIds(declaration: BinderDeclaration): Set<DeclarationId> {
        val localStatement = declaration.anchorNode?.parent as? LocalStatement ?: return emptySet()
        return localStatement.init.mapNotNull { identifier ->
            binder.declarationIndex.getDeclarations(identifier)
                .firstOrNull { it.kind == DeclarationKind.LOCAL }
                ?.id
        }.toSet()
    }

    private fun findVisibleValueDeclarationIgnoringScope(
        name: String,
        position: Position,
        excludedDeclarations: Set<DeclarationId>
    ): BinderDeclaration? {
        return findVisibleValueDeclaration(
            name = name,
            position = position,
            context = Context(
                lexicalScopeId = binder.positionQueries.getScopeAt(position)?.id ?: binder.scopeGraph.rootScope.id,
                excludedDeclarations = excludedDeclarations
            )
        )
    }

    private fun parameterTypeOfDeclaration(declaration: BinderDeclaration, context: Context): Type {
        val overrideType = context.localOverrides[declaration.name]
        if (overrideType != null && overrideType != UnknownType) {
            return overrideType
        }
        layoutListenerParameterType(declaration)?.let { return it }
        return declaration.declaredType ?: overrideType ?: UnknownType
    }

    private fun layoutListenerParameterType(declaration: BinderDeclaration): Type? {
        if (declaration.kind != DeclarationKind.PARAMETER) {
            return null
        }
        val function = resolveOwningFunctionDeclaration(binder, declaration) ?: return null
        // Only first parameter of layout listener callbacks is the view/item.
        val paramIndex = function.params.indexOfFirst { it.name == declaration.name }
        if (paramIndex != 0) {
            return null
        }
        val parent = runCatching { function.parent }.getOrNull() as? TableKey ?: return null
        val fieldName = when (val key = parent.key) {
            is Identifier -> key.name
            is ConstantNode -> stringLiteralOf(key)
            else -> null
        } ?: return null
        return when (fieldName) {
            "onClick", "onLongClick", "onItemClick", "onCheckedChanged" ->
                androidLuaHydratedSurface("AndroidView")
            else -> null
        }
    }

    private fun callableDeclaration(node: ExpressionNode, context: Context): BinderDeclaration? {
        val identifier = node as? Identifier ?: return null
        if (context.localOverrides.containsKey(identifier.name)) {
            return null
        }
        return findVisibleValueDeclaration(identifier.name, identifier.range.start, context)
    }

    private fun isVisibleAt(declaration: BinderDeclaration, position: Position): Boolean {
        // Builtin ranges belong to declaration resources, not the user's source file.
        // They are ambient bindings and must not be gated by unrelated file positions.
        if (declaration.origin == DeclarationOrigin.BUILTIN) {
            return true
        }
        if (binder.isChunkGlobalFunctionDeclaration(declaration)) {
            return true
        }
        val range = declaration.range ?: return true
        return comparePositions(range.start, position) <= 0
    }

    private fun resolveAssignedValueType(
        expressions: List<ExpressionNode>,
        targetIndex: Int,
        context: Context
    ): Type {
        if (expressions.isEmpty()) {
            return PrimitiveType.NIL
        }

        val lastExpressionIndex = expressions.lastIndex
        return if (targetIndex < lastExpressionIndex) {
            ValueSequence.of(evaluate(expressions[targetIndex], context)).collapseToSingle().typeAt(0)
        } else {
            ValueSequence.of(evaluate(expressions[lastExpressionIndex], context)).typeAt(targetIndex - lastExpressionIndex)
        }
    }

    private fun attachVisibleAstMembersToType(
        declaration: BinderDeclaration,
        baseType: Type,
        context: Context
    ): Type {
        if (declaration.kind.namespace != DeclarationNamespace.VALUE) {
            return baseType
        }
        val memberDeclarations = visibleMemberDeclarationsForValue(declaration, context)
        if (memberDeclarations.isEmpty()) {
            return attachNestedMemberAssignments(baseType, declaration, context)
        }
        val fields = linkedMapOf<String, Type>()
        val methods = linkedMapOf<String, Type>()
        memberDeclarations.forEach { memberDeclaration ->
            val memberType = typeOfDeclaration(memberDeclaration, context)
            if (memberType != UnknownType) {
                if (memberDeclaration.kind == DeclarationKind.METHOD) {
                    methods[memberDeclaration.name] = memberType
                } else {
                    fields[memberDeclaration.name] = memberType
                }
            }
        }
        val merged = when (baseType) {
            is TableType -> baseType.copy(
                fields = baseType.fields + fields,
                methods = baseType.methods + methods
            )
            is ModuleType -> baseType.copy(
                fields = baseType.fields + fields,
                methods = baseType.methods + methods
            )
            is ClassType -> baseType.copy(
                fields = baseType.fields + fields,
                methods = baseType.methods + methods
            )
            else -> baseType
        }
        // Control-flow-assigned sub-table fields (`data.scrollData.last = b` inside a
        // later callback) must complete on the sub-table itself (`data.scrollData.`
        // offering scroll/last/page), mirroring direct-member attachment.
        return attachNestedMemberAssignments(merged, declaration, context)
    }

    /**
     * Merge nested member-assignment paths rooted at [declaration]'s name
     * (`data.scrollData.scroll = i > 0`) into the base type's sub-table fields, so a
     * field only ever written through control-flow callbacks still completes and types.
     */
    private fun attachNestedMemberAssignments(
        baseType: Type,
        declaration: BinderDeclaration,
        context: Context
    ): Type {
        val nested = nestedMemberAssignmentsFor(declaration, context)
        if (nested.isEmpty()) {
            return baseType
        }
        var merged = baseType
        nested.forEach { (path, members) ->
            merged = attachMemberPath(merged, path, members)
        }
        return merged
    }

    /**
     * Collect FIELD/METHOD declarations whose anchor LHS is a nested member path rooted
     * at [declaration]'s name (`data.scrollData.last`): root resolves to this declaration
     * and the intermediate segments form the sub-table path. Grouped by path.
     */
    private fun nestedMemberAssignmentsFor(
        declaration: BinderDeclaration,
        context: Context
    ): Map<List<String>, List<Pair<String, Type>>> {
        // No lexical-owner filter here: correctness is guaranteed by the root-identifier
        // resolution below (the assignment's root must resolve to THIS declaration), and
        // field declarations may be owned by scope nodes outside the query position's
        // owner chain (callbacks assigned to member functions, etc.).
        val grouped = linkedMapOf<List<String>, MutableList<Pair<String, Type>>>()
        memberAssignmentsByRootName()[declaration.name].orEmpty().forEach { anchor ->
            val candidate = anchor.declaration
            val anchorMember = anchor.anchorMember
            // Segments between the root identifier and the assigned member form the
            // sub-table path (data.scrollData.last → path [scrollData]).
            val segments = ArrayDeque<String>()
            var cursor: ExpressionNode = anchorMember
            while (cursor is MemberExpression) {
                segments.addFirst(cursor.identifier.name)
                cursor = cursor.base
            }
            if (segments.isEmpty()) {
                return@forEach
            }
            // The bucket matched the root NAME; the root must also RESOLVE to this
            // declaration (same-named locals in other scopes must not merge in).
            val root = cursor as? Identifier ?: return@forEach
            val scopeId = binder.positionQueries.getScopeAt(root.range.start)?.id ?: context.lexicalScopeId
            val resolvedRoot = findVisibleValueDeclaration(
                root.name,
                root.range.start,
                context.copy(lexicalScopeId = scopeId)
            ) ?: return@forEach
            val sameDeclaration = if (
                declaration.symbolId != null && resolvedRoot.symbolId != null
            ) {
                declaration.symbolId == resolvedRoot.symbolId
            } else {
                declaration.id == resolvedRoot.id
            }
            if (!sameDeclaration) {
                return@forEach
            }
            val path = segments.dropLast(1)
            if (path.isEmpty()) {
                return@forEach
            }
            // Unknown-typed nested members STILL belong on the surface: the user asks
            // for `data.scrollData.` to offer scroll/last/page even when the assigned
            // RHS is a bare parameter (type unknown). Completion presence is the point.
            val memberType = typeOfDeclaration(candidate, context)
            grouped.getOrPut(path) { mutableListOf() }.add(candidate.name to memberType)
        }
        return grouped
    }

    private fun attachMemberPath(base: Type, path: List<String>, members: List<Pair<String, Type>>): Type {
        val parents = ArrayList<Pair<Type, String>>(path.size)
        var current = base
        for (segment in path) {
            val child = when (current) {
                is TableType -> current.fields[segment]
                is ModuleType -> current.fields[segment]
                else -> return base
            } ?: TableType()
            parents += current to segment
            current = child
        }
        var enriched = when (current) {
            is TableType -> current.copy(fields = current.fields + members.toMap())
            is ModuleType -> current.copy(fields = current.fields + members.toMap())
            else -> TableType(fields = members.toMap())
        }
        for ((parent, segment) in parents.asReversed()) {
            enriched = when (parent) {
                is TableType -> parent.copy(fields = parent.fields + (segment to enriched))
                is ModuleType -> parent.copy(fields = parent.fields + (segment to enriched))
                else -> error("Member path parent must be a table or module")
            }
        }
        return enriched
    }

    /**
     * One member-assignment declaration grouped by the ROOT identifier name of its LHS
     * chain (`data.scrollData.last = b` -> root "data"), plus the full LHS member
     * expression for path/segment inspection.
     */
    private data class MemberAssignmentAnchor(
        val declaration: BinderDeclaration,
        val anchorMember: MemberExpression
    )

    /** Root-name index over member-assignment declarations, built once per evaluator. */
    private fun memberAssignmentsByRootName(): Map<String, List<MemberAssignmentAnchor>> {
        memberAssignmentsByRoot?.let { return it }
        val index = linkedMapOf<String, MutableList<MemberAssignmentAnchor>>()
        binder.declarationIndex.declarations.forEach { candidate ->
            if (candidate.kind != DeclarationKind.FIELD && candidate.kind != DeclarationKind.METHOD) {
                return@forEach
            }
            val anchorMember = candidate.anchorNode?.parent as? MemberExpression ?: return@forEach
            var cursor: ExpressionNode = anchorMember
            while (cursor is MemberExpression) {
                cursor = cursor.base
            }
            val root = cursor as? Identifier ?: return@forEach
            index.getOrPut(root.name) { mutableListOf() }.add(
                MemberAssignmentAnchor(candidate, anchorMember)
            )
        }
        memberAssignmentsByRoot = index
        return index
    }

    private fun visibleMemberDeclarationsForValue(
        declaration: BinderDeclaration,
        context: Context
    ): List<BinderDeclaration> {
        val valueName = declaration.name
        val lexicalOwner = binder.scopeGraph.getScope(context.lexicalScopeId)?.ownerNode
        val candidates = memberAssignmentsByRootName()[valueName].orEmpty()
            .map { it.declaration }
        val scopedMatches = lexicalOwner?.let { owner ->
            candidates.filter { candidate ->
                isDeclaredInLexicalOwnerChain(candidate, owner) &&
                    isMemberBoundToValueDeclaration(candidate, declaration, valueName, context)
            }
        }.orEmpty()
        if (scopedMatches.isNotEmpty()) {
            return scopedMatches
        }
        return candidates.filter { candidate ->
            isMemberBoundToValueDeclaration(candidate, declaration, valueName, context)
        }
    }

    private fun isMemberBoundToValueDeclaration(
        memberDeclaration: BinderDeclaration,
        valueDeclaration: BinderDeclaration,
        baseName: String,
        context: Context
    ): Boolean {
        val anchorMember = memberDeclaration.anchorNode?.parent as? MemberExpression
        val anchorBase = anchorMember?.base as? Identifier
        if (anchorBase?.name != baseName) {
            return false
        }
        val scopeId = binder.positionQueries.getScopeAt(anchorBase.range.start)?.id ?: context.lexicalScopeId
        val resolvedBase = findVisibleValueDeclaration(
            anchorBase.name,
            anchorBase.range.start,
            context.copy(lexicalScopeId = scopeId)
        ) ?: return false
        return if (valueDeclaration.symbolId != null && resolvedBase.symbolId != null) {
            valueDeclaration.symbolId == resolvedBase.symbolId
        } else {
            valueDeclaration.id == resolvedBase.id
        }
    }

    private fun mergeDeclaredAndInferredCallableType(
        declaration: BinderDeclaration,
        declared: CallableType,
        inferred: CallableType,
        preferDeclaredReturn: Boolean = false
    ): CallableType {
        val declaredSignature = declared.callSignatures.firstOrNull() ?: return inferred
        val inferredSignature = inferred.callSignatures.firstOrNull() ?: return declared
        val declaredParameterOffset = if (
            declaration.kind == DeclarationKind.METHOD &&
                declaredSignature.parameters.firstOrNull()?.name == "self" &&
                inferredSignature.parameters.firstOrNull()?.name != "self"
        ) {
            1
        } else {
            0
        }
        // METHOD callers shift by 1 when the declared signature leads with an implicit `self`
        // that body inference omitted; otherwise offset is 0 and this is a plain positional merge.
        val mergedParameters = inferredSignature.parameters.mapIndexed { index, parameter ->
            val declaredParameter = declaredSignature.parameters.getOrNull(index + declaredParameterOffset)
            val parameterType = when {
                declaredParameter == null -> parameter.type
                parameter.type == UnknownType -> declaredParameter.type
                declaredParameter.type == UnknownType -> parameter.type
                else -> declaredParameter.type
            }
            parameter.copy(
                type = parameterType,
                optional = declaredParameter?.optional ?: parameter.optional,
                vararg = declaredParameter?.vararg ?: parameter.vararg
            )
        }
        val returnType = when {
            preferDeclaredReturn && declaredSignature.returnType != UnknownType -> declaredSignature.returnType
            inferredSignature.returnType != UnknownType -> inferredSignature.returnType
            else -> declaredSignature.returnType
        }
        // TASK-670: body inference builds FunctionType without typeParameters; keep declared
        // @generic labels (fun<T>) when merging declared+inferred callables for value types.
        val typeParameters = declaredSignature.typeParameters.ifEmpty {
            inferredSignature.typeParameters
        }
        val rebuiltName = FunctionType(
            parameters = mergedParameters,
            returnType = returnType,
            typeParameters = typeParameters
        ).name
        val mergedSignature = FunctionType(
            parameters = mergedParameters,
            returnType = returnType,
            typeParameters = typeParameters,
            name = rebuiltName
        )
        val signatures = listOf(mergedSignature) + declared.callSignatures.drop(1)
        val merged = if (signatures.size == 1) mergedSignature else OverloadedFunctionType(signatures)
        return if (declaration.kind == DeclarationKind.METHOD) {
            enrichMethodCallableType(binder, declaration, merged)
        } else {
            merged
        }
    }

    private fun staticTableKeyName(field: TableKey): String? {
        return when (val key = field.key) {
            is Identifier -> key.name
            is ConstantNode -> when (key.constantType) {
                ConstantNode.TYPE.STRING -> key.stringOf()
                ConstantNode.TYPE.INTERGER -> key.rawValue.toString().toIntOrNull()?.toString() ?: key.rawValue.toString()
                else -> null
            }

            else -> null
        }
    }

    private fun callArgumentType(node: ExpressionNode, context: Context): Type {
        val type = evaluate(node, context)
        return if (type is ClassType || type is CustomType) {
            type.hydrateJavaProviderType(workspaceContext.resolveImportTarget)
        } else type
    }

    private fun buildCallArgumentSequences(node: CallExpression, context: Context): List<ValueSequence> {
        val argumentSequences = mutableListOf<ValueSequence>()
        (effectiveCallBase(node) as? MemberExpression)?.takeIf { it.indexer == ":" }?.let { member ->
            argumentSequences += ValueSequence.of(callArgumentType(member.base, context)).collapseToSingle()
        }

        val arguments = callArguments(node)
        arguments.forEachIndexed { index, argument ->
            val sequence = ValueSequence.of(callArgumentType(argument, context))
            argumentSequences += if (index == arguments.lastIndex) sequence else sequence.collapseToSingle()
        }
        return argumentSequences
    }

    private fun inferFunctionReturnType(body: BlockNode, context: Context): Type {
        val returnSites = collectReturnSites(body, context)
        if (returnSites.isEmpty()) {
            return PrimitiveType.VOID
        }

        val returnSequences = returnSites.map(ReturnSite::values).let { sites ->
            if (mayFallThrough(body)) sites + ValueSequence() else sites
        }
        val openTailTypes = returnSequences.mapNotNull(ValueSequence::variadicTail)
        val maxFixedArity = returnSequences.maxOf { it.fixed.size }
        val totalArity = if (openTailTypes.isEmpty()) maxFixedArity else maxOf(maxFixedArity, 1)

        if (totalArity == 0) return PrimitiveType.VOID
        if (totalArity <= 1 && openTailTypes.isEmpty()) {
            return unionTypeOf(returnSequences.map { it.typeAt(0) })
        }

        val slots = (0 until maxFixedArity).map { index -> unionTypeOf(returnSequences.map { it.typeAt(index) }) }.toMutableList()
        if (openTailTypes.isNotEmpty()) {
            slots += VarargType(unionTypeOf(openTailTypes))
        }

        return if (slots.size == 1 && slots.single() is VarargType) {
            slots.single()
        } else {
            MultiReturnType(types = slots)
        }
    }

    internal fun buildFunctionBodyContext(
        function: FunctionDeclaration,
        parameters: List<FunctionParameter>,
        fallbackScopeId: ScopeId,
        fallbackVarargType: Type = VarargType(UnknownType)
    ): Context {
        val functionScopeId = function.body?.let(binder.scopeGraph::getScope)?.id ?: fallbackScopeId
        return Context(
            lexicalScopeId = functionScopeId,
            localOverrides = parameters.associate { it.name to it.type },
            varargType = parameters.lastOrNull { it.vararg }?.type ?: fallbackVarargType
        )
    }

    internal fun collectReturnSites(body: BlockNode, context: Context): List<ReturnSite> {
        val output = mutableListOf<ReturnSite>()
        collectReturnTypes(body, context, output)
        return output
    }

    private fun collectReturnTypes(block: BlockNode, context: Context, output: MutableList<ReturnSite>) {
        block.returnStatement?.let { statement ->
            output += ReturnSite(statement = statement, values = collectReturnTuple(statement.arguments, context))
        }

        block.statements.forEach { statement ->
            when (statement) {
                is IfStatement -> statement.causes.forEach { collectReturnTypes(it.body, context, output) }
                is WhileStatement -> collectReturnTypes(statement.body, context, output)
                is DoStatement -> collectReturnTypes(statement.body, context, output)
                is RepeatStatement -> collectReturnTypes(statement.body, context, output)
                is ForGenericStatement -> collectReturnTypes(statement.body, context, output)
                is ForNumericStatement -> collectReturnTypes(statement.body, context, output)
                is SwitchStatement -> statement.causes.forEach { cause ->
                    when (cause) {
                        is CaseCause -> collectReturnTypes(cause.body, context, output)
                        is DefaultCause -> collectReturnTypes(cause.body, context, output)
                    }
                }

                is WhenStatement -> {
                    collectStatementReturns(statement.ifCause, context, output)
                    statement.elseCause?.let { collectStatementReturns(it, context, output) }
                }

                is FunctionDeclaration -> Unit
                else -> Unit
            }
        }
    }

    private fun collectStatementReturns(statement: StatementNode, context: Context, output: MutableList<ReturnSite>) {
        // IfClause covers ElseIfClause/ElseClause too (they are subtypes) and every
        // subtype arm recursed into the same body; FunctionDeclaration fell to `else`.
        when (statement) {
            is IfClause -> collectReturnTypes(statement.body, context, output)
            else -> Unit
        }
    }

    private fun collectReturnTuple(arguments: List<ExpressionNode>, context: Context): ValueSequence {
        return ValueSequence.fromExpressionResults(arguments.map { evaluate(it, context) })
    }

    private fun primitiveArrayElementTypeFor(name: String): Type? = when (name) {
        "boolean", "java.lang.Boolean" -> PrimitiveType.BOOLEAN
        "byte", "short", "int", "integer", "long", "float", "double",
        "number", "java.lang.Byte", "java.lang.Short", "java.lang.Integer",
        "java.lang.Long", "java.lang.Float", "java.lang.Double", "java.math.BigDecimal",
        "java.math.BigInteger" -> PrimitiveType.NUMBER
        "char", "string", "java.lang.Character", "java.lang.String", "java.lang.CharSequence" -> PrimitiveType.STRING
        else -> null
    }

    private companion object {
        // Hard bounds for TASK-379 loadlayout AST walks (prevent parent+block OOM).
        private val LOADLAYOUT_FAMILY_NAMES = setOf("loadlayout", "loadlayout2", "loadlayout3")


        private const val LAYOUT_ID_DETAIL = "view id, also registered into the loadlayout root/ids table"
        private const val LAYOUT_SRC_DETAIL = "image path or resource for image-bearing views"
        private const val LAYOUT_ITEMS_DETAIL = "adapter items: table, function, or resource name"
        private const val LAYOUT_STYLE_DETAIL = "style resource or ?attr reference"
        private const val LAYOUT_ON_CLICK_DETAIL = "function(view) click callback"
        private const val LAYOUT_PARAM_DETAIL = "ViewGroup.LayoutParams key"

        private val LAYOUT_PARAM_SUGGESTIONS = listOf(
            "layout_width" to "-1(fill/match) / -2(wrap) / \"24dp\"",
            "layout_height" to "-1(fill/match) / -2(wrap) / \"24dp\"",
            "layout_margin" to "all-side margin, number or \"8dp\"",
            "layout_marginLeft" to LAYOUT_PARAM_DETAIL,
            "layout_marginTop" to LAYOUT_PARAM_DETAIL,
            "layout_marginRight" to LAYOUT_PARAM_DETAIL,
            "layout_marginBottom" to LAYOUT_PARAM_DETAIL,
            "layout_marginStart" to LAYOUT_PARAM_DETAIL,
            "layout_marginEnd" to LAYOUT_PARAM_DETAIL,
            "layout_weight" to "LinearLayout weight",
            "layout_gravity" to "gravity in parent (left|top|center...)",
            "layout_x" to "absolute x offset",
            "layout_y" to "absolute y offset"
        )

        private val KNOWN_ANDROID_VIEW_SIMPLE_NAMES = setOf(
            "View",
            "Button",
            "TextView",
            "EditText",
            "ImageView",
            "ImageButton",
            "ListView",
            "GridView",
            "ScrollView",
            "HorizontalScrollView",
            "LinearLayout",
            "RelativeLayout",
            "FrameLayout",
            "TableLayout",
            "TableRow",
            "CardView",
            "RecyclerView",
            "WebView",
            "CheckBox",
            "RadioButton",
            "RadioGroup",
            "Switch",
            "ProgressBar",
            "SeekBar",
            "Spinner",
            "TabLayout",
            "Toolbar",
            "ViewPager"
        )
    }

}

/**
 * One Lua property key suggested inside an AndroLua layout table (loadlayout context).
 * [detail] is a short human-readable value hint shown next to the completion label.
 */
data class LuaLayoutPropertySuggestion(
    val label: String,
    val detail: String?
)

/**
 * Where a `loadlayout(...)` call's layout spec table comes from, kept symbolic at
 * collection time so the one-shot usage walk never resolves types or workspace files.
 */
private sealed class LoadlayoutLayoutSource {
    /** Inline layout table literal as the first argument. */
    class Inline(val table: TableConstructorExpression) : LoadlayoutLayoutSource()

    /** First argument names a local table variable (`loadlayout(parentLayout, ids)`). */
    class LocalTable(val name: String, val from: BaseASTNode) : LoadlayoutLayoutSource()

    /** First argument is a layout path string (`loadlayout("layout/main")`). */
    class WorkspaceLayout(val path: String) : LoadlayoutLayoutSource()

    /** First argument is a call whose body returns the spec table (`addTab(...)`). */
    class CalleeReturn(val callee: String, val from: BaseASTNode) : LoadlayoutLayoutSource()
}

/**
 * Where the AndroLua runtime registers a loadlayout call's `id="..."` views: a named ids
 * table, the elements of a base table (`_pageids[i]`), or `_G` when no ids table passed.
 */
private sealed class LoadlayoutIdsSink {
    data class Named(val name: String) : LoadlayoutIdsSink()
    data class Elements(val baseName: String) : LoadlayoutIdsSink()
    object Globals : LoadlayoutIdsSink()
}

private class LoadlayoutUsage(val sink: LoadlayoutIdsSink, val source: LoadlayoutLayoutSource)

/**
 * The view class in force at a layout-table position: [sourceName] is the identifier as
 * written in the layout table (extension lookup key), [type] is the resolved class surface.
 */
data class LuaLayoutClassMatch(
    val sourceName: String,
    val type: Type
)

/** Configuration changes (androidJar, import prefixes, metadata) invalidate shared caches. */
