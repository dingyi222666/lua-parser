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
import io.github.dingyi222666.luaparser.semantic.types.model.ErrorType
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionParameter
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.LiteralType
import io.github.dingyi222666.luaparser.semantic.types.model.MultiReturnType
import io.github.dingyi222666.luaparser.semantic.types.model.ModuleType
import io.github.dingyi222666.luaparser.semantic.types.model.NeverType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.model.TableType
import io.github.dingyi222666.luaparser.semantic.types.model.TupleType
import io.github.dingyi222666.luaparser.semantic.types.model.Type
import io.github.dingyi222666.luaparser.semantic.types.model.TypeParameterType
import io.github.dingyi222666.luaparser.semantic.types.model.UnknownType
import io.github.dingyi222666.luaparser.semantic.types.model.VarargType
import io.github.dingyi222666.luaparser.semantic.types.model.CustomType
import io.github.dingyi222666.luaparser.semantic.types.resolve.intersectionTypeOf
import io.github.dingyi222666.luaparser.semantic.types.resolve.overloadedFunctionOf
import io.github.dingyi222666.luaparser.semantic.types.resolve.unionTypeOf

fun LegacyType.toModelType(): Type = when (this) {
    LegacyUnknownType -> UnknownType
    LegacyErrorType -> ErrorType
    LegacyNeverType -> NeverType
    is LegacyPrimitiveType -> PrimitiveType(name = name, kind = kind.toModelKind())
    is LegacyLiteralType -> LiteralType(value = value, baseType = baseType.toModelType() as PrimitiveType)
    is LegacyFunctionType -> FunctionType(
        parameters = parameters.map(LegacyParameterType::toModelParameter),
        returnType = returnType.toModelType()
    )
    is LegacyOverloadedFunctionType -> overloadedFunctionOf(callSignatures.map { it.toModelType() as FunctionType })
    is LegacyTableType -> TableType(
        fields = fields.mapValues { (_, value) -> value.toModelType() },
        methods = methods.mapValues { (_, value) -> value.toModelType() },
        indexSignature = indexSignature?.let {
            TableType.IndexSignature(
                keyType = it.keyType.toModelType(),
                valueType = it.valueType.toModelType()
            )
        }
    )
    is LegacyModuleType -> ModuleType(
        moduleName = moduleName,
        fields = fields.mapValues { (_, value) -> value.toModelType() },
        methods = methods.mapValues { (_, value) -> value.toModelType() },
        indexSignature = indexSignature?.let {
            ModuleType.IndexSignature(
                keyType = it.keyType.toModelType(),
                valueType = it.valueType.toModelType()
            )
        }
    )
    is LegacyUnionType -> unionTypeOf(types.map { it.toModelType() })
    is LegacyIntersectionType -> intersectionTypeOf(types.map { it.toModelType() })
    is LegacyVarArgType -> when {
        types.size == 1 -> VarargType(types.first().toModelType())
        else -> VarargType(TupleType(types.map { it.toModelType() }))
    }
    is LegacyMultiReturnType -> MultiReturnType(types.map { it.toModelType() })
    is LegacyTypeParameterType -> TypeParameterType(name = symbolName, constraint = constraint?.toModelType())
    is LegacyGenericType -> AppliedType(baseName = baseName, typeArguments = typeParameters.map { it.toModelType() })
    is LegacyArrayType -> ArrayType(elementType.toModelType())
    is LegacyTupleType -> TupleType(elementTypes.map { it.toModelType() })
    is LegacyCustomType -> CustomType(name)
    is LegacyAliasType -> AliasType(name = name, target = target.toModelType())
    is LegacyClassType -> ClassType(
        name = name,
        fields = fields.mapValues { (_, value) -> value.toModelType() },
        methods = methods.mapValues { (_, value) -> value.toModelType() },
        superClass = parent?.toModelType() as? ClassType,
        typeParameters = typeParameters.mapNotNull { it.toModelType() as? TypeParameterType }
    )
    else -> CustomType(name)
}

private fun LegacyPrimitiveType.Kind.toModelKind(): PrimitiveType.Kind = when (this) {
    LegacyPrimitiveType.Kind.NIL -> PrimitiveType.Kind.NIL
    LegacyPrimitiveType.Kind.NUMBER -> PrimitiveType.Kind.NUMBER
    LegacyPrimitiveType.Kind.STRING -> PrimitiveType.Kind.STRING
    LegacyPrimitiveType.Kind.BOOLEAN -> PrimitiveType.Kind.BOOLEAN
    LegacyPrimitiveType.Kind.THREAD -> PrimitiveType.Kind.THREAD
    LegacyPrimitiveType.Kind.USERDATA -> PrimitiveType.Kind.USERDATA
    LegacyPrimitiveType.Kind.ANY -> PrimitiveType.Kind.ANY
    LegacyPrimitiveType.Kind.UNKNOWN -> PrimitiveType.Kind.UNKNOWN
}

private fun LegacyParameterType.toModelParameter(): FunctionParameter = FunctionParameter(
    name = name,
    type = type.toModelType(),
    optional = optional,
    vararg = vararg
)
