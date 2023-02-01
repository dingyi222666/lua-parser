package io.github.dingyi222666.parser.lua.util

/**
 * @author: dingyi
 * @date: 2023/2/1
 * @description:
 **/

inline fun <T> T?.require(): T {
    return this!!
}