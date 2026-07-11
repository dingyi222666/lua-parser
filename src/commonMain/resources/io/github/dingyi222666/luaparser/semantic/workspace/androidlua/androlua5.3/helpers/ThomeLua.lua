-- Asset helper model for assets/ThomeLua.lua.
-- ThomeLua overlaps heavily with AndLua and adds conversion helpers.

---@class ThomeLua: AndLua
local ThomeLua = {}

---@param source string
---@param index integer|string
---@return integer
function ThomeLua.byteAt(source, index) end

---@param code integer|string
---@return string
function ThomeLua.charFromCode(code) end

---@param source string
---@param index integer|string
---@param code integer|string
---@return boolean
function ThomeLua.byteEquals(source, index, code) end

return ThomeLua
