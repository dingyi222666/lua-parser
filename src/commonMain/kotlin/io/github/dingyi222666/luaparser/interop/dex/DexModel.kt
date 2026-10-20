package io.github.dingyi222666.luaparser.interop.dex

/**
 * Result of parsing a classes.dex (Dalvik executable, versions 035/037/038/039).
 *
 * The parser never throws: malformed input is reported through [diagnostics] and the
 * affected class/section is skipped instead.
 *
 * @property classes     every class_def that could be parsed successfully, in dex order.
 * @property diagnostics non-fatal notes about anomalies encountered during parsing
 *                       (unknown/skipped sections, truncation, undecodable MUTF-8, ...).
 *                       Empty for a well-formed dex.
 */
data class DexParseResult(
    val classes: List<DexClass>,
    val diagnostics: List<String>
)

/**
 * Metadata of a single class declared in the dex.
 *
 * @property binaryName            binary name derived from the type descriptor, e.g. the
 *                                 descriptor `Lcom/foo/Bar;` becomes `com/foo/Bar`.
 *                                 Array and primitive descriptors are kept verbatim
 *                                 (`[I`, `Ljava/lang/String;` style inputs stay unchanged).
 * @property simpleName            last `/`-separated segment of [binaryName], e.g. `Bar`.
 * @property accessFlags           raw dex class access flags bit field (ACC_PUBLIC = 0x1, ...).
 * @property superbinaryName       binary name of the superclass, or null when the class has
 *                                 none (superclass_idx == NO_INDEX, i.e. java/lang/Object itself).
 * @property interfaceBinaryNames  binary names of directly implemented interfaces, in dex order.
 * @property fields                declared fields (static first, then instance, dex order).
 * @property methods               declared methods (direct first, then virtual, dex order).
 */
data class DexClass(
    val binaryName: String,
    val simpleName: String,
    val accessFlags: Int,
    val superbinaryName: String?,
    val interfaceBinaryNames: List<String>,
    val fields: List<DexField>,
    val methods: List<DexMethod>
)

/**
 * A single field declaration.
 *
 * @property name            plain field name, e.g. `count`.
 * @property typeDescriptor  JNI-style field type descriptor: `I`, `Ljava/lang/String;`, `[I`, ...
 * @property accessFlags     raw dex field access flags bit field.
 */
data class DexField(
    val name: String,
    val typeDescriptor: String,
    val accessFlags: Int
)

/**
 * A single method declaration (static, private, constructor and instance methods alike).
 *
 * @property name                   plain method name, e.g. `hello`; `<init>` for constructors.
 * @property parameterDescriptors   JNI-style parameter type descriptors, in declaration order.
 * @property returnDescriptor       JNI-style return type descriptor, `V` for void.
 * @property accessFlags            raw dex method access flags bit field.
 */
data class DexMethod(
    val name: String,
    val parameterDescriptors: List<String>,
    val returnDescriptor: String,
    val accessFlags: Int
)
