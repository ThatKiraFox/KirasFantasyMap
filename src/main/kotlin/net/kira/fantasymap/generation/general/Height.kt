package net.kira.fantasymap.generation.general

import net.kira.fantasymap.imported.FastNoiseLite
import net.kira.fantasymap.model.mapgrid.MapGrid
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

private const val MIN_HEIGHT = -999

fun assignHeights(grid: MapGrid, worldWidth: Float, worldHeight: Float, seed: Long, maxHeight: Int, fraction: Float,
                  baseFrequency: Float, heightPower: Float, preset: ReliefPreset) {
    val noise = FastNoiseLite((seed + 1).toInt()).apply {
        SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2)
        SetFractalType(if (preset.ridged) FastNoiseLite.FractalType.Ridged else FastNoiseLite.FractalType.FBm)
        SetFractalOctaves(preset.octaves)
        SetFractalLacunarity(2f)
        SetFractalGain(preset.gain)
        SetFrequency(1f)
    }
    val canyonNoise = FastNoiseLite((seed + 100).toInt()).apply {
        SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2)
        SetFrequency(1f)
    }
    val worldSize = max(worldWidth, worldHeight)
    val half = min(worldWidth, worldHeight) / 2f
    val canyon = FloatArray(grid.cells.size)         // 1 в центре каньона, 0 вдали от него

    for (cell in grid.cells) {
        val centerX = (cell.minX + cell.maxX) / 2f
        val centerY = (cell.minY + cell.maxY) / 2f
        var value = noise.GetNoise(centerX / worldSize * baseFrequency, centerY / worldSize * baseFrequency) * preset.noiseWeight
        preset.mask?.let { value += it((centerX - worldWidth / 2f) / half, (centerY - worldHeight / 2f) / half) }
        cell.height = (value * maxHeight).toInt()

        if (preset.canyonDepth > 0f) {
            val n = canyonNoise.GetNoise(centerX / worldSize * preset.canyonFrequency, centerY / worldSize * preset.canyonFrequency)
            canyon[cell.id] = exp(-(n / preset.canyonWidth).pow(2))
        }
    }

    // Море: порог по доле клеток. Сверху не режем, высоту дальше нормирует самый высокий пик.
    val heights = IntArray(grid.cells.size) { grid.cells[it].height }.apply { sort() }
    val index = (heights.size * fraction).toInt().coerceAtMost(heights.size - 1)
    val threshold = heights[index] + if (fraction >= 1f) 1 else 0
    for (cell in grid.cells) {
        cell.height = (cell.height - threshold).coerceAtLeast(MIN_HEIGHT)
    }

    val peak = grid.cells.maxOf { it.height }.coerceAtLeast(1)
    for (cell in grid.cells) {
        if (cell.height <= 0) continue
        val share = cell.height.toFloat() / peak
        cell.height = (share.pow(heightPower) * maxHeight).toInt().coerceAtLeast(1)
    }

    // Каньоны режем после сглаживания, в долях maxHeight, и не глубже уровня моря.
    if (preset.canyonDepth > 0f) {
        for (cell in grid.cells) {
            if (cell.height <= 0) continue
            cell.height = (cell.height - (canyon[cell.id] * preset.canyonDepth * maxHeight).toInt()).coerceAtLeast(1)
        }
    }

    val h = grid.cells.filter { !it.isRemoved && it.height > 0 }.map { it.height }.sorted()
    println("land height: p10 ${h[h.size / 10]}, p50 ${h[h.size / 2]}, p90 ${h[h.size * 9 / 10]}")
}