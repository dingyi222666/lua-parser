package io.github.dingyi222666.luaparser.semantic.binder

import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.OverloadedFunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.Type
import io.github.dingyi222666.luaparser.semantic.types.model.UnknownType
import io.github.dingyi222666.luaparser.semantic.workspace.std.BuiltinOverlayLoader
import io.github.dingyi222666.luaparser.semantic.workspace.std.BuiltinOverlaySnapshot

internal object BuiltinSymbolSeeder {
    private val standaloneCompatibilityGlobals = BuiltinOverlayLoader.standaloneGlobals()

    /**
     * Seeds root builtin VALUE declarations from the active overlay globals snapshot.
     *
     * Android-Lua / AndroLua overlays document `luajava` as a ModuleType with helper methods
     * (including `getContext(): AndroidLuaContext`). Seeding those declared types is required so
     * ExpressionTypeEvaluator can hard-lock getContext call returns (TASK-575) and so activity/
     * this/service ClassType surfaces remain available for AndroidLuaContext member completion
     * (getLuaDir/getLuaPath/setContentView/…).
     *
     * require "import" / env_import installs free-id helpers (import, env_import, compile, enum,
     * each, dump, printstack, getids, thread, task, timer, loadlayout, loadbitmap, loadmenu).
     * Documented FUNCTION / FunctionType members must seed as [functionDeclaration] so free-id
     * completions/hover expose CompletionItemKind.FUNCTION and installer call shapes (TASK-603).
     * Do not invent jar-only members without stubs.
     */
    fun seed(
        builder: SymbolTableBuilder,
        globals: BuiltinOverlaySnapshot.GlobalsSnapshot = standaloneCompatibilityGlobals
    ) {
        val documentedGlobals = globals.file.moduleExportSurface
            ?.members
            .orEmpty()
            .filter { member -> member.exportPath.size == 1 }
            .associateBy { member -> member.name }

        globals.globalNames.forEach { name ->
            val documented = documentedGlobals[name]
                ?.takeIf { member ->
                    member.kind == SymbolKind.FUNCTION ||
                        member.kind == SymbolKind.METHOD ||
                        member.type != UnknownType
                }
            val declaration = when {
                documented != null && isDocumentedCallableGlobal(documented.kind, documented.type) ->
                    functionDeclaration(
                        id = builder.nextDeclarationId(),
                        name = name,
                        origin = DeclarationOrigin.BUILTIN,
                        owner = DeclarationOwner.Root,
                        range = documented.range,
                        declaredType = documented.type
                    )

                documented != null -> globalDeclaration(
                    id = builder.nextDeclarationId(),
                    name = name,
                    origin = DeclarationOrigin.BUILTIN,
                    owner = DeclarationOwner.Root,
                    range = documented.range,
                    declaredType = documented.type
                )

                name in globals.moduleFieldNames.keys || name == "_G" || name == "_VERSION" ->
                    globalDeclaration(
                        id = builder.nextDeclarationId(),
                        name = name,
                        origin = DeclarationOrigin.BUILTIN,
                        owner = DeclarationOwner.Root
                    )

                // AndroLua import-install helpers fall here when overlay surface is thin:
                // still seed as functions so free-id completions keep FUNCTION kind.
                name in ANDROLUA_IMPORT_INSTALL_HELPER_GLOBALS -> functionDeclaration(
                    id = builder.nextDeclarationId(),
                    name = name,
                    origin = DeclarationOrigin.BUILTIN,
                    owner = DeclarationOwner.Root
                )

                else -> functionDeclaration(
                    id = builder.nextDeclarationId(),
                    name = name,
                    origin = DeclarationOrigin.BUILTIN,
                    owner = DeclarationOwner.Root
                )
            }
            builder.addDeclarationWithSymbol(declaration)
        }
    }

    private fun isDocumentedCallableGlobal(kind: SymbolKind, type: Type): Boolean {
        if (kind == SymbolKind.FUNCTION || kind == SymbolKind.METHOD) {
            return true
        }
        // Documented FunctionType/OverloadedFunctionType free-ids (env_import, import, loadlayout…)
        // must seed as FUNCTION even when EmmyLua parse marks them FIELD via assignment forms.
        return type is FunctionType || type is OverloadedFunctionType
    }

    /**
     * Free-id helpers installed by AndroLua runtime `require "import"` / `env_import(_G)`.
     * Keep in sync with BuiltinOverlayLoader.androlua53Catalog globalNames helpers.
     */
    private val ANDROLUA_IMPORT_INSTALL_HELPER_GLOBALS = setOf(
        "import",
        "env_import",
        "compile",
        "enum",
        "each",
        "dump",
        "printstack",
        "getids",
        "thread",
        "task",
        "timer",
        "loadlayout",
        "loadbitmap",
        "loadmenu"
    )
}
