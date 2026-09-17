package io.github.dingyi222666.luaparser.interop.dex

/**
 * Little-endian, offset-seekable byte reader over the raw bytes of a dex file.
 *
 * Covers every primitive the dex format uses in the sections consumed by [parseDex]:
 * fixed-width `u1`/`u2`/`u4`, the variable-width `uleb128`/`sleb128`/`uleb128p1` encodings,
 * and MUTF-8 (modified UTF-8, the Dalvik string_data_item variant) decoding.
 *
 * Failure model: this reader never throws. A read that runs past the end of [data], or a
 * malformed variable-width/MUTF-8 value, sets [failed] (plus [failure]) and returns `0`/`null`
 * for that read; callers check [failed] and record a diagnostic. Subsequent reads keep
 * returning degraded values until the caller re-seeks.
 *
 * All multi-byte values are little-endian per the dex specification.
 */
internal class DexByteReader(
    private val data: ByteArray
) {
    /** Absolute position of the next read, in bytes. May point past [data]; reads then fail. */
    var position: Int = 0

    /** True once any read has failed; reset per region via [resetFailure]. */
    var failed: Boolean = false
        private set

    /** Human-readable reason of the most recent failure, or null. */
    var failure: String? = null
        private set

    val size: Int get() = data.size

    val remaining: Int get() = data.size - position

    /** Moves the read cursor to [offset]; negative offsets clamp to 0. */
    fun seek(offset: Int) {
        position = if (offset < 0) 0 else offset
    }

    /** Clears the [failed]/[failure] state so a new region can be read independently. */
    fun resetFailure() {
        failed = false
        failure = null
    }

    /** Reads one unsigned byte (0..255). */
    fun readU1(): Int {
        if (position >= data.size) {
            return fail("read past end of data at offset $position")
        }
        return data[position++].toInt() and 0xFF
    }

    /** Reads one unsigned 16-bit little-endian value. */
    fun readU2(): Int {
        val lo = readU1()
        if (failed) return 0
        val hi = readU1()
        if (failed) return 0
        return lo or (hi shl 8)
    }

    /**
     * Reads one unsigned 32-bit little-endian value. Values >= 2^31 come back negative
     * (two's complement); dex offsets/sizes that large are treated as out of bounds upstream.
     */
    fun readU4(): Int {
        val b0 = readU1()
        if (failed) return 0
        val b1 = readU1()
        if (failed) return 0
        val b2 = readU1()
        if (failed) return 0
        val b3 = readU1()
        if (failed) return 0
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    /**
     * Reads an unsigned LEB128 value (dex string/data sizes and indices). Accepts at most
     * 5 bytes; bits above the low 32 are truncated.
     */
    fun readUleb128(): Int {
        var result = 0
        var shift = 0
        while (true) {
            val byte = readU1()
            if (failed) return 0
            result = result or ((byte and 0x7F) shl shift)
            if (byte and 0x80 == 0) return result
            shift += 7
            if (shift >= 35) return fail("uleb128 exceeds 5 bytes at offset ${position - 1}")
        }
    }

    /**
     * Reads a signed LEB128 value (used by encoded catch handlers and debug info; implemented
     * for completeness of the dalvik primitive set). The result is sign-extended from the
     * highest consumed bit group.
     */
    fun readSleb128(): Int {
        var result = 0
        var shift = 0
        var byte: Int
        do {
            byte = readU1()
            if (failed) return 0
            result = result or ((byte and 0x7F) shl shift)
            shift += 7
        } while (byte and 0x80 != 0 && shift < 35)
        if (byte and 0x80 != 0) {
            return fail("sleb128 exceeds 5 bytes at offset ${position - 1}")
        }
        if (shift < 32) {
            // Arithmetic sign extension of the last, partially used byte group.
            result = result shl (32 - shift) shr (32 - shift)
        }
        return result
    }

    /** Reads an unsigned LEB128 value biased by +1 (`uleb128p1`, used in dex try/debug items). */
    fun readUleb128p1(): Int = readUleb128() - 1

    /**
     * Reads one MUTF-8 encoded string terminated by a single 0x00 byte, as stored in a
     * string_data_item (the item begins with a uleb128 `utf16_size` that the caller reads
     * first and passes as [maxUtf16Units]).
     *
     * Dalvik MUTF-8 specifics handled here:
     * - the NUL code point is encoded as the two bytes 0xC0 0x80; a raw 0x00 byte only ever
     *   appears as the terminator,
     * - two-byte sequences must not be overlong (decoded value >= 0x80),
     * - code points U+0800..U+FFFF use 3-byte sequences; per CESU-8, UTF-16 surrogate
     *   halves appear as two such 3-byte sequences and are kept as surrogate chars,
     * - bytes 0x80..0xBF and 0xF0..0xFF, truncated sequences and missing terminators are
     *   malformed.
     *
     * @return the decoded string, or null (with [failed] set) on truncation or malformed
     *         bytes - callers record a diagnostic instead of this ever throwing.
     */
    fun readMutf8(maxUtf16Units: Int = Int.MAX_VALUE): String? {
        val builder = StringBuilder()
        while (true) {
            if (position >= data.size) {
                fail("MUTF-8 string not terminated before end of data")
                return null
            }
            val b0 = data[position++].toInt() and 0xFF
            when {
                b0 == 0x00 -> return builder.toString() // raw NUL = terminator
                b0 <= 0x7F -> builder.append(b0.toChar())
                b0 < 0xC0 -> {
                    fail("invalid MUTF-8 byte 0x${b0.toString(16)} at offset ${position - 1}")
                    return null
                }
                b0 <= 0xDF -> {
                    val b1 = readContinuation() ?: return null
                    val codePoint = ((b0 and 0x1F) shl 6) or b1
                    if (codePoint == 0 && b0 == 0xC0) {
                        // 0xC0 0x80 is the MUTF-8 encoding of U+0000.
                        builder.append(mutf8Null())
                    } else if (codePoint < 0x80) {
                        fail("overlong MUTF-8 2-byte sequence at offset ${position - 2}")
                        return null
                    } else {
                        builder.append(codePoint.toChar())
                    }
                }
                b0 <= 0xEF -> {
                    val b1 = readContinuation() ?: return null
                    val b2 = readContinuation() ?: return null
                    val codePoint = ((b0 and 0x0F) shl 12) or (b1 shl 6) or b2
                    if (codePoint < 0x800) {
                        fail("overlong MUTF-8 3-byte sequence at offset ${position - 3}")
                        return null
                    }
                    builder.append(codePoint.toChar())
                }
                else -> {
                    fail("invalid MUTF-8 lead byte 0x${b0.toString(16)} at offset ${position - 1}")
                    return null
                }
            }
            if (builder.length > maxUtf16Units) {
                fail("MUTF-8 string longer than declared utf16_size $maxUtf16Units")
                return null
            }
        }
    }

    /** The U+0000 char, built numerically so this source file stays NUL-free. */
    private fun mutf8Null(): Char = Char(0)

    /**
     * Reads one 0x80..0xBF continuation byte and returns its low 6 bits, or null (with
     * [failed] set) on anything else / end of data.
     */
    private fun readContinuation(): Int? {
        if (position >= data.size) {
            fail("truncated MUTF-8 sequence at offset $position")
            return null
        }
        val b = data[position++].toInt() and 0xFF
        if (b and 0xC0 != 0x80) {
            fail("invalid MUTF-8 continuation byte 0x${b.toString(16)} at offset ${position - 1}")
            return null
        }
        return b and 0x3F
    }

    /** Marks the failure state; conventional degraded results are 0 / null. */
    private fun fail(message: String): Int {
        failed = true
        failure = message
        return 0
    }
}
