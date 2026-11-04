package net.kira.fantasymap.generation.general

import net.kira.fantasymap.generation.hydrology.RIVER_THRESHOLD
import net.kira.fantasymap.generation.hydrology.findOcean
import net.kira.fantasymap.model.Biomes
import net.kira.fantasymap.model.mapgrid.Cell
import net.kira.fantasymap.model.mapgrid.MapGrid
import java.util.Arrays
import kotlin.math.ln

private const val FLAT_SLOPE = 10f              // S3 ниже этого: Flat (медиана slope 14.7, p10 6.3)
private const val A_PERCENTILE = 0.85f          // A высокая: ln(1 + water) не ниже этого перцентиля по суше
private const val WET = 0.6f                    // W для болота
private const val DRY = 0.3f                    // W для солончака
private const val LAKEBED_MIN_DEPTH = 700f      // D для солончака
private const val NEAR_WATER_STEPS = 2          // NearWater: сколько клеток от воды
private const val NEAR_RIVER_STEPS = 2          // NearWater (река) для поймы
private const val GLACIER_TEMPERATURE = -36f    // T ниже этого: ледник на суше
private const val OCEAN_GLACIER_TEMPERATURE = -11f

// Зональные биомы суши: температура в градусах, влажность в перцентилях по суше.
private const val COLD = -5f
private const val COOL = 5f
private const val WARM = 20f

// Прикинуто линейной интерполяцией между замеренными p10 0.094, p50 0.31, p90 0.96. Сверить с печатью "W ranks".
private const val W_DRY = 0.30f          // было 0.2: жаркая пустыня
private const val W_SEMI_DRY = 0.49f     // было 0.3: степь, холодная степь, ледяная пустыня
private const val W_MEDIUM = 0.555f      // было 0.4: саванна
private const val W_MOIST = 0.59f        // было 0.45: луг
private const val W_WET = 0.68f          // было 0.6
private const val W_VERY_WET = 0.80f     // было 0.8

// Зональные леса: на реке в них галерейный лес не нужен. FOREST_STEPPE сюда не входит.
private val forests = setOf(
    Biomes.TAIGA, Biomes.BROADLEAF_FOREST, Biomes.MIXED_FOREST,
    Biomes.TROPICAL_FOREST, Biomes.RAINFOREST, Biomes.SAVANNA_FOREST
)

// Ранг значения среди суши: доля клеток, у которых значение меньше (от 0 до 1).
class Percentiles(values: FloatArray) {
    private val sorted = values.sortedArray()

    fun of(value: Float): Float {
        if (sorted.isEmpty()) return 0f
        val i = Arrays.binarySearch(sorted, value).let { if (it < 0) -it - 1 else it }
        return i.toFloat() / sorted.size
    }
}

fun assignZonalBiome(grid: MapGrid) {
    val isOcean = findOcean(grid)
    val land = grid.cells.filter { it.height > 0 && !it.isRemoved }
    val slopes = land.map { slope(it) }.sorted()
    println("slope: p10 ${slopes[slopes.size / 10]}, p50 ${slopes[slopes.size / 2]}, p90 ${slopes[slopes.size * 9 / 10]}, max ${slopes.last()}")

    val moistures = Percentiles(FloatArray(land.size) { land[it].moisture })
    println("W ranks: " + listOf(0.2f, 0.3f, 0.4f, 0.45f, 0.6f, 0.8f).joinToString { "$it -> ${moistures.of(it)}" })

    for (cell in grid.cells) {
        if (cell.isRemoved) continue
        if (cell.height <= 0) {
            assignWaterBiome(cell, isOcean)
            continue
        }
        cell.biome = landBiome(cell, moistures)
    }
    println("zonal: ${land.groupingBy { it.biome }.eachCount()}")
    val m = land.map { it.moisture }.sorted()
    println("moisture: p10 ${m[m.size / 10]}, p50 ${m[m.size / 2]}, p90 ${m[m.size * 9 / 10]}")
    assignAzonalBiomes(grid)
}

private fun landBiome(cell: Cell, moistures: Percentiles): Biomes {
    val t = cell.temperature
    val w = moistures.of(cell.moisture)
    return when {
        t > WARM -> when {
            w >= W_VERY_WET -> Biomes.RAINFOREST
            w >= W_WET -> Biomes.TROPICAL_FOREST
            w >= W_MEDIUM -> Biomes.SAVANNA_FOREST
            w >= W_DRY -> Biomes.SAVANNA
            else -> Biomes.DESERT
        }
        t > COOL -> when {
            w >= W_VERY_WET -> Biomes.BROADLEAF_FOREST
            w >= W_WET -> Biomes.MIXED_FOREST
            w >= W_MOIST -> Biomes.FOREST_STEPPE
            w >= W_SEMI_DRY -> Biomes.GRASSLAND
            else -> Biomes.STEPPE
        }
        t > COLD -> if (w >= W_SEMI_DRY) Biomes.TAIGA else Biomes.COLD_STEPPE
        else -> if (w >= W_SEMI_DRY) Biomes.TUNDRA else Biomes.ICE_DESERT
    }
}

private fun assignWaterBiome(cell: Cell, isOcean: BooleanArray) {
    val seaLevelTemperature = cell.temperature + cell.height * 6.5f / 1000f
    if (seaLevelTemperature < OCEAN_GLACIER_TEMPERATURE) {
        cell.biome = Biomes.GLACIER
    }
    else if (cell.height >= -150) {
        if (cell.temperature > 22f) cell.biome = Biomes.REEF
        else cell.biome = Biomes.SHALLOWS
    }
    else {
        if (isOcean[cell.id]) cell.biome = Biomes.OCEAN
        else cell.biome = Biomes.SEA
    }
}

// Вызывать после assignZonalBiome и после createRivers: азональные переопределяют зональные.
// Использует slope(cell) и Cell.depth (D, считается в assignRivers).
fun assignAzonalBiomes(grid: MapGrid) {

    val cells = grid.cells.filter { it.height > 0 && !it.isRemoved }
    if (cells.isEmpty()) return
    val size = grid.cells.size

    // S и S3 (среднее по клетке и её соседям по суше)
    val slopes = FloatArray(size)
    for (cell in cells) slopes[cell.id] = slope(cell)
    val s3 = FloatArray(size)
    for (cell in cells) {
        var sum = slopes[cell.id]
        var n = 1
        for (edge in cell.edges) {
            val other = edge.otherCell(cell) ?: continue
            if (other.height <= 0) continue
            sum += slopes[other.id]
            n++
        }
        s3[cell.id] = sum / n
    }

    // A: порог по перцентилю от ln(1 + water)
    val sorted = FloatArray(cells.size) { ln(1f + cells[it].water) }.also { it.sort() }
    val aThreshold = sorted[(sorted.size * A_PERCENTILE).toInt().coerceAtMost(sorted.size - 1)]

    // у океана water тоже накапливается из рек, поэтому русло только на суше
    val nearWater = reach(grid, NEAR_WATER_STEPS) { it.height <= 0 || it.isLake || it.water >= RIVER_THRESHOLD }
    val nearRiver = reach(grid, NEAR_RIVER_STEPS) { it.height > 0 && it.water >= RIVER_THRESHOLD }

    val counts = HashMap<Biomes, Int>()
    for (cell in cells) {
        val id = cell.id
        val aHigh = ln(1f + cell.water) >= aThreshold
        val flat = s3[id] < FLAT_SLOPE
        val pit = cell.depth > 0f
        val isRiver = cell.water >= RIVER_THRESHOLD
        val biome = when {                                  // порядок = приоритет, первый подошедший побеждает
            cell.temperature < GLACIER_TEMPERATURE -> Biomes.GLACIER
            cell.biome == Biomes.DESERT && aHigh && nearWater[id] -> Biomes.OASIS
            pit && cell.moisture < DRY && cell.depth >= LAKEBED_MIN_DEPTH -> Biomes.DRIED_LAKEBED
            flat && cell.moisture >= WET && (pit || aHigh) -> Biomes.SWAMP
            isRiver && cell.biome !in forests -> Biomes.GALLERY_FOREST
            flat && aHigh && nearRiver[id] -> Biomes.FLOODPLAIN
            else -> null
        }
        if (biome != null) {
            cell.biome = biome
            counts[biome] = (counts[biome] ?: 0) + 1
        }
    }
    val depths = cells.map { it.depth }.filter { it > 0f }.sorted()
    if (depths.isNotEmpty()) {
        println("depth: pit cells ${depths.size} of ${cells.size}, p50 ${depths[depths.size / 2]}, p90 ${depths[depths.size * 9 / 10]}, p99 ${depths[depths.size * 99 / 100]}, max ${depths.last()}")
    }
    println("azonal biomes: $counts")
}

// Клетки, до которых от источников не больше steps шагов по соседям.
private fun reach(grid: MapGrid, steps: Int, isSource: (Cell) -> Boolean): BooleanArray {
    val reached = BooleanArray(grid.cells.size)
    var frontier = ArrayList<Cell>()
    for (cell in grid.cells) {
        if (cell.isRemoved || !isSource(cell)) continue
        reached[cell.id] = true
        frontier.add(cell)
    }
    repeat(steps) {
        val next = ArrayList<Cell>()
        for (cell in frontier) {
            for (edge in cell.edges) {
                val other = edge.otherCell(cell) ?: continue
                if (reached[other.id]) continue
                reached[other.id] = true
                next.add(other)
            }
        }
        frontier = next
    }
    return reached
}