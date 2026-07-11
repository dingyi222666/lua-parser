-- Module model for resources/lua/hex.lua.

---@class hex
local hex = {}

---@param value string
---@return string
function hex.encode(value) end

---@param value string
---@return string
function hex.decode(value) end

---@param value string
---@param delimiter? string
---@param stx? string
---@param etx? string
---@return string
function hex.dump(value, delimiter, stx, etx) end

---@param value string
---@return string
function hex.smart_dump(value) end

---@param value string
---@return string
function hex.pack(value) end

---@param value string
---@return string
function hex.smart_pack(value) end

return hex
