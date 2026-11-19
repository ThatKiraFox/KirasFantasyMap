package net.kira.fantasymap.model.mapgrid

import net.kira.fantasymap.model.pointshape.Lake
import net.kira.fantasymap.model.pointshape.PointShape
import net.kira.fantasymap.model.pointshape.River
import java.util.concurrent.CopyOnWriteArrayList

class MapGrid {
    val vertices = ArrayList<Vertex>()
    val edges = ArrayList<Edge>()
    val cells = ArrayList<Cell>()
    val lakes = CopyOnWriteArrayList<Lake>()      // плитки рисуются в фоновых потоках, а правки идут из основного
    val rivers = CopyOnWriteArrayList<River>()
    var maxPopulationDensity = 0f

    private val allVertices = ArrayList<Vertex>()          // все когда-либо созданные вершины, индекс = id (удалённые остаются)
    private val shapes = HashMap<Int, PointShape>()        // все когда-либо добавленные озёра и реки, включая удалённые
    private var nextShapeId = 0

    fun addVertex(x: Float, y: Float): Vertex =
        Vertex(allVertices.size, x, y).also {
            vertices.add(it)
            allVertices.add(it)
        }

    fun vertexById(id: Int): Vertex = allVertices[id]

    // Ребро между двумя вершинами по их id.
    fun findEdge(startId: Int, endId: Int): Edge? {
        val a = vertexById(startId)
        return a.edges.firstOrNull { it.otherVertex(a).id == endId }
    }

    fun addLake(lake: Lake) {
        register(lake)
        lakes.add(lake)
    }

    fun addRiver(river: River) {
        register(river)
        rivers.add(river)
    }

    fun shapeById(id: Int): PointShape = shapes.getValue(id)

    private fun register(shape: PointShape) {
        if (shape.id < 0) shape.id = nextShapeId
        nextShapeId = maxOf(nextShapeId, shape.id + 1)      // при загрузке id приходит из файла
        shapes[shape.id] = shape
    }

    // Вершины клетки по кругу, в любом направлении. Общие рёбра с соседями находятся автоматически.
    fun addCell(corners: List<Vertex>): Cell {
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