package net.kira.fantasymap.model

class Edge(
    val start: Vertex,
    val end: Vertex,
    firstCell: Cell,
    var points: FloatArray = FloatArray(0)   // промежуточные точки: x, y, x, y...
) {
    private var cellA: Cell? = firstCell
    private var cellB: Cell? = null

    fun otherVertex(vertex: Vertex): Vertex = when {
        vertex === start -> end
        vertex === end -> start
        else -> error("Vertex does not belong to the edge")
    }

    fun attachCell(cell: Cell) {
        check(cellB == null) { "Cell already attached" }
        cellB = cell
    }

    fun otherCell(cell: Cell): Cell? = when {
        cellA === cell -> cellB
        cellB === cell -> cellA
        else -> error("Edge does not border the cell")
    }

    fun borders(cell: Cell): Boolean = cellA === cell || cellB === cell

    fun replaceCell(oldCell: Cell, newCell: Cell) {
        when {
            cellA === oldCell -> cellA = newCell
            cellB === oldCell -> cellB = newCell
            else -> error("Edge does not border the cell")
        }
    }
    val cells: List<Cell> get() = listOfNotNull(cellA, cellB)

    fun forEachSegment(action: (Float, Float, Float, Float) -> Unit) {
        var previousX = start.x
        var previousY = start.y
        var i = 0
        while (i < points.size) {
            action(previousX, previousY, points[i], points[i + 1])
            previousX = points[i]
            previousY = points[i + 1]
            i += 2
        }
        action(previousX, previousY, end.x, end.y)
    }

    fun insertPoint(index: Int, x: Float, y: Float) {
        val result = FloatArray(points.size + 2)
        points.copyInto(result, 0, 0, index * 2)
        result[index * 2] = x
        result[index * 2 + 1] = y
        points.copyInto(result, index * 2 + 2, index * 2, points.size)
        points = result
    }

    fun removePoint(index: Int) {
        val result = FloatArray(points.size - 2)
        points.copyInto(result, 0, 0, index * 2)
        points.copyInto(result, index * 2, index * 2 + 2, points.size)
        points = result
    }
}