package net.kira.fantasymap.generation.general

import net.kira.fantasymap.model.mapgrid.Cell
import net.kira.fantasymap.model.mapgrid.MapGrid
import kotlin.math.abs
import kotlin.math.hypot

fun slope(cell: Cell): Float {

    val (cx, cy) = cell.getCenter()
    var sum = 0f
    var n = 0
    for (edge in cell.edges) {
        val other = edge.otherCell(cell) ?: continue
        if (other.height <= 0) continue            // воду не считаем, иначе у берега уклон огромный
        val (ox, oy) = other.getCenter()
        val distance = hypot(ox - cx, oy - cy)
        if (distance <= 0f) continue
        sum += abs(other.height - cell.height) / distance
        n++
    }
    return if (n > 0) sum / n else 0f
}