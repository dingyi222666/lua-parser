package io.github.dingyi222666.lua.parser.util

/**
 * @author: dingyi
 * @date: 2023/2/1
 * @description:
 **/

inline fun <T> T?.require(): T {
    return this!!
}

fun <T> equalsMore(origin: T, vararg arg: T): Boolean {
    for (v in arg) {
        if (origin == v) {
            return true
        }
    }
    return false
}
