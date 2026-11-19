package net.kira.fantasymap.generation.general

import net.kira.fantasymap.model.mapgrid.MapGrid

private const val MAX_LATITUDE_DEGREES = 90

fun assignLatitude(grid: MapGrid, worldHeight: Float, latitudeFrom: Float, latitudeTo: Float) {
    for (cell in grid.cells) {
        val y = cell.getCenter().second
        cell.updateLatitude(latitudeFrom + (y / worldHeight) * (latitudeTo - latitudeFrom))
    }
}