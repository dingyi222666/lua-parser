--- Greeter module: definition / references demo.
local M = {}

--- Build a greeting string.
---@param name string
---@return string
function M.hello(name)
    return "hello, " .. tostring(name)
end

function M.goodbye(name)
    return "bye, " .. tostring(name)
end

return M
