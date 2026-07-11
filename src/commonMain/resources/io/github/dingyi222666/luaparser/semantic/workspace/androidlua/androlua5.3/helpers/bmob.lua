-- Asset helper model for assets/bmob.lua.
-- Same public shape as resources/lua/bmob.lua in the studied Android-Lua tree.

---@class BmobClient
local BmobClient = {}

---@param id string
---@param key string
---@return BmobClient
function BmobClient:new(id, key) end

---@param className string
---@param query? table
---@param callback? fun(code: integer, body: any)
function BmobClient:query(className, query, callback) end

---@param className string
---@param data table
---@param callback? fun(code: integer, body: any)
function BmobClient:insert(className, data, callback) end

---@param className string
---@param objectId string
---@param data table
---@param callback? fun(code: integer, body: any)
function BmobClient:update(className, objectId, data, callback) end

---@param className string
---@param objectId string
---@param key string
---@param value number
---@param callback? fun(code: integer, body: any)
function BmobClient:increment(className, objectId, key, value, callback) end

---@param className string
---@param objectId string
---@param callback? fun(code: integer, body: any)
function BmobClient:delete(className, objectId, callback) end

---@param file string
---@param callback? fun(code: integer, body: any)
function BmobClient:upload(file, callback) end

---@param objectId string
---@param callback? fun(code: integer, body: any)
function BmobClient:remove(objectId, callback) end

---@param username string
---@param password string
---@param email? string
---@param callback? fun(code: integer, body: any)
function BmobClient:sign(username, password, email, callback) end

---@param username string
---@param password string
---@param callback? fun(code: integer, body: any)
function BmobClient:login(username, password, callback) end

return BmobClient
