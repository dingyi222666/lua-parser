package io.github.dingyi222666.luaparser.semantic.binder

import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.OverloadedFunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.Type
import io.github.dingyi222666.luaparser.semantic.types.model.UnknownType
import io.github.dingyi222666.luaparser.semantic.workspace.std.BuiltinOverlaySnapshot

internal object BuiltinSymbolSeeder {
    /**
     * Seeds root builtin VALUE declarations from the active overlay globals snapshot.
     *
     * Android-Lua / AndroLua overlays document `luajava` as a ModuleType with helper methods
     * (including `getContext(): AndroidLuaContext`). Seeding those declared types is required so
     * ExpressionTypeEvaluator can resolve documented getContext returns and activity/
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
        globals: BuiltinOverlaySnapshot.GlobalsSnapshot
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
                documented != null && isDocumentedCallableGlobal(documented.type) ->
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

                else -> globalDeclaration(
                    id = builder.nextDeclarationId(),
                    name = name,
                    origin = DeclarationOrigin.BUILTIN,
                    owner = DeclarationOwner.Root
                )
            }
            builder.addDeclarationWithSymbol(declaration)
        }
    }

    private fun isDocumentedCallableGlobal(type: Type): Boolean {
        // Documented FunctionType/OverloadedFunctionType free-ids (env_import, import, loadlayout…)
        // must seed as FUNCTION even when EmmyLua parse marks them FIELD via assignment forms.
        return type is FunctionType || type is OverloadedFunctionType
    }

}
