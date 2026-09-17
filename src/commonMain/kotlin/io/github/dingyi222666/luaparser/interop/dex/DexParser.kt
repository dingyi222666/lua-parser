package io.github.dingyi222666.luaparser.interop.dex

/**
 * Pure-Kotlin parser for Dalvik Executable format (classes.dex, DEX 035/037/038/039),
 * turning the file into lightweight class metadata (see [DexClass]).
 *
 * Consumed sections (per the official dex format spec):
 * - header (fixed 0x70-byte layout, little-endian),
 * - string_ids / string_data_item (uleb128 utf16_size + MUTF-8 + 0x00 terminator),
 * - type_ids (descriptor string indices, sorted by descriptor in well-formed files),
 * - proto_ids (shorty_idx + return_type_idx + parameters_off -> type_list),
 * - field_ids (class_idx:u2, type_idx:u2, name_idx:u4),
 * - method_ids (class_idx:u2, proto_idx:u2, name_idx:u4),
 * - class_defs (class_idx, access_flags, superclass_idx, interfaces_off, ..., class_data_off),
 * - class_data_item (uleb128 counts + diff-encoded encoded_field/encoded_method lists whose
 *   trailing `code_off` is consumed but never followed).
 *
 * Deliberately NOT consumed: checksum, signature, link section, map_list, code_item,
 * debug_info_item, annotations, encoded static values (static_values_off is ignored).
 *
 * Failure model: [parseDex] never throws on malformed input. Wrong magic yields an empty
 * result plus a diagnostic; a broken section or class is skipped with a diagnostic while
 * parsing continues with the rest of the file.
 */

/** Size in bytes of the fixed 0x70-byte dex header (all header fields are read at absolute offsets). */
private const val HEADER_SIZE = 0x70

/** Little-endian endian_tag constant; the big-endian counterpart is not supported. */
private const val ENDIAN_TAG_LITTLE = 0x12345678

/** Marker index (0xFFFFFFFF) for "no entry" references such as a missing superclass. */
private const val NO_INDEX = -1

/** Fixed byte sizes of the id-table entries consumed below. */
private const val STRING_ID_ITEM_SIZE = 4
private const val TYPE_ID_ITEM_SIZE = 4
private const val PROTO_ID_ITEM_SIZE = 12
private const val FIELD_ID_ITEM_SIZE = 8
private const val METHOD_ID_ITEM_SIZE = 8
private const val CLASS_DEF_ITEM_SIZE = 32

/**
 * Parses [bytes] as a Dalvik classes.dex and returns the classes it declares together with
 * non-fatal diagnostics. Never throws; see the file-level KDoc for the failure model.
 */
fun parseDex(bytes: ByteArray): DexParseResult {
    val diagnostics = mutableListOf<String>()

    if (bytes.size < HEADER_SIZE) {
        diagnostics.add("dex: input of ${bytes.size} bytes is smaller than a dex header - skipped")
        return DexParseResult(emptyList(), diagnostics)
    }
    if (!hasDexMagic(bytes)) {
        diagnostics.add("dex: bad magic ${bytes.slice(0 until 8).joinToString(" ") { it.toInt().and(0xFF).toString(16).padStart(2, '0') }} - expected dex versions 035/037/038/039")
        return DexParseResult(emptyList(), diagnostics)
    }

    val reader = DexByteReader(bytes)
    fun u4At(offset: Int): Int {
        reader.seek(offset)
        return reader.readU4()
    }

    val endianTag = u4At(0x28)
    if (endianTag != ENDIAN_TAG_LITTLE) {
        diagnostics.add("dex: unsupported endian tag 0x${endianTag.toLong().and(0xFFFFFFFFL).toString(16)} - skipped")
        return DexParseResult(emptyList(), diagnostics)
    }

    val stringIdsSize = u4At(0x38)
    val stringIdsOff = u4At(0x3C)
    val typeIdsSize = u4At(0x40)
    val typeIdsOff = u4At(0x44)
    val protoIdsSize = u4At(0x48)
    val protoIdsOff = u4At(0x4C)
    val fieldIdsSize = u4At(0x50)
    val fieldIdsOff = u4At(0x54)
    val methodIdsSize = u4At(0x58)
    val methodIdsOff = u4At(0x5C)
    val classDefsSize = u4At(0x60)
    val classDefsOff = u4At(0x64)

    val strings = parseStrings(reader, stringIdsSize, stringIdsOff, diagnostics)
    val types = parseTypes(reader, typeIdsSize, typeIdsOff, strings, diagnostics)
    val protos = parseProtos(reader, protoIdsSize, protoIdsOff, types, diagnostics)
    val fieldIds = parseFieldIds(reader, fieldIdsSize, fieldIdsOff, types, strings, diagnostics)
    val methodIds = parseMethodIds(reader, methodIdsSize, methodIdsOff, protos, strings, diagnostics)
    val classes = parseClassDefs(reader, classDefsSize, classDefsOff, types, fieldIds, methodIds, diagnostics)

    return DexParseResult(classes, diagnostics)
}

/** Checks the 8-byte dex magic including the supported version digits 035/037/038/039. */
private fun hasDexMagic(bytes: ByteArray): Boolean {
    if (bytes.size < 8) return false
    val version = bytes[6].toInt() and 0xFF
    return bytes[0] == 0x64.toByte() && // 'd'
        bytes[1] == 0x65.toByte() && // 'e'
        bytes[2] == 0x78.toByte() && // 'x'
        bytes[3] == 0x0A.toByte() && // LF
        bytes[4] == 0x30.toByte() && // '0'
        bytes[5] == 0x33.toByte() && // '3'
        (version == 0x35 || version == 0x37 || version == 0x38 || version == 0x39) &&
        bytes[7] == 0x00.toByte()
}

/** Validates that a section of [entrySize]-byte entries fits inside the file. */
private fun sectionFits(
    reader: DexByteReader,
    name: String,
    size: Int,
    offset: Int,
    entrySize: Int,
    diagnostics: MutableList<String>
): Boolean {
    val end = offset.toLong() + size.toLong() * entrySize
    if (size < 0 || offset < 0 || end > reader.size) {
        diagnostics.add("dex: $name section (size=$size offset=$offset) out of bounds - skipped")
        return false
    }
    return true
}

/** Reads the string table; undecodable entries become null and are diagnosed. */
private fun parseStrings(
    reader: DexByteReader,
    size: Int,
    offset: Int,
    diagnostics: MutableList<String>
): List<String?> {
    if (!sectionFits(reader, "string_ids", size, offset, STRING_ID_ITEM_SIZE, diagnostics)) {
        return List(size.coerceAtLeast(0)) { null }
    }
    val result = ArrayList<String?>(size)
    for (i in 0 until size) {
        reader.resetFailure()
        reader.seek(offset + i * STRING_ID_ITEM_SIZE)
        val dataOffset = reader.readU4()
        if (reader.failed || dataOffset < 0 || dataOffset >= reader.size) {
            diagnostics.add("dex: string #$i has out-of-bounds data offset - string skipped")
            result.add(null)
            continue
        }
        reader.seek(dataOffset)
        val utf16Size = reader.readUleb128()
        if (reader.failed) {
            diagnostics.add("dex: string #$i data item truncated - string skipped")
            result.add(null)
            continue
        }
        val decoded = reader.readMutf8(utf16Size)
        if (decoded == null) {
            diagnostics.add("dex: string #$i MUTF-8 decode failed (${reader.failure}) - string skipped")
        }
        result.add(decoded)
    }
    return result
}

/** Reads type_ids as descriptor strings; unreferencable entries become null. */
private fun parseTypes(
    reader: DexByteReader,
    size: Int,
    offset: Int,
    strings: List<String?>,
    diagnostics: MutableList<String>
): List<String?> {
    if (!sectionFits(reader, "type_ids", size, offset, TYPE_ID_ITEM_SIZE, diagnostics)) {
        return List(size.coerceAtLeast(0)) { null }
    }
    val result = ArrayList<String?>(size)
    for (i in 0 until size) {
        reader.resetFailure()
        reader.seek(offset + i * TYPE_ID_ITEM_SIZE)
        val descriptorIdx = reader.readU4()
        val descriptor = strings.getOrNull(descriptorIdx)
        if (descriptor == null) {
            diagnostics.add("dex: type #$i references bad string #$descriptorIdx - type skipped")
            result.add(null)
        } else {
            result.add(descriptor)
        }
    }
    return result
}

/** Resolved proto_id_item: return type descriptor plus parameter descriptors. */
private class ProtoInfo(
    val returnDescriptor: String?,
    val parameterDescriptors: List<String>
)

private fun parseProtos(
    reader: DexByteReader,
    size: Int,
    offset: Int,
    types: List<String?>,
    diagnostics: MutableList<String>
): List<ProtoInfo?> {
    if (!sectionFits(reader, "proto_ids", size, offset, PROTO_ID_ITEM_SIZE, diagnostics)) {
        return List(size.coerceAtLeast(0)) { null }
    }
    val result = ArrayList<ProtoInfo?>(size)
    for (i in 0 until size) {
        reader.resetFailure()
        reader.seek(offset + i * PROTO_ID_ITEM_SIZE)
        reader.readU4() // shorty_idx: not needed for class metadata
        val returnTypeIdx = reader.readU4()
        val parametersOffset = reader.readU4()
        if (reader.failed) {
            diagnostics.add("dex: proto #$i truncated - proto skipped")
            result.add(null)
            continue
        }
        val returnDescriptor = types.getOrNull(returnTypeIdx)
        if (returnDescriptor == null) {
            diagnostics.add("dex: proto #$i references bad return type #$returnTypeIdx - proto skipped")
            result.add(null)
            continue
        }
        val parameters =
            if (parametersOffset == 0) emptyList()
            else readTypeList(reader, parametersOffset, types, diagnostics)
        result.add(ProtoInfo(returnDescriptor, parameters))
    }
    return result
}

/** Reads a type_list (u4 size + u2 type indices); broken entries are diagnosed and dropped. */
private fun readTypeList(
    reader: DexByteReader,
    offset: Int,
    types: List<String?>,
    diagnostics: MutableList<String>
): List<String> {
    reader.resetFailure()
    reader.seek(offset)
    val size = reader.readU4()
    if (reader.failed || size < 0 ||
        offset.toLong() + 4 + size.toLong() * 2 > reader.size
    ) {
        diagnostics.add("dex: type_list at offset $offset out of bounds - entries skipped")
        return emptyList()
    }
    val result = ArrayList<String>(size)
    for (i in 0 until size) {
        val typeIdx = reader.readU2()
        val descriptor = types.getOrNull(typeIdx)
        if (descriptor == null) {
            diagnostics.add("dex: type_list at offset $offset references bad type #$typeIdx - entry skipped")
        } else {
            result.add(descriptor)
        }
    }
    return result
}

/** Resolved field_id_item: field type descriptor + plain name. */
private class FieldId(val typeDescriptor: String?, val name: String?)

private fun parseFieldIds(
    reader: DexByteReader,
    size: Int,
    offset: Int,
    types: List<String?>,
    strings: List<String?>,
    diagnostics: MutableList<String>
): List<FieldId?> {
    if (!sectionFits(reader, "field_ids", size, offset, FIELD_ID_ITEM_SIZE, diagnostics)) {
        return List(size.coerceAtLeast(0)) { null }
    }
    val result = ArrayList<FieldId?>(size)
    for (i in 0 until size) {
        reader.resetFailure()
        reader.seek(offset + i * FIELD_ID_ITEM_SIZE)
        reader.readU2() // class_idx: owning type, implied by the referencing class_def
        val typeIdx = reader.readU2()
        val nameIdx = reader.readU4()
        if (reader.failed) {
            diagnostics.add("dex: field_id #$i truncated - field id skipped")
            result.add(null)
            continue
        }
        val typeDescriptor = types.getOrNull(typeIdx)
        val name = strings.getOrNull(nameIdx)
        if (typeDescriptor == null || name == null) {
            diagnostics.add("dex: field_id #$i references bad type #$typeIdx or name #$nameIdx - field id skipped")
            result.add(null)
            continue
        }
        result.add(FieldId(typeDescriptor, name))
    }
    return result
}

/** Resolved method_id_item: method name + resolved proto. */
private class MethodId(val name: String?, val proto: ProtoInfo?)

private fun parseMethodIds(
    reader: DexByteReader,
    size: Int,
    offset: Int,
    protos: List<ProtoInfo?>,
    strings: List<String?>,
    diagnostics: MutableList<String>
): List<MethodId?> {
    if (!sectionFits(reader, "method_ids", size, offset, METHOD_ID_ITEM_SIZE, diagnostics)) {
        return List(size.coerceAtLeast(0)) { null }
    }
    val result = ArrayList<MethodId?>(size)
    for (i in 0 until size) {
        reader.resetFailure()
        reader.seek(offset + i * METHOD_ID_ITEM_SIZE)
        reader.readU2() // class_idx: owning type, implied by the referencing class_def
        val protoIdx = reader.readU2()
        val nameIdx = reader.readU4()
        if (reader.failed) {
            diagnostics.add("dex: method_id #$i truncated - method id skipped")
            result.add(null)
            continue
        }
        val name = strings.getOrNull(nameIdx)
        val proto = protos.getOrNull(protoIdx)
        if (name == null || proto == null) {
            diagnostics.add("dex: method_id #$i references bad name #$nameIdx or proto #$protoIdx - method id skipped")
            result.add(null)
            continue
        }
        result.add(MethodId(name, proto))
    }
    return result
}

private fun parseClassDefs(
    reader: DexByteReader,
    size: Int,
    offset: Int,
    types: List<String?>,
    fieldIds: List<FieldId?>,
    methodIds: List<MethodId?>,
    diagnostics: MutableList<String>
): List<DexClass> {
    if (!sectionFits(reader, "class_defs", size, offset, CLASS_DEF_ITEM_SIZE, diagnostics)) {
        return emptyList()
    }
    val result = ArrayList<DexClass>(size)
    for (i in 0 until size) {
        try {
            parseOneClass(reader, i, offset + i * CLASS_DEF_ITEM_SIZE, types, fieldIds, methodIds, diagnostics)
                ?.let(result::add)
        } catch (t: Throwable) {
            // Last-resort guard so a single pathological class can never abort the file.
            diagnostics.add("dex: class #$i internal error (${t.message}) - class skipped")
        }
    }
    return result
}

/** Parses one class_def_item; null means the class was skipped (reason diagnosed). */
private fun parseOneClass(
    reader: DexByteReader,
    index: Int,
    offset: Int,
    types: List<String?>,
    fieldIds: List<FieldId?>,
    methodIds: List<MethodId?>,
    diagnostics: MutableList<String>
): DexClass? {
    reader.resetFailure()
    reader.seek(offset)
    val classIdx = reader.readU4()
    val accessFlags = reader.readU4()
    val superclassIdx = reader.readU4()
    val interfacesOffset = reader.readU4()
    reader.readU4() // source_file_idx: not needed
    reader.readU4() // annotations_off: not needed
    val classDataOffset = reader.readU4()
    reader.readU4() // static_values_off: not needed
    if (reader.failed) {
        diagnostics.add("dex: class #$index header entry truncated - class skipped")
        return null
    }

    val descriptor = types.getOrNull(classIdx)
    if (descriptor == null) {
        diagnostics.add("dex: class #$index references bad type #$classIdx - class skipped")
        return null
    }
    val binaryName = descriptorToBinaryName(descriptor)
    val superbinaryName: String? =
        if (superclassIdx == NO_INDEX) null
        else types.getOrNull(superclassIdx)?.let(::descriptorToBinaryName)
    val interfaces =
        if (interfacesOffset == 0) emptyList()
        else readTypeList(reader, interfacesOffset, types, diagnostics).map(::descriptorToBinaryName)

    val fieldsAndMethods =
        if (classDataOffset == 0) DexClassData(emptyList(), emptyList())
        else parseClassData(reader, index, classDataOffset, fieldIds, methodIds, diagnostics)
        ?: return null // truncated class_data skips the whole class (diagnosed)

    return DexClass(
        binaryName = binaryName,
        simpleName = binaryName.substringAfterLast('/'),
        accessFlags = accessFlags,
        superbinaryName = superbinaryName,
        interfaceBinaryNames = interfaces,
        fields = fieldsAndMethods.fields,
        methods = fieldsAndMethods.methods
    )
}

/** Fields + methods collected from a class_data_item. */
private class DexClassData(val fields: List<DexField>, val methods: List<DexMethod>)

/**
 * Parses a class_data_item: four uleb128 counts followed by diff-encoded
 * encoded_field (field_idx_diff + access_flags) and encoded_method
 * (method_idx_diff + access_flags + code_off) lists. `code_off` is consumed
 * but never followed. Static fields and instance fields form one continuous
 * field-index delta chain, as do direct and virtual methods.
 *
 * Returns null (with a diagnostic) when the item is truncated; the whole class is skipped.
 */
private fun parseClassData(
    reader: DexByteReader,
    classIndex: Int,
    offset: Int,
    fieldIds: List<FieldId?>,
    methodIds: List<MethodId?>,
    diagnostics: MutableList<String>
): DexClassData? {
    reader.resetFailure()
    reader.seek(offset)
    val staticFieldCount = reader.readUleb128()
    val instanceFieldCount = reader.readUleb128()
    val directMethodCount = reader.readUleb128()
    val virtualMethodCount = reader.readUleb128()
    if (reader.failed) {
        diagnostics.add("dex: class #$classIndex class_data counts truncated - class skipped")
        return null
    }

    val fields = ArrayList<DexField>(staticFieldCount + instanceFieldCount)
    var fieldIndex = 0
    for (i in 0 until staticFieldCount + instanceFieldCount) {
        val idxDiff = reader.readUleb128()
        val accessFlags = reader.readUleb128()
        if (reader.failed) {
            diagnostics.add("dex: class #$classIndex encoded_fields truncated at entry $i - class skipped")
            return null
        }
        fieldIndex += idxDiff
        val id = fieldIds.getOrNull(fieldIndex)
        if (id == null) {
            diagnostics.add("dex: class #$classIndex references bad field #$fieldIndex - field skipped")
        } else {
            fields.add(DexField(id.name!!, id.typeDescriptor!!, accessFlags))
        }
    }

    val methods = ArrayList<DexMethod>(directMethodCount + virtualMethodCount)
    var methodIndex = 0
    for (i in 0 until directMethodCount + virtualMethodCount) {
        val idxDiff = reader.readUleb128()
        val accessFlags = reader.readUleb128()
        reader.readUleb128() // code_off: consumed, not followed
        if (reader.failed) {
            diagnostics.add("dex: class #$classIndex encoded_methods truncated at entry $i - class skipped")
            return null
        }
        methodIndex += idxDiff
        val id = methodIds.getOrNull(methodIndex)
        if (id == null) {
            diagnostics.add("dex: class #$classIndex references bad method #$methodIndex - method skipped")
        } else {
            methods.add(
                DexMethod(
                    name = id.name!!,
                    parameterDescriptors = id.proto!!.parameterDescriptors,
                    returnDescriptor = id.proto.returnDescriptor!!,
                    accessFlags = accessFlags
                )
            )
        }
    }
    return DexClassData(fields, methods)
}

/**
 * Converts a type descriptor to a binary name: `Lcom/foo/Bar;` -> `com/foo/Bar`.
 * Array (`[I`, `[Ljava/lang/String;`) and primitive (`I`) descriptors are returned unchanged.
 */
internal fun descriptorToBinaryName(descriptor: String): String =
    if (descriptor.length >= 2 && descriptor.first() == 'L' && descriptor.last() == ';') {
        descriptor.substring(1, descriptor.length - 1)
    } else {
        descriptor
    }
