-- Small companion module so `require("sample_module")` in sample.lua resolves
-- through the workspace index (workspace/initialized + didChangeWorkspaceFolders).
local sample_module = {}

function sample_module.describe(value)
    return "value: " .. tostring(value)
end

return sample_module
