package net.kira.fantasymap.generation

import net.kira.fantasymap.model.Cell

fun orientation(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float): Float =
    (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)

fun segmentsCross(
    ax: Float, ay: Float, bx: Float, by: Float,
    cx: Float, cy: Float, dx: Float, dy: Float
): Boolean {
    if (maxOf(ax, bx) < minOf(cx, dx) || maxOf(cx, dx) < minOf(ax, bx) ||
        maxOf(ay, by) < minOf(cy, dy) || maxOf(cy, dy) < minOf(ay, by)
    ) return false
    val d1 = orientation(cx, cy, dx, dy, ax, ay)
    val d2 = orientation(cx, cy, dx, dy, bx, by)
    val d3 = orientation(ax, ay, bx, by, cx, cy)
    val d4 = orientation(ax, ay, bx, by, dx, dy)
    return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))
}

class CellValidator {
    private var buffer = FloatArray(256)

    // true, если контур клетки пересекает сам себя.
    fun selfIntersects(cell: Cell): Boolean {
        var count = 0
        cell.forEachPoint { x, y ->
            if (count * 2 + 2 > buffer.size) buffer = buffer.copyOf(buffer.size * 2)
            buffer[count * 2] = x
            buffer[count * 2 + 1] = y
            count++
        }
        var hasNaN = false
        cell.forEachPoint { x, y ->
            if (x.isNaN() || y.isNaN()) hasNaN = true
            if (count * 2 + 2 > buffer.size) buffer = buffer.copyOf(buffer.size * 2)
            buffer[count * 2] = x
            buffer[count * 2 + 1] = y
            count++
        }
        if (hasNaN) return true
        for (i in 0 until count) {
            val b = (i + 1) % count
            for (j in i + 1 until count) {
                val d = (j + 1) % count
                if (b == j || d == i) continue          // соседние отрезки касаются в общей точке, это не пересечение
                if (segmentsCross(
                        buffer[2 * i], buffer[2 * i + 1], buffer[2 * b], buffer[2 * b + 1],
                        buffer[2 * j], buffer[2 * j + 1], buffer[2 * d], buffer[2 * d + 1]
                    )
                ) return true
            }
        }
        return false
    }
}