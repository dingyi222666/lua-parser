-- Module model for resources/lua/check.lua.

---@class check
local check = {}

---@param env table
---@param id string
---@param key string
---@param field? string
function check.reg(env, id, key, field) end

---@param key string
function check.unreg(key) end

---@param env table
---@param key string
---@param target table
---@param value any
function check.new(env, key, target, value) end

---@param env table
---@param key string
---@param target table
---@param value any
function check.final(env, key, target, value) end

---@param env table
function check.check(env) end

---@param env table
function check.uncheck(env) end

return check
