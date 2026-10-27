package net.kira.fantasymap.deprecated.collections

class FloatList(initialCapacity: Int = 16) : PrimitiveList(initialCapacity, MAX_ARRAY_CAPACITY) {

    var data: FloatArray = FloatArray(initialCapacity)
        private set


    override fun resizeStorage(newCapacity: Int) { data = data.copyOf(newCapacity) }

    override fun clearStorage(indexFrom: Int, indexTo: Int) { data.fill(0f, indexFrom, indexTo) }

    fun add(value: Float): Int {
        if (occupiedSize == capacity) ensureCapacity(occupiedSize + 1)
        data[occupiedSize] = value
        return occupiedSize++
    }

    fun addAll(valuesToAdd: FloatArray, indexFrom: Int = 0, count: Int = valuesToAdd.size - indexFrom): Int {
        val start = reserveForCopy(valuesToAdd.size, indexFrom, count)
        System.arraycopy(valuesToAdd, indexFrom, data, start, count)
        return start
    }

    operator fun get(index: Int): Float { checkIndex(index); return data[index] }
    operator fun set(index: Int, value: Float) { checkIndex(index); data[index] = value }

    fun removeLast(): Float {
        check(occupiedSize > 0)
        occupiedSize--
        val removedValue = data[occupiedSize]
        data[occupiedSize] = 0f
        return removedValue
    }

    fun toArray(): FloatArray = data.copyOf(occupiedSize)

    fun copy(): FloatList = FloatList(occupiedSize).also { it.addAll(data, 0, occupiedSize) }

    inline fun forEach(action: (Float) -> Unit) {
        val d = data; val n = occupiedSize
        for (i in 0 until n) action(d[i])
    }
}