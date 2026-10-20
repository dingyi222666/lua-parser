local Arrays = luajava.bindClass("java.util.Arrays")
local Locale = luajava.bindClass("java.util.Locale")
local Integer = luajava.bindClass("java.lang.Integer")

local values = Arrays.asList("alpha", "beta")
local count = values.size()
local locales = Locale.getAvailableLocales()
local parsed = Integer.parseInt("42")
local firstTag = Locale.forLanguageTag("en-US").toLanguageTag()

return values, count, locales, parsed, firstTag
