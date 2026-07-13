--- Entry module: exercises require, locals, and cross-file defs.
local utils = require("utils")
local greeter = require("greeter")

local function run(name)
    local message = greeter.hello(name or "world")
    utils.log(message)
    return message
end

-- Hover / completion targets
local n = 42
local s = "lua-parser"
print(run(s), n)

return {
    run = run,
}
