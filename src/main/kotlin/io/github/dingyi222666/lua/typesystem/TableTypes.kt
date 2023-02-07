package io.github.dingyi222666.lua.typesystem

/**
 * @author: dingyi
 * @date: 2023/2/6
 * @description:
 **/

open class TableType(
    override val kind: TypeKind = TypeKind.Table,
    val tableName: String
) : Type {

    override val typeVariableName: String
        get() = tableName

    val fields = mutableMapOf<String, Type>()

    var indexType: Type = Type.ANY
    var valueType: Type = Type.ANY

    override fun getTypeName(): String {
        return "table<${indexType.getSimpleTypeName()},${valueType.getSimpleTypeName()}>"
    }

    override fun getSimpleTypeName(): String {
        return "table"
    }

    override fun subTypeOf(type: Type): Boolean {
        return when (type) {
            is TableType -> {
                fields.all { (k, v) ->
                    type.fields[k]?.subTypeOf(v) ?: false
                } && indexType.subTypeOf(type.indexType) && valueType.subTypeOf(type.valueType)
            }

            is UnionType -> type.types.any { it.subTypeOf(this) }
            else -> false
        }
    }


    fun setMember(name: String, type: Type) {
        fields[name] = type
    }

    fun setMember(name: String, keyType: Type, type: Type) {
        setMember(name, type)
        indexType = indexType.union(keyType)
        valueType = valueType.union(type)
    }


    fun isMember(name: String): Boolean {
        return fields.containsKey(name)
    }


    fun searchMember(name: String): Type? {
        return fields[name]
    }

    fun removeMember(name: String) {
        fields.remove(name)
    }

    override fun equals(other: Any?): Boolean {
        if (other is TableType) {
            return fields == other.fields && indexType == other.indexType && valueType == other.valueType
        }
        return false
    }

    override fun hashCode(): Int {
        var result = fields.hashCode()
        result = 31 * result + indexType.hashCode()
        result = 31 * result + valueType.hashCode()
        return result
    }

    override fun toString(): String {
        return "TableType(fields=$fields, indexType=$indexType, valueType=$valueType)"
    }
}


class UnknownLikeTableType(
    unknownName: String
) : TableType(TypeKind.Unknown, unknownName) {

    override val typeVariableName: String
        get() = tableName

    override fun getSimpleTypeName(): String {
        return "unknown"
    }

    override fun getTypeName(): String {
        return "unknown"
    }
}