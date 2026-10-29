package net.kira.fantasymap.generation

import io.ygdrasil.delaunator.Delaunator
import io.ygdrasil.delaunator.domain.Point
import net.kira.fantasymap.imported.FastNoiseLite
import net.kira.fantasymap.model.MapGrid
import net.kira.fantasymap.model.Vertex
import net.kira.fantasymap.render.curveEdges
import net.kira.fantasymap.util.timed
import java.lang.Math.toRadians
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random


class Site(val x: Double, val y: Double)

class Triangulation(val triangles: IntArray, val halfedges: IntArray)

private const val JITTER = 0.8
private const val BASE_FREQUENCY = 3f
private const val MAX_LATITUDE_DEGREES = 90
private const val MIN_HEIGHT = -999
private const val RAIN = 0.0001f// потеря влаги на единицу мира: воздух теряет 1/e влаги примерно за 1/RAIN единиц (~330)
private const val OROGRAPHIC = 0.00002f
private const val MOISTURE_NOISE_FREQUENCY = 0f
private const val MOISTURE_NOISE_STRENGTH = 0f
private const val SMOOTH_PASSES = 60
private const val MOISTURE_SMOOTH_PASSES = 3

fun generateMap(worldWidth: Float, worldHeight: Float, cellCount: Int, seed: Long, maxHeight: Int, fraction: Float): MapGrid {
    val random = Random(seed)
    println("Generations steps:")
    val sites = timed("generateSites") { generateSites(worldWidth, worldHeight, cellCount, random) }
    val grid = timed("buildGrid") { buildGrid(sites, triangulate(sites), worldWidth, worldHeight) }
    timed("curveEdges") { curveEdges(grid, Random(seed + 1)) }
    timed("assignHeights") { assignHeights(grid, worldWidth, worldHeight, seed + 2, maxHeight, fraction) }
    timed("assignLatitude") { assignLatitude(grid, worldHeight) }
    timed("assignTemperature") { assignTemperature(grid) }
    timed("assignBaseMoisture") { assignMoisture(grid, worldHeight, worldWidth, seed + 3) }
    println("edges with NaN: ${grid.edges.count { e -> e.points.any { it.isNaN() } }}")
    println("zero-length edges: ${grid.edges.count { it.start.x == it.end.x && it.start.y == it.end.y }}")
    return grid
}

fun generateSites(worldWidth: Float, worldHeight: Float, cellCount: Int, random: Random): List<Site> {
    val spacing = sqrt(worldWidth.toDouble() * worldHeight / cellCount)
    val margin = 2 * spacing
    val columns = ceil((worldWidth + 2 * margin) / spacing).toInt()
    val rows = ceil((worldHeight + 2 * margin) / spacing).toInt()

    val sites = ArrayList<Site>(columns * rows)
    for (row in 0 until rows) {
        for (column in 0 until columns) {
            val x = -margin + (column + 0.5 + (random.nextDouble() - 0.5) * JITTER) * spacing
            val y = -margin + (row + 0.5 + (random.nextDouble() - 0.5) * JITTER) * spacing
            sites.add(Site(x, y))
        }
    }
    return sites
}

fun triangulate(sites: List<Site>): Triangulation {
    val delaunator = Delaunator(sites.map { Point(it.x, it.y) })
    return Triangulation(delaunator.triangles.toIntArray(), delaunator.halfedges.toIntArray())
}

fun buildGrid(sites: List<Site>, triangulation: Triangulation, worldWidth: Float, worldHeight: Float): MapGrid {
    val triangles = triangulation.triangles
    val halfedges = triangulation.halfedges
    val grid = MapGrid()
    val vertexOfTriangle = arrayOfNulls<Vertex>(triangles.size / 3)

    fun vertexOf(triangle: Int): Vertex =
        vertexOfTriangle[triangle] ?: run {
            val a = sites[triangles[3 * triangle]]
            val b = sites[triangles[3 * triangle + 1]]
            val c = sites[triangles[3 * triangle + 2]]
            val center = circumcenter(a, b, c)
            grid.addVertex(center.x.toFloat(), center.y.toFloat()).also { vertexOfTriangle[triangle] = it }
        }

    val incomingHalfedge = IntArray(sites.size) { -1 }
    for (halfedge in triangles.indices) {
        incomingHalfedge[triangles[nextHalfedge(halfedge)]] = halfedge
    }

    for (siteIndex in sites.indices) {
        val site = sites[siteIndex]
        if (site.x < 0 || site.x > worldWidth || site.y < 0 || site.y > worldHeight) continue  // точки запаса
        val start = incomingHalfedge[siteIndex]
        if (start == -1) continue

        val corners = ArrayList<Vertex>()
        var incoming = start
        do {
            corners.add(vertexOf(incoming / 3))
            incoming = halfedges[nextHalfedge(incoming)]
        } while (incoming != -1 && incoming != start)
        if (incoming == -1) continue   // клетка не замкнулась

        grid.addCell(corners)
    }
    return grid
}

private fun nextHalfedge(halfedge: Int) = if (halfedge % 3 == 2) halfedge - 2 else halfedge + 1

private fun circumcenter(a: Site, b: Site, c: Site): Site {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val ex = c.x - a.x
    val ey = c.y - a.y
    val bLength = dx * dx + dy * dy
    val cLength = ex * ex + ey * ey
    val factor = 0.5 / (dx * ey - dy * ex)
    return Site(a.x + (ey * bLength - dy * cLength) * factor, a.y + (dx * cLength - ex * bLength) * factor)
}


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


}

fun assignLatitude(grid: MapGrid, worldHeight: Float) {
    for (cell in grid.cells) {
        val y = cell.getCenter().second
        // 0 → -90°, середина → 0°, max → +90°
        val latitude = (y / worldHeight) * 180.0f - MAX_LATITUDE_DEGREES
        cell.updateLatitude(latitude)
    }
}

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