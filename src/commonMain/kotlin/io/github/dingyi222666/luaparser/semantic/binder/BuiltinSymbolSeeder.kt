package io.github.dingyi222666.luaparser.semantic.binder

import io.github.dingyi222666.luaparser.semantic.workspace.std.BuiltinOverlayLoader
import io.github.dingyi222666.luaparser.semantic.workspace.std.BuiltinOverlaySnapshot

internal object BuiltinSymbolSeeder {
    private val standaloneCompatibilityGlobals = BuiltinOverlayLoader.standaloneGlobals()

    fun seed(
        builder: SymbolTableBuilder,
        globals: BuiltinOverlaySnapshot.GlobalsSnapshot = standaloneCompatibilityGlobals
    ) {
        globals.globalNames.forEach { name ->
            val declaration = if (name in globals.moduleFieldNames.keys || name == "_G" || name == "_VERSION") {
                globalDeclaration(
                    id = builder.nextDeclarationId(),
                    name = name,
                    origin = DeclarationOrigin.BUILTIN,
                    owner = DeclarationOwner.Root
                )
            } else {
                functionDeclaration(
                    id = builder.nextDeclarationId(),
                    name = name,
                    origin = DeclarationOrigin.BUILTIN,
                    owner = DeclarationOwner.Root
                )
            }
            builder.addDeclarationWithSymbol(declaration)
        }
    }
}
