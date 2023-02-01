package io.github.dingyi222666.lua.parser.util

/**
 * @author: dingyi
 * @date: 2023/2/1
 * @description:
 **/

inline fun <T> T?.require(): T {
    return this!!
}