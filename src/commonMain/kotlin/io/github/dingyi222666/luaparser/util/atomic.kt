fun atomic(value: Int): AtomicInt {
    return AtomicInt(value)
}

class AtomicInt(
    private var value: Int
) {
    fun get(): Int {
        return value
    }

    fun set(newValue: Int) {
        value = newValue
    }

    fun getAndDecrement(): Int {
        val old = value
        value -= 1
        return old
    }


}