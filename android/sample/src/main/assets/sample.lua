-- Lua LSP sample project (copied to filesDir/project/sample.lua on first run).
--
-- Everything below is served by the EMBEDDED lua-parser language server
-- (io.github.dingyi222666.luaparser) over a local socket:
--   * diagnostics as you type (didChange -> publishDiagnostics),
--   * completion (stdlib + workspace + luajava interop),
--   * hover, definition, document symbols, semantic tokens, formatting, ...
-- TextMate (source.lua) only paints the highlight colors.

local function greet(name)
    return "hello, " .. name .. "!"
end

local message = greet("android")
print(message)

-- Standard library completion: try typing `math.` on a new line.
local biggest = math.max(3, 14159265)

-- Workspace-wide resolution: require across files in the same folder works
-- once more files are written into filesDir/project/.
local sample_module = require("sample_module")

-- JVM interop (only active when assets/android.jar was provided):
-- hover and completion for java.* types come from the mounted android.jar.
local system = luajava.bindClass("java.lang.System")
print(system:currentTimeMillis())

return {
    message = message,
    biggest = biggest,
    module = sample_module,
}
