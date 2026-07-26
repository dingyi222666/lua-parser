-- AndroLua 5.3 LuaJava helper globals overlay.
-- Models the symbols commonly installed after require "import".

---@class JavaObject
local JavaObject = {}

---@class JavaClass<T>: JavaObject
local JavaClass = {}

---@class JavaArray<T>: JavaObject
local JavaArray = {}

---@class JavaProxy: JavaObject
local JavaProxy = {}

---@class AndroidView: JavaObject
---@java-class android.view.View
local AndroidView = {}

---@class AndroidMenu: JavaObject
---@java-class android.view.Menu
local AndroidMenu = {}

---@class AndroidMenuItem: JavaObject
---@java-class android.view.MenuItem
local AndroidMenuItem = {}

---@class Bitmap: JavaObject
---@java-class android.graphics.Bitmap
local Bitmap = {}

---@class LuaLayoutIds: table<string, integer|AndroidView>
---@field [string] AndroidView|integer

---@class LuaLayoutSpec: table
---@field [1] JavaClass<AndroidView>|string
---@field id? string
---@field text? string
---@field title? string
---@field src? string
---@field onClick? fun(view: AndroidView)|JavaProxy|string|table

---@class AndroidLuaContext
---@field luaDir string
---@field luaPath string
---@field Width integer
---@field Height integer
---@field getContext fun(): any
---@field getLuaDir fun(): string
---@field getLuaPath fun(): string
---@field getLuaExtDir fun(): string
---@field getLuaExtPath fun(...: any): string
---@field getClassLoaders fun(): table
---@field getLibrarys fun(): table<string, string>
---@field loadDex fun(name: string): JavaObject
---@field sendMsg fun(message: any)
---@field sendError fun(title: string, error: any)
---@field newActivity fun(path: string, arg?: table)
---@field newTask fun(src: string|function, callback?: function): JavaObject
---@field newThread fun(src: string|function): JavaObject
local AndroidLuaContext = {}

---@class android.content.Context
---@java-class android.content.Context
local AndroidContentContext = {}

---@class LuaActivity: AndroidLuaContext
---@java-class android.app.Activity
local LuaActivity = {}

---@class LuaService: AndroidLuaContext
---@java-class android.app.Service
local LuaService = {}

---@type LuaActivity
activity = activity

---@type LuaService
service = service

---@type LuaActivity|LuaService|AndroidLuaContext
this = this

---@type android.content.Context
context = context

---@type luajava
luajava = luajava

--- Imports Java classes, Lua modules, dex classes, or wildcard package prefixes
--- into the target environment. String imports return the resolved class/module
--- or a lazy package proxy; table imports return an array of resolved entries.
---@param package string|string[]
---@param env? table
---@return any
function import(package, env) end

--- Installs Android-Lua import helpers and lazy Java class lookup on an
--- environment. The runtime module calls this for _G when require "import" is
--- loaded, and returns this installer as the module value.
---@param env? table|string
---@return table|JavaClass<any>
function env_import(env) end

---@param name string
function compile(name) end

---@param enumeration JavaObject
---@return fun(): any
function enum(enumeration) end

---@param iterable JavaObject
---@return fun(): any
function each(iterable) end

---@param value any
---@return string
function dump(value) end

function printstack() end

---@return LuaLayoutIds
function getids() end

---@param src string|function
---@return JavaObject
function thread(src, ...) end

---@param src string|function
---@return JavaObject
function task(src, ...) end

---@param callback string|function
---@param delay integer
---@param period? integer
---@return JavaObject
function timer(callback, delay, period, ...) end

---@param path string
---@return Bitmap
function loadbitmap(path) end

---@param layout LuaLayoutSpec|table|string
---@param ids? LuaLayoutIds
---@param group? JavaClass<any>
---@return AndroidView
function loadlayout(layout, ids, group) end

---@param menu AndroidMenu|JavaObject|table
---@param spec? table
---@param root? table
---@param actionCount? integer
---@return AndroidMenu
function loadmenu(menu, spec, root, actionCount) end
