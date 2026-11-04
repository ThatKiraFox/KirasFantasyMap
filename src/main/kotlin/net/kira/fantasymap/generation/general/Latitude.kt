package net.kira.fantasymap.generation.general

import net.kira.fantasymap.model.mapgrid.MapGrid

private const val MAX_LATITUDE_DEGREES = 90

fun assignLatitude(grid: MapGrid, worldHeight: Float) {
    for (cell in grid.cells) {
        val y = cell.getCenter().second
        // 0 → -90°, середина → 0°, max → +90°
        val latitude = (y / worldHeight) * 180.0f - MAX_LATITUDE_DEGREES
        cell.updateLatitude(latitude)
    }
}