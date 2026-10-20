-- Android-Lua import.lua model. Runtime side effects are dynamic; this file
-- declares the callable surface and helper globals installed by require "import".
-- Runtime returns env_import and mutates _G with import helpers (see AndroLua import.lua).

---@class AndroidLuaImportModule
local M = {}

--- Installs Android-Lua import helpers and lazy Java class lookup on an
--- environment. The runtime module calls this for _G when require "import" is
--- loaded, and returns this installer as the module value.
---@param env? table|string
---@return table|JavaClass<any>
local function env_import(env) end

---@param package string|string[]
---@param env? table
---@return any
function M.import(package, env) end

---@param name string
function M.compile(name) end

---@param enumeration JavaObject
---@return fun(): any
function M.enum(enumeration) end

---@param iterable JavaObject
---@return fun(): any
function M.each(iterable) end

---@param value any
---@return string
function M.dump(value) end

function M.printstack() end

---@return LuaLayoutIds
function M.getids() end

---@param src string|function
---@return JavaObject
function M.thread(src, ...) end

---@param src string|function
---@return JavaObject
function M.task(src, ...) end

---@param callback string|function
---@param delay integer
---@param period? integer
---@return JavaObject
function M.timer(callback, delay, period, ...) end

-- Helper aliases installed onto the target environment by env_import(_G).
-- Runtime also keeps the global `luajava` module (with getContext(): AndroidLuaContext)
-- on the target environment; callers type host context via luajava.getContext().
-- TASK-575: getContext return is hard-locked to AndroidLuaContext (overlay ClassType members
-- such as getLuaDir/getLuaPath/setContentView) — never invent android.jar-only APIs here.
M.env_import = env_import
M.compile = M.compile
M.enum = M.enum
M.each = M.each
M.dump = M.dump
M.printstack = M.printstack
M.getids = M.getids
M.thread = M.thread
M.task = M.task
M.timer = M.timer

return env_import
