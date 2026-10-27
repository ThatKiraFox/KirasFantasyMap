package net.kira.fantasymap.generation

import io.ygdrasil.delaunator.Delaunator
import io.ygdrasil.delaunator.domain.Point
import net.kira.fantasymap.imported.FastNoiseLite
import net.kira.fantasymap.model.MapGrid
import net.kira.fantasymap.model.Vertex
import net.kira.fantasymap.render.curveEdges
import net.kira.fantasymap.util.timed
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random


class Site(val x: Double, val y: Double)

class Triangulation(val triangles: IntArray, val halfedges: IntArray)

private const val JITTER = 0.8   // 0: ровная сетка, 1: точка в любом месте своего квадрата
private const val BASE_FREQUENCY = 3f

fun generateMap(worldWidth: Float, worldHeight: Float, cellCount: Int, seed: Long): MapGrid {
    val random = Random(seed)
    val sites = generateSites(worldWidth, worldHeight, cellCount, random)
    val grid = buildGrid(sites, triangulate(sites), worldWidth, worldHeight)
    timed("curveEdges") { curveEdges(grid, Random(seed + 1)) }
    timed("assignHeights") { assignHeights(grid, worldWidth, worldHeight, seed + 2) }
    println("edges with NaN: ${grid.edges.count { e -> e.points.any { it.isNaN() } }}")
    println("zero-length edges: ${grid.edges.count { it.start.x == it.end.x && it.start.y == it.end.y }}")
    return grid
}

// Точки по сетке с дрожанием, с запасом по краям шире карты на две клетки.
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

// Единственное место, которое трогает библиотеку.
fun triangulate(sites: List<Site>): Triangulation {
    val delaunator = Delaunator(sites.map { Point(it.x, it.y) })
    return Triangulation(delaunator.triangles.toIntArray(), delaunator.halfedges.toIntArray())
}

fun buildGrid(sites: List<Site>, triangulation: Triangulation, worldWidth: Float, worldHeight: Float): MapGrid {
    val triangles = triangulation.triangles
    val halfedges = triangulation.halfedges
    val grid = MapGrid()
    val vertexOfTriangle = arrayOfNulls<Vertex>(triangles.size / 3)

    // Одна вершина на треугольник: в центре его описанной окружности.
    fun vertexOf(triangle: Int): Vertex =
        vertexOfTriangle[triangle] ?: run {
            val a = sites[triangles[3 * triangle]]
            val b = sites[triangles[3 * triangle + 1]]
            val c = sites[triangles[3 * triangle + 2]]
            val center = circumcenter(a, b, c)
            grid.addVertex(center.x.toFloat(), center.y.toFloat()).also { vertexOfTriangle[triangle] = it }
        }

    // Для каждой точки запоминаем одно входящее в неё полуребро.
    val incomingHalfedge = IntArray(sites.size) { -1 }
    for (halfedge in triangles.indices) {
        incomingHalfedge[triangles[nextHalfedge(halfedge)]] = halfedge
    }

    for (siteIndex in sites.indices) {
        val site = sites[siteIndex]
        if (site.x < 0 || site.x > worldWidth || site.y < 0 || site.y > worldHeight) continue  // точки запаса
        val start = incomingHalfedge[siteIndex]
        if (start == -1) continue

        // Обходим треугольники вокруг точки: их вершины и есть углы клетки.
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


fun assignHeights(grid: MapGrid, worldWidth: Float, worldHeight: Float, seed: Long) {
    val noise = FastNoiseLite((seed + 1).toInt()).apply {
        SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2)
        SetFractalType(FastNoiseLite.FractalType.FBm)
        SetFractalOctaves(6)
        SetFractalLacunarity(2f)    // каждый следующий слой вдвое мельче
        SetFractalGain(0.5f)        // и вдвое слабее
        SetFrequency(1f)            // по умолчанию 0.01, поэтому ставим явно
    }
    val worldSize = max(worldWidth, worldHeight)
    for (cell in grid.cells) {
        val centerX = (cell.minX + cell.maxX) / 2f
        val centerY = (cell.minY + cell.maxY) / 2f
        val value = noise.GetNoise(centerX / worldSize * BASE_FREQUENCY, centerY / worldSize * BASE_FREQUENCY)  // примерно от -1 до 1
        cell.height = (value * 224f).toInt().coerceIn(-99, 224)
    }
}