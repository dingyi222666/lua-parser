-- Handwritten LuaJava declaration overlay for Android-Lua 5.3.

---@class luajava
---@field loaded table<string, JavaClass<any>>
---@field imported string[]
---@field ids LuaLayoutIds
---@field luadir string
local luajava = {}

---@param className string
---@return JavaClass<any>
function luajava.bindClass(className) end

---@param class JavaClass<any>
---@return JavaObject
function luajava.new(class, ...) end

---@param className string
---@return JavaObject
function luajava.newInstance(className, ...) end

---@param className string
---@param methodName string
---@return any
function luajava.loadLib(className, methodName) end

---@param interfaceNames string
---@param callbacks table<string, function>
---@return JavaProxy
function luajava.createProxy(interfaceNames, callbacks) end

---@param class JavaClass<any>
---@param ... integer
---@return JavaArray<any>
function luajava.newArray(class, ...) end

---@param className string
---@param values table
---@return JavaArray<any>
function luajava.createArray(className, values) end

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

---@param class JavaClass<any>
---@param implementation table<string, function>
---@return JavaObject
function luajava.override(class, implementation) end

return luajava
