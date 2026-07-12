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
---@field performClick fun(): boolean
---@field setText fun(text: string|any)
---@field getText fun(): string
---@field setOnClickListener fun(listener: any)
---@field getWidth fun(): integer
---@field getHeight fun(): integer
---@field setVisibility fun(visibility: integer)
local AndroidView = {}

---@class AndroidMenu: JavaObject
---@field add fun(...: any): AndroidMenuItem
---@field findItem fun(id: integer|string): AndroidMenuItem
---@field clear fun()
---@field size fun(): integer
local AndroidMenu = {}

---@class AndroidMenuItem: JavaObject
---@field getTitle fun(): string
---@field setTitle fun(title: string): AndroidMenuItem
---@field setEnabled fun(enabled: boolean): AndroidMenuItem
---@field setVisible fun(visible: boolean): AndroidMenuItem
local AndroidMenuItem = {}

---@class Bitmap: JavaObject
---@field getWidth fun(): integer
---@field getHeight fun(): integer
---@field getByteCount fun(): integer
---@field recycle fun()
---@field isRecycled fun(): boolean
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
---@field setContentView fun(view: AndroidView|LuaLayoutSpec|any)
---@field getMenu fun(): AndroidMenu
---@field getSystemService fun(name: string): any
local AndroidLuaContext = {}

-- Jar-independent android.content.Context surface for the AndroLua `context`
-- global (TASK-651). Keep FQCN displayName while exposing getSystemService as a
-- modeled method even when host android.jar is absent or empty.
---@class android.content.Context
---@field getSystemService fun(name: string): any
---@field getResources fun(): any
---@field getAssets fun(): any
---@field getPackageName fun(): string
---@field getPackageManager fun(): any
---@field startActivity fun(intent: any)
---@field startService fun(intent: any): boolean
---@field getSharedPreferences fun(name: string, mode: integer): any
local AndroidContentContext = {}

---@class LuaActivity: AndroidLuaContext
local LuaActivity = {}

---@class LuaService: AndroidLuaContext
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
