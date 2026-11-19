package net.kira.fantasymap.editors

import java.io.DataInputStream
import java.io.DataOutputStream

sealed class Edit {
    abstract val type: Int
    abstract fun write(out: DataOutputStream)

    companion object {
        fun read(input: DataInputStream): Edit = when (val type = input.readByte().toInt()) {
            1 -> MoveVertex(input.readInt(), input.readFloat(), input.readFloat(), input.readMerges())
            2 -> MoveCurvePoint(input.readInt(), input.readInt(), input.readInt(), input.readFloat(), input.readFloat(), input.readMerges())
            3 -> CurvePointChange(input.readInt(), input.readInt(), input.readInt(), input.readFloat(), input.readFloat(), input.readBoolean())
            4 -> MoveShapePoint(input.readInt(), input.readInt(), input.readFloat(), input.readFloat())
            5 -> AddLake(input.readInt(), input.readFloats())
            6 -> AddRiver(input.readInt(), input.readFloats(), input.readFloat(), input.readFloat())
            7 -> RemoveShape(input.readInt())
            8 -> RiverPointChange(input.readInt(), input.readInt(), input.readFloat(), input.readFloat(), input.readBoolean())
            9 -> SetRiverWater(input.readInt(), input.readFloat(), input.readFloat())
            10 -> PaintBiomes(input.readUTF(), input.readInts())
            11 -> ApplyRiver(input.readInt())
            else -> error("Unknown edit type $type")
        }
    }
}

fun DataOutputStream.writeEdit(edit: Edit) {
    writeByte(edit.type)
    edit.write(this)
}

// Сдвиг основной точки. merges: пары (маленькая клетка, с кем объединили), по id клеток.
class MoveVertex(val vertexId: Int, val x: Float, val y: Float, val merges: List<Pair<Int, Int>>) : Edit() {
    override val type = 1
    override fun write(out: DataOutputStream) {
        out.writeInt(vertexId); out.writeFloat(x); out.writeFloat(y); out.writeMerges(merges)
    }
}

// Сдвиг точки кривой. Ребро задано id двух вершин.
class MoveCurvePoint(val startId: Int, val endId: Int, val index: Int, val x: Float, val y: Float, val merges: List<Pair<Int, Int>>) : Edit() {
    override val type = 2
    override fun write(out: DataOutputStream) {
        out.writeInt(startId); out.writeInt(endId); out.writeInt(index)
        out.writeFloat(x); out.writeFloat(y); out.writeMerges(merges)
    }
}

// Добавление (added = true) или удаление точки кривой.
class CurvePointChange(val startId: Int, val endId: Int, val index: Int, val x: Float, val y: Float, val added: Boolean) : Edit() {
    override val type = 3
    override fun write(out: DataOutputStream) {
        out.writeInt(startId); out.writeInt(endId); out.writeInt(index)
        out.writeFloat(x); out.writeFloat(y); out.writeBoolean(added)
    }
}

// Сдвиг точки озера или реки (id фигуры общий для озёр и рек).
class MoveShapePoint(val shapeId: Int, val index: Int, val x: Float, val y: Float) : Edit() {
    override val type = 4
    override fun write(out: DataOutputStream) {
        out.writeInt(shapeId); out.writeInt(index); out.writeFloat(x); out.writeFloat(y)
    }
}

class AddLake(val id: Int, val points: FloatArray) : Edit() {
    override val type = 5
    override fun write(out: DataOutputStream) { out.writeInt(id); out.writeFloats(points) }
}

class AddRiver(val id: Int, val points: FloatArray, val startWater: Float, val endWater: Float) : Edit() {
    override val type = 6
    override fun write(out: DataOutputStream) {
        out.writeInt(id); out.writeFloats(points); out.writeFloat(startWater); out.writeFloat(endWater)
    }
}

// Удаление озера или реки.
class RemoveShape(val shapeId: Int) : Edit() {
    override val type = 7
    override fun write(out: DataOutputStream) { out.writeInt(shapeId) }
}

class RiverPointChange(val riverId: Int, val index: Int, val x: Float, val y: Float, val added: Boolean) : Edit() {
    override val type = 8
    override fun write(out: DataOutputStream) {
        out.writeInt(riverId); out.writeInt(index); out.writeFloat(x); out.writeFloat(y); out.writeBoolean(added)
    }
}

class SetRiverWater(val riverId: Int, val start: Float, val end: Float) : Edit() {
    override val type = 9
    override fun write(out: DataOutputStream) { out.writeInt(riverId); out.writeFloat(start); out.writeFloat(end) }
}

// Биом по имени, а не по номеру: порядок в enum можно менять.
class PaintBiomes(val biome: String, val cellIds: IntArray) : Edit() {
    override val type = 10
    override fun write(out: DataOutputStream) { out.writeUTF(biome); out.writeInts(cellIds) }
}

// «Применить» к реке: разрезы клеток по её текущей линии.
class ApplyRiver(val riverId: Int) : Edit() {
    override val type = 11
    override fun write(out: DataOutputStream) { out.writeInt(riverId) }
}

private fun DataOutputStream.writeFloats(a: FloatArray) { writeInt(a.size); for (v in a) writeFloat(v) }
private fun DataInputStream.readFloats() = FloatArray(readInt()) { readFloat() }
private fun DataOutputStream.writeInts(a: IntArray) { writeInt(a.size); for (v in a) writeInt(v) }
private fun DataInputStream.readInts() = IntArray(readInt()) { readInt() }
private fun DataOutputStream.writeMerges(m: List<Pair<Int, Int>>) {
    writeInt(m.size); for ((a, b) in m) { writeInt(a); writeInt(b) }
}
private fun DataInputStream.readMerges() = List(readInt()) { Pair(readInt(), readInt()) }