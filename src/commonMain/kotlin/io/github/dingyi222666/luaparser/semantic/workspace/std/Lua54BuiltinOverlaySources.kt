package io.github.dingyi222666.luaparser.semantic.workspace.std

internal object Lua54BuiltinOverlaySources {
    val providerModuleSources: Map<String, String> = linkedMapOf(
        "coroutine" to """
            local coroutine = {}
            function coroutine.create(fn) end
            return coroutine
        """.trimIndent(),
        "debug" to """
            local debug = {}
            function debug.traceback(thread, message, level) end
            return debug
        """.trimIndent(),
        "io" to """
            local io = {}
            function io.open(filename, mode) end
            return io
        """.trimIndent(),
        "math" to """
            local math = {}
            function math.abs(value) end
            return math
        """.trimIndent(),
        "os" to """
            local os = {}
            function os.clock() end
            return os
        """.trimIndent(),
        "package" to """
            local package = {}
            function package.loaded() end
            function package.searchpath(name, path) end
            function package.seeall(module) end
            return package
        """.trimIndent(),
        "string" to """
            local string = {}
            function string.format(fmt, ...) end
            return string
        """.trimIndent(),
        "table" to """
            local table = {}
            function table.insert(list, value) end
            return table
        """.trimIndent(),
        "utf8" to """
            local utf8 = {}
            function utf8.len(s, i, j) end
            return utf8
        """.trimIndent()
    )

    val globalsSource: String = """
        _G = _G
        _VERSION = _VERSION
        assert = assert
        collectgarbage = collectgarbage
        coroutine = coroutine
        debug = debug
        error = error
        io = io
        ipairs = ipairs
        math = math
        next = next
        os = os
        package = package
        pairs = pairs
        pcall = pcall
        print = print
        rawequal = rawequal
        rawget = rawget
        rawlen = rawlen
        rawset = rawset
        require = require
        select = select
        string = string
        table = table
        tonumber = tonumber
        tostring = tostring
        type = type
        utf8 = utf8
        warn = warn
        xpcall = xpcall
    """.trimIndent()

    val globalNames: Set<String> = linkedSetOf(
        "_G", "_VERSION", "assert", "collectgarbage", "coroutine", "debug", "error", "io",
        "ipairs", "math", "next", "os", "package", "pairs", "pcall", "print", "rawequal",
        "rawget", "rawlen", "rawset", "require", "select", "string", "table", "tonumber",
        "tostring", "type", "utf8", "warn", "xpcall"
    )

    val moduleFieldNames: Map<String, Set<String>> = linkedMapOf(
        "coroutine" to linkedSetOf("create"),
        "debug" to linkedSetOf("traceback"),
        "io" to linkedSetOf("open"),
        "math" to linkedSetOf("abs"),
        "os" to linkedSetOf("clock"),
        // `package.seeall` is carried as a compatibility shim for legacy module(..., package.seeall) handling.
        "package" to linkedSetOf("loaded", "searchpath", "seeall"),
        "string" to linkedSetOf("format"),
        "table" to linkedSetOf("insert"),
        "utf8" to linkedSetOf("len")
    )
}
