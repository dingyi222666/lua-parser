--- Shared helpers exported for require("utils").
local M = {}

function M.log(msg)
    print("[utils]", msg)
end

function M.clamp(x, lo, hi)
    if x < lo then return lo end
    if x > hi then return hi end
    return x
end

---@param items table
function M.join(items, sep)
    sep = sep or ", "
    local out = {}
    for i = 1, #items do
        out[i] = tostring(items[i])
    end
    return table.concat(out, sep)
end

return M
