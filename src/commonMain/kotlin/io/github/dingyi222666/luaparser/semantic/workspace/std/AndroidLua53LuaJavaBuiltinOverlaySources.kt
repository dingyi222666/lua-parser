package io.github.dingyi222666.luaparser.semantic.workspace.std

internal object AndroidLua53LuaJavaBuiltinOverlaySources {
    val luajavaSource: String = """
        -- AndroLua 5.3 LuaJava helper overlay.

        ---@class luajava
        ---@field loaded table<string, JavaClass<any>> Shared cache of bound Java classes.
        ---@field imported string[] Ordered list of explicit and wildcard imports.
        ---@field ids LuaLayoutIds Current layout id table used by loadlayout helpers.
        ---@field luadir string Current Lua project directory when supplied by context.
        local luajava = {}

        --- Binds a fully qualified Java class name. Bound classes expose static
        --- fields/methods and can be called to construct Java instances.
        ---@param className string
        ---@return JavaClass<any>
        function luajava.bindClass(className) end

        --- Constructs an instance from a previously bound Java class userdata.
        ---@param class JavaClass<any>
        ---@return JavaObject
        function luajava.new(class, ...) end

        --- Binds a Java class name and invokes a matching constructor.
        ---@param className string
        ---@return JavaObject
        function luajava.newInstance(className, ...) end

        --- Loads a static Java member or runtime loader method from a class.
        ---@param className string
        ---@param methodName string
        ---@return any
        function luajava.loadLib(className, methodName) end

        --- Creates a Java interface proxy backed by Lua callbacks.
        ---@overload fun(interfaceName: string, callbacks: table<string, function>): JavaProxy
        ---@overload fun(interfaceName1: string, interfaceName2: string, callbacks: table<string, function>): JavaProxy
        ---@param interfaceNames string
        ---@param callbacks table<string, function>
        ---@return JavaProxy
        function luajava.createProxy(interfaceNames, callbacks) end

        --- Allocates a typed Java array using a bound component class.
        ---@param class JavaClass<any>
        ---@param ... integer
        ---@return JavaArray<any>
        function luajava.newArray(class, ...) end

        --- Converts a Lua table to a typed Java array using a class name.
        ---@param className string
        ---@param values table
        ---@return JavaArray<any>
        function luajava.createArray(className, values) end

        --- Converts Java arrays, collections, or maps into Lua tables.
        ---@param object JavaObject
        ---@return table
        function luajava.astable(object) end

        ---@param object JavaObject
        ---@return string
        function luajava.tostring(object) end

        ---@param object any
        ---@param class JavaClass<any>|string
        ---@return boolean
        function luajava.instanceof(object, class) end

        ---@return AndroidLuaContext
        function luajava.getContext() end

        --- Creates a Lua-backed subclass or override object for a Java class.
        ---@param class JavaClass<any>
        ---@param implementation table<string, function>
        ---@return JavaObject
        function luajava.override(class, implementation) end

        return luajava
    """.trimIndent()

    val globalsSource: String = """
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
        local AndroidView = {}

        ---@class AndroidMenu: JavaObject
        local AndroidMenu = {}

        ---@class AndroidMenuItem: JavaObject
        local AndroidMenuItem = {}

        ---@class Bitmap: JavaObject
        local Bitmap = {}

        ---@class LuaLayoutIds: table<string, integer|AndroidView>

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
    """.trimIndent()
}
