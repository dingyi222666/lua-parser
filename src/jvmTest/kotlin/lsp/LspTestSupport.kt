package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceFolder

/**
 * Package-level helpers shared by the lsp test suite. Each function is a verbatim
 * hoist of the identical private helper previously copy-pasted across the test files;
 * call sites are unchanged.
 */

internal fun service(): LuaLanguageService {
    return LuaLanguageService().apply {
        initialize(InitializeParams())
    }
}

internal fun serviceWithMetadata(metadata: Map<String, String> = emptyMap()): LuaLanguageService {
    return LuaLanguageService().apply {
        initialize(InitializeParams())
        if (metadata.isNotEmpty()) {
            setWorkspaceMetadata(metadata)
        }
        // initialize now builds the workspace on a background thread; tests
        // observe snapshot state right after setup, so wait for readiness.
        check(awaitWorkspaceReady()) { "background workspace build did not finish" }
    }
}

/**
 * Waits for the background workspace build started by initialize/setWorkspaceMetadata.
 */
internal fun LuaLanguageService.awaitReady(): LuaLanguageService {
    check(awaitWorkspaceReady()) { "background workspace build did not finish" }
    return this
}

internal fun workspaceService(): LuaLanguageService {
    return LuaLanguageService().apply {
        initialize(
            InitializeParams().apply {
                workspaceFolders = listOf(WorkspaceFolder("file:///workspace", "workspace"))
            }
        )
    }
}

internal fun unwrap(error: Throwable): Throwable {
    var current = error
    while (
        (current is ExecutionException || current is CompletionException) &&
        current.cause != null
    ) {
        current = current.cause!!
    }
    return current
}

internal fun isUnsupportedOperation(error: Throwable): Boolean {
    if (error is UnsupportedOperationException) {
        return true
    }
    val message = error.message.orEmpty()
    return message.contains("UnsupportedOperationException") ||
        message.contains("not implemented", ignoreCase = true)
}

internal fun hoverMarkup(hover: Hover): String {
    val contents = hover.contents ?: return ""
    return when {
        contents.isRight -> contents.right?.value.orEmpty()
        contents.isLeft -> contents.left.orEmpty().joinToString("\n") { either ->
            when {
                either.isRight -> either.right?.value.orEmpty()
                either.isLeft -> either.left?.toString().orEmpty()
                else -> ""
            }
        }
        else -> hover.toString()
    }
}

internal fun codeOf(diagnostic: Diagnostic): String? {
    val code = diagnostic.code ?: return null
    return if (code.isLeft) code.left else code.right?.toString()
}

internal fun fullChange(uri: String, version: Int?, text: String): DidChangeTextDocumentParams {
    return DidChangeTextDocumentParams(
        VersionedTextDocumentIdentifier(uri, version),
        listOf(TextDocumentContentChangeEvent(text))
    )
}
