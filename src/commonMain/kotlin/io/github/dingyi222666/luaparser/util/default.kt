package io.github.dingyi222666.luaparser.util

/**
 * @author: dingyi
 * @date: 2023/2/1
 * @description:
 **/

inline fun <T> T?.requireNotNull(): T {
    return requireNotNull(this)
}

inline fun <T> equalsMore(origin: T, vararg arg: T): Boolean {
    return arg.any { it == origin }
}


fun parseLuaString(text: String): String {
    //char or default

    val buffer = StringBuilder()
    var currentIndex = 0
    var isLongStringStart = false
    var isShortString = false
    var isLongStringEnd = false

    while (currentIndex < text.length) {
        val currentChar = text[currentIndex]

        if (isShortString && currentChar == '\\') {
            val nextChar = text.getOrNull(currentIndex + 1)
            if (nextChar == 'n') {
                buffer.append('\n')
            } else if (nextChar == 't') {
                buffer.append('\t')
            } else if (nextChar == 'r') {
                buffer.append('\r')
            } else if (nextChar == 'n') {
                buffer.append('\n')
            } else if (nextChar == 'f') {
                buffer.append('\u000c')
            } else if (nextChar == '\\') {
                buffer.append('\\')
            } else if (nextChar == '\'') {
                buffer.append('\'')
            } else if (nextChar == '"') {
                buffer.append('"')
            } else if (nextChar == ']') {
                buffer.append(']')
            } else if (nextChar == '[') {
                buffer.append('[')
            } else if (nextChar == '0') {
                buffer.append('\u0000')
            } else if (nextChar == 'x') {
                buffer.append(text.substring(currentIndex + 2, currentIndex + 4).toInt(16).toChar())
                currentIndex += 4
                continue
            } else if (nextChar == 'd') {
                buffer.append(text.substring(currentIndex + 2, currentIndex + 4).toInt(8).toChar())
                currentIndex += 4
                continue
            } else if (nextChar == 'u') {
                val charCode = text.substring(currentIndex + 2, currentIndex + 6).toInt(16)
                buffer.append(charCode.toChar())
                currentIndex += 6
                continue
            } else {
                throw IllegalArgumentException("Illegal escape character: $nextChar")
            }

            currentIndex += 2

            continue
        }

        if (currentChar == '\'' || currentChar == '"') {
            if (!isShortString && !isLongStringStart) {
                isShortString = true
            } else {
                break
            }

            currentIndex++
            continue
        }

        if (currentChar == '[' && !isShortString) {
            if (!isLongStringStart && text.getOrNull(currentIndex + 1) == '=') {
                isLongStringStart = true
            } else if (isLongStringStart && text.getOrNull(currentIndex - 1) == '=') {
                isLongStringStart = false
            }

            currentIndex++
            continue
        }

        if ((currentChar == '=') and !isShortString and (isLongStringStart or isLongStringEnd)) {
            currentIndex++
            continue
        }

        if (currentChar == ']' && !isShortString) {
            if (!isLongStringEnd && text.getOrNull(currentIndex + 1) == '=') {
                isLongStringEnd = true
            } else if (isLongStringEnd && text.getOrNull(currentIndex - 1) == '=') {
                break
            }

            currentIndex++
            continue
        }



        buffer.append(currentChar)

        currentIndex++

    }

    return buffer.toString()

}