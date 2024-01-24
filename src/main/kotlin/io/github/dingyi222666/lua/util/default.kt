package io.github.dingyi222666.lua.util

/**
 * @author: dingyi
 * @date: 2023/2/1
 * @description:
 **/

fun <T> T?.requireNotNull(): T {
    return requireNotNull(this)
}

fun <T> equalsMore(origin: T, vararg arg: T): Boolean {
    for (v in arg) {
        if (origin == v) {
            return true
        }
    }
    return false
}
