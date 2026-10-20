local bindClass = luajava.bindClass
local bindAgain = bindClass
local File = bindAgain("java.io.File")

local newInstance = luajava.newInstance
local makeInstance = newInstance
local newFile = makeInstance("java.io.File", "build.gradle.kts")
local fileName = newFile.getName()

local createProxy = luajava.createProxy
local proxyAgain = createProxy
local runnable = proxyAgain("java.lang.Runnable", {})
local proxyRun = runnable.run

local loadLib = luajava.loadLib
local loadAgain = loadLib
local currentTimeMillis = loadAgain("java.lang.System", "currentTimeMillis")

return File, newFile, fileName, proxyRun, currentTimeMillis
