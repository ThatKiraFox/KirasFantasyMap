package net.kira.fantasymap.generation.general

import net.kira.fantasymap.imported.FastNoiseLite
import net.kira.fantasymap.model.mapgrid.MapGrid
import kotlin.math.max

private const val MIN_HEIGHT = -999
private const val BASE_FREQUENCY = 3f

fun assignHeights(grid: MapGrid, worldWidth: Float, worldHeight: Float, seed: Long, maxHeight: Int, fraction: Float) {
    val noise = FastNoiseLite((seed + 1).toInt()).apply {
        SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2)
        SetFractalType(FastNoiseLite.FractalType.FBm)
        SetFractalOctaves(6)
        SetFractalLacunarity(2f)
        SetFractalGain(0.5f)
        SetFrequency(1f)
    }
    val worldSize = max(worldWidth, worldHeight)
    for (cell in grid.cells) {
        val centerX = (cell.minX + cell.maxX) / 2f
        val centerY = (cell.minY + cell.maxY) / 2f
        val value = noise.GetNoise(centerX / worldSize * BASE_FREQUENCY, centerY / worldSize * BASE_FREQUENCY)  // примерно от -1 до 1
        cell.height = (value * maxHeight).toInt()
    }
    val heights = IntArray(grid.cells.size) { grid.cells[it].height }.apply { sort() }
    val index = (heights.size * fraction).toInt().coerceAtMost(heights.size - 1)
    val threshold = heights[index] + if (fraction >= 1f) 1 else 0
    for (cell in grid.cells) {
        cell.height = (cell.height - threshold).coerceIn(MIN_HEIGHT, maxHeight)
    }
    val h = grid.cells.filter { !it.isRemoved && it.height > 0 }.map { it.height }.sorted()
    println("land height: p10 ${h[h.size / 10]}, p50 ${h[h.size / 2]}, p90 ${h[h.size * 9 / 10]}")

}