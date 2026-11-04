package net.kira.fantasymap.generation.hydrology

import net.kira.fantasymap.model.mapgrid.Cell
import net.kira.fantasymap.model.mapgrid.MapGrid
import java.util.PriorityQueue
import kotlin.math.ln
import kotlin.math.max

private const val SNOW = 1f                    // сколько воды даёт снег на самой высокой точке (если холодно)
const val RIVER_THRESHOLD = 50f                // с какого количества воды клетка считается рекой


// Возвращает клетки в порядке обхода от океана вглубь суши: у каждой клетки её flowsTo стоит раньше неё.
fun assignRivers(grid: MapGrid, maxHeight: Int): List<Cell> {
    val land = grid.cells.filter { it.height > 0 }
    for (cell in land) {
        val snow = if (cell.temperature < 0f) SNOW * cell.height / maxHeight else 0f
        cell.water = cell.moisture + snow
    }

    // Идём от моря вглубь суши, всегда беря самую низкую из ещё не обойдённых клеток.
    // level: высота, до которой клетка заполнена (в яме она выше собственной высоты).
    val level = IntArray(grid.cells.size)
    val visited = BooleanArray(grid.cells.size)
    val queue = PriorityQueue<Cell>(compareBy<Cell>({ level[it.id] }, { it.height }))
    val isOcean = findOcean(grid)
    for (cell in grid.cells) {
        cell.flowsTo = null
        if (isOcean[cell.id]) {
            visited[cell.id] = true
            level[cell.id] = cell.height
            queue.add(cell)
        }
    }
    val order = ArrayList<Cell>(grid.cells.size)
    while (queue.isNotEmpty()) {
        val cell = queue.poll()
        order.add(cell)
        for (edge in cell.edges) {
            val other = edge.otherCell(cell) ?: continue
            if (visited[other.id]) continue
            visited[other.id] = true
            other.flowsTo = cell
            level[other.id] = max(other.height, level[cell.id])
            queue.add(other)
        }
    }
    for (cell in grid.cells) cell.depth = (level[cell.id] - cell.height)

    // С конца: сначала клетки выше по течению, потом те, куда они втекают.
    for (cell in order.asReversed()) cell.flowsTo?.let {
        it.water += cell.water }
    println("river cells: ${land.count { it.water >= RIVER_THRESHOLD }}")

    return order
}

// Океан: самая большая связная вода. Остальные воды внутренние моря.
fun findOcean(grid: MapGrid): BooleanArray {
    val seen = BooleanArray(grid.cells.size)
    var biggest = ArrayList<Cell>()
    for (start in grid.cells) {
        if (start.height > 0 || seen[start.id]) continue
        val body = ArrayList<Cell>()
        seen[start.id] = true
        body.add(start)
        var i = 0
        while (i < body.size) {
            val cell = body[i++]
            for (edge in cell.edges) {
                val other = edge.otherCell(cell) ?: continue
                if (other.height > 0 || seen[other.id]) continue
                seen[other.id] = true
                body.add(other)
            }
        }
        if (body.size > biggest.size) biggest = body
    }
    val isOcean = BooleanArray(grid.cells.size)
    for (cell in biggest) isOcean[cell.id] = true
    return isOcean
}

fun riverWidth(water: Float): Float = 0.1f + 0.4f * ln(1f + water / RIVER_THRESHOLD)   // в единицах мира