package io.github.dingyi222666.luaparser.semantic.checker

import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode
import io.github.dingyi222666.luaparser.parser.ast.node.BinaryExpression
import io.github.dingyi222666.luaparser.parser.ast.node.BlockNode
import io.github.dingyi222666.luaparser.parser.ast.node.CallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.CaseCause
import io.github.dingyi222666.luaparser.parser.ast.node.ConstantNode
import io.github.dingyi222666.luaparser.parser.ast.node.DefaultCause
import io.github.dingyi222666.luaparser.parser.ast.node.DoStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ElseClause
import io.github.dingyi222666.luaparser.parser.ast.node.ElseIfClause
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
import io.github.dingyi222666.luaparser.parser.ast.node.UnaryExpression
import io.github.dingyi222666.luaparser.parser.ast.node.VarargLiteral
import io.github.dingyi222666.luaparser.parser.ast.node.WhenStatement
import io.github.dingyi222666.luaparser.parser.ast.node.WhileStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ArrayConstructorExpression
import io.github.dingyi222666.luaparser.semantic.SemanticWorkspaceContext
import io.github.dingyi222666.luaparser.semantic.binder.BinderDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationId
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationNamespace
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOwner
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin
import io.github.dingyi222666.luaparser.semantic.binder.Scope
import io.github.dingyi222666.luaparser.semantic.binder.ScopeId
import io.github.dingyi222666.luaparser.semantic.binder.comparePositions
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
import io.github.dingyi222666.luaparser.semantic.types.model.JavaTypeName
import io.github.dingyi222666.luaparser.semantic.types.model.IntersectionType
import io.github.dingyi222666.luaparser.semantic.types.model.LiteralType
import io.github.dingyi222666.luaparser.semantic.types.model.NeverType
import io.github.dingyi222666.luaparser.semantic.types.model.ModuleType
import io.github.dingyi222666.luaparser.semantic.types.model.MultiReturnType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.model.TableType
import io.github.dingyi222666.luaparser.semantic.types.model.TupleType
import io.github.dingyi222666.luaparser.semantic.types.model.Type
import io.github.dingyi222666.luaparser.semantic.types.model.TypeParameterType
import io.github.dingyi222666.luaparser.semantic.types.model.UnionType
import io.github.dingyi222666.luaparser.semantic.types.model.UnknownType
import io.github.dingyi222666.luaparser.semantic.types.model.VarargType
import io.github.dingyi222666.luaparser.semantic.types.resolve.DocFunctionTypeSyntaxParser
import io.github.dingyi222666.luaparser.semantic.types.resolve.TypeResolutionContext
import io.github.dingyi222666.luaparser.semantic.types.resolve.intersectionTypeOf
import io.github.dingyi222666.luaparser.semantic.types.resolve.unionTypeOf
import io.github.dingyi222666.luaparser.semantic.types.resolve.isAssignableFrom
import io.github.dingyi222666.luaparser.semantic.checker.resolveOwningFunctionDeclaration
import io.github.dingyi222666.luaparser.semantic.types.syntax.ArrayTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.FunctionTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.GenericTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.IdentifierObjectFieldNameSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.IndexTableTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.IntersectionTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.LiteralTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.MultiReturnTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.NamedTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.NullableTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.ObjectTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.QuotedObjectFieldNameSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.TupleTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.TypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.UnionTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.VarargTypeSyntax

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
    private val declarationValueTypeCache = mutableMapOf<DeclarationId, Type>()
    private val activeDeclarationIds = mutableSetOf<DeclarationId>()
    private val memberResolver = MemberResolver(binder)
    private val callChecker = CallChecker(binder)
    private val luaJavaArrayHelperNames = setOf("newArray", "createArray")
    // getContext is an AndroLua helper surface (TASK-575); include it so colon-call guards and
    // helper-owner checks stay consistent with bind/newInstance/createProxy/loadLib.
    private val luaJavaHelperNames =
        setOf("bindClass", "newInstance", "createProxy", "loadLib", "getContext") + luaJavaArrayHelperNames
    // TASK-379: hydrate Android-Lua load* aliases once per evaluator; View/Menu/Bitmap
    // surfaces from android.jar are large and must not be rebuilt on every call/local.
    private val androidLuaHydratedSurfaceCache = mutableMapOf<String, Type>()
    private val layoutViewClassTypeCache = mutableMapOf<String, Type>()
    private val loadlayoutIdsTableTypeCache = mutableMapOf<DeclarationId, Type>()
    // One-shot loadlayout(…, ids) → layout-table index for the whole binder root.
    private var loadlayoutRootUsageIndex: Map<String, List<TableConstructorExpression>>? = null

    fun evaluate(node: ExpressionNode): Type {
        expressionTypeCache[node]?.let { return it }
        val scopeId = binder.positionQueries.getScopeAt(node.range.start)?.id ?: binder.scopeGraph.rootScope.id
        val type = evaluate(node, Context(lexicalScopeId = scopeId))
        expressionTypeCache[node] = type
        return type
    }

    fun evaluate(node: ExpressionNode, context: Context): Type {
        return when (node) {
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
            is FunctionDeclaration -> evaluateFunctionDeclaration(node, context)
            is LambdaDeclaration -> evaluateLambdaDeclaration(node, context)
            else -> UnknownType
        }
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
        if (declaration != null) {
            luaJavaLocalInitializerType(declaration, context)?.let { return it }
            return typeOfDeclaration(declaration, context)
        }
        workspaceContext.importedSymbols[node.name]?.let { return it.moduleType }
        workspaceContext.resolveImportedSymbol?.invoke(node.name)?.let { return it.moduleType }
        return UnknownType
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
        // Layout-spec tables ({ LinearLayout, id=..., onClick=function(v)... }) must not
        // deep-evaluate nested function bodies / child tables — that re-expands android
        // surfaces and OOMs under android.jar. Return a cheap LuaLayoutSpec shell instead.
        if (looksLikeAndroidLayoutSpecTable(node)) {
            return CustomType("LuaLayoutSpec")
        }
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

    /**
     * Heuristic for AndroLua layout tables: first array field is a View class identifier
     * (LinearLayout/TextView/…) and/or named layout keys (id/onClick/layout_*) are present.
     * Conservative — only short-circuits when the shape is clearly a layout spec.
     */
    private fun looksLikeAndroidLayoutSpecTable(node: TableConstructorExpression): Boolean {
        if (node.fields.isEmpty()) {
            return false
        }
        var hasViewClass = false
        var hasLayoutKey = false
        node.fields.forEach { field ->
            val keyName = staticTableKeyName(field)
            if (isLayoutArrayField(field, keyName)) {
                val value = field.value
                if (value is Identifier && isLikelyAndroidViewClassName(value.name)) {
                    hasViewClass = true
                }
            } else if (keyName in LAYOUT_SPEC_KEYS) {
                hasLayoutKey = true
            }
        }
        return hasViewClass || (hasLayoutKey && node.fields.any { isLayoutArrayField(it, staticTableKeyName(it)) })
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
        val baseType = evaluateReferenceBaseType(node.base, context)
            .hydrateJavaProviderType(workspaceContext.resolveImportTarget)
        val indexType = evaluate(node.index, context)
        return memberResolver.resolveIndex(baseType, node.index, indexType, context.lexicalScopeId).type
            ?.hydrateJavaProviderType(workspaceContext.resolveImportTarget)
            ?: UnknownType
    }

    private fun evaluateCallExpression(node: CallExpression, context: Context): Type {
        resolveBuiltinRequire(node, context)?.let { return it }
        resolveDynamicImportCall(node, context)?.let { return it }
        resolveLuaJavaHelperColonCall(node, context)?.let { return it }
        resolveBindClassCall(node, context)?.let { return it }
        resolveNewInstanceCall(node, context)?.let { return it }
        resolveCreateProxyCall(node, context)?.let { return it }
        resolveLoadLibCall(node, context)?.let { return it }
        resolveLuaJavaArrayCall(node, context)?.let { return it }
        resolveJvmConstructorCall(node, context)?.let { return it }
        resolveLoadlayoutFamilyCall(node, context)?.let { return it }
        // TASK-575: hard-lock luajava.getContext() return to AndroidLuaContext when the
        // Android-Lua / AndroLua overlay is active (unshadowed helper owner).
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
            ?.let { return it }

        // TASK-589: chained static→instance Java calls must keep reflected intermediate
        // return types for completion/hover. Strict CallChecker assignability can reject
        // valid Lua primitive/table shapes against Java Object/Number/CharSequence shells
        // (e.g. Arrays.asList("a","b") → List, Locale.forLanguageTag(...).toLanguageTag()).
        // Fall back to arity / soft-shape ranking over reflection signatures only — never
        // invent a return type without a reflected signature surface.
        if (isJavaChainCallSite(node, context, callableType)) {
            javaChainedCallReturnType(
                callableType = callableType,
                argumentSequences = argumentSequences,
                lexicalScopeId = context.lexicalScopeId,
                declaration = declaration,
                priorFailure = resolution.failureReason
            )?.hydrateJavaProviderType(workspaceContext.resolveImportTarget)
                ?.let { return it }
        }

        return UnknownType
    }

    /**
     * True when this call site is a Java static/instance member, constructor, or module
     * surface chain. Ordinary Lua callables never enter the soft arity recovery path.
     */
    private fun isJavaChainCallSite(
        node: CallExpression,
        context: Context,
        callableType: Type
    ): Boolean {
        if (isJavaReflectedCallableSurface(callableType, emptyList())) {
            return true
        }
        val base = effectiveCallBase(node)
        if (base is MemberExpression) {
            val ownerType = evaluateReferenceBaseType(base.base, context)
                .hydrateJavaProviderType(workspaceContext.resolveImportTarget)
            if (isJavaReflectedCallableSurface(ownerType, emptyList()) ||
                (ownerType is ModuleType && ownerType.isJavaBackedModule())
            ) {
                return true
            }
        }
        if (callableType is ModuleType && callableType.isJavaBackedModule()) {
            return true
        }
        // Callable surfaces produced by withJavaCallableSurface are plain FunctionType /
        // OverloadedFunctionType / JavaOverloadType; treat them as Java when any signature
        // already exposes a Java-shaped parameter or return (reflection evidence).
        val signatures = callChecker.resolveCallable(callableType, context.lexicalScopeId).signatures
        return isJavaReflectedCallableSurface(callableType, signatures)
    }

    /**
     * Reflection-backed return typing for Java member/static chains when exact CallChecker
     * ranking fails closed. Only considers signatures already present on the callable surface
     * (no invented overloads). Prefer soft-assignable arity matches; pure arity is a last
     * resort for primitive/Object shells only and must not invent precise returns for rejected
     * Lua table → Java array/List/Map conversions (TASK-658) or non-Listener/non-Callback
     * interface setter callbacks (TASK-659).
     */
    private fun javaChainedCallReturnType(
        callableType: Type,
        argumentSequences: List<ValueSequence>,
        lexicalScopeId: ScopeId,
        declaration: BinderDeclaration?,
        priorFailure: CallFailureReason?
    ): Type? {
        // Only recover from ranking / non-callable shells that still expose Java signatures.
        if (priorFailure != null &&
            priorFailure != CallFailureReason.NO_MATCHING_SIGNATURE &&
            priorFailure != CallFailureReason.NON_CALLABLE
        ) {
            return null
        }
        val callableResolution = callChecker.resolveCallable(callableType, lexicalScopeId, declaration)
        val signatures = callableResolution.signatures
        if (signatures.isEmpty()) {
            return null
        }
        val argumentTypes = mutableListOf<Type>()
        argumentSequences.forEach { it.appendToCallArguments(argumentTypes) }
        val arityCompatible = signatures.filter { signature ->
            javaCallArityCompatible(signature, argumentTypes.size)
        }
        if (arityCompatible.isEmpty()) {
            return null
        }
        val softCompatible = arityCompatible.filter { signature ->
            javaCallArgumentsSoftCompatible(signature, argumentTypes)
        }
        val candidates = when {
            softCompatible.isNotEmpty() -> softCompatible
            // Table-shaped arguments must pass TypeRelations / isJavaContainerAssignableFrom.
            // Pure arity recovery would invent the method return (e.g. number) for mixed/named
            // tables → String[] and raw List<*> conversions that conservative assignability
            // already rejected.
            argumentTypes.any(::isLuaTableShapedArgument) -> return null
            // Lua function / callable callbacks only recover when soft assignability accepts
            // them (Listener/Callback interface policy via isJavaListenerAssignableFrom).
            // Pure arity would invent void/nil for setters like setAction(Action) where Action
            // is a plain interface (TASK-659).
            argumentTypes.any(::isLuaCallableShapedArgument) -> return null
            else -> arityCompatible
        }
        // Prefer the first reflection-order signature whose return is known; when all agree,
        // that single type is the chain intermediate used by completion/hover.
        val knownReturns = candidates.map { it.returnType }.filter { it != UnknownType }
        if (knownReturns.isEmpty()) {
            return candidates.first().returnType
        }
        val distinct = knownReturns.distinctBy { it.displayName }
        return if (distinct.size == 1) {
            distinct.single()
        } else {
            // Do not invent a union of unrelated overload returns without evidence which
            // overload applied; keep deterministic first known return from soft/arity rank.
            knownReturns.first()
        }
    }

    /** True for Lua table / module / array literal shapes used as Java call arguments. */
    private fun isLuaTableShapedArgument(type: Type): Boolean {
        return when (type) {
            is TableType, is ModuleType, is ArrayType -> true
            is UnionType -> type.types.any(::isLuaTableShapedArgument)
            is IntersectionType -> type.types.any(::isLuaTableShapedArgument)
            else -> false
        }
    }

    /**
     * True for Lua function / callable argument shapes. Used to refuse pure-arity Java chain
     * recovery when soft listener/callback assignability did not accept the argument.
     */
    private fun isLuaCallableShapedArgument(type: Type): Boolean {
        return when (type) {
            is CallableType -> true
            is UnionType -> type.types.any(::isLuaCallableShapedArgument)
            is IntersectionType -> type.types.any(::isLuaCallableShapedArgument)
            else -> false
        }
    }

    private fun isJavaReflectedCallableSurface(callableType: Type, signatures: List<FunctionType>): Boolean {
        if (callableType is JavaClassType ||
            callableType is JavaInstanceType ||
            callableType is JavaArrayType ||
            callableType is JavaOverloadType ||
            callableType is JavaConstructorType
        ) {
            return true
        }
        if (callableType is ModuleType && callableType.isJavaBackedModule()) {
            return true
        }
        return signatures.any { signature ->
            signature.parameters.any { isJavaShapedType(it.type) } || isJavaShapedType(signature.returnType)
        }
    }

    private fun isJavaShapedType(type: Type): Boolean {
        return when (type) {
            is JavaClassType,
            is JavaInstanceType,
            is JavaArrayType,
            is JavaConstructorType,
            is JavaOverloadType -> true
            is ArrayType -> isJavaShapedType(type.elementType)
            is VarargType -> isJavaShapedType(type.elementType)
            is UnionType -> type.types.any(::isJavaShapedType)
            is IntersectionType -> type.types.any(::isJavaShapedType)
            else -> {
                val name = type.displayName
                name.contains('.') || name.contains('$')
            }
        }
    }

    private fun javaCallArityCompatible(signature: FunctionType, argumentCount: Int): Boolean {
        val required = signature.parameters.count { !it.optional && !it.vararg }
        val hasVararg = signature.parameters.any { it.vararg }
        if (argumentCount < required) {
            return false
        }
        if (!hasVararg && argumentCount > signature.parameters.size) {
            return false
        }
        return true
    }

    private fun javaCallArgumentsSoftCompatible(
        signature: FunctionType,
        argumentTypes: List<Type>
    ): Boolean {
        if (!javaCallArityCompatible(signature, argumentTypes.size)) {
            return false
        }
        argumentTypes.forEachIndexed { argumentIndex, argumentType ->
            val parameter = signature.parameters.getOrNull(argumentIndex)
                ?: signature.parameters.lastOrNull { it.vararg }
                ?: return false
            if (!isSoftJavaCallArgument(parameter.type, argumentType)) {
                return false
            }
        }
        return true
    }

    /**
     * Soft Lua→Java argument bridge for chain return recovery only.
     * Mirrors common interop coercions (string/number/boolean/table containers/Object)
     * without inventing parameter types beyond the reflected signature surface.
     */
    private fun isSoftJavaCallArgument(parameterType: Type, argumentType: Type): Boolean {
        if (parameterType.isAssignableFrom(argumentType) ||
            parameterType.isJavaListenerAssignableFrom(argumentType) ||
            parameterType.isJavaContainerAssignableFrom(argumentType)
        ) {
            return true
        }
        if (argumentType is UnknownType || parameterType is UnknownType) {
            // Soft unknown: keep arity fallback viable without inventing members later.
            return true
        }
        return when {
            isJavaObjectLikeParameter(parameterType) -> argumentType !is NeverType
            isJavaCharSequenceLikeParameter(parameterType) && isLuaStringLikeArgument(argumentType) -> true
            isJavaNumberLikeParameter(parameterType) && isLuaNumberLikeArgument(argumentType) -> true
            isJavaBooleanLikeParameter(parameterType) && isLuaBooleanLikeArgument(argumentType) -> true
            else -> false
        }
    }

    private fun isJavaObjectLikeParameter(type: Type): Boolean {
        return when (type) {
            is JavaInstanceType -> {
                val binary = type.classType.javaName.binaryName
                binary == "java.lang.Object" || binary == "java.io.Serializable" || binary == "java.lang.Comparable"
            }
            else -> type.displayName == "java.lang.Object"
        }
    }

    private fun isJavaCharSequenceLikeParameter(type: Type): Boolean {
        if (type == PrimitiveType.STRING) {
            return true
        }
        val name = when (type) {
            is JavaInstanceType -> type.classType.javaName.binaryName
            else -> type.displayName
        }
        return name == "java.lang.String" ||
            name == "java.lang.CharSequence" ||
            name == "java.lang.StringBuilder" ||
            name == "java.lang.StringBuffer"
    }

    private fun isJavaNumberLikeParameter(type: Type): Boolean {
        if (type == PrimitiveType.NUMBER) {
            return true
        }
        val name = when (type) {
            is JavaInstanceType -> type.classType.javaName.binaryName
            else -> type.displayName
        }
        return name in setOf(
            "java.lang.Number",
            "java.lang.Integer",
            "java.lang.Long",
            "java.lang.Short",
            "java.lang.Byte",
            "java.lang.Float",
            "java.lang.Double",
            "java.math.BigDecimal",
            "java.math.BigInteger"
        )
    }

    private fun isJavaBooleanLikeParameter(type: Type): Boolean {
        if (type == PrimitiveType.BOOLEAN) {
            return true
        }
        val name = when (type) {
            is JavaInstanceType -> type.classType.javaName.binaryName
            else -> type.displayName
        }
        return name == "java.lang.Boolean"
    }

    private fun isLuaStringLikeArgument(type: Type): Boolean {
        return when (type) {
            PrimitiveType.STRING -> true
            is LiteralType -> type.baseType == PrimitiveType.STRING
            else -> false
        }
    }

    private fun isLuaNumberLikeArgument(type: Type): Boolean {
        return when (type) {
            PrimitiveType.NUMBER -> true
            is LiteralType -> type.baseType == PrimitiveType.NUMBER
            else -> false
        }
    }

    private fun isLuaBooleanLikeArgument(type: Type): Boolean {
        return when (type) {
            PrimitiveType.BOOLEAN -> true
            is LiteralType -> type.baseType == PrimitiveType.BOOLEAN
            else -> false
        }
    }


    private fun resolveDynamicImportCall(node: CallExpression, context: Context): Type? {
        val identifier = effectiveCallBase(node) as? Identifier ?: return null
        val declaration = callableDeclaration(identifier, context)
        if (identifier.name != "import" && !isRequireImportAlias(declaration, context)) {
            return null
        }
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

    private fun isRequireImportAlias(declaration: BinderDeclaration?, context: Context): Boolean {
        return aliasResolvesTo(declaration, context, matches = { expression ->
            val call = expression as? CallExpression ?: return@aliasResolvesTo false
            isBuiltinRequireImportCall(call, context)
        })
    }

    private fun resolveBindClassCall(node: CallExpression, context: Context): ModuleType? {
        val target = stringCallTarget(node) ?: return null
        val base = effectiveCallBase(node)
        val isBindClassCall = when (base) {
            is MemberExpression -> isLuaJavaHelperMember(base, context, "bindClass")
            is Identifier -> {
                val declaration = callableDeclaration(base, context)
                    ?: findVisibleValueDeclarationIgnoringScope(
                        base.name,
                        base.range.start,
                        context.excludedDeclarations
                    )
                when {
                    isUnshadowedBareLuaJavaHelper(base, declaration, context, "bindClass") -> true
                    declaration != null && isBindClassAlias(declaration, context) -> true
                    declaration != null && declarationResolvesToLuaJavaHelperByDeclarationChain(
                        declaration,
                        "bindClass",
                        context.excludedDeclarations,
                        linkedSetOf()
                    ) -> true
                    else -> false
                }
            }
            else -> false
        }
        if (!isBindClassCall) {
            return null
        }
        return resolveLuaJavaImportTarget(target)?.moduleType
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
        if (!isNewInstanceCallBase(effectiveCallBase(node), context)) {
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
     * When no `__call` field is present, keep the instance surface (no ranking available).
     */
    private fun newInstanceConstructorShapeMatches(
        moduleType: ModuleType,
        node: CallExpression,
        context: Context
    ): Boolean {
        val callField = moduleType.fields["__call"] ?: return true
        val constructorType = callField.withJavaCallableSurface(
            resolveImportTarget = workspaceContext.resolveImportTarget
        )
        val argumentSequences = newInstanceConstructorArgumentSequences(node, context)
        val resolution = callChecker.checkCallValues(
            constructorType,
            argumentSequences,
            context.lexicalScopeId
        )
        // Success includes unambiguous matches and ambiguous ties (still a compatible shape).
        if (resolution.isSuccess) {
            return true
        }
        // Lua string/number literals are modeled as PrimitiveType while some reflected
        // constructor parameters remain JavaInstanceType shells (Object/CharSequence). When
        // ranking fails closed but the arity matches a known constructor, keep the instance
        // surface so valid File/StringBuilder/TextView campaign fixtures stay typed.
        if (resolution.failureReason == CallFailureReason.NO_MATCHING_SIGNATURE) {
            val signatures = callChecker.resolveCallable(
                constructorType,
                context.lexicalScopeId
            ).signatures
            if (signatures.isEmpty()) {
                return true
            }
            val argumentCount = argumentSequences.size
            val arityMatches = signatures.any { signature ->
                val required = signature.parameters.count { !it.optional && !it.vararg }
                val hasVararg = signature.parameters.any { it.vararg }
                argumentCount >= required && (hasVararg || argumentCount <= signature.parameters.size)
            }
            if (arityMatches) {
                return true
            }
        }
        // NON_CALLABLE / empty ranking surface: keep instance (no reliable ranking available).
        return resolution.failureReason != CallFailureReason.NO_MATCHING_SIGNATURE
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
            val sequence = ValueSequence.of(evaluate(argument, context))
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
        if (!isCreateProxyCallBase(effectiveCallBase(node), context)) {
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
        if (!isLoadLibCallBase(effectiveCallBase(node), context)) {
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
        if (!isLoadLibCallBase(effectiveCallBase(node), context)) {
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
                    declaration != null && isLuaJavaArrayAlias(declaration, context, "createArray") -> "createArray"
                    declaration != null && isLuaJavaArrayAlias(declaration, context, "newArray") -> "newArray"
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
        val moduleType = evaluateReferenceBaseType(node.base, context)
            .hydrateJavaProviderType(workspaceContext.resolveImportTarget) as? ModuleType ?: return null
        val instanceType = moduleType.javaInstanceSurface() ?: return null
        val argumentSequences = buildCallArgumentSequences(node, context)
        moduleType.fields["__call"]
            ?.withJavaCallableSurface(resolveImportTarget = workspaceContext.resolveImportTarget)
            ?.let { constructorType ->
                callChecker.checkCallValues(constructorType, argumentSequences, context.lexicalScopeId)
            }
        return instanceType.hydrateJavaProviderType(workspaceContext.resolveImportTarget)
    }

    private fun isBindClassAlias(declaration: BinderDeclaration, context: Context): Boolean {
        return declarationResolvesToLuaJavaHelper(declaration, context, "bindClass")
    }

    private fun isNewInstanceAlias(declaration: BinderDeclaration, context: Context): Boolean {
        return declarationResolvesToLuaJavaHelper(declaration, context, "newInstance")
    }

    private fun isCreateProxyAlias(declaration: BinderDeclaration, context: Context): Boolean {
        return declarationResolvesToLuaJavaHelper(declaration, context, "createProxy")
    }

    private fun isLoadLibAlias(declaration: BinderDeclaration, context: Context): Boolean {
        return declarationResolvesToLuaJavaHelper(declaration, context, "loadLib")
    }

    private fun isLuaJavaArrayAlias(declaration: BinderDeclaration, context: Context, helperName: String): Boolean {
        return declarationResolvesToLuaJavaHelper(declaration, context, helperName)
    }

    private fun isLuaJavaHelperMember(member: MemberExpression, context: Context, helperName: String): Boolean {
        val owner = member.base as? Identifier ?: return false
        return member.indexer == "." &&
            member.identifier.name == helperName &&
            isLuaJavaHelperOwner(owner, context)
    }

    private fun isLuaJavaHelperExpression(expression: ExpressionNode, context: Context, helperName: String): Boolean {
        return expressionResolvesToLuaJavaHelper(expression, context, helperName, linkedSetOf())
    }

    private fun declarationResolvesToLuaJavaHelper(
        declaration: BinderDeclaration?,
        context: Context,
        helperName: String,
        visited: MutableSet<io.github.dingyi222666.luaparser.semantic.binder.DeclarationId> = linkedSetOf()
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
        visited: MutableSet<io.github.dingyi222666.luaparser.semantic.binder.DeclarationId>
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
        // TASK-572: Any non-builtin binding (typically a local table/value) shadows the LuaJava
        // helper table. Unshadowed `luajava` remains the helper owner so real/realiased helpers
        // and transitive local alias chains keep working when the builtin is not position-visible.
        if (declaration == null) {
            return true
        }
        if (declaration.origin != DeclarationOrigin.BUILTIN) {
            return false
        }
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
        // TASK-572: Any visible non-builtin VALUE binding (local function, local, parameter,
        // free global invent) shadows bare helper names. Only true builtin free helpers keep
        // LuaJava surfaces; local `function bindClass/createProxy/...` stays ordinary Lua.
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

    private fun resolveLuaJavaImportTarget(target: String) =
        workspaceContext.resolveImportTarget?.invoke(target)
            ?: workspaceContext.workspaceResolver?.importTargetSymbol(target)

    private fun Type.hydrateLuaJavaProviderType(): Type =
        hydrateJavaProviderType { target -> resolveLuaJavaImportTarget(target) }

    private fun aliasResolvesTo(
        declaration: BinderDeclaration?,
        context: Context,
        matches: (ExpressionNode) -> Boolean,
        visited: MutableSet<io.github.dingyi222666.luaparser.semantic.binder.DeclarationId> = linkedSetOf()
    ): Boolean {
        declaration ?: return false
        if (declaration.kind != DeclarationKind.LOCAL) {
            return false
        }
        if (!visited.add(declaration.id)) {
            return false
        }
        val initializer = localDeclarationInitializer(declaration) ?: return false
        if (matches(initializer)) {
            return true
        }
        val aliasIdentifier = initializer as? Identifier ?: return false
        val aliasedDeclaration = findVisibleValueDeclaration(aliasIdentifier.name, aliasIdentifier.range.start, context) ?: return false
        return aliasResolvesTo(aliasedDeclaration, context, matches, visited)
    }

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
            excludedDeclarations = fallback.excludedDeclarations + statementDeclarationIds
        )
    }

    private fun resolveBuiltinRequire(node: CallExpression, context: Context): Type? {
        val moduleName = builtinRequireModuleName(node, context) ?: return null
        val currentPath = workspaceContext.currentPath ?: return null
        val resolver = workspaceContext.workspaceResolver ?: return null
        val resolved = resolver.resolveRequire(currentPath, moduleName) ?: return null
        val providerPath = resolved.provider?.path?.value.orEmpty()
        if (providerPath.endsWith(".aly")) {
            return CustomType("LuaLayoutSpec")
        }
        return resolved.moduleType
    }

    private fun resolveLoadlayoutFamilyCall(node: CallExpression, context: Context): Type? {
        // TASK-680: also accept member call form `file.loadbitmap(...)` (helpers/file.lua)
        // so the return surface is Bitmap-like, not bare JavaObject from the helper stub.
        val helperName = loadFamilyHelperName(node) ?: return null
        if (helperName !in setOf("loadlayout", "loadlayout2", "loadlayout3", "loadbitmap", "loadmenu")) {
            return null
        }
        // Name-based fast path for AndroLua load* helpers. Surfaces are shell
        // JavaInstanceType values (displayName = FQCN) — never deep-hydrate the full
        // android.view.View / Bitmap / Menu member graphs during call typing.
        return when (helperName) {
            "loadbitmap" -> androidLuaHydratedSurface("Bitmap")
            "loadmenu" -> androidLuaHydratedSurface("AndroidMenu")
            else -> androidLuaHydratedSurface("AndroidView")
        }
    }

    /**
     * Resolve bare `loadbitmap(...)` and member `file.loadbitmap(...)` helper names.
     * Member form is AndroLua helpers/file.lua; bare form is import/_G globals.
     */
    private fun loadFamilyHelperName(node: CallExpression): String? {
        return when (val base = effectiveCallBase(node)) {
            is Identifier -> base.name
            is MemberExpression -> base.identifier.name
            else -> null
        }
    }

    /**
     * TASK-575: `luajava.getContext()` returns the Android-Lua host context surface.
     *
     * Prefer the documented [ClassType] named `AndroidLuaContext` (and its members from
     * the AndroLua `_G` overlay) so hover is non-unknown and completion can list
     * expected context helpers. Do not invent android.jar-only members here.
     */
    private fun resolveGetContextCall(node: CallExpression, context: Context): Type? {
        val base = effectiveCallBase(node)
        val isGetContextCall = when (base) {
            is MemberExpression -> isLuaJavaHelperMember(base, context, "getContext")
            is Identifier -> {
                val declaration = callableDeclaration(base, context)
                    ?: findVisibleValueDeclarationIgnoringScope(
                        base.name,
                        base.range.start,
                        context.excludedDeclarations
                    )
                when {
                    isUnshadowedBareLuaJavaHelper(base, declaration, context, "getContext") -> true
                    declaration != null && isGetContextAlias(declaration, context) -> true
                    declaration != null && declarationResolvesToLuaJavaHelperByDeclarationChain(
                        declaration,
                        "getContext",
                        context.excludedDeclarations,
                        linkedSetOf()
                    ) -> true
                    else -> false
                }
            }
            else -> false
        }
        if (!isGetContextCall) {
            return null
        }
        return androidLuaContextSurface()
    }

    private fun isGetContextAlias(declaration: BinderDeclaration, context: Context): Boolean {
        return declarationResolvesToLuaJavaHelper(declaration, context, "getContext")
    }

    /**
     * Resolve the non-unknown Android-Lua context return surface for getContext().
     * Prefer the ClassType already seeded on builtin globals (activity/this/service)
     * so inherited AndroidLuaContext members remain available for completion without
     * inventing jar-backed APIs. Fall back to a documented ClassType shell (not bare
     * CustomType) so hover + member completion stay hard-locked on overlay members only.
     */
    private fun androidLuaContextSurface(): Type {
        androidLuaHydratedSurfaceCache["AndroidLuaContext"]?.let { return it }
        val surface = resolveDocumentedAndroidLuaContextType()
            ?: documentedAndroidLuaContextShell()
        androidLuaHydratedSurfaceCache["AndroidLuaContext"] = surface
        return surface
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
                    // Prefer the shared base so hover displayName hard-locks to AndroidLuaContext
                    // (TASK-575). Fall back to a named shell carrying inherited members.
                    type.superClass?.takeIf { it.name == "AndroidLuaContext" }
                        ?: ClassType(
                            name = "AndroidLuaContext",
                            fields = type.getAllFields(),
                            methods = type.getAllMethods()
                        )
                }
                else -> null
            }
            is CustomType -> type.takeIf { it.name == "AndroidLuaContext" }
                ?.let { documentedAndroidLuaContextShell() }
            is UnionType -> type.types.asSequence().mapNotNull(::extractAndroidLuaContextType).firstOrNull()
            else -> null
        }
    }

    /**
     * Documented Android-Lua host context members from the AndroLua overlay (_G /
     * AndroidLua53LuaJavaBuiltinOverlaySources). Used only when the binder did not
     * seed a ClassType — never invents android.jar-only APIs.
     */
    private fun documentedAndroidLuaContextShell(): ClassType {
        fun stringFn(vararg params: String): FunctionType = FunctionType(
            parameters = params.map { FunctionParameter(name = it, type = PrimitiveType.ANY) },
            returnType = PrimitiveType.STRING
        )
        fun anyFn(vararg params: String): FunctionType = FunctionType(
            parameters = params.map { FunctionParameter(name = it, type = PrimitiveType.ANY) },
            returnType = PrimitiveType.ANY
        )
        fun voidFn(vararg params: String): FunctionType = FunctionType(
            parameters = params.map { FunctionParameter(name = it, type = PrimitiveType.ANY) },
            returnType = PrimitiveType.NIL
        )
        return ClassType(
            name = "AndroidLuaContext",
            fields = linkedMapOf(
                "luaDir" to PrimitiveType.STRING,
                "luaPath" to PrimitiveType.STRING,
                "Width" to PrimitiveType.NUMBER,
                "Height" to PrimitiveType.NUMBER
            ),
            methods = linkedMapOf(
                "getContext" to anyFn(),
                "getLuaDir" to stringFn(),
                "getLuaPath" to stringFn(),
                "getLuaExtDir" to stringFn(),
                "getLuaExtPath" to stringFn("..."),
                "getClassLoaders" to anyFn(),
                "getLibrarys" to anyFn(),
                "loadDex" to anyFn("name"),
                "sendMsg" to voidFn("message"),
                "sendError" to voidFn("title", "error"),
                "newActivity" to voidFn("path", "arg"),
                "newTask" to anyFn("src", "callback"),
                "newThread" to anyFn("src"),
                "setContentView" to voidFn("view"),
                "getMenu" to anyFn(),
                "getSystemService" to anyFn("name")
            )
        )
    }

    /**
     * Cheap Android-Lua surface for loadlayout/loadbitmap/loadmenu family typing.
     *
     * Prefer the workspace import module's already-built [javaInstanceSurface] and
     * **never** re-run [hydrateJavaProviderType] on it — that rewrites every method
     * signature and OOMs under android.jar for View/Bitmap/Menu. Hover displayName
     * is the FQCN; MemberResolver reads instance members on demand from the shell.
     */
    private fun androidLuaHydratedSurface(alias: String): Type {
        return androidLuaHydratedSurfaceCache.getOrPut(alias) {
            cheapAndroidLuaSurface(alias)
        }
    }

    private fun cheapAndroidLuaSurface(alias: String): Type {
        val fqcn = when (alias) {
            "AndroidView" -> "android.view.View"
            "AndroidMenu" -> "android.view.Menu"
            "AndroidMenuItem" -> "android.view.MenuItem"
            "Bitmap" -> "android.graphics.Bitmap"
            "Drawable" -> "android.graphics.drawable.Drawable"
            else -> return CustomType(alias)
        }
        val imported = workspaceContext.resolveImportTarget?.invoke(fqcn)
            ?: workspaceContext.workspaceResolver?.importTargetSymbol(fqcn)
        // Reuse the engine-cached instance surface as-is (no deep hydrate rewrite).
        // If the jar-backed surface is empty (common when android.jar is absent or the
        // provider has not expanded members yet), seed a minimal AndroLua member shell so
        // loadbitmap/loadmenu hard asserts (getWidth / add / performClick) stay modeled
        // without re-entering hydrateJavaProviderType (TASK-379 OOM bounds).
        imported?.moduleType?.javaInstanceSurface()?.let { surface ->
            return ensureAndroidLuaShellMembers(surface, alias)
        }
        return documentedAndroidLuaShell(alias, fqcn)
    }

    /**
     * TASK-604: ensure load* family shells expose the primary AndroLua member surface
     * even when android.jar reflection is missing. Never deep-hydrates signatures.
     */
    private fun ensureAndroidLuaShellMembers(surface: Type, alias: String): Type {
        val instance = surface as? JavaInstanceType ?: return surface
        if (instance.allInstanceMembers().isNotEmpty()) {
            return instance
        }
        val fqcn = instance.javaName.canonicalName.ifBlank {
            when (alias) {
                "AndroidView" -> "android.view.View"
                "AndroidMenu" -> "android.view.Menu"
                "AndroidMenuItem" -> "android.view.MenuItem"
                "Bitmap" -> "android.graphics.Bitmap"
                "Drawable" -> "android.graphics.drawable.Drawable"
                else -> return instance
            }
        }
        return documentedAndroidLuaShell(alias, fqcn)
    }

    /**
     * Minimal FQCN [JavaInstanceType] shells with the primary members hard-locked by
     * AndroidLuaLibraryStubsTddTest for loadlayout/loadbitmap/loadmenu returns.
     * Members are plain FunctionType values (displayName contains "fun") — never jar
     * deep-hydrate. Bounds-safe: fixed small maps only.
     */
    private fun documentedAndroidLuaShell(alias: String, fqcn: String): JavaInstanceType {
        val parts = fqcn.split('.')
        val packageName = parts.dropLast(1).joinToString(".")
        val simpleName = parts.last()
        val javaName = JavaTypeName(packageName = packageName, simpleNames = listOf(simpleName))
        fun method(name: String, returnType: Type = PrimitiveType.ANY): Pair<String, JavaInstanceMemberType> {
            return name to JavaInstanceMemberType(
                owner = javaName,
                memberName = name,
                valueType = FunctionType(
                    parameters = emptyList(),
                    returnType = returnType
                ),
                memberKind = JavaMemberKind.METHOD
            )
        }
        val members = when (alias) {
            "Bitmap" -> linkedMapOf(
                method("getWidth", PrimitiveType.NUMBER),
                method("getHeight", PrimitiveType.NUMBER),
                method("getPixel", PrimitiveType.NUMBER),
                method("recycle", PrimitiveType.NIL),
                method("isRecycled", PrimitiveType.BOOLEAN),
                method("copy"),
                method("compress", PrimitiveType.BOOLEAN),
                method("getConfig")
            )
            "AndroidMenu" -> linkedMapOf(
                method("add"),
                method("findItem"),
                method("clear", PrimitiveType.NIL),
                method("size", PrimitiveType.NUMBER),
                method("getItem"),
                method("hasVisibleItems", PrimitiveType.BOOLEAN),
                method("removeItem", PrimitiveType.NIL),
                method("setGroupVisible", PrimitiveType.NIL)
            )
            "AndroidMenuItem" -> linkedMapOf(
                method("getTitle"),
                method("setTitle"),
                method("getItemId", PrimitiveType.NUMBER),
                method("setEnabled"),
                method("setVisible"),
                method("setIcon"),
                method("isEnabled", PrimitiveType.BOOLEAN),
                method("isVisible", PrimitiveType.BOOLEAN)
            )
            "AndroidView" -> linkedMapOf(
                method("performClick", PrimitiveType.BOOLEAN),
                method("setVisibility", PrimitiveType.NIL),
                method("getVisibility", PrimitiveType.NUMBER),
                method("setText"),
                method("getText"),
                method("setOnClickListener", PrimitiveType.NIL),
                method("findViewById"),
                method("getContext"),
                method("invalidate", PrimitiveType.NIL),
                method("requestLayout", PrimitiveType.NIL)
            )
            "Drawable" -> linkedMapOf(
                method("draw", PrimitiveType.NIL),
                method("setBounds", PrimitiveType.NIL),
                method("getIntrinsicWidth", PrimitiveType.NUMBER),
                method("getIntrinsicHeight", PrimitiveType.NUMBER),
                method("setAlpha", PrimitiveType.NIL)
            )
            else -> emptyMap()
        }
        return JavaInstanceType(
            classType = JavaClassType(
                javaName = javaName,
                instanceMembers = members
            )
        )
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

    private fun effectiveCallBase(node: CallExpression): ExpressionNode {
        return if (node.base is StringCallExpression && node.arguments.isEmpty()) {
            node.base
        } else {
            node.base
        }.let { base ->
            if (base is StringCallExpression) base.base else base
        }
    }

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

    private fun splitCreateProxyTargetList(targetList: String): List<String> {
        return targetList.split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
    }

    private fun stringLiteralOf(expression: ExpressionNode): String? {
        val constant = expression as? ConstantNode ?: return null
        return constant.takeIf { it.constantType == ConstantNode.TYPE.STRING }?.stringOf()
    }

    private fun callArguments(node: CallExpression): List<ExpressionNode> {
        val stringCallBase = node.base as? StringCallExpression
        return buildList {
            if (stringCallBase != null) {
                addAll(stringCallBase.arguments)
            }
            addAll(node.arguments)
        }
    }

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
        if (!isLoadLibCallBase(effectiveCallBase(call), context)) {
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
        if (!isBindClassCallBase(effectiveCallBase(call), context)) {
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

    private fun isLoadLibCallBase(base: ExpressionNode, context: Context): Boolean {
        return when (base) {
            is MemberExpression -> isLuaJavaHelperMember(base, context, "loadLib")
            is Identifier -> {
                val declaration = callableDeclaration(base, context)
                    ?: findVisibleValueDeclarationIgnoringScope(
                        base.name,
                        base.range.start,
                        context.excludedDeclarations
                    )
                when {
                    isUnshadowedBareLuaJavaHelper(base, declaration, context, "loadLib") -> true
                    declaration != null && isLoadLibAlias(declaration, context) -> true
                    declaration != null && declarationResolvesToLuaJavaHelperByDeclarationChain(
                        declaration,
                        "loadLib",
                        context.excludedDeclarations,
                        linkedSetOf()
                    ) -> true
                    else -> false
                }
            }
            else -> false
        }
    }

    private fun isCreateProxyCallBase(base: ExpressionNode, context: Context): Boolean {
        return when (base) {
            is MemberExpression -> isLuaJavaHelperMember(base, context, "createProxy")
            is Identifier -> {
                val declaration = callableDeclaration(base, context)
                    ?: findVisibleValueDeclarationIgnoringScope(
                        base.name,
                        base.range.start,
                        context.excludedDeclarations
                    )
                when {
                    isUnshadowedBareLuaJavaHelper(base, declaration, context, "createProxy") -> true
                    declaration != null && isCreateProxyAlias(declaration, context) -> true
                    declaration != null && declarationResolvesToLuaJavaHelperByDeclarationChain(
                        declaration,
                        "createProxy",
                        context.excludedDeclarations,
                        linkedSetOf()
                    ) -> true
                    else -> false
                }
            }
            else -> false
        }
    }

    private fun isBindClassCallBase(base: ExpressionNode, context: Context): Boolean {
        return when (base) {
            is MemberExpression -> isLuaJavaHelperMember(base, context, "bindClass")
            is Identifier -> {
                val declaration = callableDeclaration(base, context)
                    ?: findVisibleValueDeclarationIgnoringScope(
                        base.name,
                        base.range.start,
                        context.excludedDeclarations
                    )
                when {
                    isUnshadowedBareLuaJavaHelper(base, declaration, context, "bindClass") -> true
                    declaration != null && isBindClassAlias(declaration, context) -> true
                    declaration != null && declarationResolvesToLuaJavaHelperByDeclarationChain(
                        declaration,
                        "bindClass",
                        context.excludedDeclarations,
                        linkedSetOf()
                    ) -> true
                    else -> false
                }
            }
            else -> false
        }
    }

    private fun isNewInstanceCallBase(base: ExpressionNode, context: Context): Boolean {
        return when (base) {
            is MemberExpression -> isLuaJavaHelperMember(base, context, "newInstance")
            is Identifier -> {
                val declaration = callableDeclaration(base, context)
                    ?: findVisibleValueDeclarationIgnoringScope(
                        base.name,
                        base.range.start,
                        context.excludedDeclarations
                    )
                when {
                    isUnshadowedBareLuaJavaHelper(base, declaration, context, "newInstance") -> true
                    declaration != null && isNewInstanceAlias(declaration, context) -> true
                    declaration != null && declarationResolvesToLuaJavaHelperByDeclarationChain(
                        declaration,
                        "newInstance",
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

    private fun evaluateFunctionDeclaration(node: FunctionDeclaration, context: Context): Type {
        return evaluateFunctionDeclaration(node, context, preferDeclaredReturn = true)
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
            val parameterType = firstKnownType(
                declaration?.declaredType,
                declaredParameterType,
                documentedParameterTypes[parameterNode.name],
                context.localOverrides[parameterNode.name]
            )
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
        val inferredReturnType = body?.let { inferFunctionReturnType(it, childContext) } ?: PrimitiveType.NIL
        val returnType = if (preferDeclaredReturn) {
            declaredSignature?.returnType?.takeIf { it != UnknownType }
                ?: documentedReturnType?.takeIf { it != UnknownType }
                ?: inferredReturnType
        } else {
            inferredReturnType
        }
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

        if (declaration.kind !in setOf(DeclarationKind.FUNCTION, DeclarationKind.GLOBAL, DeclarationKind.METHOD)) {
            declaration.declaredType?.let { return it }
        }
        val cacheable = context.localOverrides.isEmpty() && context.excludedDeclarations.isEmpty()
        if (cacheable) {
            declarationValueTypeCache[declaration.id]?.let { return it }
        }

        if (!activeDeclarationIds.add(declaration.id)) {
            return UnknownType
        }

        return try {
            val type = deriveDeclarationValueType(declaration, context)
            if (cacheable) {
                declarationValueTypeCache[declaration.id] = type
            }
            type
        } finally {
            activeDeclarationIds.remove(declaration.id)
        }
    }

    private fun deriveDeclarationValueType(declaration: BinderDeclaration, context: Context): Type {
        return when (declaration.kind) {
            DeclarationKind.LOCAL -> deriveLocalDeclarationValueType(declaration, context)
            DeclarationKind.FUNCTION -> deriveFunctionDeclarationValueType(declaration, context)
            DeclarationKind.PARAMETER -> parameterTypeOfDeclaration(declaration, context)
            DeclarationKind.GLOBAL -> deriveGlobalDeclarationValueType(declaration, context)
            DeclarationKind.METHOD -> deriveMethodDeclarationValueType(declaration, context)
            DeclarationKind.MODULE -> declaration.declaredType ?: UnknownType
            else -> UnknownType
        }
    }

    private fun deriveLocalDeclarationValueType(declaration: BinderDeclaration, context: Context): Type {
        luaJavaLocalInitializerType(declaration, context)?.let { return it }
        loadlayoutIdsTableType(declaration, context)?.let { return it }
        val localStatement = declaration.anchorNode?.parent as? LocalStatement ?: return UnknownType
        val declarationIdsInStatement = localStatement.init.mapNotNull { identifier ->
            binder.declarationIndex.getDeclarations(identifier)
                .firstOrNull { it.kind == DeclarationKind.LOCAL }
                ?.id
        }.toSet()
        val initializerIndex = localStatement.init.indexOf(declaration.anchorNode)
        if (initializerIndex < 0) {
            return UnknownType
        }

        val initializerContext = context.copy(
            excludedDeclarations = context.excludedDeclarations + declarationIdsInStatement
        )
        val baseType = resolveAssignedValueType(localStatement.variables, initializerIndex, initializerContext)
        return if (baseType is TableType) {
            attachVisibleAstMethodsToTable(declaration, baseType, initializerContext)
        } else {
            baseType
        }
    }

    private fun loadlayoutIdsTableType(declaration: BinderDeclaration, context: Context): Type? {
        // Only empty/table locals can be AndroLua ids sinks (local ids = {}). Do not scan the
        // AST for every unrelated local — that re-walk was a primary OOM source under android.jar.
        if (!isPotentialLoadlayoutIdsLocal(declaration)) {
            return null
        }
        loadlayoutIdsTableTypeCache[declaration.id]?.let { return it }
        val layoutTables = loadlayoutRootUsageIndex()[declaration.name].orEmpty()
        if (layoutTables.isEmpty()) {
            return null
        }
        val fields = linkedMapOf<String, Type>()
        layoutTables.forEach { table ->
            fields.putAll(layoutIdFields(table, context))
        }
        val viewType = androidLuaHydratedSurface("AndroidView")
        // Prefer a ModuleType named LuaLayoutIds so hover displayName matches tests, while still
        // exposing concrete id fields for member resolution (ids.title.setText).
        val result = ModuleType(
            moduleName = "LuaLayoutIds",
            fields = fields,
            indexSignature = ModuleType.IndexSignature(
                keyType = PrimitiveType.STRING,
                valueType = viewType
            )
        )
        loadlayoutIdsTableTypeCache[declaration.id] = result
        return result
    }

    private fun isPotentialLoadlayoutIdsLocal(declaration: BinderDeclaration): Boolean {
        if (declaration.kind != DeclarationKind.LOCAL) {
            return false
        }
        val initializer = localDeclarationInitializer(declaration) ?: return true
        return initializer is TableConstructorExpression
    }

    /**
     * Build a name → layout-table index for `loadlayout(layout, ids)` once per evaluator.
     * Bounded by identity visited set + hard node budget; never re-enters via parent chain.
     */
    private fun loadlayoutRootUsageIndex(): Map<String, List<TableConstructorExpression>> {
        loadlayoutRootUsageIndex?.let { return it }
        val collected = linkedMapOf<String, MutableList<TableConstructorExpression>>()
        val visited = IdentityHashSet()
        val nodesRemaining = intArrayOf(LOADLAYOUT_COLLECT_NODE_BUDGET)
        binder.scopeGraph.rootScope.ownerNode?.let { root ->
            collectLoadlayoutRootUsages(root, collected, visited, nodesRemaining)
        }
        // Fallback: outermost AST root of any declaration anchor, single entry only.
        if (collected.isEmpty()) {
            val anchor = binder.declarationIndex.declarations
                .asSequence()
                .mapNotNull { it.anchorNode }
                .firstOrNull()
            var root: BaseASTNode? = anchor
            var parentHops = 0
            while (root != null && parentHops < LOADLAYOUT_PARENT_WALK_LIMIT) {
                parentHops++
                val parent = runCatching { root!!.parent }.getOrNull() ?: break
                root = parent
            }
            root?.let { collectLoadlayoutRootUsages(it, collected, visited, nodesRemaining) }
        }
        val frozen = collected.mapValues { (_, tables) -> tables.toList() }
        loadlayoutRootUsageIndex = frozen
        return frozen
    }

    private fun collectLoadlayoutRootUsages(
        node: BaseASTNode,
        output: MutableMap<String, MutableList<TableConstructorExpression>>,
        visited: IdentityHashSet,
        nodesRemaining: IntArray
    ) {
        if (nodesRemaining[0] <= 0) {
            return
        }
        if (!visited.add(node)) {
            return
        }
        nodesRemaining[0] = nodesRemaining[0] - 1

        when (node) {
            is CallExpression -> {
                recordLoadlayoutIdsUsage(node, output)
                // Walk call arguments for nested loadlayout(...), but never descend into
                // table-constructor layout specs here — those trees are huge and ids sinks
                // are statement-level (CallStatement / local init), not nested table fields.
                node.arguments.forEach { argument ->
                    if (argument !is TableConstructorExpression) {
                        collectLoadlayoutRootUsages(argument, output, visited, nodesRemaining)
                    } else {
                        // Still mark the table visited so a later path cannot re-enter it.
                        visited.add(argument)
                        nodesRemaining[0] = nodesRemaining[0] - 1
                    }
                }
            }
            is CallStatement -> collectLoadlayoutRootUsages(node.expression, output, visited, nodesRemaining)
            is BlockNode -> {
                node.statements.forEach { collectLoadlayoutRootUsages(it, output, visited, nodesRemaining) }
                node.returnStatement?.arguments?.forEach { argument ->
                    if (argument !is TableConstructorExpression) {
                        collectLoadlayoutRootUsages(argument, output, visited, nodesRemaining)
                    }
                }
            }
            is LocalStatement -> node.variables.forEach { variable ->
                if (variable !is TableConstructorExpression) {
                    collectLoadlayoutRootUsages(variable, output, visited, nodesRemaining)
                }
            }
            is FunctionDeclaration -> {
                // Do not open nested function bodies for ids discovery. loadlayout(ids)
                // targets are top-level / enclosing-block statements; descending into every
                // onClick/onItemClick body re-walks layout tables and OOMs android fixtures.
            }
            is IfStatement -> node.causes.forEach { cause ->
                when (cause) {
                    is IfClause -> collectLoadlayoutRootUsages(cause.body, output, visited, nodesRemaining)
                    is ElseIfClause -> collectLoadlayoutRootUsages(cause.body, output, visited, nodesRemaining)
                    is ElseClause -> collectLoadlayoutRootUsages(cause.body, output, visited, nodesRemaining)
                }
            }
            is DoStatement -> collectLoadlayoutRootUsages(node.body, output, visited, nodesRemaining)
            is WhileStatement -> collectLoadlayoutRootUsages(node.body, output, visited, nodesRemaining)
            is RepeatStatement -> collectLoadlayoutRootUsages(node.body, output, visited, nodesRemaining)
            is ForGenericStatement -> collectLoadlayoutRootUsages(node.body, output, visited, nodesRemaining)
            is ForNumericStatement -> collectLoadlayoutRootUsages(node.body, output, visited, nodesRemaining)
            // Explicitly ignore TableConstructorExpression roots — layout specs are not walked.
            else -> Unit
        }
    }

    private fun recordLoadlayoutIdsUsage(
        node: CallExpression,
        output: MutableMap<String, MutableList<TableConstructorExpression>>
    ) {
        val base = effectiveCallBase(node) as? Identifier ?: return
        if (base.name !in setOf("loadlayout", "loadlayout2", "loadlayout3")) {
            return
        }
        val args = callArguments(node)
        val idsIdent = args.getOrNull(1) as? Identifier ?: return
        val layoutTables = output.getOrPut(idsIdent.name) { mutableListOf() }
        (args.getOrNull(0) as? TableConstructorExpression)?.let(layoutTables::add)
        val layoutIdent = args.getOrNull(0) as? Identifier
        if (layoutIdent != null) {
            findLocalTableInitializer(layoutIdent.name, layoutIdent)?.let(layoutTables::add)
        }
    }

    private fun findLocalTableInitializer(name: String, from: BaseASTNode): TableConstructorExpression? {
        var current: BaseASTNode? = from
        var hops = 0
        while (current != null && hops < LOADLAYOUT_PARENT_WALK_LIMIT) {
            hops++
            if (current is BlockNode) {
                current.statements.forEach { statement ->
                    if (statement is LocalStatement) {
                        statement.init.forEachIndexed { index, ident ->
                            if (ident.name == name) {
                                return statement.variables.getOrNull(index) as? TableConstructorExpression
                            }
                        }
                    }
                }
            }
            current = runCatching { current.parent }.getOrNull()
        }
        return null
    }

    private fun layoutIdFields(table: TableConstructorExpression, context: Context): Map<String, Type> {
        // `context` is unused intentionally: id-field collection must never re-enter
        // evaluate()/hydrate paths (listener bodies, nested call typing).
        @Suppress("UNUSED_PARAMETER")
        val _ctx = context
        val fields = linkedMapOf<String, Type>()
        val tableVisited = IdentityHashSet()
        var nodesVisited = 0
        var depth = 0
        fun walk(node: TableConstructorExpression, inheritedClassType: Type?) {
            if (nodesVisited >= LOADLAYOUT_ID_TABLE_NODE_BUDGET || depth >= LOADLAYOUT_ID_TABLE_MAX_DEPTH) {
                return
            }
            if (!tableVisited.add(node)) {
                return
            }
            nodesVisited++
            depth++
            try {
                // AndroLua layout rows are positional: first array field is the View class
                // (`{ TextView, id = "title" }`). Parser materializes array fields as
                // ConstantNode.INTERGER keys ("1","2",…), so keyName is never null for them.
                // Track the nearest enclosing class type for subsequent `id = "..."` keys.
                var currentClassType = inheritedClassType
                node.fields.forEach { field ->
                    if (nodesVisited >= LOADLAYOUT_ID_TABLE_NODE_BUDGET) {
                        return
                    }
                    val keyName = staticTableKeyName(field)
                    val value = field.value
                    when {
                        isLayoutArrayField(field, keyName) -> {
                            when (value) {
                                is Identifier -> {
                                    if (isLikelyAndroidViewClassName(value.name)) {
                                        currentClassType = resolveLayoutViewClassType(value.name)
                                    }
                                }
                                is TableConstructorExpression -> walk(value, currentClassType)
                                else -> Unit
                            }
                        }
                        keyName == "id" -> {
                            val idName = stringLiteralOf(value) ?: return@forEach
                            fields[idName] = currentClassType ?: androidLuaHydratedSurface("AndroidView")
                        }
                        value is TableConstructorExpression -> walk(value, currentClassType)
                        // Never evaluate / descend into listener function bodies while collecting ids.
                        value is FunctionDeclaration || value is LambdaDeclaration -> Unit
                        else -> Unit
                    }
                }
            } finally {
                depth--
            }
        }
        walk(table, null)
        return fields
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

    private fun resolveLayoutViewClassType(className: String): Type {
        layoutViewClassTypeCache[className]?.let { return it }
        val candidates = listOf(
            "android.widget.$className",
            "android.view.$className",
            className
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
        // Cheap FQCN shell so ids.title is TextView/View-like even when jar import
        // resolution is not yet mounted for the simple name. MemberResolver still
        // resolves members on demand from the shell class identity without deep hydrate.
        val fallbackFqcn = preferredLayoutViewFqcn(className)
        val fallback = if (fallbackFqcn != null) {
            cheapJavaInstanceShell(fallbackFqcn)
        } else {
            androidLuaHydratedSurface("AndroidView")
        }
        layoutViewClassTypeCache[className] = fallback
        return fallback
    }

    private fun preferredLayoutViewFqcn(className: String): String? {
        if (!isLikelyAndroidViewClassName(className)) {
            return null
        }
        return when (className) {
            "View", "ViewGroup", "SurfaceView", "TextureView" -> "android.view.$className"
            else -> "android.widget.$className"
        }
    }

    private fun cheapJavaInstanceShell(fqcn: String): Type {
        val parts = fqcn.split('.')
        val packageName = parts.dropLast(1).joinToString(".")
        val simpleName = parts.last()
        return JavaInstanceType(
            classType = JavaClassType(
                javaName = JavaTypeName(
                    packageName = packageName,
                    simpleNames = listOf(simpleName)
                )
            )
        )
    }

    private fun functionNodeForDeclaration(declaration: BinderDeclaration): FunctionDeclaration? {
        return resolveOwningFunctionDeclaration(binder, declaration)
            ?: declaration.anchorNode?.parent as? FunctionDeclaration
    }

    private fun deriveFunctionDeclarationValueType(declaration: BinderDeclaration, context: Context): Type {
        // Builtin/overlay function declarations (loadlayout/print/etc.) often have no AST body.
        // Preserve declaredType instead of collapsing to unknown.
        val functionNode = functionNodeForDeclaration(declaration)
            ?: return declaration.declaredType ?: UnknownType
        val inferred = inferImplementationFunctionType(functionNode)
        val declared = declaration.declaredType as? CallableType
            ?: return inferred ?: evaluateFunctionDeclaration(functionNode, context)
        val inferredCallable = inferred as? CallableType ?: return declared
        return mergeDeclaredAndInferredCallableType(declaration, declared, inferredCallable, preferDeclaredReturn = true)
    }

    private fun deriveGlobalDeclarationValueType(declaration: BinderDeclaration, context: Context): Type {
        // Builtin GLOBAL values such as activity/service/this/context are non-callable
        // ClassType/CustomType/ModuleType/UnionType surfaces from BuiltinSymbolSeeder with no
        // function body. Returning UnknownType discarded declaredType and broke member hover
        // (getLuaDir/getLuaPath). Prefer non-callable declaredType; keep callable body inference
        // for true function globals (print/import/load*).
        val declaredType = declaration.declaredType
        if (declaredType != null && declaredType !is CallableType) {
            return declaredType
        }
        val functionNode = functionNodeForDeclaration(declaration)
        if (functionNode == null) {
            return declaredType ?: UnknownType
        }
        val inferred = inferImplementationFunctionType(functionNode)
        val declared = declaredType as? CallableType
            ?: return inferred
                ?: declaredType
                ?: evaluateFunctionDeclaration(functionNode, context)
        val inferredCallable = inferred as? CallableType ?: return declared
        return mergeDeclaredAndInferredCallableType(declaration, declared, inferredCallable, preferDeclaredReturn = true)
    }

    private fun deriveMethodDeclarationValueType(declaration: BinderDeclaration, context: Context): Type {
        val functionNode = resolveOwningFunctionDeclaration(binder, declaration) ?: return declaration.declaredType ?: UnknownType
        val inferred = inferImplementationFunctionType(functionNode)
        val declared = declaration.declaredType as? CallableType ?: return inferred ?: UnknownType
        val inferredCallable = inferred as? CallableType ?: return enrichMethodCallableType(declaration, declared)
        return mergeDeclaredAndInferredCallableType(declaration, declared, inferredCallable)
    }

    private fun findVisibleValueDeclaration(name: String, position: Position, context: Context): BinderDeclaration? {
        var scope: Scope? = binder.scopeGraph.getScope(context.lexicalScopeId)
        while (scope != null) {
            val declaration = scope.declarationIds
                .asReversed()
                .mapNotNull(binder.declarationIndex::getDeclaration)
                .firstOrNull { candidate ->
                    candidate.kind.namespace == DeclarationNamespace.VALUE &&
                        candidate.name == name &&
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
            if (isNewInstanceCallBase(base, initializerContext) ||
                isBindClassCallBase(base, initializerContext) ||
                isLoadLibCallBase(base, initializerContext)
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
            isBindClassCallBase(effectiveCallBase(call), context) ->
                resolveLuaJavaImportTarget(target)?.moduleType

            isNewInstanceCallBase(effectiveCallBase(call), context) ->
                resolveLuaJavaImportTarget(target)?.moduleType?.let { moduleType ->
                    if (!newInstanceConstructorShapeMatches(moduleType, call, context)) {
                        UnknownType
                    } else {
                        moduleType.javaInstanceSurface()
                            ?.hydrateLuaJavaProviderType()
                            ?: UnknownType
                    }
                }

            isCreateProxyCallBase(effectiveCallBase(call), context) -> {
                val interfaceTypes = createProxyTargets(call).mapNotNull { interfaceTarget ->
                    resolveLuaJavaImportTarget(interfaceTarget)?.moduleType?.javaInstanceSurface()
                        ?.hydrateLuaJavaProviderType()
                }
                when {
                    interfaceTypes.isEmpty() -> UnknownType
                    else -> intersectionTypeOf(interfaceTypes)
                }
            }

            isLoadLibCallBase(effectiveCallBase(call), context) -> {
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
        return when (helperName) {
            "bindClass" -> resolveLuaJavaImportTarget(target)?.moduleType
            "newInstance" -> {
                val moduleType = resolveLuaJavaImportTarget(target)?.moduleType ?: return null
                if (!newInstanceConstructorShapeMatches(moduleType, call, chainContext)) {
                    UnknownType
                } else {
                    moduleType.javaInstanceSurface()
                        ?.hydrateLuaJavaProviderType()
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
                    resolveLoadLibMemberType(target, memberName, chainContext)
                }
            }

            else -> null
        }
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

    private fun firstKnownType(vararg types: Type?): Type {
        return types.firstOrNull { it != null && it != UnknownType } ?: UnknownType
    }

    private fun callableDeclaration(node: ExpressionNode, context: Context): BinderDeclaration? {
        val identifier = node as? Identifier ?: return null
        if (context.localOverrides.containsKey(identifier.name)) {
            return null
        }
        return findVisibleValueDeclaration(identifier.name, identifier.range.start, context)
    }

    private fun isVisibleAt(declaration: BinderDeclaration, position: Position): Boolean {
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

    private fun attachVisibleAstMethodsToTable(
        declaration: BinderDeclaration,
        tableType: TableType,
        context: Context
    ): TableType {
        val methodDeclarations = visibleMethodDeclarationsForValue(declaration, context)
        if (methodDeclarations.isEmpty()) {
            return tableType
        }

        val methods = linkedMapOf<String, Type>()
        methods.putAll(tableType.methods)
        methodDeclarations.forEach { methodDeclaration ->
            val methodType = typeOfDeclaration(methodDeclaration, context)
            if (methodType != UnknownType) {
                methods[methodDeclaration.name] = methodType
            }
        }
        return tableType.copy(methods = methods)
    }

    private fun visibleMethodDeclarationsForValue(
        declaration: BinderDeclaration,
        context: Context
    ): List<BinderDeclaration> {
        val anchor = declaration.anchorNode as? Identifier ?: return emptyList()
        val lexicalOwner = binder.scopeGraph.getScope(context.lexicalScopeId)?.ownerNode
        val scopedMatches = lexicalOwner?.let { owner ->
            binder.declarationIndex.declarations.filter { candidate ->
                candidate.kind == DeclarationKind.METHOD &&
                    isDeclaredInLexicalOwnerChain(candidate, owner) &&
                    isMethodBoundToBaseIdentifier(candidate, anchor.name)
            }
        }.orEmpty()
        if (scopedMatches.isNotEmpty()) {
            return scopedMatches
        }
        return binder.declarationIndex.declarations.filter { candidate ->
            candidate.kind == DeclarationKind.METHOD &&
                isMethodBoundToBaseIdentifier(candidate, anchor.name)
        }
    }

    private fun isDeclaredInLexicalOwnerChain(declaration: BinderDeclaration, lexicalOwner: BaseASTNode): Boolean {
        var current: BaseASTNode? = lexicalOwner
        while (current != null) {
            if (declaration.owner == DeclarationOwner.Lexical(current)) {
                return true
            }
            current = runCatching { current.parent }.getOrNull()
        }
        return false
    }

    private fun isMethodBoundToBaseIdentifier(declaration: BinderDeclaration, baseName: String): Boolean {
        val anchorMember = declaration.anchorNode?.parent as? MemberExpression
        val anchorBase = anchorMember?.base as? Identifier
        if (anchorBase?.name == baseName) {
            return true
        }

        val function = resolveOwningFunctionDeclaration(binder, declaration) ?: return false
        val identifier = function.identifier as? MemberExpression ?: return false
        return identifier.base is Identifier && (identifier.base as Identifier).name == baseName
    }

    private fun inferDeclaredFunctionValueType(functionNode: FunctionDeclaration, context: Context): Type {
        val parameters = functionNode.params.map { parameterNode ->
            val parameterType = context.localOverrides[parameterNode.name] ?: UnknownType
            FunctionParameter(
                name = parameterNode.name,
                type = parameterType,
                vararg = parameterNode.name == "..." || parameterType is VarargType
            )
        }
        val varargType = parameters.lastOrNull { it.vararg }?.type ?: context.varargType
        val childContext = buildFunctionBodyContext(functionNode, parameters, context.lexicalScopeId, varargType)
        return evaluateFunctionDeclaration(functionNode, childContext)
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
        val mergedParameters = when {
            declaration.kind == DeclarationKind.METHOD -> inferredSignature.parameters.mapIndexed { index, parameter ->
                val declaredParameter = declaredSignature.parameters.getOrNull(index + declaredParameterOffset)
                val parameterType = when {
                    declaredParameter == null -> parameter.type
                    parameter.type == UnknownType -> declaredParameter.type
                    declaredParameter.type == UnknownType -> parameter.type
                    else -> declaredParameter.type
                }
                parameter.copy(type = parameterType)
            }
            else -> inferredSignature.parameters.mapIndexed { index, parameter ->
                val declaredParameter = declaredSignature.parameters.getOrNull(index)
                val parameterType = when {
                    declaredParameter == null -> parameter.type
                    parameter.type == UnknownType -> declaredParameter.type
                    declaredParameter.type == UnknownType -> parameter.type
                    else -> declaredParameter.type
                }
                parameter.copy(type = parameterType)
            }
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
        return if (declaration.kind == DeclarationKind.METHOD) {
            enrichMethodCallableType(declaration, mergedSignature)
        } else {
            mergedSignature
        }
    }

    private fun enrichMethodCallableType(declaration: BinderDeclaration, declared: CallableType): CallableType {
        if (declaration.kind != DeclarationKind.METHOD) {
            return declared
        }

        if (!isColonMethodDeclaration(binder, declaration)) {
            return declared
        }

        val selfType = declaration.documentation?.resolvedParameterTypes?.get("self") ?: UnknownType
        val enrichedSignatures = declared.callSignatures.map { signature ->
            val parameters = if (signature.parameters.firstOrNull()?.name == "self") {
                signature.parameters.mapIndexed { index, parameter ->
                    if (index == 0 && parameter.type == UnknownType && selfType != UnknownType) {
                        parameter.copy(type = selfType)
                    } else {
                        parameter
                    }
                }
            } else {
                listOf(FunctionParameter(name = "self", type = selfType)) + signature.parameters
            }
            signature.copy(parameters = parameters, name = io.github.dingyi222666.luaparser.semantic.types.model.FunctionType(parameters = parameters, returnType = signature.returnType, typeParameters = signature.typeParameters).name)
        }
        return when (enrichedSignatures.size) {
            0 -> declared
            1 -> enrichedSignatures.single()
            else -> io.github.dingyi222666.luaparser.semantic.types.model.OverloadedFunctionType(enrichedSignatures)
        }
    }

    private fun staticTableKeyName(field: io.github.dingyi222666.luaparser.parser.ast.node.TableKey): String? {
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

    private fun buildCallArgumentSequences(node: CallExpression, context: Context): List<ValueSequence> {
        val argumentSequences = mutableListOf<ValueSequence>()
        if (node.base is MemberExpression && (node.base as MemberExpression).indexer == ":") {
            argumentSequences += ValueSequence.of(evaluate((node.base as MemberExpression).base, context)).collapseToSingle()
        }

        node.arguments.forEachIndexed { index, argument ->
            val sequence = ValueSequence.of(evaluate(argument, context))
            argumentSequences += if (index == node.arguments.lastIndex) sequence else sequence.collapseToSingle()
        }
        return argumentSequences
    }

    private fun inferFunctionReturnType(body: BlockNode, context: Context): Type {
        val returnSites = collectReturnSites(body, context)
        if (returnSites.isEmpty()) {
            return PrimitiveType.NIL
        }

        val returnSequences = returnSites.map(ReturnSite::values)
        val openTailTypes = returnSequences.mapNotNull(ValueSequence::variadicTail)
        val maxFixedArity = returnSequences.maxOf { it.fixed.size }
        val totalArity = if (openTailTypes.isEmpty()) maxFixedArity else maxOf(maxFixedArity, 1)

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
        when (statement) {
            is IfClause -> collectReturnTypes(statement.body, context, output)
            is ElseIfClause -> collectReturnTypes(statement.body, context, output)
            is ElseClause -> collectReturnTypes(statement.body, context, output)
            is FunctionDeclaration -> Unit
            else -> Unit
        }
    }

    private fun collectReturnTuple(arguments: List<ExpressionNode>, context: Context): ValueSequence {
        return ValueSequence.fromExpressionResults(arguments.map { evaluate(it, context) })
    }

    private fun resolveTypeSyntax(typeSyntax: TypeSyntax, context: TypeResolutionContext): Type = when (typeSyntax) {
        is NamedTypeSyntax -> resolveNamedType(typeSyntax.name, context)
        is LiteralTypeSyntax -> normalizeLiteral(typeSyntax.value)
        is UnionTypeSyntax -> unionTypeOf(typeSyntax.options.map { resolveTypeSyntax(it, context) })
        is IntersectionTypeSyntax -> intersectionTypeOf(typeSyntax.types.map { resolveTypeSyntax(it, context) })
        is ArrayTypeSyntax -> ArrayType(resolveTypeSyntax(typeSyntax.elementType, context))
        is GenericTypeSyntax -> resolveGenericType(typeSyntax, context)
        is NullableTypeSyntax -> unionTypeOf(resolveTypeSyntax(typeSyntax.innerType, context), PrimitiveType.NIL)
        is TupleTypeSyntax -> TupleType(typeSyntax.elements.map { resolveTypeSyntax(it, context) })
        is MultiReturnTypeSyntax -> MultiReturnType(typeSyntax.types.map { resolveTypeSyntax(it, context) })
        is VarargTypeSyntax -> VarargType(resolveTypeSyntax(typeSyntax.elementType, context))
        is FunctionTypeSyntax -> resolveFunctionTypeSyntax(typeSyntax, context)
        is ObjectTypeSyntax -> resolveObjectTypeSyntax(typeSyntax, context)
        is IndexTableTypeSyntax -> TableType(
            indexSignature = TableType.IndexSignature(
                keyType = resolveTypeSyntax(typeSyntax.keyType, context),
                valueType = resolveTypeSyntax(typeSyntax.valueType, context)
            )
        )
    }

    private fun resolveNamedType(name: String, context: TypeResolutionContext): Type {
        primitiveTypeFor(name)?.let { return it }
        val declaration = context.resolveTypeReference(name) ?: return io.github.dingyi222666.luaparser.semantic.types.model.CustomType(name)
        return if (declaration.kind == DeclarationKind.TYPE_PARAMETER) {
            TypeParameterType(
                name = declaration.name,
                constraint = declaration.declaredTypeSyntax?.let { resolveTypeSyntax(it, context) },
                defaultType = declaration.declaredType
            )
        } else {
            binder.declarationIndex.getDeclaration(declaration.id)?.declaredType
                ?: declaration.declaredType
                ?: io.github.dingyi222666.luaparser.semantic.types.model.CustomType(name)
        }
    }

    private fun resolveGenericType(typeSyntax: GenericTypeSyntax, context: TypeResolutionContext): Type {
        val baseName = (typeSyntax.baseType as? NamedTypeSyntax)?.name
            ?: resolveTypeSyntax(typeSyntax.baseType, context).displayName
        val arguments = typeSyntax.arguments.map { resolveTypeSyntax(it, context) }
        return if (baseName == "table" && arguments.size == 2) {
            TableType(indexSignature = TableType.IndexSignature(arguments[0], arguments[1]))
        } else {
            AppliedType(baseName = baseName, typeArguments = arguments)
        }
    }

    private fun resolveFunctionTypeSyntax(typeSyntax: FunctionTypeSyntax, context: TypeResolutionContext): FunctionType {
        val parameters = typeSyntax.parameters.map { parameter ->
            val baseType = resolveTypeSyntax(parameter.type, context)
            FunctionParameter(
                name = parameter.name ?: if (parameter.vararg) "..." else "_",
                type = if (parameter.vararg) VarargType(baseType) else baseType,
                optional = parameter.optional,
                vararg = parameter.vararg
            )
        }
        val returnType = resolveTypeSyntax(typeSyntax.returnType, context)
        return FunctionType(parameters = parameters, returnType = returnType)
    }

    private fun resolveObjectTypeSyntax(typeSyntax: ObjectTypeSyntax, context: TypeResolutionContext): Type {
        val fields = linkedMapOf<String, Type>()
        typeSyntax.fields.forEach { field ->
            val name = when (val fieldName = field.name) {
                is IdentifierObjectFieldNameSyntax -> fieldName.value
                is QuotedObjectFieldNameSyntax -> fieldName.literal.removeSurrounding("\"").removeSurrounding("'")
            }
            val valueType = resolveTypeSyntax(field.type, context)
            fields[name] = if (field.optional) unionTypeOf(valueType, PrimitiveType.NIL) else valueType
        }

        val indexSignature = typeSyntax.indexers.firstOrNull()?.let { indexer ->
            TableType.IndexSignature(
                keyType = resolveTypeSyntax(indexer.keyType, context),
                valueType = resolveTypeSyntax(indexer.valueType, context)
            )
        }
        return TableType(fields = fields, indexSignature = indexSignature)
    }

    private fun normalizeLiteral(value: String): Type {
        val normalized = value.trim()
        return when {
            normalized == "true" -> LiteralType(true, PrimitiveType.BOOLEAN)
            normalized == "false" -> LiteralType(false, PrimitiveType.BOOLEAN)
            normalized == "nil" -> PrimitiveType.NIL
            normalized.startsWith("\"") || normalized.startsWith("'") -> {
                LiteralType(normalized.removeSurrounding("\"").removeSurrounding("'"), PrimitiveType.STRING)
            }

            normalized.contains('.') -> LiteralType(normalized.toDoubleOrNull() ?: normalized, PrimitiveType.NUMBER)
            else -> LiteralType(normalized.toLongOrNull() ?: normalized, PrimitiveType.NUMBER)
        }
    }

    private fun primitiveTypeFor(name: String): Type? = when (name) {
        "string" -> PrimitiveType.STRING
        "number", "integer" -> PrimitiveType.NUMBER
        "boolean", "bool" -> PrimitiveType.BOOLEAN
        "nil", "void" -> PrimitiveType.NIL
        "function" -> PrimitiveType.FUNCTION
        "table" -> PrimitiveType.TABLE
        "thread" -> PrimitiveType.THREAD
        "userdata" -> PrimitiveType.USERDATA
        "any" -> PrimitiveType.ANY
        "unknown" -> UnknownType
        else -> null
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
        private const val LOADLAYOUT_COLLECT_NODE_BUDGET = 4_096
        private const val LOADLAYOUT_ID_TABLE_NODE_BUDGET = 512
        private const val LOADLAYOUT_ID_TABLE_MAX_DEPTH = 32
        private const val LOADLAYOUT_PARENT_WALK_LIMIT = 64

        private val LAYOUT_SPEC_KEYS = setOf(
            "id",
            "onClick",
            "onLongClick",
            "onItemClick",
            "onCheckedChanged",
            "layout_width",
            "layout_height",
            "layout_weight",
            "layout_margin",
            "layout_gravity",
            "padding",
            "text",
            "src",
            "background"
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

    /**
     * Identity-based set for AST node walks. identityHashCode alone can collide; buckets
     * store live references and use === so the same object is never re-entered.
     */
    private class IdentityHashSet {
        private val buckets = HashMap<Int, MutableList<Any>>()

        fun add(node: Any): Boolean {
            val code = System.identityHashCode(node)
            val bucket = buckets.getOrPut(code) { mutableListOf() }
            if (bucket.any { it === node }) {
                return false
            }
            bucket.add(node)
            return true
        }
    }
}
