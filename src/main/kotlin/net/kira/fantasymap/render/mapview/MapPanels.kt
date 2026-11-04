package net.kira.fantasymap.render.mapview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.Button
import androidx.compose.material.Slider
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.kira.fantasymap.editors.PendingEdit
import net.kira.fantasymap.model.pointshape.Lake
import net.kira.fantasymap.model.pointshape.River
import net.kira.fantasymap.render.*
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

private const val WATER_MIN = 10f                    // границы ползунков воды реки
private const val WATER_MAX = 100_000f

// Ползунок логарифмический: вода в реках отличается на порядки.
private fun waterToSlider(water: Float): Float = ln(water.coerceIn(WATER_MIN, WATER_MAX) / WATER_MIN) / ln(WATER_MAX / WATER_MIN)
private fun sliderToWater(value: Float): Float = WATER_MIN * (WATER_MAX / WATER_MIN).pow(value)

@Composable
fun PanelText(text: String) {
    Text(text, color = standardTextColor, modifier = Modifier.background(standardTextBackground).padding(4.dp))
}

@Composable
fun ErrorText(text: String) {
    Text(text, color = errorTextColor, modifier = Modifier.background(errorTextBackground).padding(4.dp))
}

@Composable
fun LayersPanel(state: MapState) {
    Column(Modifier.padding(8.dp)) {
        Button(onClick = { state.toggleLayersPanel() }) { Text(if (state.layersOpen) "Слои ▲" else "Слои ▼") }
        if (state.layersOpen) {
            for (row in LAYERS.entries.chunked(3)) {
                Row {
                    for (layer in row) {
                        Button(onClick = { state.toggleLayer(layer) }) {
                            Text((if (layer in state.layers) "✓ " else "") + layer.name)
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun EditPanel(state: MapState) {
    @Suppress("UNUSED_VARIABLE")
    val version = state.pendingVersion               // чтобы текст обновлялся после каждого решения
    val currentDrag = state.drag
    val currentShapeDrag = state.shapeDrag
    val currentPending = state.pending
    val shape = state.selectedShape
    val prompt = state.riverPrompt

    PanelText(hintText(state))

    val selectedRiver = shape as? River
    if (selectedRiver != null && prompt == null && currentPending == null && currentDrag == null && currentShapeDrag == null) {
        RiverWaterSliders(state, selectedRiver)
    }

    Spacer(Modifier.height(4.dp))
    when {
        prompt != null -> RiverPromptButtons(state, prompt)
        currentPending != null -> Row {
            Button(onClick = { state.keepSmallCell(currentPending) }) { Text("Оставить как есть") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { state.abortPending(currentPending) }) { Text("Отменить правку (Esc)") }
        }
        currentDrag == null && currentShapeDrag == null -> {
            Row {
                Button(onClick = { state.undoEdit() }) { Text("Отменить (Ctrl+Z)") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { state.redoEdit() }) { Text("Повторить (Ctrl+Y)") }
            }
            Spacer(Modifier.height(4.dp))
            Row {
                Button(onClick = { state.toggleAddingLake() }) { Text(if (state.addingLake) "Отмена (Esc)" else "Добавить озеро") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { state.toggleAddingRiver() }) { Text(if (state.addingRiver) "Отмена (Esc)" else "Добавить реку") }
                Spacer(Modifier.width(8.dp))
                Button(enabled = state.selectedShape != null, onClick = { state.deleteSelectedShape() }) { Text("Удалить (Delete)") }
            }
        }
    }
}

@Composable
private fun RiverWaterSliders(state: MapState, river: River) {
    Spacer(Modifier.height(4.dp))
    PanelText("Вода в начале: ${river.startWater.roundToInt()}")
    Slider(
        value = waterToSlider(river.startWater),
        onValueChange = { state.editRiverWater(river, sliderToWater(it), river.endWater) },
        onValueChangeFinished = { state.finishRiverWater(river) },
        modifier = Modifier.width(220.dp)
    )
    PanelText("Вода в конце (предел): ${river.endWater.roundToInt()}")
    Slider(
        value = waterToSlider(river.endWater),
        onValueChange = { state.editRiverWater(river, river.startWater, sliderToWater(it)) },
        onValueChangeFinished = { state.finishRiverWater(river) },
        modifier = Modifier.width(220.dp)
    )
}

@Composable
private fun RiverPromptButtons(state: MapState, prompt: River) {
    val question = when {
        prompt !in state.grid.rivers -> "Река удалена. Вернуть клеткам прежний вид?"
        prompt.cutPoints == null -> "Новая река. Прорезать по ней клетки?"
        else -> "Линия реки изменилась. Перерезать клетки по новой линии?"
    }
    PanelText(question)
    Row {
        Button(onClick = { state.applyRiverPrompt(prompt) }) { Text("Применить") }
        Spacer(Modifier.width(8.dp))
        Button(onClick = { state.discardRiverPrompt(prompt) }) { Text("Отменить правки") }
    }
}

private fun hintText(state: MapState): String {
    val currentDrag = state.drag
    val currentPending = state.pending
    val cell = state.selectedCell
    val shape = state.selectedShape
    val zoomedIn = state.camera.scale >= (CURVE_HANDLES_MIN_SCALE)
    return when {
        state.riverPrompt != null -> "Примените изменения реки к клеткам или отмените их."
        currentPending != null -> pendingHint(currentPending, state.averageCellArea)
        state.addingLake -> "Кликните по карте, где поставить озеро. Esc — отмена."
        state.addingRiver -> "Кликните по карте, где поставить реку. Esc — отмена."
        currentDrag != null && !currentDrag.isValid -> "Здесь нельзя: ${currentDrag.problem}. Отпустите, чтобы отменить."
        currentDrag != null -> "Отпустите, чтобы применить. Esc — отмена."
        state.shapeDrag != null -> "Отпустите, чтобы применить. Esc — отмена."
        shape is Lake -> "Озеро. Потяните точку, чтобы изменить форму."
        shape is River -> "Река. Потяните точку, чтобы изменить течение. Клик по линии добавляет точку, ПКМ по точке удаляет её."
        cell == null -> "Кликните по клетке, озеру или реке, чтобы выбрать"
        else -> "Клетка №${cell.id}, высота ${cell.height}. Белые точки основные (общие с соседними клетками), " +
                (if (zoomedIn) "зелёные точки кривой границы. " else "чтобы увидеть точки кривых, приблизьте карту. ") +
                "Потяните точку, чтобы переместить её."
    }
}

private fun pendingHint(pending: PendingEdit, averageCellArea: Float): String {
    val small = pending.smallCell ?: return ""
    val percent = (small.area() / averageCellArea * 100f).roundToInt()
    return if (pending.plans.isEmpty()) {
        "Клетка №${small.id} стала слишком маленькой ($percent% от средней), объединить её не с чем. " +
                "Оставьте как есть или отмените правку."
    } else {
        "Клетка №${small.id} стала слишком маленькой ($percent% от средней). " +
                "Кликните по подсвеченной соседней клетке, чтобы объединить с ней, или оставьте как есть."
    }
}