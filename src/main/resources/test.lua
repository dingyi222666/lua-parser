local a, b, c = 12, true, nil
local d = {
    a = 12,
    ['c'] = 15,
    --[[a = 13,]]
    d = {
        a = 12
    }
}

function d.e()

end