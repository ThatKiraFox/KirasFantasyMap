package net.kira.fantasymap.deprecated.collections

internal const val MAX_ARRAY_CAPACITY = Int.MAX_VALUE - 8

internal fun growCapacity(currentCapacity: Int, requiredCapacity: Int, maxCapacity: Int): Int {
    check(requiredCapacity in 0..maxCapacity) { "Collection too large: $requiredCapacity" }
    val newCapacity = (currentCapacity + currentCapacity / 2).let { if (it < 0) maxCapacity else it }
    return maxOf(newCapacity, requiredCapacity, 8).coerceAtMost(maxCapacity)
}

abstract class PrimitiveList(initialCapacity: Int, private val maxCapacity: Int) {
    init {
        require(initialCapacity in 0..maxCapacity) { "initialCapacity out of range: $initialCapacity" }
    }

    var capacity: Int = initialCapacity
        private set

    var occupiedSize: Int = 0
        protected set

    protected abstract fun resizeStorage(newCapacity: Int)
    protected abstract fun clearStorage(indexFrom: Int, indexTo: Int)

    fun ensureCapacity(requiredCapacity: Int) {
        if (requiredCapacity <= capacity) return
        val newCapacity = growCapacity(capacity, requiredCapacity, maxCapacity)
        resizeStorage(newCapacity)
        capacity = newCapacity
    }

    fun reserve(count: Int): Int {
        require(count >= 0)
        check(count <= maxCapacity - occupiedSize) { "Collection too large" }
        val start = occupiedSize
        ensureCapacity(start + count)
        occupiedSize = start + count
        return start
    }

    fun truncate(newSize: Int) {
        require(newSize in 0..occupiedSize)
        clearStorage(newSize, occupiedSize)
        occupiedSize = newSize
    }

    fun clear() = truncate(0)

    fun trimToSize() {
        if (capacity > occupiedSize) {
            resizeStorage(occupiedSize)
            capacity = occupiedSize
        }
    }

    protected fun checkIndex(index: Int) {
        if (index < 0 || index >= occupiedSize) {
            throw IndexOutOfBoundsException("index=$index occupiedSize=$occupiedSize")
        }
    }

    protected fun reserveForCopy(sourceSize: Int, indexFrom: Int, count: Int): Int {
        require(indexFrom >= 0 && count >= 0 && count <= sourceSize - indexFrom) {
            "Invalid range: indexFrom=$indexFrom, count=$count, sourceSize=$sourceSize"
        }
        return reserve(count)
    }
}