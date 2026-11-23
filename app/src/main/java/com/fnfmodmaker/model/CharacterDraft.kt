package com.fnfmodmaker.model

/** Validación compartida entre el formulario y la exportación del personaje base. */
internal fun characterProblems(
    hasSheet: Boolean,
    sheetWidth: Int,
    sheetHeight: Int,
    frameWidth: String,
    frameHeight: String,
    scale: String,
    frameCounts: List<String>
): List<String> = buildList {
    if (!hasSheet) add("Elige una hoja de sprites PNG.")
    val width = frameWidth.toIntOrNull() ?: 0
    val height = frameHeight.toIntOrNull() ?: 0
    if (width <= 0 || height <= 0) add("Indica un ancho y alto de frame mayores que cero.")
    val scaleValue = scale.toFloatOrNull()
    if (scaleValue == null || !scaleValue.isFinite() || scaleValue <= 0f) {
        add("Indica una escala mayor que cero.")
    }
    val counts = frameCounts.map { it.toIntOrNull() }
    if (counts.any { it == null || it < 0 }) {
        add("Indica un número de frames válido para cada animación; usa 0 para omitirla.")
    } else {
        if ((counts.firstOrNull() ?: 0) <= 0) add("La animación idle necesita al menos un frame.")
        if (hasSheet && width > 0 && height > 0) {
            val available = (sheetWidth / width).toLong() * (sheetHeight / height).toLong()
            val required = counts.sumOf { it!!.toLong() }
            if (available <= 0) add("La hoja es más pequeña que un frame.")
            else if (required > available) add("Las animaciones necesitan $required frames, pero solo caben $available.")
        }
    }
}

/** Nombres compatibles con las animaciones básicas de Psych y V-Slice. */
val CHARACTER_ANIMATIONS = listOf("idle", "singLEFT", "singDOWN", "singUP", "singRIGHT")

data class CharacterFrame(val image: PickedImage, val width: Int, val height: Int)

data class CharacterAnimationSettings(
    val fps: String = "24",
    val offsetX: String = "0",
    val offsetY: String = "0",
    val loop: Boolean = false
)

/** Cuadrícula limitada para no crear texturas excesivas en móviles. */
data class CharacterAtlasLayout(val columns: Int, val cellWidth: Int, val cellHeight: Int, val width: Int, val height: Int)

fun planCharacterAtlas(sizes: List<Pair<Int, Int>>, limit: Int = 4096): CharacterAtlasLayout? {
    if (sizes.isEmpty() || limit <= 0 || sizes.any { it.first <= 0 || it.second <= 0 }) return null
    val cellWidth = sizes.maxOf { it.first }
    val cellHeight = sizes.maxOf { it.second }
    if (cellWidth > limit || cellHeight > limit) return null
    val maxColumns = minOf(sizes.size, limit / cellWidth)
    // Usar la cuadrícula válida con menor superficie; desempatar por forma más cuadrada.
    return (1..maxColumns).mapNotNull { columns ->
        val rows = (sizes.size + columns - 1) / columns
        val width = columns * cellWidth
        val height = rows.toLong() * cellHeight
        if (height > limit) null else CharacterAtlasLayout(columns, cellWidth, cellHeight, width, height.toInt())
    }.minWithOrNull(compareBy<CharacterAtlasLayout> { it.width.toLong() * it.height }.thenBy { kotlin.math.abs(it.width - it.height) })
}

/** Referencia espacial del editor, expresada en píxeles respecto al punto del escenario. */
data class CharacterPlacement(val x: Int, val y: Int, val cameraX: Int, val cameraY: Int)

fun CharacterDraft.placement(): CharacterPlacement = CharacterPlacement(
    positionX.toIntOrNull() ?: 0, positionY.toIntOrNull() ?: 0,
    cameraX.toIntOrNull() ?: 0, cameraY.toIntOrNull() ?: 0
)

/** Psych invierte el eje X de camera_position para Boyfriend; V-Slice no. */
fun CharacterPlacement.psychCameraX(side: CharacterSide): Int =
    if (side == CharacterSide.PLAYER) -cameraX else cameraX

/** El teclado táctil mueve en dirección visual; el offset de animación usa el signo opuesto. */
enum class CharacterAdjustment(val label: String) { POSITION("Personaje"), ANIMATION("Animación"), CAMERA("Cámara") }

fun CharacterDraft.nudged(target: CharacterAdjustment, animation: String, dx: Int, dy: Int): CharacterDraft {
    fun shifted(value: String, delta: Long): String =
        ((value.toIntOrNull() ?: 0).toLong() + delta).coerceIn(-999999, 999999).toString()
    return when (target) {
        CharacterAdjustment.POSITION -> copy(positionX = shifted(positionX, dx.toLong()), positionY = shifted(positionY, dy.toLong()))
        CharacterAdjustment.CAMERA -> copy(cameraX = shifted(cameraX, dx.toLong()), cameraY = shifted(cameraY, dy.toLong()))
        CharacterAdjustment.ANIMATION -> {
            val current = settingsFor(animation)
            copy(animationSettings = animationSettings + (animation to current.copy(
                offsetX = shifted(current.offsetX, -dx.toLong()), offsetY = shifted(current.offsetY, -dy.toLong()))))
        }
    }
}

data class GridFrameRegion(val x: Int, val y: Int, val width: Int, val height: Int)

/** Igual que el exportador: las animaciones se reparten consecutivamente por filas. */
fun gridAnimationRegions(
    sheetWidth: Int, sheetHeight: Int, frameWidth: Int, frameHeight: Int,
    animations: List<Pair<String, Int>>, selected: String
): List<GridFrameRegion> {
    if (frameWidth <= 0 || frameHeight <= 0 || sheetWidth <= 0 || sheetHeight <= 0) return emptyList()
    val columns = sheetWidth / frameWidth
    val rows = sheetHeight / frameHeight
    if (columns <= 0 || rows <= 0 || animations.any { it.second < 0 }) return emptyList()
    val count = animations.firstOrNull { it.first == selected }?.second ?: return emptyList()
    val start = animations.takeWhile { it.first != selected }.sumOf { it.second.toLong() }
    if (start + count > columns.toLong() * rows || count > 100) return emptyList()
    return (0 until count).map { index ->
        val cursor = start + index
        GridFrameRegion(((cursor % columns) * frameWidth).toInt(), ((cursor / columns) * frameHeight).toInt(), frameWidth, frameHeight)
    }
}

/** Quién usa el personaje creado. */
enum class CharacterSide(val label: String) {
    PLAYER("Jugador"),
    OPPONENT("Oponente")
}

/** Variación de personaje (skin/forma alternativa: Erect, Pico, Dead, etc.). */
data class CharacterVariation(
    val variationId: String = "",           // ej: "erect", "pico", "dead", "normal"
    val displayName: String = "",           // ej: "Erect", "Pico", "Dead"
    val sheet: PickedImage? = null,         // spritesheet propio de esta variación
    val sheetWidth: Int = 0,
    val sheetHeight: Int = 0,
    val frameWidth: String = "150",
    val frameHeight: String = "150",
    val scale: String = "1.0",
    val flipX: Boolean = false,
    // Animaciones (puede sobrescribir las del personaje base)
    val idleFrames: String = "2",
    val leftFrames: String = "1",
    val downFrames: String = "1",
    val upFrames: String = "1",
    val rightFrames: String = "1",
    // Icono de vida específico para esta variación
    val icon: PickedImage? = null,
    // Si es la variación por defecto
    val isDefault: Boolean = false
) {
    val frameWidthValue: Int get() = frameWidth.toIntOrNull() ?: 0
    val frameHeightValue: Int get() = frameHeight.toIntOrNull() ?: 0
    val scaleValue: Float get() = scale.toFloatOrNull() ?: 1f
    
    val animations: List<Pair<String, Int>>
        get() = listOf(
            "idle" to (idleFrames.toIntOrNull() ?: 0),
            "singLEFT" to (leftFrames.toIntOrNull() ?: 0),
            "singDOWN" to (downFrames.toIntOrNull() ?: 0),
            "singUP" to (upFrames.toIntOrNull() ?: 0),
            "singRIGHT" to (rightFrames.toIntOrNull() ?: 0)
        ).filter { it.second > 0 }
    
    val totalFrames: Int get() = animations.sumOf { it.second }
    val columns: Int get() = if (frameWidthValue > 0 && sheetWidth > 0) sheetWidth / frameWidthValue else 0
    val rows: Int get() = if (frameHeightValue > 0 && sheetHeight > 0) sheetHeight / frameHeightValue else 0
    val availableFrames: Int get() = (columns.toLong() * rows).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    
    val isReady: Boolean
        get() = sheet != null &&
            frameWidthValue > 0 &&
            frameHeightValue > 0 &&
            totalFrames > 0 &&
            availableFrames >= totalFrames
}

/**
 * Datos para crear un personaje propio a partir de una hoja de sprites (grid).
 * Equivale al "Spritesheet and XML generator" de escritorio, pero en el móvil.
 * Ahora soporta múltiples variaciones (Erect, Pico, Dead, etc.).
 */
data class CharacterDraft(
    val enabled: Boolean = false,
    val charId: String = "",
    val displayName: String = "",
    val sheet: PickedImage? = null,
    val sheetWidth: Int = 0,
    val sheetHeight: Int = 0,
    val icon: PickedImage? = null,
    val frameWidth: String = "150",
    val frameHeight: String = "150",
    val scale: String = "1.0",
    val flipX: Boolean = false,
    val side: CharacterSide = CharacterSide.OPPONENT,
    val idleFrames: String = "2",
    val leftFrames: String = "1",
    val downFrames: String = "1",
    val upFrames: String = "1",
    val rightFrames: String = "1",
    // Variaciones del personaje (Erect, Pico, Dead, etc.)
    val variations: List<CharacterVariation> = emptyList(),
    val useIndividualFrames: Boolean = false,
    val animationFrames: Map<String, List<CharacterFrame>> = emptyMap(),
    val animationSettings: Map<String, CharacterAnimationSettings> = emptyMap(),
    val positionX: String = "0",
    val positionY: String = "0",
    val cameraX: String = "0",
    val cameraY: String = "0",
    val useAtlas: Boolean = false,
    val atlasSheet: PickedImage? = null,
    val atlasSheetWidth: Int = 0,
    val atlasSheetHeight: Int = 0,
    val atlasXml: PickedImage? = null,
    val atlasFrames: List<SparrowFrame> = emptyList(),
    val atlasPrefixes: Map<String, String> = emptyMap(),
    val customAnimations: List<String> = emptyList(),
    val customFrameCounts: Map<String, String> = emptyMap()
) {
    /** Carpeta/ID del personaje (minúsculas y guiones). */
    val charFolder: String
        get() {
            val fromId = slugify(charId)
            if (fromId.isNotEmpty()) return fromId
            val fromName = slugify(displayName)
            return fromName.ifEmpty { "mi-personaje" }
        }

    val frameWidthValue: Int get() = frameWidth.toIntOrNull() ?: 0
    val frameHeightValue: Int get() = frameHeight.toIntOrNull() ?: 0
    val scaleValue: Float get() = scale.toFloatOrNull() ?: 1f

    val animationNames: List<String>
        get() = CHARACTER_ANIMATIONS + customAnimations.filter { it !in CHARACTER_ANIMATIONS }.distinct()

    fun prefixFor(animation: String): String = atlasPrefixes[animation] ?: ""

    /** El orden de la cuadrícula es idle, direcciones y después animaciones personalizadas. */
    val animations: List<Pair<String, Int>>
        get() = when {
            useAtlas -> animationNames.map { it to atlasFramesForPrefix(atlasFrames, prefixFor(it)).size }
            useIndividualFrames -> animationNames.map { it to animationFrames[it].orEmpty().size }
            else -> listOf(
                "idle" to (idleFrames.toIntOrNull() ?: 0),
                "singLEFT" to (leftFrames.toIntOrNull() ?: 0),
                "singDOWN" to (downFrames.toIntOrNull() ?: 0),
                "singUP" to (upFrames.toIntOrNull() ?: 0),
                "singRIGHT" to (rightFrames.toIntOrNull() ?: 0)
            ) + customAnimations.map { it to (customFrameCounts[it]?.toIntOrNull() ?: 0) }
        }.filter { it.second > 0 }

    fun settingsFor(animation: String): CharacterAnimationSettings =
        animationSettings[animation] ?: CharacterAnimationSettings()

    val totalFrames: Int get() = animations.sumOf { it.second }

    /** Columnas y filas que caben en la hoja con el tamaño de frame actual. */
    val columns: Int
        get() = if (frameWidthValue > 0 && sheetWidth > 0) sheetWidth / frameWidthValue else 0
    val rows: Int
        get() = if (frameHeightValue > 0 && sheetHeight > 0) sheetHeight / frameHeightValue else 0
    val availableFrames: Int get() = (columns.toLong() * rows).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    /** Errores concretos que impiden generar el personaje base. */
    val problems: List<String>
        get() = buildList {
            if (useAtlas) {
                val scaleValue = scale.toFloatOrNull()
                if (scaleValue == null || !scaleValue.isFinite() || scaleValue <= 0f) add("Indica una escala mayor que cero.")
                if (atlasSheet == null) add("Elige el PNG del atlas.")
                if (atlasXml == null || atlasFrames.isEmpty()) add("Elige un XML Sparrow válido.")
                if (atlasSheet != null && atlasFrames.isNotEmpty() && !atlasFramesFit(atlasFrames, atlasSheetWidth, atlasSheetHeight))
                    add("El XML contiene frames fuera de los límites del PNG.")
                if (atlasFramesForPrefix(atlasFrames, prefixFor("idle")).isEmpty())
                    add("Asigna a idle un prefijo que coincida con algún frame del XML.")
                for (name in animationNames.filter { prefixFor(it).isNotBlank() }) {
                    if (atlasFramesForPrefix(atlasFrames, prefixFor(name)).isEmpty()) add("El prefijo de $name no coincide con ningún frame del XML.")
                }
            } else if (useIndividualFrames) {
                val scaleValue = scale.toFloatOrNull()
                if (scaleValue == null || !scaleValue.isFinite() || scaleValue <= 0f) add("Indica una escala mayor que cero.")
                if (animationFrames["idle"].isNullOrEmpty()) add("Añade al menos un frame para idle.")
                val frames = animationNames.flatMap { animationFrames[it].orEmpty() }
                if (frames.size > 100) add("El máximo es 100 frames por personaje.")
                if (frames.isNotEmpty() && planCharacterAtlas(frames.map { it.width to it.height }) == null) {
                    add("Los frames no caben en una hoja de 4096 × 4096; reduce sus dimensiones o cantidad.")
                }
            } else {
                addAll(characterProblems(sheet != null, sheetWidth, sheetHeight, frameWidth, frameHeight, scale,
                    listOf(idleFrames, leftFrames, downFrames, upFrames, rightFrames) + customAnimations.map { customFrameCounts[it] ?: "0" }))
            }
            if (listOf(positionX, positionY, cameraX, cameraY).any { it.toIntOrNull() == null }) {
                add("Las posiciones del personaje y de la cámara deben ser números enteros.")
            }
            for ((animation, _) in animations) {
                val settings = settingsFor(animation)
                if (settings.fps.toIntOrNull() !in 1..60) add("FPS de $animation debe estar entre 1 y 60.")
                if (settings.offsetX.toIntOrNull() == null || settings.offsetY.toIntOrNull() == null) {
                    add("Los offsets de $animation deben ser números enteros.")
                }
            }
        }

    val isReady: Boolean get() = !enabled || problems.isEmpty()

    /** Obtiene una variación por ID, o null si no existe. */
    fun getVariation(variationId: String): CharacterVariation? =
        variations.firstOrNull { it.variationId == variationId }
    
    /** Lista de IDs de variaciones disponibles. */
    val variationIds: List<String> get() = variations.map { it.variationId }
    
    /** Variación por defecto (la marcada como isDefault o la primera). */
    val defaultVariation: CharacterVariation?
        get() = variations.firstOrNull { it.isDefault } ?: variations.firstOrNull()
    
    /** Total de frames incluyendo variaciones (para validación). */
    val totalFramesWithVariations: Int
        get() = totalFrames + variations.sumOf { it.totalFrames }
    
    /** Valida que el personaje base y todas las variaciones estén listas. */
    val allReady: Boolean
        get() = isReady && variations.all { it.isReady }
}
