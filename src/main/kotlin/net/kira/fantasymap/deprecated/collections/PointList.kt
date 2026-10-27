package net.kira.fantasymap.deprecated.collections

class PointList(initialCapacity: Int) : PrimitiveList(initialCapacity, MAX_ARRAY_CAPACITY) {

    var coordinateData: FloatArray = FloatArray(initialCapacity * 2)
        private set

    override fun resizeStorage(newCapacity: Int) {
        coordinateData = coordinateData.copyOf(newCapacity * 2)
    }

    override fun clearStorage(indexFrom: Int, indexTo: Int) {
        coordinateData.fill(0f, 2 * indexFrom, 2 * indexTo)
    }

    fun x(index: Int): Float { checkIndex(index); return coordinateData[2 * index] }
    fun y(index: Int): Float { checkIndex(index); return coordinateData[2 * index + 1] }

    fun set(index: Int, x: Float, y: Float) {
        checkIndex(index)
        coordinateData[2 * index] = x
        coordinateData[2 * index + 1] = y
    }

    fun add(x: Float, y: Float): Int {
        val index = reserve(1)
        coordinateData[2 * index] = x
        coordinateData[2 * index + 1] = y
        return index
    }

    fun addAll(coordinatesToAdd: FloatArray, indexFrom: Int, count: Int): Int {
        require(coordinatesToAdd.size % 2 == 0) { "Coordinates array must have even length: ${coordinatesToAdd.size}" }
        val start = reserveForCopy(coordinatesToAdd.size / 2, indexFrom, count)
        System.arraycopy(coordinatesToAdd, 2 * indexFrom, coordinateData, 2 * start, 2 * count)
        return start
    }

    fun toArray(): FloatArray = coordinateData.copyOf(2 * occupiedSize)

    fun copy(): PointList = PointList(occupiedSize).also { it.addAll(coordinateData, 0, occupiedSize) }
}