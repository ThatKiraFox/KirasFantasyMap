package net.kira.fantasymap.generation

import io.ygdrasil.delaunator.Delaunator
import io.ygdrasil.delaunator.domain.Point
import net.kira.fantasymap.generation.hydrology.assignLakes
import net.kira.fantasymap.generation.hydrology.assignMoisture
import net.kira.fantasymap.generation.hydrology.assignRivers
import net.kira.fantasymap.generation.hydrology.createLakes
import net.kira.fantasymap.generation.hydrology.createRivers
import net.kira.fantasymap.model.MapGrid
import net.kira.fantasymap.model.Vertex
import net.kira.fantasymap.render.curveEdges
import net.kira.fantasymap.util.timed
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlin.random.Random


class Site(val x: Double, val y: Double)

class Triangulation(val triangles: IntArray, val halfedges: IntArray)

private const val JITTER = 0.8

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
    val order = timed("assignRivers") { assignRivers(grid, maxHeight) }
    timed("assignLakes") { assignLakes(grid) }
    val steps = timed("cutRivers") { cutRivers(grid, order, worldWidth, worldHeight, Random(seed + 4)) }
    timed("createLakes") { createLakes(grid) }
    timed("createRivers") { createRivers(grid, order, steps) }
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