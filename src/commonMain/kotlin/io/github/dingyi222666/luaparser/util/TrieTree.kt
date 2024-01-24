package io.github.dingyi222666.luaparser.util

import kotlin.math.abs
import kotlin.math.max

// https://github.com/Rosemoe/sora-editor/blob/main/editor/src/main/java/io/github/rosemoe/sora/util/TrieTree.java#L30


/**
 * @author Rose
 * TrieTree to query values quickly
 */
class TrieTree<T> {
    val root: Node<T>
    private var maxLen = 0

    init {
        root = Node()
    }

    fun put(v: String, token: T) {
        maxLen = max(v.length.toDouble(), maxLen.toDouble()).toInt()
        addInternal(root, v, 0, v.length, token)
    }

    fun put(v: CharSequence, off: Int, len: Int, token: T) {
        maxLen = max(maxLen.toDouble(), len.toDouble()).toInt()
        addInternal(root, v, off, len, token)
    }

    fun get(s: CharSequence, offset: Int, len: Int): T? {
        if (len > maxLen) {
            return null
        }
        return getInternal(root, s, offset, len)
    }

    private fun getInternal(node: Node<T>, s: CharSequence, offset: Int, len: Int): T? {
        if (len == 0) {
            return node.token
        }
        val point = s[offset]
        val sub = node.map.get(point) ?: return null
        return getInternal(sub, s, offset + 1, len - 1)
    }

    private fun addInternal(node: Node<T>, v: CharSequence, i: Int, len: Int, token: T) {
        val point = v[i]
        var sub = node.map.get(point)
        if (sub == null) {
            sub = Node()
            node.map.put(point, sub)
        }
        if (len == 1) {
            sub.token = token
        } else {
            addInternal(sub, v, i + 1, len - 1, token)
        }
    }

    class Node<T> {
        val map: HashCharMap<Node<T>> = HashCharMap()

        var token: T? = null
    }

    /**
     * Hashmap with fixed length
     *
     * @author Rosemoe
     */
    class HashCharMap<V> {
        private val columns = arrayOfNulls<LinkedPair<V>?>(CAPACITY)
        private val ends = arrayOfNulls<LinkedPair<V>?>(CAPACITY)


        fun get(first: Char): V? {
            val position = position(first.code)
            var pair = columns[position]
            while (pair != null) {
                if (pair.first == first) {
                    return pair.second
                }
                pair = pair.next
            }
            return null
        }

        private fun get(first: Char, position: Int): LinkedPair<V>? {
            var pair = columns[position]
            while (pair != null) {
                if (pair.first == first) {
                    return pair
                }
                pair = pair.next
            }
            return null
        }

        fun put(first: Char, second: V) {
            val position = position(first.code)
            if (ends[position] == null) {
                val pair = LinkedPair<V>()
                ends[position] = pair
                columns[position] = ends[position]
                pair.first = first
                pair.second = second
                return
            }
            var p = get(first, position)
            if (p == null) {
                ends[position]!!.next = LinkedPair()
                p = ends[position]!!.next
                ends[position] = p
            }
            p!!.first = first
            p.second = second
        }


        companion object {
            private const val CAPACITY = 64
            private fun position(first: Int): Int {
                return (abs((first xor (first shl 6) * (if ((first and 1) != 0) 3 else 1)).toDouble()) % CAPACITY).toInt()
            }
        }
    }


    class LinkedPair<V> {
        var next: LinkedPair<V>? = null

        var first: Char = 0.toChar()

        var second: V? = null
    }
}
