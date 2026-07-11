-- Module model for resources/lua/json.lua.

---@class json
local json = {}

---@param value any
---@return string
function json.encode(value) end

---@param source string
---@param startPos? integer
---@return any
function json.decode(source, startPos) end

---@return any
function json.null() end

---@param source string
---@return string
function json.encodeString(source) end

---@param value table
---@return boolean
function json.isArray(value) end

---@param value any
---@return boolean
function json.isEncodable(value) end

return json
