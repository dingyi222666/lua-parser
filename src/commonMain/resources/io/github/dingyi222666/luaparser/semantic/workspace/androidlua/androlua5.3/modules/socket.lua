-- Module model for resources/lua/socket.lua and native LuaSocket entry points.

---@class LuaSocketClient
local LuaSocketClient = {}

---@param pattern? string|integer
---@return string|nil, string?
function LuaSocketClient:receive(pattern) end

---@param data string
---@param i? integer
---@param j? integer
---@return integer|nil, string?
function LuaSocketClient:send(data, i, j) end

---@param timeout number
function LuaSocketClient:settimeout(timeout) end

---@return any
function LuaSocketClient:close() end

---@return integer
function LuaSocketClient:getfd() end

---@return boolean
function LuaSocketClient:dirty() end

---@class LuaSocketServer
local LuaSocketServer = {}

---@return LuaSocketClient|nil, string?
function LuaSocketServer:accept() end

---@param timeout number
function LuaSocketServer:settimeout(timeout) end

---@return any
function LuaSocketServer:close() end

---@class socket
---@field _VERSION string
---@field BLOCKSIZE integer
---@field sourcet table<string, function>
---@field sinkt table<string, function>
local socket = {}

---@param address string
---@param port integer|string
---@param laddress? string
---@param lport? integer|string
---@return LuaSocketClient|nil, string?
function socket.connect(address, port, laddress, lport) end

---@param address string
---@param port integer|string
---@param laddress? string
---@param lport? integer|string
---@return LuaSocketClient|nil, string?
function socket.connect4(address, port, laddress, lport) end

---@param address string
---@param port integer|string
---@param laddress? string
---@param lport? integer|string
---@return LuaSocketClient|nil, string?
function socket.connect6(address, port, laddress, lport) end

---@param host string
---@param port integer|string
---@param backlog? integer
---@return LuaSocketServer|nil, string?
function socket.bind(host, port, backlog) end

---@param table table<string, function>
---@return function
function socket.choose(table) end

---@return function
function socket.newtry(finalizer) end

---@param ok any
---@return any
function socket.try(ok, ...) end

---@param f function
---@return function
function socket.protect(f) end

---@param count integer
---@return any
function socket.skip(count, ...) end

---@param mode string
---@param socket LuaSocketClient
---@param length? integer
---@return Ltn12Source
function socket.source(mode, socket, length) end

---@param mode string
---@param socket LuaSocketClient
---@return Ltn12Sink
function socket.sink(mode, socket) end

return socket
