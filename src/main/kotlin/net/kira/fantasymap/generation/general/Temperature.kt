package net.kira.fantasymap.generation.general

import net.kira.fantasymap.model.mapgrid.MapGrid
import java.lang.Math.toRadians
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

fun assignTemperature(grid: MapGrid, equator: Float, northPole: Float, southPole: Float, lapseRate: Float) {
    val lapseRate0 = lapseRate / 1000f

    for (cell in grid.cells) {
        val latitude = abs(cell.latitude).toDouble().coerceIn(0.0, 90.0)
        val phi = toRadians(latitude)
        val term = cos(phi).pow(3) * (1.0 + 1.5 * sin(phi).pow(2))
        val pole = if (cell.latitude >= 0f) northPole else southPole
        cell.temperature = (pole + (equator - pole) * term).toFloat() - cell.height * lapseRate0
    }
}