#!/usr/bin/env lua
-- line comment
---@class LexerFixture
local decimal = 42
local hex = 0x2a
local short = "line\nfeed"
local single = 'quote\'s'
local long = [=[alpha
beta]=]
--[=[ block comment ]=]
return decimal, hex, short, single, long, "a" .. "b"
