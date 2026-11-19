package net.kira.fantasymap.render

import java.awt.FileDialog
import java.awt.Frame
import java.io.File

fun chooseFile(title: String, save: Boolean): File? {
    val dialog = FileDialog(null as Frame?, title, if (save) FileDialog.SAVE else FileDialog.LOAD)
    if (save) dialog.file = "map.fmap" else dialog.setFilenameFilter { _, name -> name.endsWith(".fmap") }
    dialog.isVisible = true                                  // окно модальное, ждёт выбора
    val name = dialog.file ?: return null
    val chosen = File(dialog.directory, name)
    return if (save && !name.endsWith(".fmap")) File(chosen.path + ".fmap") else chosen
}