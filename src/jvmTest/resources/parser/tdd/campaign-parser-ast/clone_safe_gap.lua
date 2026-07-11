local seed = 1 + 2 * 3

for i = 1, 3, 1 do
    seed = seed + i
end

local pack = { seed, call(seed), (seed + 1) }
