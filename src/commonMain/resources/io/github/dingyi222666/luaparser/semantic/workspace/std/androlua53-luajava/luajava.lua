-- AndroLua 5.3 LuaJava helper overlay.

---@class luajava
---@field loaded table<string, JavaClass<any>> Shared cache of bound Java classes.
---@field imported string[] Ordered list of explicit and wildcard imports.
---@field ids LuaLayoutIds Current layout id table used by loadlayout helpers.
---@field luadir string Current Lua project directory when supplied by context.
local luajava = {}

--- Binds a fully qualified Java class name, including primitive names such as
--- "int" and "boolean". Bound classes expose static fields/methods and can be
--- called to construct instances.
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

--- Loads a Java static member or loader method from a class.
---@param className string
---@param methodName string
---@return any
function luajava.loadLib(className, methodName) end

--- Creates a Java interface proxy backed by Lua callbacks. Android-Lua accepts
--- comma-separated interface names and, in compatibility layers, multiple
--- string interface arguments before the callback table.
---@overload fun(interfaceName: string, callbacks: table<string, function>): JavaProxy
---@overload fun(interfaceName1: string, interfaceName2: string, callbacks: table<string, function>): JavaProxy
---@param interfaceNames string
---@param callbacks table<string, function>
---@return JavaProxy
function luajava.createProxy(interfaceNames, callbacks) end

--- Allocates a typed Java array using a bound component class and dimensions.
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
---@overload fun<K, V>(object: java.util.Map<K, V>): table<K, V>
---@overload fun<T>(object: java.util.List<T>): table<number, T>
---@overload fun<T>(object: java.util.Collection<T>): table<number, T>
---@overload fun<T>(object: JavaArray<T>): table<number, T>
---@param object any
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
