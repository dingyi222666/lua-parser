-- Module model for resources/lua/console.lua.

---@class console
local console = {}

---@param text string
---@return string
function console.format(text) end

---@param path string
---@return any
function console.build(path) end

---@param path string
---@return any
function console.build_aly(path) end

return console
