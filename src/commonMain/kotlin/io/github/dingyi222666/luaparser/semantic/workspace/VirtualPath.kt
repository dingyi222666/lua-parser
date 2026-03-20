package io.github.dingyi222666.luaparser.semantic.workspace

data class VirtualPath private constructor(val value: String) {
    init {
        require(value.isNotEmpty()) { "VirtualPath cannot be empty." }
        require(!value.startsWith('/')) { "VirtualPath must be workspace-relative." }
        require(value != ".") { "VirtualPath must not resolve to the workspace root." }
    }

    override fun toString(): String = value

    fun resolve(child: String): VirtualPath = of(
        when {
            child.isEmpty() -> value
            else -> "$value/$child"
        }
    )

    companion object {
        fun of(path: String): VirtualPath {
            val normalized = normalize(path)
            require(normalized.isNotEmpty()) { "VirtualPath cannot be empty." }
            return VirtualPath(normalized)
        }

        private fun normalize(path: String): String {
            val normalizedSeparators = path.replace('\\', '/')
            val segments = mutableListOf<String>()
            normalizedSeparators.split('/')
                .filter { it.isNotEmpty() && it != "." }
                .forEach { segment ->
                    when (segment) {
                        ".." -> {
                            require(segments.isNotEmpty()) { "VirtualPath cannot escape the workspace: $path" }
                            segments.removeAt(segments.lastIndex)
                        }

                        else -> segments += segment
                    }
                }
            return segments.joinToString("/")
        }
    }
}
