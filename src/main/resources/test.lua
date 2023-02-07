local a, b = 12, true

local function c(a)
    return a .. "6"
end

local function d(t)
    return 12 + t
end

local e = d(c(a))
