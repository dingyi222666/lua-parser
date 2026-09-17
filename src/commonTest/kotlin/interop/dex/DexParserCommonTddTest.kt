package interop.dex

import io.github.dingyi222666.luaparser.interop.dex.DexByteReader
import io.github.dingyi222666.luaparser.interop.dex.DexClass
import io.github.dingyi222666.luaparser.interop.dex.DexField
import io.github.dingyi222666.luaparser.interop.dex.DexMethod
import io.github.dingyi222666.luaparser.interop.dex.DexParseResult
import io.github.dingyi222666.luaparser.interop.dex.parseDex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TDD-style tests for the pure-Kotlin dex parser: a minimal single-class dex is
 * hand-assembled byte-by-byte per the official Dalvik specification (checksum and
 * signature left as zeros, map_list omitted) and then parsed back.
 */
class DexParserCommonTddTest {

    // ---------------------------------------------------------------- builders

    /** Tiny little-endian byte sink for assembling a dex by hand. */
    private class DexWriter {
        val out = ArrayList<Byte>(512)

        val size: Int get() = out.size

        fun u1(v: Int) {
            out.add(v.toByte())
        }

        fun u2(v: Int) {
            u1(v and 0xFF)
            u1((v shr 8) and 0xFF)
        }

        fun u4(v: Int) {
            u2(v and 0xFFFF)
            u2((v ushr 16) and 0xFFFF)
        }

        fun uleb(value: Int) {
            var v = value
            do {
                var b = v and 0x7F
                v = v ushr 7
                if (v != 0) b = b or 0x80
                u1(b)
            } while (v != 0)
        }

        fun bytes(bs: ByteArray) {
            for (b in bs) out.add(b)
        }

        fun align4() {
            while (out.size % 4 != 0) u1(0)
        }

        fun patch4(offset: Int, v: Int) {
            out[offset] = (v and 0xFF).toByte()
            out[offset + 1] = ((v shr 8) and 0xFF).toByte()
            out[offset + 2] = ((v shr 16) and 0xFF).toByte()
            out[offset + 3] = ((v shr 24) and 0xFF).toByte()
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }

    /** MUTF-8 encoder for the fixed test strings (BMP only, as per the dex spec variant). */
    private fun mutf8(s: String): ByteArray {
        val out = ArrayList<Byte>(s.length)
        for (ch in s) {
            val c = ch.code
            when {
                c == 0 -> {
                    out.add(0xC0.toByte())
                    out.add(0x80.toByte())
                }
                c < 0x80 -> out.add(c.toByte())
                c < 0x800 -> {
                    out.add((0xC0 or (c shr 6)).toByte())
                    out.add((0x80 or (c and 0x3F)).toByte())
                }
                else -> {
                    out.add((0xE0 or (c shr 12)).toByte())
                    out.add((0x80 or ((c shr 6) and 0x3F)).toByte())
                    out.add((0x80 or (c and 0x3F)).toByte())
                }
            }
        }
        return out.toByteArray()
    }

    private fun ulebLengthOf(v: Int): Int = if (v < 0x80) 1 else 2

    /** Offsets of the interesting regions inside [buildGreeterDex], for truncation tests. */
    private class BuiltDex(val bytes: ByteArray, val classDataOffset: Int)

    /**
     * Assembles a minimal, spec-shaped dex 035 file declaring exactly:
     * `public class com.example.Greeter extends java.lang.Object` with
     * `private int count` and `public void hello(java.lang.String)`.
     */
    private fun buildGreeterDex(): BuiltDex {
        val w = DexWriter()
        val strings = listOf(
            "VL", // 0: shorty of hello
            "Lcom/example/Greeter;", // 1
            "Ljava/lang/Object;", // 2
            "Ljava/lang/String;", // 3
            "I", // 4
            "V", // 5
            "count", // 6
            "hello" // 7
        )

        // Pre-compute forward offsets (all tables 4-byte aligned as the spec requires).
        val stringIdsOffset = 112
        val typeIdsOffset = stringIdsOffset + strings.size * 4 // 144
        val protoIdsOffset = typeIdsOffset + 5 * 4 // 164
        val fieldIdsOffset = protoIdsOffset + 12 // 176
        val methodIdsOffset = fieldIdsOffset + 8 // 184
        val classDefsOffset = methodIdsOffset + 8 // 192
        val typeListOffset = classDefsOffset + 32 // 224
        var p = typeListOffset + 4 + 2 // type_list: u4 size + one u2 entry
        p = (p + 3) and 3.inv() // pad to next multiple of 4
        val stringDataOffset = p
        val stringDataOffsets = IntArray(strings.size)
        for (i in strings.indices) {
            stringDataOffsets[i] = p
            p += ulebLengthOf(strings[i].length) + mutf8(strings[i]).size + 1 // + NUL terminator
        }
        val classDataOffset = (p + 3) and 3.inv()
        val classDataSize = 4 + 2 + 3 // counts(0,1,1,0) + one encoded_field + one encoded_method
        val codeItemOffset = (classDataOffset + classDataSize + 3) and 3.inv()

        // Header placeholder (patched at the end).
        repeat(112) { w.u1(0) }

        // string_ids (data offsets patched after string_data is laid out)
        repeat(strings.size) { w.u4(0) }
        // type_ids: descriptors sorted by string index: 1,2,3,4,5
        for (t in intArrayOf(1, 2, 3, 4, 5)) w.u4(t)
        // proto_ids: shorty_idx=0 ("VL"), return_type_idx=4 ("V"), parameters_off=type_list
        w.u4(0)
        w.u4(4)
        w.u4(typeListOffset)
        // field_ids: class=com/example/Greeter(0), type=I(3), name=count(6)
        w.u2(0)
        w.u2(3)
        w.u4(6)
        // method_ids: class=0, proto=0, name=hello(7)
        w.u2(0)
        w.u2(0)
        w.u4(7)
        // class_defs: class_idx=0, ACC_PUBLIC, superclass=Object(1), no interfaces,
        // source_file=NO_INDEX, no annotations, class_data at classDataOffset, no static values
        w.u4(0)
        w.u4(1)
        w.u4(1)
        w.u4(0)
        w.u4(-1) // NO_INDEX
        w.u4(0)
        w.u4(classDataOffset)
        w.u4(0)

        // type_list for hello's parameters: [Ljava/lang/String; (type idx 2)
        w.u4(1)
        w.u2(2)
        w.align4()
        check(w.size == stringDataOffset)

        // string_data_items: uleb128 utf16_size + MUTF-8 bytes + 0x00 terminator
        for (s in strings) {
            w.uleb(s.length)
            w.bytes(mutf8(s))
            w.u1(0)
        }

        // class_data_item: 0 static, 1 instance field, 1 direct method, 0 virtual methods
        w.align4()
        check(w.size == classDataOffset)
        w.uleb(0)
        w.uleb(1)
        w.uleb(1)
        w.uleb(0)
        w.uleb(0) // encoded_field: field_idx_diff = 0 (field id #0)
        w.uleb(2) // ACC_PRIVATE
        w.uleb(0) // encoded_method: method_idx_diff = 0 (method id #0)
        w.uleb(1) // ACC_PUBLIC
        w.uleb(codeItemOffset) // code_off: consumed, never followed

        // A realistic (but ignored by the parser) code_item for hello: return-void
        w.align4()
        check(w.size == codeItemOffset)
        w.u2(1) // registers_size
        w.u2(2) // ins_size
        w.u2(1) // outs_size
        w.u2(0) // tries_size
        w.u4(0) // debug_info_off
        w.u4(1) // insns_size
        w.u2(0x000E) // return-void
        w.u2(0) // padding

        // Header
        w.patch4(0x00, 0x0A786564) // "dex" LF
        w.patch4(0x04, 0x00353330) // "035" NUL
        w.patch4(0x08, 0) // checksum: zeros per test brief
        // signature (bytes 0x0C..0x1F) stays zeros
        w.patch4(0x20, w.size) // file_size
        w.patch4(0x24, 112) // header_size
        w.patch4(0x28, 0x12345678) // endian_tag
        w.patch4(0x2C, 0) // link_size
        w.patch4(0x30, 0) // link_off
        w.patch4(0x34, 0) // map_off (map_list deliberately absent)
        w.patch4(0x38, strings.size) // string_ids_size
        w.patch4(0x3C, stringIdsOffset)
        w.patch4(0x40, 5) // type_ids_size
        w.patch4(0x44, typeIdsOffset)
        w.patch4(0x48, 1) // proto_ids_size
        w.patch4(0x4C, protoIdsOffset)
        w.patch4(0x50, 1) // field_ids_size
        w.patch4(0x54, fieldIdsOffset)
        w.patch4(0x58, 1) // method_ids_size
        w.patch4(0x5C, methodIdsOffset)
        w.patch4(0x60, 1) // class_defs_size
        w.patch4(0x64, classDefsOffset)
        w.patch4(0x68, w.size - (classDefsOffset + 32)) // data_size
        w.patch4(0x6C, classDefsOffset + 32) // data_off

        // Back-patch string data offsets
        for (i in strings.indices) {
            w.patch4(stringIdsOffset + i * 4, stringDataOffsets[i])
        }

        return BuiltDex(w.toByteArray(), classDataOffset)
    }

    // ---------------------------------------------------------------- core parse

    @Test
    fun parsesMinimalSingleClassDex() {
        val dex = buildGreeterDex()
        val result = parseDex(dex.bytes)

        assertTrue(result.diagnostics.isEmpty(), "unexpected diagnostics: ${result.diagnostics}")
        assertEquals(1, result.classes.size)

        val expected = DexClass(
            binaryName = "com/example/Greeter",
            simpleName = "Greeter",
            accessFlags = 1, // ACC_PUBLIC
            superbinaryName = "java/lang/Object",
            interfaceBinaryNames = emptyList(),
            fields = listOf(
                DexField(name = "count", typeDescriptor = "I", accessFlags = 2) // ACC_PRIVATE
            ),
            methods = listOf(
                DexMethod(
                    name = "hello",
                    parameterDescriptors = listOf("Ljava/lang/String;"),
                    returnDescriptor = "V",
                    accessFlags = 1 // ACC_PUBLIC
                )
            )
        )
        assertEquals(expected, result.classes[0])
    }

    @Test
    fun wrongMagicYieldsEmptyResultPlusDiagnostic() {
        val dex = buildGreeterDex().bytes.copyOf()
        dex[0] = 'K'.code.toByte()
        dex[1] = 'T'.code.toByte()
        dex[2] = 'L'.code.toByte()
        dex[3] = 'X'.code.toByte()

        val result = parseDex(dex)
        assertTrue(result.classes.isEmpty())
        assertTrue(result.diagnostics.isNotEmpty())
        assertTrue(result.diagnostics[0].contains("magic"))
    }

    @Test
    fun tooSmallInputYieldsEmptyResultPlusDiagnostic() {
        val result = parseDex(ByteArray(64))
        assertTrue(result.classes.isEmpty())
        assertTrue(result.diagnostics.isNotEmpty())
    }

    @Test
    fun unsupportedVersionYieldsEmptyResultPlusDiagnostic() {
        val dex = buildGreeterDex().bytes.copyOf()
        dex[6] = '1'.code.toByte() // dex\n031 - unsupported version digit

        val result = parseDex(dex)
        assertTrue(result.classes.isEmpty())
        assertTrue(result.diagnostics.isNotEmpty())
    }

    // ---------------------------------------------------------------- MUTF-8

    /** Decodes raw MUTF-8 bytes (already NUL-terminated) through [DexByteReader]. */
    private fun decodeMutf8(vararg byteInts: Int): Pair<String?, Boolean> {
        val reader = DexByteReader(byteInts.map { it.toByte() }.toByteArray())
        val decoded = reader.readMutf8()
        return decoded to reader.failed
    }

    @Test
    fun decodesAsciiMutf8() {
        val (decoded, failed) = decodeMutf8(0x61, 0x0A, 0x62, 0x00)
        assertFalse(failed)
        assertEquals("a" + Char(10) + "b", decoded)
    }

    @Test
    fun decodesThreeByteMutf8() {
        // U+65E5 U+672C
        val (decoded, failed) = decodeMutf8(0xE6, 0x97, 0xA5, 0xE6, 0x9C, 0xAC, 0x00)
        assertFalse(failed)
        assertEquals("日本", decoded)
    }

    @Test
    fun decodesEmbeddedNulAsC080() {
        // MUTF-8 encodes U+0000 as 0xC0 0x80; a raw 0x00 would terminate the string.
        val (decoded, failed) = decodeMutf8(0x61, 0xC0, 0x80, 0x62, 0x00)
        assertFalse(failed)
        assertEquals("a" + Char(0) + "b", decoded)
    }

    @Test
    fun rejectsRawNulTerminatorMidway() {
        // raw 0x00 ends the string: "a" only
        val (decoded, failed) = decodeMutf8(0x61, 0x00, 0x62)
        assertFalse(failed)
        assertEquals("a", decoded)
    }

    @Test
    fun reportsInvalidMutf8InsteadOfThrowing() {
        // 0x80 is never a valid lead byte
        val (bad1, failed1) = decodeMutf8(0x80, 0x00)
        assertNull(bad1)
        assertTrue(failed1)

        // truncated 3-byte sequence, then end of data without terminator
        val (bad2, failed2) = decodeMutf8(0xE6, 0x97)
        assertNull(bad2)
        assertTrue(failed2)

        // valid 3-byte char but missing 0x00 terminator before end of data
        val (bad3, failed3) = decodeMutf8(0xE6, 0x97, 0xA5)
        assertNull(bad3)
        assertTrue(failed3)

        // overlong two-byte encoding of U+0001
        val (bad4, failed4) = decodeMutf8(0xC0, 0x81, 0x00)
        assertNull(bad4)
        assertTrue(failed4)
    }

    @Test
    fun uleb128RoundTrip() {
        val w = DexWriter()
        w.uleb(0)
        w.uleb(127)
        w.uleb(128)
        w.uleb(16909060) // 0x01020304
        val reader = DexByteReader(w.toByteArray())
        assertEquals(0, reader.readUleb128())
        assertEquals(127, reader.readUleb128())
        assertEquals(128, reader.readUleb128())
        assertEquals(16909060, reader.readUleb128())
        assertFalse(reader.failed)
    }

    // ---------------------------------------------------------------- robustness

    @Test
    fun truncatedClassDataDoesNotThrowAndIsDiagnosed() {
        val dex = buildGreeterDex()
        // Keep bytes up to and including 3 of the 4 class_data count ulebs.
        val cut = dex.bytes.copyOfRange(0, dex.classDataOffset + 3)

        val result = parseDex(cut)
        assertTrue(result.classes.isEmpty(), "truncated class_data must skip the class")
        assertTrue(result.diagnostics.isNotEmpty(), "truncation must be diagnosed")
    }

    @Test
    fun truncatedStringTableDoesNotThrow() {
        val dex = buildGreeterDex()
        // Cut inside the string_ids table (112..144).
        val cut = dex.bytes.copyOfRange(0, 130)

        val result = parseDex(cut)
        assertNotNull(result)
        assertTrue(result.classes.isEmpty())
        assertTrue(result.diagnostics.isNotEmpty())
    }

    @Test
    fun truncatedCodeItemTailIsIrrelevantSinceCodeIsNeverFollowed() {
        val dex = buildGreeterDex()
        // Chop the last 4 bytes of the code_item the class_data points at.
        val cut = dex.bytes.copyOfRange(0, dex.bytes.size - 4)

        val result = parseDex(cut)
        assertTrue(result.diagnostics.isEmpty(), "code_off is consumed, not followed: ${result.diagnostics}")
        assertEquals(1, result.classes.size)
        assertEquals("com/example/Greeter", result.classes[0].binaryName)
        assertEquals(1, result.classes[0].methods.size)
    }

    @Test
    fun headerFieldOffsetsMatchRealWorldDexLayout() {
        // Guard against accidental reordering of the hand-built header: offsets taken from
        // a real Dalvik 035 file were string_ids@112, type_ids after it, etc. We assert our
        // builder produces the same contiguity pattern (tables start where the previous ends).
        val dex = buildGreeterDex()
        fun u4At(offset: Int): Int =
            (dex.bytes[offset].toInt() and 0xFF) or
                ((dex.bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((dex.bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((dex.bytes[offset + 3].toInt() and 0xFF) shl 24)

        val stringIdsSize = u4At(0x38)
        val stringIdsOff = u4At(0x3C)
        assertEquals(112, stringIdsOff)
        assertEquals(8, stringIdsSize)
        assertEquals(stringIdsOff + stringIdsSize * 4, u4At(0x44)) // type_ids_off follows
        val typeIdsSize = u4At(0x40)
        assertEquals(u4At(0x44) + typeIdsSize * 4, u4At(0x4C)) // proto_ids_off follows
        assertEquals(u4At(0x20), dex.bytes.size) // file_size field matches actual bytes
        assertContentEquals(byteArrayOf(0x64, 0x65, 0x78, 0x0A, 0x30, 0x33, 0x35, 0x00), dex.bytes.copyOfRange(0, 8))
    }
}
