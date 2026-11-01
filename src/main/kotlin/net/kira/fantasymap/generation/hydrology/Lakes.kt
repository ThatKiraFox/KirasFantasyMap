package net.kira.fantasymap.generation.hydrology

import net.kira.fantasymap.model.Cell
import net.kira.fantasymap.model.PointShape.Lake
import net.kira.fantasymap.model.MapGrid
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

private const val LAKE_HEIGHT_DIFFERENCE = 30     // соседи ниже этой разницы высот с ямой тоже становятся озером
private const val LAKE_MIN_MOISTURE = 0.25f

// Яма: клетка суши, у которой все соседи выше неё.
private const val LAKE_MIN_SHARE = 0.3f
private const val LAKE_MAX_SHARE = 0.8f
private const val LAKE_POINTS = 32
private const val LAKE_WOBBLE = 0.15f
private const val LAKE_REACH = 1.1f
private const val LAKE_NECK_ANGLE = 0.5f

fun assignLakes(grid: MapGrid) {
    var pits = 0
    for (cell in grid.cells) {
        if (cell.height <= 0 || cell.moisture < LAKE_MIN_MOISTURE) continue
        val neighbors = cell.edges.mapNotNull { it.otherCell(cell) }
        if (neighbors.any { it.height <= cell.height }) continue
        pits++
        cell.isLake = true
        for (neighbor in neighbors) {
            if (neighbor.height - cell.height < LAKE_HEIGHT_DIFFERENCE) neighbor.isLake = true
        }
    }
    println("lakes: pits $pits, lake cells ${grid.cells.count { it.isLake }}")
}

// Превращает клетки-озёра в объекты Lake. Вызывается после cutRivers: форма считается по клеткам с учётом разрезов.
fun createLakes(grid: MapGrid) {
    for (cell in grid.cells) {
        if (!cell.isLake || cell.isRemoved) continue

        // Если все клетки вокруг вершины озёра, отдельное озеро из центров этих клеток: между озёрами не остаётся дыр.
        for (corner in cell.corners()) {
            val around = corner.edges.flatMap { it.cells }.distinct()
            if (around.size < 3 || around.any { !it.isLake || it.isRemoved }) continue
            if (around.minOf { it.id } != cell.id) continue          // каждую вершину один раз
            val centers = around.map { it.getCenter() }.sortedBy { atan2(it.second - corner.y, it.first - corner.x) }
            grid.lakes.add(Lake(FloatArray(centers.size * 2) { if (it % 2 == 0) centers[it / 2].first else centers[it / 2].second }))
        }

        grid.lakes.add(Lake(blobPoints(cell)))
    }
    println("lakes: ${grid.lakes.size}")
}

// Округлая форма в центре клетки, тянется к соседним озёрам до середины между центрами. Точки в мировых координатах.
private fun blobPoints(cell: Cell): FloatArray {
    val (centerX, centerY) = cell.getCenter()
    val pi = PI.toFloat()
    val share = LAKE_MIN_SHARE + (LAKE_MAX_SHARE - LAKE_MIN_SHARE) * cell.moisture
    val radius = sqrt(cell.area() / pi) * share
    val random = Random(cell.id)                 // форма одна и та же при каждой генерации
    val radii = FloatArray(LAKE_POINTS) { radius * (1f + (random.nextFloat() * 2f - 1f) * LAKE_WOBBLE) }

    for (edge in cell.edges) {
        val other = edge.otherCell(cell) ?: continue
        if (!other.isLake || other.isRemoved) continue
        val (otherX, otherY) = other.getCenter()
        val dx = (otherX - centerX) / 2f
        val dy = (otherY - centerY) / 2f
        val reach = hypot(dx, dy) * LAKE_REACH
        val toward = atan2(dy, dx)
        for (i in 0 until LAKE_POINTS) {
            var diff = 2f * pi * i / LAKE_POINTS - toward
            while (diff > pi) diff -= 2f * pi
            while (diff < -pi) diff += 2f * pi
            if (abs(diff) >= LAKE_NECK_ANGLE || reach <= radii[i]) continue
            val t = cos(diff / LAKE_NECK_ANGLE * pi / 2f).let { it * it }
            radii[i] += (reach - radii[i]) * t
        }
    }

    val points = FloatArray(LAKE_POINTS * 2)
    for (i in 0 until LAKE_POINTS) {
        val angle = 2f * pi * i / LAKE_POINTS
        points[2 * i] = centerX + cos(angle) * radii[i]
        points[2 * i + 1] = centerY + sin(angle) * radii[i]
    }
    return points
}