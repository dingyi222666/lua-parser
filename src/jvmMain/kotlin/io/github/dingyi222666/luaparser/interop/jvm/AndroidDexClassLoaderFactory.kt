package io.github.dingyi222666.luaparser.interop.jvm

import java.io.File
import java.lang.reflect.Constructor

/**
 * Creates a [java.lang.reflect]-based wrapper over `dalvik.system.DexClassLoader`
 * so JVM bytecode reflection keeps working on an Android host where the framework
 * ships as dex, not as a jar.
 *
 * Everything is reflective on purpose: this file compiles in the plain-JVM
 * source set (jvmMain is also compiled into the android/ AAR, but desktop
 * builds must never link dalvik classes).
 */
object AndroidDexClassLoaderFactory {

    /**
     * Builds a ClassLoader over one or more dex files (paths joined with ':').
     * [optimizedDir] receives the ART optimized output and must exist.
     * Returns null on any non-Android runtime — callers fall back to jar
     * reflection unchanged.
     */
    fun create(dexPaths: List<String>, optimizedDir: File, parent: ClassLoader): ClassLoader? {
        val realPaths = dexPaths.filter { it.isNotBlank() }
        if (realPaths.isEmpty()) {
            return null
        }
        return runCatching {
            val dexClassLoaderClass = Class.forName("dalvik.system.DexClassLoader")
            val ctor: Constructor<*> = dexClassLoaderClass.constructors.first { ctor ->
                ctor.parameterCount == 4 &&
                    ctor.parameterTypes[0] == String::class.java &&
                    ctor.parameterTypes[3] == ClassLoader::class.java
            }
            @Suppress("UNCHECKED_CAST")
            ctor.newInstance(
                realPaths.joinToString(File.pathSeparator),
                optimizedDir.absolutePath,
                null,
                parent
            ) as ClassLoader
        }.getOrNull()
    }
}
