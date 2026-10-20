package io.github.dingyi222666.luaparser.semantic.workspace

import io.github.dingyi222666.luaparser.parser.LuaParseResult
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachmentIndex

internal class DocumentRevision(
    val source: String,
    val parsed: LuaParseResult
) {
    var facts: DocumentFacts? = null
    var comments: CommentAttachmentIndex? = null
    val fingerprint: String by lazy { workspaceFingerprintHash(source) }
    val tokenFingerprint: SourceTokenFingerprint by lazy { SourceTokenFingerprint(source) }
}

internal class DocumentRevisionStore {
    private val revisions = mutableMapOf<VirtualPath, DocumentRevision>()

    operator fun get(path: VirtualPath): DocumentRevision? = revisions[path]

    fun put(path: VirtualPath, source: String, parsed: LuaParseResult): DocumentRevision =
        DocumentRevision(source, parsed).also { revisions[path] = it }

    fun matches(path: VirtualPath, source: String): Boolean = revisions[path]?.source == source

    fun require(path: VirtualPath): DocumentRevision =
        checkNotNull(revisions[path]) { "Missing parsed document revision for $path" }

    fun comments(path: VirtualPath, compute: () -> CommentAttachmentIndex): CommentAttachmentIndex {
        val revision = require(path)
        return revision.comments ?: compute().also { revision.comments = it }
    }

    fun retain(paths: Set<VirtualPath>) {
        revisions.keys.retainAll(paths)
    }

    fun clear() = revisions.clear()
}
