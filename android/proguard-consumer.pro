# Consumer R8 rules shipped inside the luaparser-android AAR
# (android/defaultConfig.consumerProguardFiles). They fire automatically for
# any app consuming the AAR; apps consuming the fat classes jar via their own
# dex pipeline must add this file to proguardFiles by hand.
#
# ---- lsp4j ----------------------------------------------------------------
# lsp4j model classes (Hover, CompletionItem, Diagnostic, ...) are built and
# read by Gson through reflection (field access + no-arg constructor), so
# renaming or stripping members breaks every request/response.
-keep class org.eclipse.lsp4j.** { *; }
-dontwarn org.eclipse.lsp4j.**

# lsp4j.jsonrpc resolves service interfaces/remote proxies and Either
# adapters reflectively (MessageJsonHandler, ReflectionEndpoint, ...).
-keep class org.eclipse.lsp4j.jsonrpc.** { *; }
-dontwarn org.eclipse.lsp4j.jsonrpc.**

# ---- Gson -----------------------------------------------------------------
# Generic signatures + annotations drive Gson's type resolution.
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod

# TypeToken subclasses are looked up reflectively for parameterized types.
-keep class com.google.gson.reflect.TypeToken { *; }
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken

# @SerializedName fields must keep their names (they define the wire format).
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-dontwarn com.google.gson.**

# ---- Kotlin metadata ------------------------------------------------------
# lsp4j declares @NotNull / @Nullable style annotations used by its jsonrpc
# layer when validating parameters.
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
