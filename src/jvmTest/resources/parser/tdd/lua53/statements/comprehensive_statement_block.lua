::entry::
local total, label = 0, "start"

do
    total = total + 1
    if total == 1 then
        label = "one"
    elseif total == 2 then
        label = "two"
    else
        label = "many"
    end
end

while total < 3 do
    total = total + 1
    if total == 2 then break end
end

repeat
    total = total - 1
until total == 1

for i = 1, 3, 1 do
    total = total + i
end

for key, value in pairs(items), next do
    total = total + value
end

function mod.run(self, ...)
    local argc = ...
    return self, argc
end

local function finish(value)
    print(value)
    return value
end

finish(total)
goto entry
return total, label
