/*
 * Zalith Launcher 2
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package com.movtery.zalithlauncher.game.control

import android.content.Context
import androidx.compose.ui.graphics.Color
import com.movtery.layer_controller.data.ButtonShape
import com.movtery.layer_controller.data.ButtonSize
import com.movtery.layer_controller.data.ButtonStyle
import com.movtery.layer_controller.data.ClickEvent
import com.movtery.layer_controller.data.JoystickData
import com.movtery.layer_controller.data.JoystickStyle
import com.movtery.layer_controller.data.NormalData
import com.movtery.layer_controller.data.TextAlignment
import com.movtery.layer_controller.data.VisibilityType
import com.movtery.layer_controller.data.lang.createTranslatable
import com.movtery.layer_controller.layout.ControlLayer
import com.movtery.layer_controller.layout.ControlLayout
import com.movtery.layer_controller.layout.EmptyControlLayout
import com.movtery.layer_controller.layout.loadLayoutFromFile
import com.movtery.layer_controller.layout.loadLayoutFromFileUncheck
import com.movtery.layer_controller.layout.loadLayoutFromString
import com.movtery.layer_controller.observable.ObservableControlLayout
import com.movtery.layer_controller.utils.newRandomFileName
import com.movtery.layer_controller.utils.randomUUID
import com.movtery.layer_controller.utils.saveToFile
import com.movtery.zalithlauncher.context.copyAssetFile
import com.movtery.zalithlauncher.path.PathManager
import com.movtery.zalithlauncher.setting.AllSettings
import com.movtery.zalithlauncher.utils.file.readString
import com.movtery.zalithlauncher.utils.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.commons.io.FileUtils
import java.io.File
import java.io.InputStream
import kotlin.math.roundToInt

private const val TAG = "ControlManager"

private val lotusJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

object ControlManager {

    private val scope = CoroutineScope(Dispatchers.IO)

    private val _dataList = MutableStateFlow<List<ControlData>>(emptyList())
    val dataList = _dataList.asStateFlow()

    private var currentJob: Job? = null

    private val _selectedLayout = MutableStateFlow<ControlData?>(null)
    val selectedLayout = _selectedLayout.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()

    private fun getNewRandomFile(): File {
        return File(
            PathManager.DIR_CONTROL_LAYOUTS,
            "${newRandomFileName()}.json"
        )
    }

    fun checkDefaultAndRefresh(context: Context) {
        scope.launch(Dispatchers.IO) {
            val files = (PathManager.DIR_CONTROL_LAYOUTS.listFiles() ?: emptyArray())
                .filter {
                    it.isFile &&
                        it.exists() &&
                        it.extension.equals("json", true)
                }

            if (files.isEmpty()) {
                unpackDefaultControl(context)
            }

            refresh()
        }
    }

    fun refresh() {
        currentJob?.cancel()

        currentJob = scope.launch(Dispatchers.IO) {
            _isRefreshing.update { true }
            _dataList.update { emptyList() }

            PathManager.DIR_CONTROL_LAYOUTS.listFiles()
                ?.mapNotNull { file ->
                    if (!file.isFile ||
                        !file.exists() ||
                        !file.extension.equals("json", true)
                    ) {
                        return@mapNotNull null
                    }

                    var isSupport = true

                    val layout: ControlLayout = try {
                        loadLayoutFromFile(file)
                    } catch (_: IllegalArgumentException) {
                        isSupport = false

                        runCatching {
                            loadLayoutFromFileUncheck(file)
                        }.onFailure { e ->
                            Logger.warning(
                                TAG,
                                "Failed to load control layout! file = $file",
                                e
                            )
                        }.getOrNull() ?: return@mapNotNull null
                    } catch (e: Exception) {
                        Logger.warning(
                            TAG,
                            "Failed to load control layout! file = $file",
                            e
                        )
                        return@mapNotNull null
                    }

                    ControlData(
                        file = file,
                        controlLayout = ObservableControlLayout(layout),
                        isSupport = isSupport
                    )
                }
                ?.let { list ->
                    _dataList.update {
                        list.sortedBy {
                            if (it.isSupport) {
                                it.controlLayout.info.name.default
                            } else {
                                it.file.name
                            }
                        }
                    }
                }

            checkSettings()
            _isRefreshing.update { false }
        }
    }

    private fun checkSettings() {
        val setting = AllSettings.controlLayout.getValue()

        val layout = _dataList.value.find {
            it.file.name == setting && it.isSupport
        } ?: dataList.value.firstOrNull {
            it.isSupport
        }?.also {
            AllSettings.controlLayout.save(it.file.name)
        }

        if (layout == null) {
            AllSettings.controlLayout.reset()
        }

        _selectedLayout.update { layout }
    }

    private suspend fun unpackDefaultControl(context: Context) =
        withContext(Dispatchers.IO) {
            try {
                val file = getNewRandomFile()
                context.copyAssetFile(
                    fileName = "default_layout.json",
                    output = file,
                    overwrite = false
                )
            } catch (e: Exception) {
                Logger.warning(
                    TAG,
                    "Failed to unpack default control layout",
                    e
                )
            }
        }

    fun selectControl(data: ControlData) {
        if (!data.file.exists() || !data.isSupport) return

        AllSettings.controlLayout.save(data.file.name)
        _selectedLayout.update { data }
    }

    fun deleteControl(data: ControlData) {
        scope.launch(Dispatchers.IO) {
            if (!data.file.exists()) return@launch

            FileUtils.deleteQuietly(data.file)
            refresh()
        }
    }

    fun saveControl(
        data: ControlData,
        submitError: (Exception) -> Unit
    ) {
        scope.launch(Dispatchers.IO) {
            if (!data.file.exists()) {
                refresh()
                return@launch
            }

            val layout = data.controlLayout.pack()

            try {
                layout.saveToFile(data.file)
            } catch (e: Exception) {
                submitError(e)
            }

            refresh()
        }
    }

    /**
     * Imports both native ZL2 layouts and Lotus/Pojav legacy layouts.
     */
    suspend fun importControl(
        inputStream: InputStream,
        onSerializationError: (Exception) -> Unit,
        catchedError: (Exception) -> Unit,
        onFinished: () -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        val file = getNewRandomFile()

        try {
            inputStream.use { stream ->
                val jsonString = stream.readString()

                val layout = if (isLotusLayout(jsonString)) {
                    convertLotusLayout(jsonString)
                } else {
                    loadLayoutFromString(jsonString)
                }

                layout.saveToFile(file)
            }

            onFinished()
        } catch (e: SerializationException) {
            FileUtils.deleteQuietly(file)
            onSerializationError(e)
        } catch (e: Exception) {
            FileUtils.deleteQuietly(file)
            catchedError(e)
        }
    }

    private fun isLotusLayout(jsonString: String): Boolean {
        return runCatching {
            val root = lotusJson
                .parseToJsonElement(jsonString)
                .jsonObject

            root.containsKey("mControlDataList") ||
                root.containsKey("mJoystickDataList")
        }.getOrDefault(false)
    }

    private fun convertLotusLayout(jsonString: String): ControlLayout {
        val root = lotusJson
            .parseToJsonElement(jsonString)
            .jsonObject

        val controls = root["mControlDataList"]?.jsonArray ?: buildJsonArray { }
        val joysticks = root["mJoystickDataList"]?.jsonArray ?: buildJsonArray { }

        val styles = mutableListOf<ButtonStyle>()
        val joystickStyles = mutableListOf<JoystickStyle>()
        val normalButtons = mutableListOf<NormalData>()
        val joystickButtons = mutableListOf<JoystickData>()

        controls.forEachIndexed { index, element ->
            runCatching {
                val data = element.jsonObject

                val name = data.string("name")
                    .ifBlank { "Button ${index + 1}" }

                val width = data.float("width")
                    .coerceAtLeast(5f)

                val height = data.float("height")
                    .coerceAtLeast(5f)

                val position = parseLotusPosition(
                    data.string("dynamicX"),
                    data.string("dynamicY")
                )

                val visibility = parseVisibility(
                    data.boolean("displayInGame", true),
                    data.boolean("displayInMenu", true)
                )

                val style = createLotusButtonStyle(
                    name = "Lotus - $name",
                    bgColor = data.int("bgColor", 0x80000000.toInt()),
                    opacity = data.float("opacity", 1f),
                    strokeColor = data.int("strokeColor", -1),
                    strokeWidth = data.float("strokeWidth", 0f),
                    cornerRadius = data.float("cornerRadius", 0f)
                )

                styles += style

                val events = mutableListOf<ClickEvent>()
                val keycodes = data["keycodes"]?.jsonArray ?: buildJsonArray { }

                keycodes.forEach { keyElement ->
                    val keycode = keyElement.jsonPrimitive.intOrNull ?: 0
                    if (keycode == 0) return@forEach

                    val keyName = glfwKeyName(keycode)

                    if (keyName != null) {
                        events += ClickEvent(
                            type = ClickEvent.Type.Key,
                            key = keyName
                        )
                    } else if (keycode < 0) {
                        Logger.warning(
                            TAG,
                            "Skipped unsupported Lotus special keycode " +
                                "$keycode for control \"$name\""
                        )
                    }
                }

                if (events.isEmpty()) return@runCatching

                normalButtons += NormalData(
                    createTranslatable(name),
                    randomUUID(),
                    position,
                    ButtonSize(
                        type = ButtonSize.Type.Dp,
                        widthDp = width,
                        heightDp = height,
                        widthPercentage = 1000,
                        heightPercentage = 1000,
                        widthReference = ButtonSize.Reference.ScreenWidth,
                        heightReference = ButtonSize.Reference.ScreenHeight
                    ),
                    style.uuid,
                    TextAlignment.Left,
                    false,
                    false,
                    false,
                    visibility,
                    events,
                    data.boolean("isSwipeable", false),
                    data.boolean("passThruEnabled", false),
                    data.boolean("isToggle", false)
                )
            }.onFailure { error ->
                Logger.warning(
                    TAG,
                    "Failed to convert Lotus control #$index",
                    error
                )
            }
        }

        joysticks.forEachIndexed { index, element ->
            runCatching {
                val data = element.jsonObject

                val width = data.float(
                    "width",
                    data.float("height", 160f)
                ).coerceAtLeast(20f)

                val height = data.float(
                    "height",
                    width
                ).coerceAtLeast(20f)

                val size = ((width + height) / 2f)
                    .coerceAtLeast(20f)

                val style = createLotusJoystickStyle(
                    name = "Lotus Joystick ${index + 1}",
                    bgColor = data.int("bgColor", 0x80000000.toInt()),
                    opacity = data.float("opacity", 1f),
                    strokeColor = data.int("strokeColor", -1),
                    strokeWidth = data.float("strokeWidth", 0f),
                    cornerRadius = data.float("cornerRadius", 50f)
                )

                joystickStyles += style

                joystickButtons += JoystickData(
                    uuid = randomUUID(),
                    position = parseLotusPosition(
                        data.string("dynamicX"),
                        data.string("dynamicY")
                    ),
                    sizeType = ButtonSize.Type.Dp,
                    sizeDp = size,
                    visibilityType = VisibilityType.ALWAYS,
                    joystickStyleId = style.uuid,
                    canLock = data.boolean("forwardLock", false)
                )
            }.onFailure { error ->
                Logger.warning(
                    TAG,
                    "Failed to convert Lotus joystick #$index",
                    error
                )
            }
        }

        val layer = ControlLayer(
            name = "Lotus",
            uuid = randomUUID(),
            hide = false,
            hideWhenMouse = true,
            hideWhenGamepad = true,
            visibilityType = VisibilityType.ALWAYS,
            normalButtons = normalButtons,
            textBoxes = emptyList(),
            joystickButtons = joystickButtons
        )

        val infoData = root["mControlInfoDataList"]?.jsonObject

        val layoutName = infoData?.string("name")
            ?.takeIf { it.isNotBlank() && it != "null" }
            ?: "Lotus Layout"

        val author = infoData?.string("author")
            ?.takeIf { it.isNotBlank() && it != "null" }
            ?: "Lotus"

        val description = infoData?.string("desc")
            ?.takeIf { it.isNotBlank() && it != "null" }
            ?: "Imported from Lotus/Pojav"

        return EmptyControlLayout.copy(
            info = ControlLayout.Info(
                name = createTranslatable(layoutName),
                author = createTranslatable(author),
                description = createTranslatable(description),
                versionCode = 1,
                versionName = "Lotus Import"
            ),
            layers = listOf(layer),
            styles = styles,
            joystickStyles = joystickStyles
        )
    }

    private fun parseLotusPosition(
        xExpression: String,
        yExpression: String
    ): com.movtery.layer_controller.data.ButtonPosition {
        return com.movtery.layer_controller.data.ButtonPosition(
            x = expressionToPosition(xExpression, "screen_width"),
            y = expressionToPosition(yExpression, "screen_height")
        )
    }

    private fun expressionToPosition(
        expression: String,
        dimensionName: String
    ): Int {
        if (expression.isBlank()) return 0

        val normalized = expression
            .replace(" ", "")
            .replace("\n", "")

        val pattern = Regex(
            """([0-9]+(?:\.[0-9]+)?)\*\$\{$dimensionName\}"""
        )

        val match = pattern.find(normalized)

        if (match != null) {
            return (
                match.groupValues[1]
                    .toFloatOrNull()
                    ?.times(10000f)
                    ?.roundToInt()
                    ?: 0
            ).coerceIn(0, 10000)
        }

        if (normalized.contains("\${$dimensionName}")) {
            return 10000
        }

        return normalized
            .toFloatOrNull()
            ?.coerceIn(0f, 1f)
            ?.times(10000f)
            ?.roundToInt()
            ?: 0
    }

    private fun parseVisibility(
        displayInGame: Boolean,
        displayInMenu: Boolean
    ): VisibilityType {
        return when {
            displayInGame && displayInMenu -> VisibilityType.ALWAYS
            displayInGame -> VisibilityType.IN_GAME
            displayInMenu -> VisibilityType.IN_MENU
            else -> VisibilityType.ALWAYS
        }
    }

    private fun createLotusButtonStyle(
        name: String,
        bgColor: Int,
        opacity: Float,
        strokeColor: Int,
        strokeWidth: Float,
        cornerRadius: Float
    ): ButtonStyle {
        val background = colorFromArgb(bgColor)
        val border = colorFromArgb(strokeColor)

        val alpha = opacity.coerceIn(0f, 1f)
        val radius = cornerRadius.coerceIn(0f, 50f)
        val borderWidth = strokeWidth.coerceIn(0f, 50f).roundToInt()

        val config = ButtonStyle.StyleConfig(
            alpha = alpha,
            pressedAlpha = alpha,
            backgroundColor = background,
            pressedBackgroundColor = background,
            contentColor = Color.White,
            pressedContentColor = Color.White,
            fontSize = null,
            pressedFontSize = null,
            borderWidth = borderWidth,
            pressedBorderWidth = borderWidth,
            borderColor = border,
            pressedBorderColor = border,
            borderRadius = ButtonShape(radius),
            pressedBorderRadius = ButtonShape(radius)
        )

        return ButtonStyle(
            name = name,
            uuid = randomUUID(),
            animateSwap = false,
            commonStyle = true,
            lightStyle = config,
            darkStyle = config
        )
    }

    private fun createLotusJoystickStyle(
        name: String,
        bgColor: Int,
        opacity: Float,
        strokeColor: Int,
        strokeWidth: Float,
        cornerRadius: Float
    ): JoystickStyle {
        val background = colorFromArgb(bgColor)
        val border = colorFromArgb(strokeColor)

        val config = JoystickStyle.StyleConfig(
            alpha = opacity.coerceIn(0f, 1f),
            backgroundColor = background,
            joystickColor = Color.White.copy(alpha = 0.5f),
            joystickCanLockColor = Color.Yellow.copy(alpha = 0.5f),
            joystickLockedColor = Color.Green.copy(alpha = 0.5f),
            lockMarkColor = Color.White,
            borderWidthRatio = strokeWidth.coerceIn(0f, 50f).roundToInt(),
            borderColor = border,
            backgroundShape = cornerRadius.coerceIn(0f, 50f).roundToInt(),
            joystickShape = cornerRadius.coerceIn(0f, 50f).roundToInt(),
            joystickSize = 0.5f
        )

        return JoystickStyle(
            name = name,
            uuid = randomUUID(),
            commonStyle = true,
            lightStyle = config,
            darkStyle = config
        )
    }

    private fun colorFromArgb(value: Int): Color {
        return Color(value.toUInt().toULong())
    }

    private fun glfwKeyName(keycode: Int): String? {
        return when (keycode) {
            32 -> "SPACE"
            39 -> "APOSTROPHE"
            44 -> "COMMA"
            45 -> "MINUS"
            46 -> "PERIOD"
            47 -> "SLASH"
            in 48..57 -> keycode.toChar().toString()
            59 -> "SEMICOLON"
            61 -> "EQUAL"
            in 65..90 -> keycode.toChar().toString()
            91 -> "LEFT_BRACKET"
            92 -> "BACKSLASH"
            93 -> "RIGHT_BRACKET"
            96 -> "GRAVE_ACCENT"

            256 -> "ESCAPE"
            257 -> "ENTER"
            258 -> "TAB"
            259 -> "BACKSPACE"
            260 -> "INSERT"
            261 -> "DELETE"
            262 -> "RIGHT"
            263 -> "LEFT"
            264 -> "DOWN"
            265 -> "UP"
            266 -> "PAGE_UP"
            267 -> "PAGE_DOWN"
            268 -> "HOME"
            269 -> "END"
            280 -> "CAPS_LOCK"
            281 -> "SCROLL_LOCK"
            282 -> "NUM_LOCK"
            283 -> "PRINT_SCREEN"
            284 -> "PAUSE"

            in 290..314 -> "F${keycode - 289}"

            320 -> "KP_0"
            321 -> "KP_1"
            322 -> "KP_2"
            323 -> "KP_3"
            324 -> "KP_4"
            325 -> "KP_5"
            326 -> "KP_6"
            327 -> "KP_7"
            328 -> "KP_8"
            329 -> "KP_9"
            330 -> "KP_DECIMAL"
            331 -> "KP_DIVIDE"
            332 -> "KP_MULTIPLY"
            333 -> "KP_SUBTRACT"
            334 -> "KP_ADD"
            335 -> "KP_ENTER"
            336 -> "KP_EQUAL"

            340 -> "LEFT_SHIFT"
            341 -> "LEFT_CONTROL"
            342 -> "LEFT_ALT"
            343 -> "LEFT_SUPER"
            344 -> "RIGHT_SHIFT"
            345 -> "RIGHT_CONTROL"
            346 -> "RIGHT_ALT"
            347 -> "RIGHT_SUPER"
            348 -> "MENU"

            else -> null
        }
    }

    private fun JsonObject.string(
        key: String,
        default: String = ""
    ): String {
        return this[key]?.jsonPrimitive?.content ?: default
    }

    private fun JsonObject.int(
        key: String,
        default: Int = 0
    ): Int {
        return this[key]?.jsonPrimitive?.intOrNull ?: default
    }

    private fun JsonObject.float(
        key: String,
        default: Float = 0f
    ): Float {
        return this[key]?.jsonPrimitive?.floatOrNull ?: default
    }

    private fun JsonObject.boolean(
        key: String,
        default: Boolean = false
    ): Boolean {
        return this[key]?.jsonPrimitive?.booleanOrNull ?: default
    }
}
