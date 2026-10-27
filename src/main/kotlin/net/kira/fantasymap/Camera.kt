package net.kira.fantasymap

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class Camera(centerX: Float, centerY: Float, scale: Float) {
    var centerX by mutableStateOf(centerX)
    var centerY by mutableStateOf(centerY)
    var scale by mutableStateOf(scale)      // пикселей экрана на одну единицу мира

    fun worldToScreenX(worldX: Float, screenWidth: Float) = (worldX - centerX) * scale + screenWidth / 2f
    fun worldToScreenY(worldY: Float, screenHeight: Float) = screenHeight / 2f - (worldY - centerY) * scale  // Y мира вверх, экрана вниз
    fun screenToWorldX(screenX: Float, screenWidth: Float) = (screenX - screenWidth / 2f) / scale + centerX
    fun screenToWorldY(screenY: Float, screenHeight: Float) = centerY - (screenY - screenHeight / 2f) / scale

    // Зум так, чтобы точка мира под курсором осталась на месте.
    fun zoomAt(cursorX: Float, cursorY: Float, screenWidth: Float, screenHeight: Float, factor: Float) {
        val worldX = screenToWorldX(cursorX, screenWidth)
        val worldY = screenToWorldY(cursorY, screenHeight)
        scale = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        centerX = worldX - (cursorX - screenWidth / 2f) / scale
        centerY = worldY + (cursorY - screenHeight / 2f) / scale
    }

    private companion object {
        const val MIN_SCALE = 0.05f
        const val MAX_SCALE = 200f
    }
}