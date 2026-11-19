package net.kira.fantasymap.util

import net.kira.fantasymap.generation.general.GenerationParams
import net.kira.fantasymap.generation.general.generateMap
import net.kira.fantasymap.model.mapgrid.MapGrid
import java.nio.ByteBuffer
import java.util.zip.CRC32

// Контрольная сумма всего, на что будут ссылаться правки: вершины (id, координаты), рёбра (концы, точки), клетки (id, удалена ли, рёбра, высота).
fun gridChecksum(grid: MapGrid): Long {
    val crc = CRC32()
    val buffer = ByteBuffer.allocate(64)
    fun put(vararg values: Int) {
        buffer.clear()
        for (v in values) buffer.putInt(v)
        crc.update(buffer.array(), 0, values.size * 4)
    }

    put(grid.cells.size, grid.vertices.size, grid.edges.size, grid.lakes.size, grid.rivers.size)
    for (vertex in grid.vertices) put(vertex.id, vertex.x.toRawBits(), vertex.y.toRawBits())
    for (edge in grid.edges) {
        put(edge.start.id, edge.end.id, edge.points.size)
        for (p in edge.points) put(p.toRawBits())
    }
    for (cell in grid.cells) {
        put(cell.id, if (cell.isRemoved) 1 else 0, cell.height, cell.edges.size)
        for (edge in cell.edges) put(edge.start.id, edge.end.id)
    }
    for (river in grid.rivers) {
        put(river.points.size, river.startWater.toRawBits(), river.endWater.toRawBits())
        for (p in river.points) put(p.toRawBits())
    }
    return crc.value
}

fun checkDeterminism(params: GenerationParams) {
    val first = gridChecksum(generateMap(params))
    val second = gridChecksum(generateMap(params))
    println("determinism: $first vs $second -> ${if (first == second) "OK" else "DIFFERENT"}")
}