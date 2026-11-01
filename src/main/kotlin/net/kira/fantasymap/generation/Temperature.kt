package net.kira.fantasymap.generation

import net.kira.fantasymap.model.MapGrid
import java.lang.Math.toRadians
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

fun assignTemperature(grid: MapGrid) {
    val a = -18.3
    val b = 44.5
    val lapseRate = 6.5f / 1000f

    for (cell in grid.cells) {
        val latitude = abs(cell.latitude).toDouble().coerceIn(0.0, 90.0)
        val phi = toRadians(latitude)
        val cosPhi = cos(phi)
        val sinPhi = sin(phi)
        val term = cosPhi.pow(3) * (1.0 + 1.5 * sinPhi.pow(2))
        var temperature = (a + b * term).toFloat()

        temperature -= cell.height * lapseRate

        cell.temperature = temperature
    }
}