local M = {}
local dep = require("dep")
module("legacy.module", package.seeall)
function M.make(name, ...)
    return { name = name, dep = dep, args = {...} }
end
return {
    make = M.make,
    dep = dep,
}
