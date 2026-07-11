local function wrap(first, ...)
    local pack = { first, ... }
    return function(extra)
        return first, extra, ...
    end
end

return wrap(1, 2)
