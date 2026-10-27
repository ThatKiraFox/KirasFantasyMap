package net.kira.fantasymap.deprecated.collections

class IntList(initialCapacity: Int = 16) : PrimitiveList(initialCapacity, MAX_ARRAY_CAPACITY) {
    var data: IntArray = IntArray(initialCapacity)
        private set

    override fun resizeStorage(newCapacity: Int) { data = data.copyOf(newCapacity) }
    override fun clearStorage(indexFrom: Int, indexTo: Int) { data.fill(0, indexFrom, indexTo) }

    fun add(value: Int): Int {
        if (occupiedSize == capacity) ensureCapacity(occupiedSize + 1)
        data[occupiedSize] = value
        return occupiedSize++
    }

    fun addAll(valuesToAdd: IntArray, indexFrom: Int = 0, count: Int = valuesToAdd.size - indexFrom): Int {
        val start = reserveForCopy(valuesToAdd.size, indexFrom, count)
        System.arraycopy(valuesToAdd, indexFrom, data, start, count)
        return start
    }

    operator fun get(index: Int): Int { checkIndex(index); return data[index] }
    operator fun set(index: Int, value: Int) { checkIndex(index); data[index] = value }

    fun removeLast(): Int {
        check(occupiedSize > 0)
        occupiedSize--
        val removedValue = data[occupiedSize]; data[occupiedSize] = 0
        return removedValue
    }

    fun toArray(): IntArray = data.copyOf(occupiedSize)

    fun copy(): IntList = IntList(occupiedSize).also { it.addAll(data, 0, occupiedSize) }

    inline fun forEach(action: (Int) -> Unit) {
        val d = data; val n = occupiedSize
        for (i in 0 until n) action(d[i])
    }
}