package interop.jvm

import io.github.dingyi222666.luaparser.interop.dex.DexClass
import io.github.dingyi222666.luaparser.interop.dex.parseDex
import io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.interop.jvm.PrefixedImportUnsupportedReason
import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.semantic.types.model.JavaInstanceType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceInput
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TDD corpus for dex library mounting in the workspace JVM provider (D3).
 *
 * A tiny dex is hand-assembled byte-by-byte per the Dalvik specification (the same
 * approach as `commonTest .../interop/dex/DexParserCommonTddTest.kt`), declaring:
 * `public class com.example.Greeter extends java.lang.Object` with
 * - `public static final String TAG` shape: public static `TAG: String`,
 * - `private int count` (never surfaces: non-public),
 * - `public Greeter()` constructor (drives `__call`),
 * - `public static String greet()` (drives module methods),
 * - `public void hello(String)` (drives instance members).
 *
 * Acceptance:
 * - bindClass/import of `com.example.Greeter` resolves with members when the dex is a
 *   configured classpath entry (or named by a `path:Class` import prefix),
 * - bare `import "<dex path>"` mounts the library module plus per-class providers,
 * - existing .dex/.apk prefixes no longer diagnose as unsupported,
 * - odex/vdex STILL diagnose as unsupported via ANDROID_ODEX_UNSUPPORTED,
 * - JvmWorkspaceEngine mounts the dex class provider end-to-end from metadata.
 *
 * Test-only. No production fixtures are mutated: dex/apk bytes are written to a
 * per-test temp directory. Verification is review-owned serial jvmTest (TASK-043);
 * workers do not run Gradle.
 */
class DexMountingTddTest {

    // ------------------------------------------------------------ fixture dex

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

    /** MUTF-8 encoder for the fixed BMP-only test strings. */
    private fun mutf8(s: String): ByteArray {
        val out = ArrayList<Byte>(s.length)
        for (ch in s) {
            val c = ch.code
            when {
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

    /**
     * Assembles a minimal dex 035 file declaring `public class com.example.Greeter`
     * with a public static field, a private instance field, a public constructor,
     * a public static method and a public instance method.
     */
    private fun buildGreeterDexBytes(): ByteArray {
        val w = DexWriter()
        val strings = listOf(
            "L", // 0: shorty of greet ()Ljava/lang/String;
            "VL", // 1: shorty of hello (Ljava/lang/String;)V
            "V", // 2: shorty of <init> ()V
            "Lcom/example/Greeter;", // 3
            "Ljava/lang/Object;", // 4
            "Ljava/lang/String;", // 5
            "I", // 6
            "V", // 7
            "TAG", // 8
            "count", // 9
            "<init>", // 10
            "greet", // 11
            "hello" // 12
        )

        // Forward offsets (all tables 4-byte aligned per the spec).
        val stringIdsOffset = 112
        val typeIdsOffset = stringIdsOffset + strings.size * 4
        val protoIdsOffset = typeIdsOffset + 5 * 4
        val fieldIdsOffset = protoIdsOffset + 3 * 12
        val methodIdsOffset = fieldIdsOffset + 2 * 8
        val classDefsOffset = methodIdsOffset + 3 * 8
        val typeListOffset = classDefsOffset + 32
        var p = typeListOffset + 4 + 2 // type_list: u4 size + one u2 entry
        p = (p + 3) and 3.inv()
        val stringDataOffset = p
        val stringDataOffsets = IntArray(strings.size)
        for (i in strings.indices) {
            stringDataOffsets[i] = p
            p += ulebLengthOf(strings[i].length) + mutf8(strings[i]).size + 1
        }
        val classDataOffset = (p + 3) and 3.inv()
        // counts(4x1B) + 2 fields x2 uleb + 3 methods x(2 uleb + 2-byte code offset uleb)
        val classDataSize = 4 + 2 * 2 + 3 * (1 + 1 + 2)
        val codeItemOffset = (classDataOffset + classDataSize + 3) and 3.inv()

        // Header placeholder (patched at the end).
        repeat(112) { w.u1(0) }

        // string_ids (data offsets patched after string_data is laid out)
        repeat(strings.size) { w.u4(0) }
        // type_ids: descriptors in string index order: 3,4,5,6,7
        for (t in intArrayOf(3, 4, 5, 6, 7)) w.u4(t)
        // proto_ids: (shorty, return, parameters_off)
        w.u4(2); w.u4(4); w.u4(0) // p0: ()V
        w.u4(0); w.u4(2); w.u4(0) // p1: ()Ljava/lang/String;
        w.u4(1); w.u4(4); w.u4(typeListOffset) // p2: (Ljava/lang/String;)V
        // field_ids: (class_idx, type_idx, name_idx) sorted
        w.u2(0); w.u2(2); w.u4(8) // f0: Greeter.TAG : String
        w.u2(0); w.u2(3); w.u4(9) // f1: Greeter.count : int
        // method_ids: (class_idx, proto_idx, name_idx) sorted
        w.u2(0); w.u2(1); w.u4(11) // m0: greet
        w.u2(0); w.u2(0); w.u4(10) // m1: <init>
        w.u2(0); w.u2(2); w.u4(12) // m2: hello
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

        for (s in strings) {
            w.uleb(s.length)
            w.bytes(mutf8(s))
            w.u1(0)
        }

        // class_data_item: 1 static field, 1 instance field, 2 direct methods, 1 virtual method
        w.align4()
        check(w.size == classDataOffset)
        w.uleb(1)
        w.uleb(1)
        w.uleb(2)
        w.uleb(1)
        w.uleb(0) // static field diff 0 -> TAG
        w.uleb(0x9) // ACC_PUBLIC | ACC_STATIC
        w.uleb(1) // instance field diff 1 -> count
        w.uleb(0x2) // ACC_PRIVATE
        w.uleb(0) // direct method diff 0 -> greet
        w.uleb(0x9) // ACC_PUBLIC | ACC_STATIC
        w.uleb(codeItemOffset)
        w.uleb(1) // direct method diff 1 -> <init>
        w.uleb(0x1) // ACC_PUBLIC
        w.uleb(codeItemOffset)
        // The method_idx_diff chain accumulates CONTINUOUSLY across the direct and
        // virtual lists (matching dx/ART output and the parser), so hello (idx 2)
        // encodes 2 - lastDirect(1) = 1.
        w.uleb(1) // virtual method diff 1 -> hello
        w.uleb(0x1) // ACC_PUBLIC
        w.uleb(codeItemOffset)

        // One shared (ignored) code_item: return-void
        w.align4()
        check(w.size == codeItemOffset)
        w.u2(1) // registers_size
        w.u2(0) // ins_size
        w.u2(0) // outs_size
        w.u2(0) // tries_size
        w.u4(0) // debug_info_off
        w.u4(1) // insns_size
        w.u2(0x000E) // return-void
        w.u2(0) // padding

        // Header
        w.patch4(0x00, 0x0A786564) // "dex" LF
        w.patch4(0x04, 0x00353330) // "035" NUL
        w.patch4(0x08, 0) // checksum zeros (parser never reads it)
        w.patch4(0x20, w.size) // file_size
        w.patch4(0x24, 112) // header_size
        w.patch4(0x28, 0x12345678) // endian_tag
        w.patch4(0x2C, 0)
        w.patch4(0x30, 0)
        w.patch4(0x34, 0) // map_off deliberately absent
        w.patch4(0x38, strings.size)
        w.patch4(0x3C, stringIdsOffset)
        w.patch4(0x40, 5)
        w.patch4(0x44, typeIdsOffset)
        w.patch4(0x48, 3)
        w.patch4(0x4C, protoIdsOffset)
        w.patch4(0x50, 2)
        w.patch4(0x54, fieldIdsOffset)
        w.patch4(0x58, 3)
        w.patch4(0x5C, methodIdsOffset)
        w.patch4(0x60, 1)
        w.patch4(0x64, classDefsOffset)
        w.patch4(0x68, w.size - (classDefsOffset + 32)) // data_size
        w.patch4(0x6C, classDefsOffset + 32) // data_off

        for (i in strings.indices) {
            w.patch4(stringIdsOffset + i * 4, stringDataOffsets[i])
        }

        return w.toByteArray()
    }

    /** Temp .dex file shared by the whole suite (bytes are never modified). */
    private val dexFile: File by lazy {
        val file = Files.createTempFile("greeter", ".dex").toFile()
        file.writeBytes(buildGreeterDexBytes())
        file.deleteOnExit()
        file
    }

    /** Temp .apk (zip) wrapping the same dex as a classes.dex entry. */
    private val apkFile: File by lazy {
        val file = Files.createTempFile("greeter", ".apk").toFile()
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write(buildGreeterDexBytes())
            zip.closeEntry()
        }
        file.deleteOnExit()
        file
    }

    private fun configuration(vararg classpathEntries: String): JvmWorkspaceConfiguration {
        return JvmWorkspaceConfiguration(classpathEntries = classpathEntries.toList()).normalized()
    }

    // ---------------------------------------------------- parse sanity (fixture)

    @Test
    fun fixture_dex_parses_to_expected_class_shape() {
        val result = parseDex(dexFile.readBytes())
        assertTrue(result.diagnostics.isEmpty(), "unexpected diagnostics: ${result.diagnostics}")
        assertEquals(1, result.classes.size)
        val expected = DexClass(
            binaryName = "com/example/Greeter",
            simpleName = "Greeter",
            accessFlags = 1,
            superbinaryName = "java/lang/Object",
            interfaceBinaryNames = emptyList(),
            fields = listOf(
                DexFieldStatic,
                DexFieldCount
            ),
            methods = listOf(DexMethodGreet, DexMethodInit, DexMethodHello)
        )
        assertEquals(expected, result.classes[0])
    }

    private companion object {
        val DexFieldStatic = io.github.dingyi222666.luaparser.interop.dex.DexField("TAG", "Ljava/lang/String;", 0x9)
        val DexFieldCount = io.github.dingyi222666.luaparser.interop.dex.DexField("count", "I", 0x2)
        val DexMethodGreet = io.github.dingyi222666.luaparser.interop.dex.DexMethod("greet", emptyList(), "Ljava/lang/String;", 0x9)
        val DexMethodInit = io.github.dingyi222666.luaparser.interop.dex.DexMethod("<init>", emptyList(), "V", 0x1)
        val DexMethodHello = io.github.dingyi222666.luaparser.interop.dex.DexMethod(
            "hello", listOf("Ljava/lang/String;"), "V", 0x1
        )
    }

    // ------------------------------------------------ configured classpath dex

    @Test
    fun bind_class_resolves_dex_class_with_members_from_classpath_dex() {
        val provider = JvmClassModuleProvider()
        val symbol = provider.importedSymbolForTarget("com.example.Greeter", configuration(dexFile.path))

        assertNotNull(symbol, "bindClass target must resolve from a classpath dex")
        assertEquals("Greeter", symbol.alias)
        assertEquals("Greeter", symbol.moduleName)
        assertEquals("__jvm__/classes/com/example/Greeter.lua", symbol.providerPath.value)

        val module = symbol.moduleType
        assertTrue("__class" in module.fields, "module must expose __class: fields=${module.fields.keys}")
        assertTrue("__call" in module.fields, "constructor must expose __call: fields=${module.fields.keys}")
        assertTrue("TAG" in module.fields, "public static field must surface as module field")
        assertEquals(PrimitiveType.STRING, module.fields["TAG"])
        assertTrue("greet" in module.methods, "public static method must surface as module method")

        val classShell = assertNotNull(module.fields["__class"]) as? JavaInstanceType
        assertTrue(
            "hello" in classShell!!.classType.allInstanceMembers(),
            "public instance method must surface on the __class shell"
        )
    }

    @Test
    fun providers_mount_dex_class_provider_for_requested_class() {
        val provider = JvmClassModuleProvider()
        val requested = configuration(dexFile.path)
            .copy(classes = linkedSetOf("com.example.Greeter"))
            .normalized()
        val providers = provider.providersFor(requested)

        val path = VirtualPath.of("__jvm__/classes/com/example/Greeter.lua")
        val snapshot = assertNotNull(providers[path], "expected dex class provider at $path")
        val surface = assertNotNull(snapshot.moduleExportSurface)
        val memberNames = surface.members.map { it.name }.toSet()
        assertTrue("hello" in memberNames, "export members must include hello: $memberNames")
        assertTrue("TAG" in memberNames, "export members must include TAG: $memberNames")
        assertTrue("greet" in memberNames, "export members must include greet: $memberNames")
    }

    @Test
    fun classpath_dex_entry_is_not_diagnosed_unsupported() {
        val config = configuration(dexFile.path)
        assertNull(
            config.prefixedImportUnsupportedReason(dexFile.path),
            "an existing .dex classpath entry must not diagnose as unsupported"
        )
    }

    // -------------------------------------------------------- prefixed import

    @Test
    fun prefixed_dex_import_resolves_class_in_that_library() {
        val provider = JvmClassModuleProvider()
        val importText = "${dexFile.path}:com.example.Greeter"
        val config = JvmWorkspaceConfiguration(androluaImports = listOf(importText))

        val symbol = provider.importedSymbolForTarget(importText, config)
        assertNotNull(symbol, "path:Class import must resolve against the named dex library")
        assertEquals("Greeter", symbol.alias)
        assertTrue("__call" in symbol.moduleType.fields)
    }

    @Test
    fun simple_alias_resolves_after_prefixed_dex_import_registers_library() {
        val provider = JvmClassModuleProvider()
        val importText = "${dexFile.path}:com.example.Greeter"
        val config = JvmWorkspaceConfiguration(androluaImports = listOf(importText))

        assertNotNull(provider.importedSymbolForTarget(importText, config))
        val alias = provider.importedSymbolForTarget("Greeter", config)
        assertNotNull(alias, "short alias must resolve once the dex library is registered")
        assertEquals("Greeter", alias.alias)
    }

    // ------------------------------------------------------------ bare import

    @Test
    fun bare_dex_path_import_mounts_library_module_and_class_providers() {
        val provider = JvmClassModuleProvider()
        val config = JvmWorkspaceConfiguration(androluaImports = listOf(dexFile.path))

        val symbol = provider.importedSymbolForTarget(dexFile.path, config)
        assertNotNull(symbol, "bare dex path import must mount the library module")
        assertEquals(dexFile.nameWithoutExtension, symbol.moduleName)
        assertTrue("Greeter" in symbol.moduleType.fields, "library module fields are keyed by class simple name")

        val providers = provider.providersFor(config)
        assertTrue(
            VirtualPath.of("__jvm__/classes/com/example/Greeter.lua") in providers,
            "bare import must mount per-class providers; paths=${providers.keys.map { it.value }}"
        )
        // VirtualPath normalizes the double slash after the "__jvm__/dex/" prefix for
        // absolute target paths, so compare through the same normalization.
        assertTrue(
            VirtualPath.of("__jvm__/dex/${dexFile.path}.lua") in providers,
            "bare import must mount the library module provider"
        )
    }

    @Test
    fun dex_class_resolves_dotted_and_nested_forms() {
        val provider = JvmClassModuleProvider()
        val config = configuration(dexFile.path)
        assertEquals(
            "com.example.Greeter",
            provider.importedClassName("com.example.Greeter", config),
            "dotted binary target must resolve (suppresses unresolved-bindClass diagnostics)"
        )
        assertNull(
            provider.importedClassName("com.example.Missing", config),
            "unknown targets must stay unresolved"
        )
    }

    // -------------------------------------------------------------------- apk

    @Test
    fun apk_wrapping_classes_dex_mounts_through_zip_extraction() {
        val provider = JvmClassModuleProvider()
        val symbol = provider.importedSymbolForTarget("com.example.Greeter", configuration(apkFile.path))
        assertNotNull(symbol, "apk library must mount via its classes.dex zip entry")
        assertEquals("Greeter", symbol.alias)
    }

    // -------------------------------------------------- odex/vdex diagnostics

    @Test
    fun odex_and_vdex_prefixes_still_diagnose_as_unsupported() {
        assertEquals(
            PrefixedImportUnsupportedReason.ANDROID_ODEX_UNSUPPORTED,
            JvmWorkspaceConfiguration().prefixedImportUnsupportedReason("classes.odex"),
            "odex must keep its own unsupported reason"
        )
        assertEquals(
            PrefixedImportUnsupportedReason.ANDROID_ODEX_UNSUPPORTED,
            JvmWorkspaceConfiguration().prefixedImportUnsupportedReason("boot.vdex"),
            "vdex must keep its own unsupported reason"
        )

        val provider = JvmClassModuleProvider()
        val diagnostics = provider.importDiagnostics(
            JvmWorkspaceConfiguration(
                androluaImports = listOf("classes.odex:java.lang.String", "boot.vdex:java.util.Locale")
            )
        )
        assertEquals(2, diagnostics.size)
        val odexDetail = PrefixedImportUnsupportedReason.ANDROID_ODEX_UNSUPPORTED.diagnosticDetail
        diagnostics.forEach { diagnostic ->
            assertEquals(JvmClassModuleProvider.UNSUPPORTED_PREFIXED_IMPORT_CODE, diagnostic.code)
            assertTrue(
                diagnostic.message.contains(odexDetail),
                "odex/vdex diagnostics must embed ANDROID_ODEX_UNSUPPORTED detail: ${diagnostic.message}"
            )
        }
    }

    @Test
    fun non_resolving_dex_prefix_keeps_dex_unsupported_diagnostic() {
        val config = JvmWorkspaceConfiguration()
        assertEquals(
            PrefixedImportUnsupportedReason.ANDROID_DEX_UNSUPPORTED,
            config.prefixedImportUnsupportedReason("missing/libs.dex")
        )
        val provider = JvmClassModuleProvider()
        val diagnostics = provider.importDiagnostics(
            config.copy(androluaImports = listOf("missing/libs.dex:com.example.Greeter"))
        )
        assertEquals(1, diagnostics.size)
        assertTrue(
            diagnostics.single().message.contains(PrefixedImportUnsupportedReason.ANDROID_DEX_UNSUPPORTED.diagnosticDetail)
        )
    }

    // ----------------------------------------------------- engine end-to-end

    @Test
    fun engine_mounts_dex_class_provider_from_bind_class_and_metadata() {
        val engine = JvmWorkspaceEngine()
        val result = engine.build(
            LuaWorkspaceInput(
                files = mapOf(
                    VirtualPath.of("main.lua") to
                        "local Greeter = luajava.bindClass(\"com.example.Greeter\")\n"
                ),
                metadata = mapOf(
                    JvmWorkspaceConfiguration.CLASSPATH_METADATA_KEY to dexFile.path
                ),
                standardLibraryOverlayVersion = LuaVersion.ANDROLUA_5_3
            )
        ).snapshot

        val providerPath = VirtualPath.of("__jvm__/classes/com/example/Greeter.lua")
        val snapshot = assertNotNull(result.extraProviders[providerPath], "engine must mount the dex class provider")
        val module = assertNotNull(snapshot.moduleExportSurface?.moduleType)
        assertTrue("__call" in module.fields, "dex module keeps the constructor __call surface")
        assertTrue("__class" in module.fields, "dex module keeps the __class instance shell")
    }

    @Test
    fun engine_mounts_dex_library_from_bare_source_import() {
        val engine = JvmWorkspaceEngine()
        val result = engine.build(
            LuaWorkspaceInput(
                files = mapOf(
                    VirtualPath.of("main.lua") to "local dex = import \"${dexFile.path}\"\n"
                ),
                metadata = emptyMap(),
                standardLibraryOverlayVersion = LuaVersion.ANDROLUA_5_3
            )
        ).snapshot

        assertTrue(
            VirtualPath.of("__jvm__/classes/com/example/Greeter.lua") in result.extraProviders,
            "bare source import of the dex must mount per-class providers; " +
                "paths=${result.extraProviders.keys.map { it.value }}"
        )
    }
}
