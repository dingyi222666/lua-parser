package io.github.dingyi222666.luaparser.util

// https://blog.rosemoe.cyou/2020/02/15/highlight-editor-creation/#%E4%BC%98%E5%8C%96
object Character {
    private val state_start: IntArray = IntArray(2048) { 0 }

    private val state_part: IntArray = IntArray(2048) { 0 }

    private var isInit = false

    private fun get(values: IntArray, bitIndex: Int): Boolean {
        return ((values[bitIndex / 32] and (1 shl (bitIndex % 32))) != 0)
    }

    private fun set(values: IntArray, bitIndex: Int) {
        values[bitIndex / 32] = values[bitIndex / 32] or (1 shl (bitIndex % 32))
    }

    fun initMap() {
        if (isInit) {
            return
        }


        for (i in 0..65535) {
            if (i > '\u0080'.code) {
                set(state_part, i)
                set(state_start, i)
            }

            //  ((CharRange('a', 'z') + CharRange('A', 'Z')).toSet())
            if ((i > 'a'.code && i <= 'z'.code) || (i > 'A'.code && i <= 'Z'.code)) {

                set(state_start, i)

                if (i >= '0'.code && i <= '9'.code) {
                    set(state_part, i)
                }
            }
        }

        isInit = true
    }

    fun isJavaIdentifierPart(key: Char): Boolean {
        return get(state_part, key.code)
    }

    fun isJavaIdentifierStart(key: Char): Boolean {
        return get(state_start, key.code)
    }
}