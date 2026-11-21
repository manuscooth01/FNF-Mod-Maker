package com.fnfmodmaker.model

/** Quién usa el personaje creado. */
enum class CharacterSide(val label: String) {
    PLAYER("Jugador"),
    OPPONENT("Oponente")
}

/**
 * Datos para crear un personaje propio a partir de una hoja de sprites (grid).
 * Equivale al "Spritesheet and XML generator" de escritorio, pero en el móvil.
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
    val rightFrames: String = "1"
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

    /** Animaciones estándar de FNF en el orden en que se reparten los frames. */
    val animations: List<Pair<String, Int>>
        get() = listOf(
            "idle" to (idleFrames.toIntOrNull() ?: 0),
            "singLEFT" to (leftFrames.toIntOrNull() ?: 0),
            "singDOWN" to (downFrames.toIntOrNull() ?: 0),
            "singUP" to (upFrames.toIntOrNull() ?: 0),
            "singRIGHT" to (rightFrames.toIntOrNull() ?: 0)
        ).filter { it.second > 0 }

    val totalFrames: Int get() = animations.sumOf { it.second }

    /** Columnas y filas que caben en la hoja con el tamaño de frame actual. */
    val columns: Int
        get() = if (frameWidthValue > 0 && sheetWidth > 0) sheetWidth / frameWidthValue else 0
    val rows: Int
        get() = if (frameHeightValue > 0 && sheetHeight > 0) sheetHeight / frameHeightValue else 0
    val availableFrames: Int get() = columns * rows

    /** ¿Están todos los datos mínimos y caben los frames? */
    val isReady: Boolean
        get() = !enabled || (
            sheet != null &&
                frameWidthValue > 0 &&
                frameHeightValue > 0 &&
                totalFrames > 0 &&
                availableFrames >= totalFrames
            )
}
