-- AndroLua (LuaJ) bit32 compatibility library.
-- Lua 5.2-era bit ops kept available under Lua 5.3 hosts; real AndroLua projects
-- use these directly (demo workspace: bit32.band(app.applicationInfo.flags, ...)).

---
--- Returns the number `x` converted to bitwise-operable range.
---@param x number
---@return number
function bit32.tobit(x) return 0 end

---
--- Returns a hexadecimal string representation of `x` with `n` digits.
---@param x number
---@param n? number
---@return string
function bit32.tohex(x, n) return "" end

---
--- Returns the bitwise NOT of `x`.
---@param x number
---@return number
function bit32.bnot(x) return 0 end

---
--- Returns the bitwise AND of its arguments.
---@vararg number
---@return number
function bit32.band(...) return 0 end

---
--- Returns the bitwise OR of its arguments.
---@vararg number
---@return number
function bit32.bor(...) return 0 end

---
--- Returns the bitwise XOR of its arguments.
---@vararg number
---@return number
function bit32.bxor(...) return 0 end

---
--- Returns true if the bitwise AND of its arguments is non-zero.
---@vararg number
---@return boolean
function bit32.btest(...) return true end

---
--- Returns `x` shifted left by `disp` bits.
---@param x number
---@param disp number
---@return number
function bit32.lshift(x, disp) return 0 end

---
--- Returns `x` shifted right by `disp` bits (logical shift).
---@param x number
---@param disp number
---@return number
function bit32.rshift(x, disp) return 0 end

---
--- Returns `x` shifted right by `disp` bits (arithmetic shift).
---@param x number
---@param disp number
---@return number
function bit32.arshift(x, disp) return 0 end

---
--- Returns the bits of `x` between `field` and `field+width-1`.
---@param x number
---@param field number
---@param width? number
---@return number
function bit32.extract(x, field, width) return 0 end

---
--- Returns a copy of `x` with the bits between `field` and `field+width-1`
--- replaced by `v`.
---@param x number
---@param v number
---@param field number
---@param width? number
---@return number
function bit32.replace(x, v, field, width) return 0 end

---
--- Swaps the byte order of `x` (LuaJ extension).
---@param x number
---@return number
function bit32.bswap(x) return 0 end
