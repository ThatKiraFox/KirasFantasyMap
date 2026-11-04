package net.kira.fantasymap.generation.hydrology

import net.kira.fantasymap.imported.FastNoiseLite
import net.kira.fantasymap.model.mapgrid.MapGrid
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow

private const val RAIN = 0.0001f// потеря влаги на единицу мира: воздух теряет 1/e влаги примерно за 1/RAIN единиц (~330)
private const val OROGRAPHIC = 0.00002f
private const val MOISTURE_NOISE_FREQUENCY = 0f
private const val MOISTURE_NOISE_STRENGTH = 0f
private const val SMOOTH_PASSES = 60
private const val MOISTURE_SMOOTH_PASSES = 3

fun baseMoisture(latitude: Float): Double {
    val lat = abs(latitude) / 90.0
    var base = 0.95 * exp(-(lat / 0.28).pow(2.0))
    base -= 0.35 * exp(-((lat - 0.33) / 0.12).pow(2.0))
    base += 0.75 * exp(-((lat - 0.55) / 0.22).pow(2.0))
    base += 0.15 * exp(-((lat - 0.85) / 0.18).pow(2.0))
    return base.coerceAtLeast(0.0).pow(0.85).coerceIn(0.05, 1.0)
}

fun assignMoisture(grid: MapGrid, worldWidth: Float, worldHeight: Float, seed: Long) {
    val noise = FastNoiseLite(seed.toInt()).apply {
        SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2)
        SetFractalType(FastNoiseLite.FractalType.FBm)
        SetFractalOctaves(4)
        SetFrequency(1f)
    }
    val worldSize = max(worldWidth, worldHeight)

    val count = grid.cells.size
    val centerX = FloatArray(count) { (grid.cells[it].minX + grid.cells[it].maxX) / 2f }
    val centerY = FloatArray(count) { (grid.cells[it].minY + grid.cells[it].maxY) / 2f }

    // Сглаженные высоты: уклон считаем по крупному рельефу, а не по шуму между соседними клетками.
    var smooth = FloatArray(count) { grid.cells[it].height.toFloat() }
    repeat(SMOOTH_PASSES) {
        val next = FloatArray(count)
        for (cell in grid.cells) {
            var sum = smooth[cell.id]
            var k = 1
            for (edge in cell.edges) {
                val other = edge.otherCell(cell) ?: continue
                sum += smooth[other.id]
                k++
            }
            next[cell.id] = sum / k
        }
        smooth = next
    }

    // От -1 до +1: знак даёт направление (+ вправо, - влево), модуль силу. Ноль на 0°, 30°, 60°, 90°
    val wind = FloatArray(count) {
        -cos(Math.toRadians((abs(grid.cells[it].latitude) - 15.0) * 6.0)).toFloat()
    }
    val carried = FloatArray(count) { -1f }      // влага, которую воздух несёт дальше; -1: ещё не посчитано

    for (cell in grid.cells) {
        if (cell.height <= 0) carried[cell.id] = baseMoisture(cell.latitude).toFloat()
    }

    for (direction in floatArrayOf(-1f, 1f)) {
        val land = grid.cells
            .filter { it.height > 0 && (if (wind[it.id] >= 0f) 1f else -1f) == direction }
            .sortedBy { centerX[it.id] * direction }          // от наветренной стороны к подветренной
        for (cell in land) {
            var sumMoisture = 0f
            var sumHeight = 0f
            var sumDistance = 0f
            var n = 0
            for (edge in cell.edges) {
                val other = edge.otherCell(cell) ?: continue
                if (centerX[other.id] * direction >= centerX[cell.id] * direction) continue   // не наветренный сосед
                if (carried[other.id] < 0f) continue
                sumMoisture += carried[other.id]
                sumHeight += smooth[other.id]
                sumDistance += hypot(centerX[cell.id] - centerX[other.id], centerY[cell.id] - centerY[other.id])
                n++
            }

            val strength = abs(wind[cell.id])
            val local = baseMoisture(cell.latitude).toFloat()
            val incoming = if (n > 0) strength * (sumMoisture / n) + (1f - strength) * local else local

            val distance = if (n > 0) sumDistance / n else 1f
            val slope = if (n > 0) (smooth[cell.id] - sumHeight / n) / distance * strength else 0f
            val rate = (RAIN + OROGRAPHIC * slope).coerceIn(RAIN * 0.3f, RAIN * 3f)
            val rain = 1f - exp(-rate * distance)

            val noiseFactor = 1f + MOISTURE_NOISE_STRENGTH * noise.GetNoise(
                centerX[cell.id] / worldSize * MOISTURE_NOISE_FREQUENCY,
                centerY[cell.id] / worldSize * MOISTURE_NOISE_FREQUENCY
            )
            // на ровном месте rate = RAIN, и влажность равна incoming; на подъёме больше, на спуске меньше
            cell.moisture = (incoming * rate / RAIN * temperatureFactor(cell.temperature) * noiseFactor).coerceIn(0.05f, 1f)
            carried[cell.id] = incoming * (1f - rain)
        }
    }
    var result = FloatArray(count) { grid.cells[it].moisture }
    repeat(MOISTURE_SMOOTH_PASSES) {
        val next = result.copyOf()
        for (cell in grid.cells) {
            if (cell.height <= 0) continue
            var sum = result[cell.id]
            var k = 1
            for (edge in cell.edges) {
                val other = edge.otherCell(cell) ?: continue
                if (other.height <= 0) continue          // океан не смешиваем, иначе берег размоется
                sum += result[other.id]
                k++
            }
            next[cell.id] = sum / k
        }
        result = next
    }
    for (cell in grid.cells) if (cell.height > 0) cell.moisture = result[cell.id]
}

private fun temperatureFactor(temperature: Float): Float =
    0.6f + 0.4f * ((temperature + 10f) / 40f).coerceIn(0f, 1f)