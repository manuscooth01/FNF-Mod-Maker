package com.fnfmodmaker.model

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
    val availableFrames: Int get() = columns * rows
    
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
    val variations: List<CharacterVariation> = emptyList()
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
