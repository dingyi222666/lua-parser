::again::
for i = 1, 3 do
    local record = { index = i, [i] = values[i], flag = false }
end
for key, value in pairs(items) do
    local entry = { name = key, payload = value }
end
goto again
