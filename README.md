# lua-parser

## _work in progress_

A Lua 5.3 Lexer & Parser written in pure Kotlin.

## Features

- [X] Kotlin Multiplatform support (JVM / JS / Native)
- [x] Parse source to AST
- [x] Transform AST to source code
- [ ] Semantic analysis. Provide type information (Work in progress)

## Usage

- Add the dependency to your gradle file

```kotlin
implementation("io.github.dingyi222666:luaparser:1.0.0")
```

Ok. Use it like this:

```kotlin
val lexer = LuaLexer("print('hello world')")
val parser = LuaParser()

val root = parser.parse(lexer)

println(AST2Lua().asCode(root))
```

More usage coming soon.

## Special thanks

[GavinHigham/lpil53](https://github.com/GavinHigham/lpil53)

[fstirlitz/luaparse](https://github.com/fstirlitz/luaparse)

[Rosemose/sora-editor](https://github.com/Rosemoe/sora-editor/blob/main/language-java/src/main/java/io/github/rosemoe/sora/langs/java/JavaTextTokenizer.java)