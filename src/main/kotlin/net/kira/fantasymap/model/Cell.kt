package net.kira.fantasymap.model

import kotlin.math.abs

class Cell(val id: Int) {
    val edges = ArrayList<Edge>()

    var minX = 0f
        private set
    var minY = 0f
        private set
    var maxX = 0f
        private set
    var maxY = 0f
        private set
    var height: Int = -1

    var isRemoved = false

    private val NO_POINTS = FloatArray(0)

    fun forEachPoint(includeEdgePoints: Boolean = true, action: (Float, Float) -> Unit) {
        if (edges.isEmpty()) return
        var current = firstVertex()
        for (edge in edges) {
            val points = if (includeEdgePoints) edge.points else NO_POINTS
            if (edge.start === current) {
                action(edge.start.x, edge.start.y)
                for (i in points.indices step 2) action(points[i], points[i + 1])
                current = edge.end
            } else {
                action(edge.end.x, edge.end.y)
                for (i in points.size - 2 downTo 0 step 2) action(points[i], points[i + 1])
                current = edge.start
            }
        }
    }

    fun updateBounds() {
        var lowX = Float.MAX_VALUE
        var lowY = Float.MAX_VALUE
        var highX = -Float.MAX_VALUE
        var highY = -Float.MAX_VALUE
        forEachPoint { x, y ->
            if (x < lowX) lowX = x
            if (x > highX) highX = x
            if (y < lowY) lowY = y
            if (y > highY) highY = y
        }
        minX = lowX; minY = lowY; maxX = highX; maxY = highY
    }

    // Вершина, с которой начинаем обход: та у первого ребра, что не общая со вторым.
    private fun firstVertex(): Vertex {
        val first = edges[0]
        val second = edges[1]
        return if (first.end === second.start || first.end === second.end) first.start else first.end
    }
    fun contains(x: Float, y: Float): Boolean {
        if (x < minX || x > maxX || y < minY || y > maxY) return false
        var inside = false
        var hasPrevious = false
        var firstX = 0f
        var firstY = 0f
        var previousX = 0f
        var previousY = 0f
        forEachPoint { pointX, pointY ->
            if (!hasPrevious) {
                firstX = pointX
                firstY = pointY
                hasPrevious = true
            } else if (crossesRay(previousX, previousY, pointX, pointY, x, y)) {
                inside = !inside
            }
            previousX = pointX
            previousY = pointY
        }
        if (hasPrevious && crossesRay(previousX, previousY, firstX, firstY, x, y)) inside = !inside
        return inside
    }

    // Пересекает ли отрезок a-b луч, идущий от точки вправо.
    private fun crossesRay(ax: Float, ay: Float, bx: Float, by: Float, x: Float, y: Float): Boolean =
        (ay > y) != (by > y) && x < (bx - ax) * (y - ay) / (by - ay) + ax

    // Углы клетки: вершины её рёбер без повторов.
    fun corners(): List<Vertex> {
        val result = ArrayList<Vertex>()
        for (edge in edges) {
            if (result.none { it === edge.start }) result.add(edge.start)
            if (result.none { it === edge.end }) result.add(edge.end)
        }
        return result
    }

    fun area(): Float {
        var sum = 0.0
        var hasPrevious = false
        var firstX = 0f
        var firstY = 0f
        var previousX = 0f
        var previousY = 0f
        forEachPoint { x, y ->
            if (hasPrevious) {
                sum += previousX.toDouble() * y - x.toDouble() * previousY
            } else {
                firstX = x
                firstY = y
                hasPrevious = true
            }
            previousX = x
            previousY = y
        }
        if (hasPrevious) sum += previousX.toDouble() * firstY - firstX.toDouble() * previousY
        return (abs(sum) / 2.0).toFloat()
    }
}