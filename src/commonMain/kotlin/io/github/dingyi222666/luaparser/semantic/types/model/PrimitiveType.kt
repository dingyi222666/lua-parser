package io.github.dingyi222666.luaparser.semantic.types.model

data class PrimitiveType(
    override val name: String,
    val kind: Kind
) : Type {
    enum class Kind {
        VOID,
        NIL,
        BOOLEAN,
        NUMBER,
        STRING,
        FUNCTION,
        TABLE,
        THREAD,
        USERDATA,
        ANY,
        UNKNOWN,
        NEVER,
        ERROR
    }

    companion object {
        val VOID = PrimitiveType("void", Kind.VOID)
        val NIL = PrimitiveType("nil", Kind.NIL)
        val BOOLEAN = PrimitiveType("boolean", Kind.BOOLEAN)
        val NUMBER = PrimitiveType("number", Kind.NUMBER)
        val STRING = PrimitiveType("string", Kind.STRING)
        val FUNCTION = PrimitiveType("function", Kind.FUNCTION)
        val TABLE = PrimitiveType("table", Kind.TABLE)
        val THREAD = PrimitiveType("thread", Kind.THREAD)
        val USERDATA = PrimitiveType("userdata", Kind.USERDATA)
        val ANY = PrimitiveType("any", Kind.ANY)
        val UNKNOWN = PrimitiveType("unknown", Kind.UNKNOWN)
        val NEVER = PrimitiveType("never", Kind.NEVER)
        val ERROR = PrimitiveType("error", Kind.ERROR)
    }
}

/**
 * The single shared Kind -> sentinel mapping. TypeNormalizer.canonicalizePrimitive and
 * TypeRelations previously carried two more copies of this ladder (one byte-identical);
 * both now route through here. Callers that must collapse UNKNOWN/NEVER/ERROR onto the
 * UnknownType/NeverType/ErrorType objects do so on top of this mapping.
 */
internal fun PrimitiveType.canonicalByKind(): PrimitiveType = when (kind) {
    PrimitiveType.Kind.VOID -> PrimitiveType.VOID
    PrimitiveType.Kind.NIL -> PrimitiveType.NIL
    PrimitiveType.Kind.BOOLEAN -> PrimitiveType.BOOLEAN
    PrimitiveType.Kind.NUMBER -> PrimitiveType.NUMBER
    PrimitiveType.Kind.STRING -> PrimitiveType.STRING
    PrimitiveType.Kind.FUNCTION -> PrimitiveType.FUNCTION
    PrimitiveType.Kind.TABLE -> PrimitiveType.TABLE
    PrimitiveType.Kind.THREAD -> PrimitiveType.THREAD
    PrimitiveType.Kind.USERDATA -> PrimitiveType.USERDATA
    PrimitiveType.Kind.ANY -> PrimitiveType.ANY
    PrimitiveType.Kind.UNKNOWN -> PrimitiveType.UNKNOWN
    PrimitiveType.Kind.NEVER -> PrimitiveType.NEVER
    PrimitiveType.Kind.ERROR -> PrimitiveType.ERROR
}

object UnknownType : Type {
    override val name: String = PrimitiveType.UNKNOWN.name
}

object ErrorType : Type {
    override val name: String = PrimitiveType.ERROR.name
}

object NeverType : Type {
    override val name: String = PrimitiveType.NEVER.name
}
