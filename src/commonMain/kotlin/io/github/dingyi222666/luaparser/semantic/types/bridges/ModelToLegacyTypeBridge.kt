package io.github.dingyi222666.luaparser.semantic.types.bridges

import io.github.dingyi222666.luaparser.semantic.types.AliasType as LegacyAliasType
import io.github.dingyi222666.luaparser.semantic.types.ArrayType as LegacyArrayType
import io.github.dingyi222666.luaparser.semantic.types.ClassType as LegacyClassType
import io.github.dingyi222666.luaparser.semantic.types.CustomType as LegacyCustomType
import io.github.dingyi222666.luaparser.semantic.types.ErrorType as LegacyErrorType
import io.github.dingyi222666.luaparser.semantic.types.FunctionType as LegacyFunctionType
import io.github.dingyi222666.luaparser.semantic.types.GenericType as LegacyGenericType
import io.github.dingyi222666.luaparser.semantic.types.IntersectionType as LegacyIntersectionType
import io.github.dingyi222666.luaparser.semantic.types.LiteralType as LegacyLiteralType
import io.github.dingyi222666.luaparser.semantic.types.MultiReturnType as LegacyMultiReturnType
import io.github.dingyi222666.luaparser.semantic.types.ModuleType as LegacyModuleType
import io.github.dingyi222666.luaparser.semantic.types.NeverType as LegacyNeverType
import io.github.dingyi222666.luaparser.semantic.types.OverloadedFunctionType as LegacyOverloadedFunctionType
import io.github.dingyi222666.luaparser.semantic.types.ParameterType as LegacyParameterType
import io.github.dingyi222666.luaparser.semantic.types.PrimitiveType as LegacyPrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.TableType as LegacyTableType
import io.github.dingyi222666.luaparser.semantic.types.TupleType as LegacyTupleType
import io.github.dingyi222666.luaparser.semantic.types.Type as LegacyType
import io.github.dingyi222666.luaparser.semantic.types.TypeParameterType as LegacyTypeParameterType
import io.github.dingyi222666.luaparser.semantic.types.UnionType as LegacyUnionType
import io.github.dingyi222666.luaparser.semantic.types.UnknownType as LegacyUnknownType
import io.github.dingyi222666.luaparser.semantic.types.VarArgType as LegacyVarArgType
import io.github.dingyi222666.luaparser.semantic.types.model.AliasType
import io.github.dingyi222666.luaparser.semantic.types.model.AppliedType
import io.github.dingyi222666.luaparser.semantic.types.model.ArrayType
import io.github.dingyi222666.luaparser.semantic.types.model.ClassType
import io.github.dingyi222666.luaparser.semantic.types.model.CustomType
import io.github.dingyi222666.luaparser.semantic.types.model.ErrorType
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionParameter
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.IntersectionType
import io.github.dingyi222666.luaparser.semantic.types.model.LiteralType
import io.github.dingyi222666.luaparser.semantic.types.model.MultiReturnType
import io.github.dingyi222666.luaparser.semantic.types.model.ModuleType
import io.github.dingyi222666.luaparser.semantic.types.model.NeverType
import io.github.dingyi222666.luaparser.semantic.types.model.OverloadedFunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.model.TableType
import io.github.dingyi222666.luaparser.semantic.types.model.TupleType
import io.github.dingyi222666.luaparser.semantic.types.model.Type
import io.github.dingyi222666.luaparser.semantic.types.model.TypeParameterType
import io.github.dingyi222666.luaparser.semantic.types.model.UnionType
import io.github.dingyi222666.luaparser.semantic.types.model.UnknownType
import io.github.dingyi222666.luaparser.semantic.types.model.VarargType

fun Type.toLegacyType(): LegacyType = when (this) {
    UnknownType -> LegacyUnknownType
    ErrorType -> LegacyErrorType
    NeverType -> LegacyNeverType
    is PrimitiveType -> PrimitiveTypeBridge.toLegacy(this)
    is LiteralType -> LegacyLiteralType(value = value, baseType = PrimitiveTypeBridge.toLegacyPrimitive(baseType))
    is FunctionType -> LegacyFunctionType(
        parameters = parameters.map(FunctionParameter::toLegacyParameter),
        returnType = returnType.toLegacyType()
    )
    is OverloadedFunctionType -> LegacyOverloadedFunctionType(callSignatures.map { it.toLegacyType() as LegacyFunctionType })
    is TableType -> LegacyTableType(
        fields = fields.mapValues { (_, value) -> value.toLegacyType() },
        methods = methods.mapValues { (_, value) -> value.toLegacyType() },
        indexSignature = indexSignature?.let {
            LegacyTableType.IndexSignature(
                keyType = it.keyType.toLegacyType(),
                valueType = it.valueType.toLegacyType()
            )
        }
    )
    is ModuleType -> LegacyModuleType(
        moduleName = moduleName,
        fields = fields.mapValues { (_, value) -> value.toLegacyType() },
        methods = methods.mapValues { (_, value) -> value.toLegacyType() },
        indexSignature = indexSignature?.let {
            LegacyModuleType.IndexSignature(
                keyType = it.keyType.toLegacyType(),
                valueType = it.valueType.toLegacyType()
            )
        }
    )
    is UnionType -> LegacyUnionType(types.map(Type::toLegacyType).toSet())
    is IntersectionType -> LegacyIntersectionType(types.map(Type::toLegacyType).toSet())
    is TupleType -> LegacyTupleType(elementTypes.map(Type::toLegacyType))
    is MultiReturnType -> LegacyMultiReturnType(types.map(Type::toLegacyType))
    is VarargType -> LegacyVarArgType(listOf(elementType.toLegacyType()))
    is ArrayType -> LegacyArrayType(elementType.toLegacyType())
    is AliasType -> LegacyAliasType(name = name, target = target.toLegacyType())
    is TypeParameterType -> LegacyTypeParameterType(symbolName = name, constraint = constraint?.toLegacyType())
    is AppliedType -> LegacyGenericType(baseName = baseName, typeParameters = typeArguments.map(Type::toLegacyType))
    is ClassType -> LegacyClassType(
        name = name,
        fields = fields.mapValues { (_, value) -> value.toLegacyType() },
        methods = methods.mapValues { (_, value) -> value.toLegacyType() },
        parent = superClass?.toLegacyType() as? LegacyClassType,
        typeParameters = typeParameters.map { it.toLegacyType() }
    )
    is CustomType -> LegacyCustomType(name)
}

private fun FunctionParameter.toLegacyParameter(): LegacyParameterType = LegacyParameterType(
    name = name,
    type = type.toLegacyType(),
    optional = optional,
    vararg = vararg
)

private object PrimitiveTypeBridge {
    fun toLegacy(type: PrimitiveType): LegacyType {
        return when (type.kind) {
            PrimitiveType.Kind.NIL -> LegacyPrimitiveType.NIL
            PrimitiveType.Kind.BOOLEAN -> LegacyPrimitiveType.BOOLEAN
            PrimitiveType.Kind.NUMBER -> LegacyPrimitiveType.NUMBER
            PrimitiveType.Kind.STRING -> LegacyPrimitiveType.STRING
            PrimitiveType.Kind.FUNCTION -> LegacyCustomType(type.name)
            PrimitiveType.Kind.TABLE -> LegacyCustomType(type.name)
            PrimitiveType.Kind.THREAD -> LegacyPrimitiveType.THREAD
            PrimitiveType.Kind.USERDATA -> LegacyPrimitiveType.USERDATA
            PrimitiveType.Kind.ANY -> LegacyPrimitiveType.ANY
            PrimitiveType.Kind.UNKNOWN -> LegacyPrimitiveType.UNKNOWN
            PrimitiveType.Kind.NEVER -> LegacyNeverType
            PrimitiveType.Kind.ERROR -> LegacyErrorType
        }
    }

    fun toLegacyPrimitive(type: PrimitiveType): LegacyPrimitiveType {
        return when (type.kind) {
            PrimitiveType.Kind.NIL -> LegacyPrimitiveType.NIL
            PrimitiveType.Kind.BOOLEAN -> LegacyPrimitiveType.BOOLEAN
            PrimitiveType.Kind.NUMBER -> LegacyPrimitiveType.NUMBER
            PrimitiveType.Kind.STRING -> LegacyPrimitiveType.STRING
            PrimitiveType.Kind.THREAD -> LegacyPrimitiveType.THREAD
            PrimitiveType.Kind.USERDATA -> LegacyPrimitiveType.USERDATA
            PrimitiveType.Kind.ANY -> LegacyPrimitiveType.ANY
            PrimitiveType.Kind.UNKNOWN,
            PrimitiveType.Kind.FUNCTION,
            PrimitiveType.Kind.TABLE,
            PrimitiveType.Kind.NEVER,
            PrimitiveType.Kind.ERROR -> LegacyPrimitiveType.UNKNOWN
        }
    }
}
