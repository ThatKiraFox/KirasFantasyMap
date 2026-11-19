package net.kira.fantasymap.generation.general

import net.kira.fantasymap.model.Biomes
import net.kira.fantasymap.model.arableFraction
import net.kira.fantasymap.model.mapgrid.Cell
import net.kira.fantasymap.model.mapgrid.MapGrid
import java.util.*
import kotlin.math.exp
import kotlin.math.hypot

const val POPULATION_COUNT = 80
private const val POPULATION_SMOOTH_PASSES = 5     // сколько раз усредняем плотность с соседями
private const val WATER_BONUS = 1.3f
private const val WATER_STEPS = 2          // сколько клеток от воды считается «рядом»
private const val CORE_MIN_DISTANCE = 250f   // км между ядрами
private const val CORE_MIN_SHARE = 0.5f      // ядро: плотность не ниже этой доли от 95-го перцентиля
private const val FILL_DECAY = 300f          // км: на таком расстоянии от ядра заполнение e^-1
private const val FILL_MIN = 0.1f


private val biomesWithWater = setOf(Biomes.GALLERY_FOREST, Biomes.FLOODPLAIN, Biomes.OASIS)

fun assignPopulation(grid: MapGrid, maxHeight: Int, riverThreshold: Float) {
    val nearWater = reach(grid, WATER_STEPS) { it.height <= 0 || it.isLake || it.water >= riverThreshold }
    var area = 0

    val base = FloatArray(grid.cells.size)
    for (cell in grid.cells) {
        if (cell.isRemoved || cell.height <= 0) continue
        val biome = cell.biome ?: continue
        var factor = 1f
        if (biome !in biomesWithWater && nearWater[cell.id]) factor *= WATER_BONUS
        factor *= 1f - 0.8f * (cell.height.toFloat() / maxHeight).coerceIn(0f, 1f)          // высота
        factor *= 1f - 0.8f * (slope(cell) / 40f).coerceIn(0f, 1f)                          // уклон
        factor *= 0.8f + 0.2f * ((cell.temperature + 10f) / 30f).coerceIn(0f, 1f)           // температура
        area += (cell.area() * biome.arableFraction()).toInt()
        base[cell.id] = biome.arableFraction() * POPULATION_COUNT * factor
    }
    println(area)
    var density = base
    repeat(POPULATION_SMOOTH_PASSES) {
        val next = density.copyOf()
        for (cell in grid.cells) {
            if (cell.isRemoved || cell.height <= 0 || base[cell.id] == 0f) continue   // вода и ледники остаются нулём
            var sum = density[cell.id]
            var k = 1
            for (edge in cell.edges) {
                val other = edge.otherCell(cell) ?: continue
                if (other.isRemoved || other.height <= 0) continue                    // воду не смешиваем, иначе берег размоется
                sum += density[other.id]
                k++
            }
            next[cell.id] = sum / k
        }
        density = next
    }

    var total = 0L
    var maxDensity = 0f
    val reference = percentile95(grid,density)       // 95-й перцентиль положительных плотностей, как у цвета
    val cores = findCores(grid, density, reference)
    val distance = landDistances(grid, cores, Float.MAX_VALUE)
    for (cell in grid.cells) {
        if (cell.isRemoved || cell.height <= 0) continue
        val fill = if (distance[cell.id] == Float.MAX_VALUE) FILL_MIN
        else maxOf(FILL_MIN, exp(-distance[cell.id] / (FILL_DECAY * coefficient)))
        density[cell.id] *= fill
    }
    println("population: cores ${cores.size}")
    for (cell in grid.cells) {
        if (cell.isRemoved) continue
        cell.population = (density[cell.id] * cell.area()).toInt()
        total += cell.population
        if (density[cell.id] > maxDensity) maxDensity = density[cell.id]
    }
    grid.maxPopulationDensity = percentile95(grid, density)
    println("population: total $total, max density $maxDensity")
}


// Расстояние по суше от ближайшего из sources, не дальше limit. Float.MAX_VALUE: не достигнуто.
private fun landDistances(grid: MapGrid, sources: List<Cell>, limit: Float): FloatArray {
    val distance = FloatArray(grid.cells.size) { Float.MAX_VALUE }
    val queue = PriorityQueue<Pair<Float, Cell>>(compareBy { it.first })
    for (source in sources) {
        distance[source.id] = 0f
        queue.add(0f to source)
    }
    while (queue.isNotEmpty()) {
        val (d, cell) = queue.poll()
        if (d > distance[cell.id]) continue
        val (cx, cy) = cell.getCenter()
        for (edge in cell.edges) {
            val other = edge.otherCell(cell) ?: continue
            if (other.isRemoved || other.height <= 0) continue
            val (ox, oy) = other.getCenter()
            val next = d + hypot(ox - cx, oy - cy)
            if (next < distance[other.id] && next <= limit) {
                distance[other.id] = next
                queue.add(next to other)
            }
        }
    }
    return distance
}

private fun findCores(grid: MapGrid, density: FloatArray, reference: Float): List<Cell> {
    val candidates = grid.cells
        .filter { !it.isRemoved && it.height > 0 && density[it.id] >= reference * CORE_MIN_SHARE }
        .sortedByDescending { density[it.id] }
    val suppressed = BooleanArray(grid.cells.size)
    val cores = ArrayList<Cell>()
    for (cell in candidates) {
        if (suppressed[cell.id]) continue
        cores.add(cell)
        val near = landDistances(grid, listOf(cell), CORE_MIN_DISTANCE * coefficient)
        for (other in candidates) if (near[other.id] < Float.MAX_VALUE) suppressed[other.id] = true
    }
    return cores
}

private fun percentile95(grid: MapGrid, density: FloatArray): Float {
    val positive = grid.cells
        .filter { !it.isRemoved && density[it.id] > 0f }
        .map { density[it.id] }
        .sorted()
    if (positive.isEmpty()) return 0f
    return positive[(positive.size * 0.95f).toInt().coerceAtMost(positive.size - 1)]
}