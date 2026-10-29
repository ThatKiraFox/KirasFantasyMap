package net.kira.fantasymap.render

import net.kira.fantasymap.generation.CellValidator
import net.kira.fantasymap.model.Cell
import net.kira.fantasymap.model.Edge
import net.kira.fantasymap.model.MapGrid
import kotlin.math.sqrt
import kotlin.random.Random

private const val ITERATIONS = 4
private const val DISPLACEMENT = 0.2f        // насколько середина может сместиться вбок, в долях длины отрезка
private const val FIX_PASSES = 3             // сколько раз пробуем уменьшить смещение у испорченных клеток
private val validators = ThreadLocal.withInitial { CellValidator() }

fun curveEdges(grid: MapGrid, random: Random) {
    for (edge in grid.edges) {
        edge.points = curvedPoints(edge, DISPLACEMENT, random)
    }

    var broken: List<Cell> = grid.cells.parallelStream()
        .filter { validators.get().selfIntersects(it) }
        .collect(java.util.stream.Collectors.toList())
    println("curved edges: broken cells after first pass: ${broken.size}")

    var displacement = DISPLACEMENT
    for (pass in 2..FIX_PASSES + 1) {
        if (broken.isEmpty()) break
        displacement /= 2f
        for (cell in broken) {
            for (edge in cell.edges) edge.points = curvedPoints(edge, displacement, random)
        }
        // Пересчёт мог испортить и соседей, поэтому проверяем испорченные клетки и их соседей.
        val affected = LinkedHashSet<Cell>()
        for (cell in broken) {
            affected.add(cell)
            for (edge in cell.edges) edge.otherCell(cell)?.let { affected.add(it) }
        }
        broken = affected.filter { validators.get().selfIntersects(it) }
        println("curved edges: pass $pass, broken cells: ${broken.size}")
    }

    // Что не удалось исправить, делаем прямым.
    for (cell in broken) {
        for (edge in cell.edges) edge.points = FloatArray(0)
    }
    println("curved edges: straightened cells: ${broken.size}")

    for (cell in grid.cells) cell.updateBounds()
}
// Промежуточные точки ломаной от начала ребра до конца, без самих концов.
private fun curvedPoints(edge: Edge, displacement: Float, random: Random): FloatArray {
    var points = floatArrayOf(edge.start.x, edge.start.y, edge.end.x, edge.end.y)
    repeat(ITERATIONS) {
        val segmentCount = points.size / 2 - 1
        val next = FloatArray((2 * segmentCount + 1) * 2)
        for (i in 0 until segmentCount) {
            val ax = points[2 * i]
            val ay = points[2 * i + 1]
            val bx = points[2 * i + 2]
            val by = points[2 * i + 3]
            val dx = bx - ax
            val dy = by - ay
            val length = sqrt(dx * dx + dy * dy)
            var shiftX = 0f
            var shiftY = 0f

            if (length > 0f) {
                val offset = (random.nextFloat() * 2f - 1f) * displacement * length
                shiftX = -dy / length * offset
                shiftY = dx / length * offset
            }

            next[4 * i] = ax
            next[4 * i + 1] = ay
            next[4 * i + 2] = (ax + bx) / 2f + shiftX
            next[4 * i + 3] = (ay + by) / 2f + shiftY
        }
        next[next.size - 2] = points[points.size - 2]
        next[next.size - 1] = points[points.size - 1]
        points = next
    }
    return points.copyOfRange(2, points.size - 2)
}