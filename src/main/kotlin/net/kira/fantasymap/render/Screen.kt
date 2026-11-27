package net.kira.fantasymap.render

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.Checkbox
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Slider
import androidx.compose.material.Switch
import androidx.compose.material.Tab
import androidx.compose.material.TabRow
import androidx.compose.material.Text
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.kira.fantasymap.generation.general.GenerationParams
import net.kira.fantasymap.render.mapview.ErrorText
import net.kira.fantasymap.render.mapview.PanelText
import net.kira.fantasymap.util.AppSettings
import net.kira.fantasymap.util.SettingsStore
import net.kira.fantasymap.util.writeTo
import java.awt.Panel
import kotlin.math.roundToInt
import kotlin.random.Random

private enum class SettingsSection(val title: String) { GENERATION("Генерация"), APP("Приложение") }

@Composable
fun MainScreen(error: String?, onNew: () -> Unit, onOpen: () -> Unit, onExit: () -> Unit, onSettings: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(mapBackgroundColor),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Fantasy Map", color = standardTextColor, fontSize = 36.sp)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onNew, modifier = Modifier.width(220.dp)) { Text("Новая карта") }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onOpen, modifier = Modifier.width(220.dp)) { Text("Открыть") }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onSettings, modifier = Modifier.width(220.dp)) { Text("Настройки") }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onExit, modifier = Modifier.width(220.dp)) { Text("Выход") }
        error?.let {
            Spacer(Modifier.height(16.dp))
            ErrorText(it)
        }
    }
}

@Composable
fun LoadingScreen(text: String) {
    Column(
        Modifier.fillMaxSize().background(mapBackgroundColor),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        PanelText(text)
    }
}

@Composable
fun SettingsScreen(current: GenerationParams, appSettings: AppSettings, onApply: (GenerationParams) -> Unit, onBack: () -> Unit) {
    var tab by remember { mutableStateOf(0) }

    var width by remember { mutableStateOf(current.worldWidth.toString()) }
    var height by remember { mutableStateOf(current.worldHeight.toString()) }
    var maxHeight by remember { mutableStateOf(current.maxHeight.toString()) }
    var latitudeFrom by remember { mutableStateOf(current.latitudeFrom.toString()) }
    var latitudeTo by remember { mutableStateOf(current.latitudeTo.toString()) }
    var cellCount by remember { mutableStateOf(current.cellCount.coerceIn(10_000, 300_000)) }
    var seed by remember { mutableStateOf(current.seed.toString()) }
    var fraction by remember { mutableStateOf(current.fraction) }
    var subTab by remember { mutableStateOf(0) }
    var equatorTemperature by remember { mutableStateOf(current.equatorTemperature.toString()) }
    var northPoleTemperature by remember { mutableStateOf(current.northPoleTemperature.toString()) }
    var southPoleTemperature by remember { mutableStateOf(current.southPoleTemperature.toString()) }
    var lapseRate by remember { mutableStateOf(current.lapseRate.toString()) }
    var humidity by remember { mutableStateOf(current.humidity.toString()) }
    var lakeMinMoisture by remember { mutableStateOf(current.lakeMinMoisture) }
    var riverThreshold by remember { mutableStateOf(current.riverThreshold.toString()) }
    var baseFrequency by remember { mutableStateOf(current.baseFrequency) }
    var heightPower by remember { mutableStateOf(current.heightPower) }
    var merkatorProjection by remember { mutableStateOf(current.mercatorProjection) }
    var section by remember { mutableStateOf(SettingsSection.GENERATION) }
    var curveIterations by remember { mutableStateOf(current.curveIterations) }
    var curveDisplacement by remember { mutableStateOf(current.curveDisplacement) }
    var autosaveMinutes by remember { mutableStateOf(appSettings.autosaveMinutes) }
    var curvesMinLevel by remember { mutableStateOf(appSettings.curvesMinLevel) }

    val params = run {
        val w = width.toFloatOrNull()
        val h = height.toFloatOrNull()
        val m = maxHeight.toIntOrNull()
        val lf = latitudeFrom.toFloatOrNull()
        val lt = latitudeTo.toFloatOrNull()
        val et = equatorTemperature.toFloatOrNull()
        val nt = northPoleTemperature.toFloatOrNull()
        val st = southPoleTemperature.toFloatOrNull()
        val lr = lapseRate.toFloatOrNull()
        val s = seed.toLongOrNull()
        val hum = humidity.toFloatOrNull()
        val rt = riverThreshold.toFloatOrNull()
        val bf = baseFrequency
        val hp = heightPower
        val mp = merkatorProjection
        val ci = curveIterations
        val cd = curveDisplacement
        if (w != null && h != null && m != null && lf != null && lt != null && s != null &&
            w > 0 && h > 0 && m > 0 && lf >= -90 && lt <= 90 && lf < lt && et != null && nt != null && st != null &&
            lr != null && hum != null && rt !=null)
            GenerationParams(w, h, cellCount, s, m, fraction,
                lf, lt, et, nt, st, lr, hum,
                lakeMinMoisture, rt, bf, hp, mp, ci, cd)
        else null
    }

    Column(
        Modifier.fillMaxSize().background(mapBackgroundColor),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        PanelText("Настройки")
        Spacer(Modifier.height(12.dp))
        Row {
            for (s in SettingsSection.entries) {
                Button(onClick = { section = s }) { Text((if (s == section) "✓ " else "") + s.title) }
                Spacer(Modifier.width(4.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        if (section == SettingsSection.GENERATION) {
            TabRow(
                selectedTabIndex = tab,
                backgroundColor = mapBackgroundColor,
                contentColor = standardTextColor,
                modifier = Modifier.width(400.dp)
            ) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Обычные") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Расширенные") })
            }
            Spacer(Modifier.height(12.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally) {
                if (tab == 0) {
                    Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        ParamField("Сид", seed) { seed = it }
                        Spacer(Modifier.width(4.dp))
                        Button(onClick = { seed = Random.nextLong(0, 1_000_000).toString() }) { Text("Случайный сид") }
                    }
                    PanelText("Учитывать искажение площади")
                    Switch(merkatorProjection, {merkatorProjection = it})
                    PanelText("Число клеток: ${cellCount / 1000}k")
                    Slider(
                        value = cellCount / 1000f,
                        onValueChange = { cellCount = it.roundToInt() * 1000 },
                        valueRange = 10f..300f,
                        modifier = Modifier.width(260.dp)
                    )
                    PanelText("Доля моря: ${(fraction * 100).roundToInt()}%")
                    Slider(value = fraction, onValueChange = { fraction = it }, modifier = Modifier.width(260.dp))
                } else {
                    TabRow(
                        selectedTabIndex = subTab,
                        backgroundColor = mapBackgroundColor,
                        contentColor = standardTextColor,
                        modifier = Modifier.width(500.dp)
                    ) {
                        Tab(selected = subTab == 0, onClick = { subTab = 0 }, text = { Text("Размеры") })
                        Tab(selected = subTab == 1, onClick = { subTab = 1 }, text = { Text("Климат") })
                        Tab(selected = subTab == 2, onClick = { subTab = 2 }, text = { Text("Рельеф") })
                        Tab(selected = subTab == 3, onClick = { subTab = 3 }, text = { Text("Гидрология") })
                        Tab(selected = subTab == 4, onClick = { subTab = 4 }, text = { Text("Границы") })
                    }
                    Spacer(Modifier.height(8.dp))

                    if (subTab == 0) {
                        ParamField("Размер X (ширина, км)", width) { width = it }
                        ParamField("Размер Y (высота, км)", height) { height = it }
                        ParamField("Размер Z (макс. высота)", maxHeight) { maxHeight = it }
                    }
                    if (subTab == 1) {
                        Column(Modifier.width(260.dp)) {
                            ParamField("Широта снизу (от -90)", latitudeFrom) { latitudeFrom = it }
                            ParamField("Широта сверху (до 90)", latitudeTo) { latitudeTo = it }
                            ParamField("Температура экватора", equatorTemperature) { equatorTemperature = it }
                            ParamField("Температура северного полюса", northPoleTemperature) { northPoleTemperature = it }
                            ParamField("Температура южного полюса", southPoleTemperature) { southPoleTemperature = it }
                            ParamField("Изменение температуры с высотой", lapseRate) { lapseRate = it}
                            ParamField("Коэффицент влажности", humidity) { humidity = it }
                        }
                    }
                    if (subTab == 2) {
                        PanelText("Частота генерации (чем выше, тем больше материков): ${(baseFrequency * 100).roundToInt()}%")
                        Slider (
                            value = baseFrequency,
                            onValueChange = { baseFrequency = it },
                            valueRange = 1f..10f,
                            modifier = Modifier.width(260.dp)
                        )
                        PanelText("Сглаживание высоких гор (чем больше - тем меньше гор): ${(heightPower * 100).roundToInt()}%")
                        Slider (
                            value = heightPower,
                            onValueChange = { heightPower = it },
                            valueRange = 1f..10f,
                            modifier = Modifier.width(260.dp)
                        )
                    }
                    if (subTab == 3) {
                        ParamField("Минимальная влажность для реки", riverThreshold) {riverThreshold = it}
                        PanelText("Минимальная влажность для озера: ${(lakeMinMoisture * 100).roundToInt()}%")
                        Slider (
                            value = lakeMinMoisture,
                            onValueChange = { lakeMinMoisture = it },
                            valueRange = 0f..1f,
                            modifier = Modifier.width(260.dp)
                        )
                    }
                    if (subTab == 4) {
                        PanelText("Количество точек кривых: ${(1 shl curveIterations) - 1} на ребро")
                        Slider(
                            value = curveIterations.toFloat(),
                            onValueChange = { curveIterations = it.roundToInt() },
                            valueRange = 0f..5f,
                            steps = 4,
                            modifier = Modifier.width(260.dp)
                        )
                        PanelText("Кривизна прямых: ${(curveDisplacement * 100).roundToInt()}%")
                        Slider(
                            value = curveDisplacement,
                            onValueChange = { curveDisplacement = it },
                            valueRange = 0f..0.4f,
                            modifier = Modifier.width(260.dp)
                        )
                    }
                }
            }
        } else {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                PanelText(if (autosaveMinutes == 0) "Автосохранение: выключено" else "Автосохранение: раз в $autosaveMinutes мин")
                Slider(
                    value = autosaveMinutes.toFloat(),
                    onValueChange = { autosaveMinutes = it.roundToInt() },
                    valueRange = 0f..30f,
                    steps = 29,
                    modifier = Modifier.width(260.dp)
                )
                PanelText("Минимальное приближение для отображения точек кривых: уровень $curvesMinLevel")
                Slider(
                    value = curvesMinLevel.toFloat(),
                    onValueChange = { curvesMinLevel = it.roundToInt() },
                    valueRange = 0f..MAX_LEVEL.toFloat(),
                    steps = MAX_LEVEL - 1,
                    modifier = Modifier.width(260.dp)
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row {
            Button(enabled = params != null, onClick = {
                SettingsStore.update { appSettings.writeTo(SettingsStore.load()) }
                params?.let(onApply)
            }) { Text("Применить") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onBack) { Text("Назад") }
        }
    }
}

@Composable
private fun ParamField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        textStyle = TextStyle(color = standardTextColor),
        colors = TextFieldDefaults.outlinedTextFieldColors(
            textColor = standardTextColor,
            cursorColor = standardTextColor,
            focusedLabelColor = standardTextColor,
            unfocusedLabelColor = standardTextColor,
            focusedBorderColor = standardTextColor,
            unfocusedBorderColor = standardCellOutline
        ),
        modifier = Modifier.width(260.dp).padding(vertical = 2.dp)
    )
}