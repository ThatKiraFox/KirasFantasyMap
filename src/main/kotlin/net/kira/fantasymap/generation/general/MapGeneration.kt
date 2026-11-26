package net.kira.fantasymap.generation.general

import io.ygdrasil.delaunator.Delaunator
import io.ygdrasil.delaunator.domain.Point
import net.kira.fantasymap.generation.GenerationStep
import net.kira.fantasymap.generation.hydrology.RiverStep
import net.kira.fantasymap.generation.loadingString
import net.kira.fantasymap.model.mapgrid.Cell
import net.kira.fantasymap.model.mapgrid.MapGrid
import net.kira.fantasymap.model.mapgrid.Vertex
import net.kira.fantasymap.render.DEFAULT_DISPLACEMENT
import net.kira.fantasymap.render.DEFAULT_ITERATIONS
import net.kira.fantasymap.util.timed
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlin.random.Random


class Site(val x: Double, val y: Double)

class Triangulation(val triangles: IntArray, val halfedges: IntArray)

private const val JITTER = 0.8

class GenerationParams(
    val worldWidth: Float, val worldHeight: Float, val cellCount: Int,
    val seed: Long, val maxHeight: Int, val fraction: Float,
    val latitudeFrom: Float = -90f, val latitudeTo: Float = 90f,
    val equatorTemperature: Float = 26.2f,
    val northPoleTemperature: Float = -18.3f,
    val southPoleTemperature: Float = -18.3f,
    val lapseRate: Float = 6.5f,
    val humidity: Float = 1f,
    val lakeMinMoisture: Float = 0.2f,
    val riverThreshold: Float = 50f,
    val baseFrequency: Float = 2f,
    val heightPower: Float = 3f,
    val mercatorProjection: Boolean = true,
    val curveIterations: Int = DEFAULT_ITERATIONS,
    val curveDisplacement: Float = DEFAULT_DISPLACEMENT
)

class GenerationContext(val params: GenerationParams, val grid: MapGrid) {
    var order: List<Cell> = emptyList()                       // из RIVERS
    var riverSteps: Map<Cell, RiverStep> = emptyMap()         // из CUT_RIVERS
}

var coefficient = 1f
    private set

val DEFAULT_STEPS: List<GenerationStep> = GenerationStep.entries.toList()

fun generateMap(params: GenerationParams, steps: List<GenerationStep> = DEFAULT_STEPS, onStep: (String) -> Unit = {}): MapGrid {
    coefficient = params.worldWidth / 4000f
    onStep("Сетка")
    val random = Random(params.seed)
    val sites = generateSites(params.worldWidth, params.worldHeight, params.cellCount, random)
    val grid = buildGrid(sites, triangulate(sites), params.worldWidth, params.worldHeight)
    val context = GenerationContext(params, grid)
    for (step in steps) {
        onStep(step.loadingString())
        timed(step.name) { step.run(context) }
    }
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