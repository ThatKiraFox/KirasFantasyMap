package net.kira.fantasymap.repository

import net.kira.fantasymap.editors.AddLake
import net.kira.fantasymap.editors.AddRiver
import net.kira.fantasymap.editors.ApplyRiver
import net.kira.fantasymap.editors.CurvePointChange
import net.kira.fantasymap.editors.Edit
import net.kira.fantasymap.editors.MergeCells
import net.kira.fantasymap.editors.MoveCurvePoint
import net.kira.fantasymap.editors.MoveShapePoint
import net.kira.fantasymap.editors.MoveVertex
import net.kira.fantasymap.editors.PaintBiomes
import net.kira.fantasymap.editors.RemoveShape
import net.kira.fantasymap.editors.RiverPointChange
import net.kira.fantasymap.editors.SetRiverWater
import net.kira.fantasymap.generation.hydrology.cutAlongRiver
import net.kira.fantasymap.generation.hydrology.uncutRiver
import net.kira.fantasymap.generation.hydrology.updateRiverProfiles
import net.kira.fantasymap.model.Biomes
import net.kira.fantasymap.model.mapgrid.Cell
import net.kira.fantasymap.model.mapgrid.CellIndex
import net.kira.fantasymap.model.mapgrid.Edge
import net.kira.fantasymap.model.mapgrid.MapGrid
import net.kira.fantasymap.model.pointshape.Lake
import net.kira.fantasymap.model.pointshape.River

class EditApplier(private val grid: MapGrid, private val cellIndex: CellIndex, private val riverThreshold: Float) {
    var failed = 0
        private set

    fun apply(edits: List<Edit>) {
        for (edit in edits) {
            try {
                applyOne(edit)
            } catch (e: Exception) {
                failed++
                println("load: edit ${edit::class.simpleName} failed: $e")
            }
        }
        updateRiverProfiles(grid.rivers, riverThreshold)
    }

    private fun applyOne(edit: Edit) {
        when (edit) {
            is MoveVertex -> {
                val vertex = grid.vertexById(edit.vertexId)
                vertex.x = edit.x
                vertex.y = edit.y
                refresh(vertex.edges.flatMap { it.cells }.distinct())
                merge(edit.merges)
            }
            is MoveCurvePoint -> {
                val edge = edgeOf(edit.startId, edit.endId)
                edge.points[2 * edit.index] = edit.x
                edge.points[2 * edit.index + 1] = edit.y
                refresh(edge.cells)
                merge(edit.merges)
            }
            is CurvePointChange -> {
                val edge = edgeOf(edit.startId, edit.endId)
                if (edit.added) edge.insertPoint(edit.index, edit.x, edit.y) else edge.removePoint(edit.index)
                refresh(edge.cells)
            }
            is MoveShapePoint -> grid.shapeById(edit.shapeId).movePoint(edit.index, edit.x, edit.y)
            is AddLake -> grid.addLake(Lake(edit.points).also { it.id = edit.id })
            is AddRiver -> grid.addRiver(River(edit.points, edit.startWater, edit.endWater, riverThreshold = riverThreshold).also { it.id = edit.id })
            is RemoveShape -> when (val shape = grid.shapeById(edit.shapeId)) {
                is Lake -> grid.lakes.remove(shape)
                is River -> grid.rivers.remove(shape)
            }
            is RiverPointChange -> {
                val river = grid.shapeById(edit.riverId) as River
                if (edit.added) river.insertPoint(edit.index, edit.x, edit.y) else river.removePoint(edit.index)
            }
            is SetRiverWater -> {
                val river = grid.shapeById(edit.riverId) as River
                river.startWater = edit.start
                river.endWater = edit.end
            }
            is PaintBiomes -> {
                val biome = Biomes.valueOf(edit.biome)
                for (id in edit.cellIds) {
                    val cell = grid.cells[id]
                    if (!cell.isRemoved) cell.biome = biome
                }
            }
            is ApplyRiver -> {
                val river = grid.shapeById(edit.riverId) as River
                val onMap = river in grid.rivers
                uncutRiver(grid, cellIndex, river)
                if (onMap) cutAlongRiver(grid, cellIndex, river)
                river.cutPoints = if (onMap) river.points.copyOf() else null
                river.generation++
            }
        }
    }

    private fun edgeOf(startId: Int, endId: Int): Edge =
        grid.findEdge(startId, endId) ?: error("edge $startId-$endId not found")

    private fun refresh(cells: List<Cell>) {
        for (cell in cells) if (!cell.isRemoved) cellIndex.reindex(cell)
    }

    private fun merge(pairs: List<Pair<Int, Int>>) {
        for ((smallId, targetId) in pairs) {
            val plan = MergeCells.plan(grid.cells[smallId], grid.cells[targetId])
            if (plan == null) { failed++; println("load: merge $smallId -> $targetId failed"); continue }
            plan.apply(grid, cellIndex)
        }
    }
}