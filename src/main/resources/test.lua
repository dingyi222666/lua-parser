local a, b,c,d = 12, true,{},""

function c.c(self)
    return self.d
end

c.d = 12

local e = c:c()

