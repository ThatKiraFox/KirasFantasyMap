package net.kira.fantasymap.model.PointShape

sealed class PointShape(initialPoints: FloatArray) {
    var points: FloatArray = initialPoints
        set(value) {
            field = value
            updateBounds()
        }

    var minX = 0f
        private set
    var minY = 0f
        private set
    var maxX = 0f
        private set
    var maxY = 0f
        private set

    // На сколько рисунок выходит за габариты точек (толщина реки).
    open val margin: Float get() = 0f

    init {
        updateBounds()
    }

    fun movePoint(index: Int, x: Float, y: Float) {
        points[2 * index] = x
        points[2 * index + 1] = y
        updateBounds()
    }

    // Новая точка становится точкой номер index.
    fun insertPoint(index: Int, x: Float, y: Float) {
        val result = FloatArray(points.size + 2)
        points.copyInto(result, 0, 0, index * 2)
        result[index * 2] = x
        result[index * 2 + 1] = y
        points.copyInto(result, index * 2 + 2, index * 2, points.size)
        points = result
    }

    fun removePoint(index: Int) {
        val result = FloatArray(points.size - 2)
        points.copyInto(result, 0, 0, index * 2)
        points.copyInto(result, index * 2, index * 2 + 2, points.size)
        points = result
    }

    // Сглаженная кривая лежит внутри выпуклой оболочки точек, поэтому габариты по точкам достаточны.
    private fun updateBounds() {
        var lowX = Float.MAX_VALUE
        var lowY = Float.MAX_VALUE
        var highX = -Float.MAX_VALUE
        var highY = -Float.MAX_VALUE
        for (i in points.indices step 2) {
            lowX = minOf(lowX, points[i])
            highX = maxOf(highX, points[i])
            lowY = minOf(lowY, points[i + 1])
            highY = maxOf(highY, points[i + 1])
        }
        minX = lowX
        minY = lowY
        maxX = highX
        maxY = highY
    }
}