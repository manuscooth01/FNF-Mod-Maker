package com.fnfmodmaker.model

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Configuración de un stage (escenario) personalizado.
 * Permite definir fondo, sprites, posición de cámara, colores, etc.
 */
data class StageDraft(
    val enabled: Boolean = false,
    val stageId: String = "",
    val displayName: String = "",
    
    // Fondo (background image)
    val backgroundImage: PickedImage? = null,
    val backgroundColor: String = "#000000",
    
    // Sprites del stage (props, foreground, etc.)
    val sprites: List<StageSprite> = emptyList(),
    
    // Posición de cámara por defecto
    val cameraPosition: CameraPosition = CameraPosition(),
    
    // Colores de zona (para V-Slice)
    val zoneColors: List<String> = emptyList(),
    
    // Para Psych: nombre del stage base (stage, stage-psych, etc.)
    val baseStagePsych: String = "stage",
    
    // Para V-Slice: nombre del stage base (mainStage, phillyStreets, etc.)
    val baseStageVSlice: String = "mainStage"
) {
    val stageFolder: String
        get() = stageId.lowercase().replace(" ", "-").replace(Regex("[^a-z0-9-]"), "").ifEmpty { "custom-stage" }
    
    val isReady: Boolean
        get() = enabled && stageId.isNotBlank() && backgroundImage != null
}

/** Sprite individual dentro del stage (prop, foreground element, etc.). */
data class StageSprite(
    val spriteId: String = "",
    val displayName: String = "",
    val image: PickedImage? = null,
    val x: Float = 0f,
    val y: Float = 0f,
    val scale: Float = 1f,
    val rotation: Float = 0f,
    val animated: Boolean = false,
    val animationFrames: Int = 1,
    val animationFps: Int = 24,
    val layer: Layer = Layer.FOREGROUND
) {
    enum class Layer(val label: String, val zIndex: Int) {
        BACKGROUND("Fondo", 0),
        MIDDLE("Intermedio", 1),
        FOREGROUND("Frente", 2),
        HUD("HUD", 3)
    }
}

/** Posición y comportamiento de cámara. */
data class CameraPosition(
    val x: Float = 0f,
    val y: Float = 0f,
    val zoom: Float = 1f,
    val followPlayer: Boolean = true,
    val bounds: CameraBounds = CameraBounds()
)

data class CameraBounds(
    val left: Float = -1000f,
    val right: Float = 1000f,
    val top: Float = -500f,
    val bottom: Float = 500f
)

/** Extensión a ModConfig para incluir stage personalizado. */
data class ModConfig(
    // ... campos existentes ...
) {
    // Agregar stageDraft como propiedad
    val stageDraft: StageDraft = StageDraft()
    
    // Método para obtener el stageDraft (compatibilidad)
    fun getStageDraft(): StageDraft = stageDraft
}

/** Agregar a ModConfig las propiedades de stage. 
 * Nota: Como ModConfig es data class, se debe agregar stageDraft en la definición real.
 * Aquí solo documentamos la estructura.
 */

// En ModConfig.kt real, agregar:
// val stageDraft: StageDraft = StageDraft()