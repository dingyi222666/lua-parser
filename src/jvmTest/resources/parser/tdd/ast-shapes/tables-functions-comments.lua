--- doc for module
local module = {
    name = "demo",
    [key] = value,
    child = { enabled = true },
    42,
}

-- regular comment
function module:create(first, ...)
    local packed = { first, ... }
    return self.name, packed
end

return module
