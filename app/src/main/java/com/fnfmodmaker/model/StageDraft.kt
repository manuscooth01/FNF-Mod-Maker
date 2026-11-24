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
    val baseStageVSlice: String = "mainStage",
    // Engine-specific coordinates keep old project defaults and schemas separate.
    val vSliceLayout: StageLayout = defaultVSliceLayout(),
    val psychLayout: StageLayout = defaultPsychLayout(),
    val psychCameraSpeed: Float = 1f
) {
    val stageFolder: String
        get() = "fnfmm-" + stageId.lowercase().replace(" ", "-").replace(Regex("[^a-z0-9-]"), "").take(40).ifEmpty { "escenario" }
    
    val isReady: Boolean
        get() = enabled && stageId.isNotBlank() && backgroundImage != null
}

/** Stage placement is separate from character and animation offsets. */
data class StageActorPlacement(
    val x: Float = 0f, val y: Float = 0f,
    val cameraX: Float = 0f, val cameraY: Float = 0f,
    val scale: Float = 1f, val zIndex: Int = 0,
    val scrollX: Float = 1f, val scrollY: Float = 1f
)
enum class StageRole(val label: String) { BF("Jugador · BF"), DAD("Rival · DAD"), GF("Acompañante · GF") }
data class StageLayout(val bf: StageActorPlacement = StageActorPlacement(), val dad: StageActorPlacement = StageActorPlacement(), val gf: StageActorPlacement = StageActorPlacement()) {
    fun actor(role: StageRole): StageActorPlacement = when (role) { StageRole.BF -> bf; StageRole.DAD -> dad; StageRole.GF -> gf }
    fun withActor(role: StageRole, actor: StageActorPlacement): StageLayout = when (role) {
        StageRole.BF -> copy(bf = actor); StageRole.DAD -> copy(dad = actor); StageRole.GF -> copy(gf = actor)
    }
}
fun defaultVSliceLayout() = StageLayout(
    bf = StageActorPlacement(989.5f, 885f, -100f, -100f, zIndex = 300),
    dad = StageActorPlacement(335f, 885f, 150f, -100f, zIndex = 200),
    gf = StageActorPlacement(751.5f, 787f, 0f, 0f, zIndex = 100))
fun defaultPsychLayout() = StageLayout(
    bf = StageActorPlacement(770f, 100f), dad = StageActorPlacement(100f, 100f), gf = StageActorPlacement(400f, 130f))

/** Do not silently serialize fields absent from a target engine's stage format. */
fun stageLayoutProblems(stage: StageDraft, engine: TargetEngine): List<String> = buildList {
    fun check(layout: StageLayout, name: String, psych: Boolean) {
        StageRole.entries.forEach { role ->
            val actor = layout.actor(role)
            if (listOf(actor.x, actor.y, actor.cameraX, actor.cameraY, actor.scale, actor.scrollX, actor.scrollY).any { !it.isFinite() } || actor.scale <= 0f || actor.scale > 100f || listOf(actor.x, actor.y, actor.cameraX, actor.cameraY).any { kotlin.math.abs(it) > 100000f })
                add("$name · ${role.label}: posición, cámara o escala inválida.")
            if (psych && (actor.scale != 1f || actor.zIndex != 0 || actor.scrollX != 1f || actor.scrollY != 1f))
                add("Psych · ${role.label}: escala, Z y scroll de personaje no se representan en el JSON de escenario.")
        }
    }
    if (engine.includesVSlice) check(stage.vSliceLayout, "V-Slice", false)
    if (engine.includesPsych) {
        check(stage.psychLayout, "Psych", true)
        if (!stage.psychCameraSpeed.isFinite() || stage.psychCameraSpeed <= 0f || stage.psychCameraSpeed > 100f) add("Psych: velocidad de cámara inválida.")
    }
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
    val animationLoop: Boolean = true,
    val layer: Layer = Layer.FOREGROUND
) {
    enum class Layer(val label: String, val zIndex: Int) {
        BACKGROUND("Fondo", 0),
        MIDDLE("Intermedio", 1),
        FOREGROUND("Frente", 2),
        HUD("HUD", 3)
    }
    
    /** Duración total de la animación en ms. */
    val animationDurationMs: Long
        get() = if (animationFrames > 0 && animationFps > 0) (animationFrames * 1000L / animationFps) else 0L
    
    /** Frame actual basado en tiempo transcurrido. */
    fun getCurrentFrame(elapsedMs: Long): Int {
        if (!animated || animationFrames <= 1) return 0
        val frameMs = 1000L / animationFps
        val frame = if (animationLoop) {
            (elapsedMs / frameMs) % animationFrames.toLong()
        } else {
            (elapsedMs / frameMs).coerceAtMost((animationFrames - 1).toLong())
        }
        return frame.toInt()
    }
}

enum class AnimationPlayState {
    STOPPED, PLAYING, PAUSED
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
/** Old draft fields that cannot yet be represented by the simple exporter. */
fun StageDraft.hasUnsupportedFeatures(): Boolean = sprites.isNotEmpty() || zoneColors.isNotEmpty() ||
    cameraPosition.x != 0f || cameraPosition.y != 0f || !cameraPosition.followPlayer || cameraPosition.bounds != CameraBounds() ||
    backgroundColor != "#000000" || baseStagePsych != "stage" || baseStageVSlice != "mainStage"
