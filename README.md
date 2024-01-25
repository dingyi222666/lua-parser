# lua-parser

## _work in progress_

A Lua 5.3 parser written in pure kotlin.

## Features

- [X] Kotlin Multiplatform support (JVM/JS/Native)
- [x] Parse Source To AST
- [x] Transform AST to source code
- [ ] Semantic Analysis. Provide type information (Work in progress)

#  # Usage

```kotlin
val lexer = LuaLexer(source)
val parser = LuaParser()

val root = parser.parse(lexer)

println(AST2Lua().asCode(root))
```

More usage coming soon.

## Special thanks

[GavinHigham/lpil53](https://github.com/GavinHigham/lpil53)

[fstirlitz/luaparse](https://github.com/fstirlitz/luaparse)

[Rosemose/sora-editor](https://github.com/Rosemoe/sora-editor/blob/main/language-java/src/main/java/io/github/rosemoe/sora/langs/java/JavaTextTokenizer.java)