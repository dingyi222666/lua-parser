-- lead statement comment
::entry::
local total, label = 0, "start"

do
    total = total + 1
end

while total < 3 do
    total = total + 1
end

repeat
    total = total - 1
until total == 1

for i = 1, 3, 1 do
    total = total + i
end

for key, value in pairs(items), next do
    consume(key, value)
end

function mod.run(self, ...)
    return self, ...
end

local function finish(value)
    return value
end

finish(total)
goto entry
return total, label
