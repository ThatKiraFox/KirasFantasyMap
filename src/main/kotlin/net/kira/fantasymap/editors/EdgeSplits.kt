package net.kira.fantasymap.editors

import net.kira.fantasymap.model.mapgrid.Edge
import net.kira.fantasymap.model.mapgrid.MapGrid
import net.kira.fantasymap.model.mapgrid.Vertex

class EdgeSplit(val vertex: Vertex, val first: Edge, val second: Edge)   // first: от начала до вершины, second: от вершины до конца

// Делит ребро в точке (x, y), лежащей на отрезке номер segment его ломаной.
fun splitEdge(grid: MapGrid, edge: Edge, segment: Int, x: Float, y: Float): EdgeSplit {
    val vertex = grid.addVertex(x, y)
    val cells = edge.cells
    val first = Edge(edge.start, vertex, cells[0], edge.points.copyOfRange(0, segment * 2))
    val second = Edge(vertex, edge.end, cells[0], edge.points.copyOfRange(segment * 2, edge.points.size))
    if (cells.size > 1) {
        first.attachCell(cells[1])
        second.attachCell(cells[1])
    }
    first.riverWater = edge.riverWater
    second.riverWater = edge.riverWater

    edge.start.edges.remove(edge)
    edge.end.edges.remove(edge)
    edge.start.edges.add(first)
    vertex.edges.add(first)
    vertex.edges.add(second)
    edge.end.edges.add(second)
    grid.edges.remove(edge)
    grid.edges.add(first)
    grid.edges.add(second)

    // В кольце каждой клетки ребро заменяется двумя, в том порядке, в котором кольцо идёт по ребру.
    for (cell in cells) {
        val ring = cell.edges
        val i = ring.indexOfFirst { it === edge }
        val previous = ring[(i - 1 + ring.size) % ring.size]
        val forward = previous.start === edge.start || previous.end === edge.start
        ring.removeAt(i)
        if (forward) {
            ring.add(i, first)
            ring.add(i + 1, second)
        } else {
            ring.add(i, second)
            ring.add(i + 1, first)
        }
    }
    return EdgeSplit(vertex, first, second)
}

// Обратное к splitEdge. false: нельзя (у вершины не два ребра, или по сторонам разные клетки).
fun unsplitVertex(grid: MapGrid, vertex: Vertex): Boolean {
    if (vertex.edges.size != 2) return false
    val first = vertex.edges[0]
    val second = vertex.edges[1]
    val cells = first.cells
    if (cells.size != second.cells.size || cells.any { c -> second.cells.none { it === c } }) return false
    if (first.riverWater != second.riverWater) return false

    val a = first.otherVertex(vertex)
    val b = second.otherVertex(vertex)
    val pointsToVertex = if (first.start === a) first.points else reversedPoints(first.points)
    val pointsFromVertex = if (second.start === vertex) second.points else reversedPoints(second.points)
    val merged = Edge(a, b, cells[0], pointsToVertex + floatArrayOf(vertex.x, vertex.y) + pointsFromVertex)
    if (cells.size > 1) merged.attachCell(cells[1])
    merged.riverWater = first.riverWater

    a.edges.remove(first)
    a.edges.add(merged)
    b.edges.remove(second)
    b.edges.add(merged)
    vertex.edges.clear()
    grid.edges.remove(first)
    grid.edges.remove(second)
    grid.edges.add(merged)
    grid.vertices.remove(vertex)

    for (cell in cells) {
        val ring = cell.edges
        val i1 = ring.indexOfFirst { it === first }
        val i2 = ring.indexOfFirst { it === second }
        val low = minOf(i1, i2)
        val high = maxOf(i1, i2)
        if (high - low == 1) {
            ring[low] = merged
            ring.removeAt(high)
        } else {                         // они стоят по краям списка: кольцо замыкается
            ring[0] = merged
            ring.removeAt(ring.size - 1)
        }
    }
    return true
}

private fun reversedPoints(points: FloatArray): FloatArray {
    val result = FloatArray(points.size)
    for (i in 0 until points.size / 2) {
        result[2 * i] = points[points.size - 2 - 2 * i]
        result[2 * i + 1] = points[points.size - 1 - 2 * i]
    }
    return result
}