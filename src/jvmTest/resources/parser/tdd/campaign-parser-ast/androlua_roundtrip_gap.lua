local mapper = lambda value -> value + 1
local ids = [mapper(1), mapper(2), mapper(3)]
print("ready")
Button({ text = "Save" })
return ids[1], mapper(ids[2])
