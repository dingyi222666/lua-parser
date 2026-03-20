package io.github.dingyi222666.luaparser.semantic.types.model

data class PrimitiveType(
    override val name: String,
    val kind: Kind
) : Type {
    enum class Kind {
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

object UnknownType : Type {
    override val name: String = PrimitiveType.UNKNOWN.name
}

object ErrorType : Type {
    override val name: String = PrimitiveType.ERROR.name
}

object NeverType : Type {
    override val name: String = PrimitiveType.NEVER.name
}
