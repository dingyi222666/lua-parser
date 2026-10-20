local total = 0
local items = { 1, 2, 3 }

function tally(list)
    local sum = 0
    for index, value in ipairs(list) do
        if value % 2 == 0 then
            sum = sum + value
        elseif value > 10 then
            sum = sum + value * 2
        else
            sum = sum + 1
        end
    end
    repeat
        sum = sum - 1
    until sum <= 10
    return sum
end

for key, value in pairs(items) do
    total = total + tally({ key, value })
end

while total < 20 do
    total = total + 1
end

return total
