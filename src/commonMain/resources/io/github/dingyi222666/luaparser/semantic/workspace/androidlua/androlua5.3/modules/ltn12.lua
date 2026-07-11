-- Module model for resources/lua/ltn12.lua.

---@alias Ltn12Source fun(): string|nil, string?
---@alias Ltn12Sink fun(chunk: string|nil, err?: string): any
---@alias Ltn12Filter fun(chunk: string|nil): string|nil, string?

---@class ltn12.filter
local filter = {}

---@param low function
---@param ctx? any
---@param extra? any
---@return Ltn12Filter
function filter.cycle(low, ctx, extra) end

---@return Ltn12Filter
function filter.chain(...) end

---@class ltn12.source
local source = {}

---@return Ltn12Source
function source.empty() end

---@param err string
---@return Ltn12Source
function source.error(err) end

---@param handle any
---@param io_err? string
---@return Ltn12Source
function source.file(handle, io_err) end

---@param src Ltn12Source
---@return Ltn12Source
function source.simplify(src) end

---@param s string
---@return Ltn12Source
function source.string(s) end

---@param src Ltn12Source
---@return Ltn12Source
function source.rewind(src) end

---@param src Ltn12Source
---@param f Ltn12Filter
---@return Ltn12Source
function source.chain(src, f, ...) end

---@return Ltn12Source
function source.cat(...) end

---@class ltn12.sink
local sink = {}

---@param t table
---@return Ltn12Sink, table
function sink.table(t) end

---@param snk Ltn12Sink
---@return Ltn12Sink
function sink.simplify(snk) end

---@param handle any
---@param io_err? string
---@return Ltn12Sink
function sink.file(handle, io_err) end

---@return Ltn12Sink
function sink.null() end

---@param err string
---@return Ltn12Sink
function sink.error(err) end

---@param f Ltn12Filter
---@param snk Ltn12Sink
---@return Ltn12Sink
function sink.chain(f, snk, ...) end

---@class ltn12.pump
local pump = {}

---@param src Ltn12Source
---@param snk Ltn12Sink
---@return any
function pump.step(src, snk) end

---@param src Ltn12Source
---@param snk Ltn12Sink
---@param step? function
---@return any
function pump.all(src, snk, step) end

---@class ltn12
---@field filter ltn12.filter
---@field source ltn12.source
---@field sink ltn12.sink
---@field pump ltn12.pump
---@field BLOCKSIZE integer
---@field _VERSION string
local ltn12 = {
  filter = filter,
  source = source,
  sink = sink,
  pump = pump,
}

return ltn12
