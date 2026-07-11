require "import"

-- Runtime-only reflective LuaJava targets. These intentionally do not use
-- import-mounted Android framework providers; unresolved diagnostics prove the
-- semantic boundary when no real JVM classpath is available.
local TextViewClass = luajava.bindClass("android.widget.TextView")
local proxy = luajava.createProxy("android.view.View.OnClickListener", {})
local nativeOpen = luajava.loadLib("com.example.NativeOnly", "open")

return TextViewClass, proxy, nativeOpen
