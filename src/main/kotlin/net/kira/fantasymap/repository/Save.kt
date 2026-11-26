package net.kira.fantasymap.repository

import net.kira.fantasymap.editors.Edit
import net.kira.fantasymap.editors.writeEdit
import net.kira.fantasymap.generation.GenerationStep
import net.kira.fantasymap.generation.general.GenerationParams
import net.kira.fantasymap.generation.general.generateMap
import net.kira.fantasymap.model.mapgrid.CellIndex
import net.kira.fantasymap.model.mapgrid.MapGrid
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.sqrt

const val FORMAT_VERSION = 1       // поднимать при изменении структуры файла
const val GENERATOR_VERSION = 3    // поднимать, когда генерация при тех же параметрах даёт другую карту

class SaveFile(val params: GenerationParams, val steps: List<GenerationStep>, val edits: List<Edit>) {

    fun write(file: File) {
        val temp = File(file.path + ".tmp")      // пишем во временный файл, чтобы сбой не испортил старое сохранение
        DataOutputStream(temp.outputStream().buffered()).use { raw ->
            raw.writeBytes("FMAP")
            raw.writeInt(FORMAT_VERSION)
            raw.writeInt(GENERATOR_VERSION)
            DataOutputStream(GZIPOutputStream(raw).buffered()).use { out ->
                out.writeFloat(params.worldWidth)
                out.writeFloat(params.worldHeight)
                out.writeInt(params.cellCount)
                out.writeLong(params.seed)
                out.writeInt(params.maxHeight)
                out.writeFloat(params.fraction)
                out.writeFloat(params.latitudeFrom)
                out.writeFloat(params.latitudeTo)
                out.writeFloat(params.equatorTemperature)
                out.writeFloat(params.northPoleTemperature)
                out.writeFloat(params.southPoleTemperature)
                out.writeFloat(params.lapseRate)
                out.writeFloat(params.humidity)
                out.writeFloat(params.lakeMinMoisture)
                out.writeFloat(params.riverThreshold)
                out.writeFloat(params.baseFrequency)
                out.writeFloat(params.heightPower)
                out.writeBoolean(params.mercatorProjection)
                out.writeInt(params.curveIterations)
                out.writeFloat(params.curveDisplacement)
                out.writeInt(steps.size)
                for (step in steps) out.writeInt(step.id)

                out.writeInt(edits.size)
                for (edit in edits) out.writeEdit(edit)
            }
        }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)


    }
    companion object {
        fun read(file: File): SaveFile {
            DataInputStream(file.inputStream().buffered()).use { raw ->
                val magic = ByteArray(4).also { raw.readFully(it) }
                require(String(magic) == "FMAP") { "Это не файл карты" }
                val format = raw.readInt()
                require(format == FORMAT_VERSION) { "Неизвестная версия формата: $format" }
                val generator = raw.readInt()
                require(generator == GENERATOR_VERSION) { "Файл создан другой версией генератора ($generator), карта получится другой" }
                DataInputStream(GZIPInputStream(raw).buffered()).use { input ->
                    val params = GenerationParams(
                        input.readFloat(), input.readFloat(), input.readInt(),
                        input.readLong(), input.readInt(), input.readFloat(),
                        input.readFloat(), input.readFloat(),
                        input.readFloat(), input.readFloat(), input.readFloat(),
                        input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat(),
                        input.readFloat(), input.readFloat(), input.readBoolean(),
                        input.readInt(), input.readFloat(),
                    )
                    val steps = List(input.readInt()) { GenerationStep.byId(input.readInt()) }
                    val edits = List(input.readInt()) { Edit.read(input) }
                    return SaveFile(params, steps, edits)
                }
            }
        }
    }
}

class LoadedMap(
    val grid: MapGrid, val params: GenerationParams, val steps: List<GenerationStep>,
    val edits: List<Edit>, val failed: Int, val file: File?
)

fun loadMap(file: File, onStep: (String) -> Unit = {}): LoadedMap {
    val save = SaveFile.read(file)
    val grid = generateMap(save.params, save.steps, onStep)
    onStep("Применение правок")
    val averageArea = save.params.worldWidth * save.params.worldHeight / grid.cells.size
    val index = CellIndex(
        grid.cells.filter { !it.isRemoved },
        save.params.worldWidth,
        save.params.worldHeight,
        sqrt(averageArea) * 2f
    )
    val applier = EditApplier(grid, index, save.params.riverThreshold)
    applier.apply(save.edits)
    println("loadMap: grid ${System.identityHashCode(grid)}, rivers ${grid.rivers.size}, edits by type ${save.edits.groupingBy { it::class.simpleName }.eachCount()}")
    return LoadedMap(grid, save.params, save.steps, save.edits, applier.failed, file)
}