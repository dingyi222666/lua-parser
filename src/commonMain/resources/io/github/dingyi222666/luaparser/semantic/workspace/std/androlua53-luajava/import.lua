-- AndroLua 5.3 require "import" overlay.

---@class AndroidLuaImportModule
local M = {}

--- Installs import helpers into an environment and enables lazy Java class
--- lookup using default package prefixes and later wildcard imports.
---@param env? table|string
---@return table|JavaClass<any>
local function env_import(env) end

--- Imports a class/module name or an array of names into the selected
--- environment. Examples: "java.io.File", "java.io.*", and
--- "dexname:fully.qualified.ClassName".
---@param package string|string[]
---@param env? table
---@return any
function M.import(package, env) end

--- Compiles a Lua source file through the Android-Lua runtime helper.
---@param name string
function M.compile(name) end

--- Iterates a Java Enumeration.
---@param enumeration JavaObject
---@return fun(): any
function M.enum(enumeration) end

--- Iterates a Java Iterable.
---@param iterable JavaObject
---@return fun(): any
function M.each(iterable) end

--- Serializes a Lua value for diagnostic output.
---@param value any
---@return string
function M.dump(value) end

--- Prints the current Lua stack for debugging.
function M.printstack() end

--- Returns the current loadlayout id table.
---@return LuaLayoutIds
function M.getids() end

--- Starts an Android-Lua thread from source text or a Lua function.
---@param src string|function
---@return JavaObject
function M.thread(src, ...) end

--- Starts an Android-Lua async task from source text or a Lua function.
---@param src string|function
---@return JavaObject
function M.task(src, ...) end

--- Starts an Android-Lua timer.
---@param callback string|function
---@param delay integer
---@param period? integer
---@return JavaObject
function M.timer(callback, delay, period, ...) end

return env_import
