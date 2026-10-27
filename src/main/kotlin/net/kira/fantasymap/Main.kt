package net.kira.fantasymap

import net.kira.fantasymap.render.MapView
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import net.kira.fantasymap.generation.generateMap

fun main() {
    val start = System.currentTimeMillis()
    val before = usedMemoryMb()
    val grid = generateMap(4000f, 2500f, 300_000, seed = 1)
    val after = usedMemoryMb()
    println("cells=${grid.cells.size}, edges=${grid.edges.size}, vertices=${grid.vertices.size}, ${System.currentTimeMillis() - start} ms")
    println("grid takes about ${after - before} MB")
    application {
        Window(
            onCloseRequest = { exitApplication() } ,
            title = "FantasyMap App",
        ) {
            MapView(grid, 4000f, 2500f)
        }
    }
}

fun usedMemoryMb(): Long {
    System.gc()
    val runtime = Runtime.getRuntime()
    return (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
}