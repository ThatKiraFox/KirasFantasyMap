package net.kira.fantasymap.model

import kotlin.random.Random

class MapGrid {
    val vertices = ArrayList<Vertex>()
    val edges = ArrayList<Edge>()
    val cells = ArrayList<Cell>()

    fun addVertex(x: Float, y: Float): Vertex = Vertex(x, y).also { vertices.add(it) }

    fun addCell(corners: List<Vertex>): Cell {   // вершины клетки по кругу, в любом направлении
        require(corners.size >= 3) { "A cell needs at least 3 corners" }
        val cell = Cell(cells.size)
        for (i in corners.indices) {
            val a = corners[i]
            val b = corners[(i + 1) % corners.size]
            cell.edges.add(findOrCreateEdge(a, b, cell))
        }
        cell.updateBounds()
        cells.add(cell)
        return cell
    }

    private fun findOrCreateEdge(a: Vertex, b: Vertex, cell: Cell): Edge {
        val existing = a.edges.firstOrNull { it.otherVertex(a) === b }
        if (existing != null) {
            existing.attachCell(cell)
            return existing
        }
        val edge = Edge(a, b, cell)
        a.edges.add(edge)
        b.edges.add(edge)
        edges.add(edge)
        return edge
    }
}