package com.fnfmodmaker.mod

import com.fnfmodmaker.model.TargetEngine
import java.io.File

/** Audio-only adapter permits deterministic testing without invoking Android or FFmpeg. */
internal data class AudioSource(val file: File, val isOgg: Boolean)
internal interface AudioEncoder {
    fun convert(input: File, output: File): String?
    fun mix(first: File, second: File, output: File): String?
}

internal fun characterVoiceFileId(raw: String): String =
    raw.trim().filterNot { it in "/\\:*?\"<>|" }.ifEmpty { "bf" }

internal fun validateVoiceNames(engine: TargetEngine, player: String, opponent: String, hasSplitVoices: Boolean) {
    if (engine.includesVSlice && hasSplitVoices) {
        val playerFile = "Voices-${characterVoiceFileId(player)}.ogg"
        val opponentFile = "Voices-${characterVoiceFileId(opponent)}.ogg"
        require(!playerFile.equals(opponentFile, ignoreCase = true)) {
            "V-Slice: las voces del jugador y del rival coinciden en $playerFile; usa IDs de personaje distintos."
        }
    }
}

/** Throws on any required missing or failed track; caller must not package a failed build. */
internal fun exportSongAudio(
    songDir: File, songFolder: String, engine: TargetEngine, playerId: String, opponentId: String,
    instrumental: AudioSource, player: AudioSource?, opponent: AudioSource?, encoder: AudioEncoder,
    progress: (String) -> Unit = {}
) {
    validateVoiceNames(engine, playerId, opponentId, player != null && opponent != null)
    val engineLabel = when (engine) {
        TargetEngine.BOTH -> "V-Slice y Psych Engine"
        TargetEngine.VSLICE -> "V-Slice"
        TargetEngine.PSYCH -> "Psych Engine"
    }
    check(songDir.isDirectory || songDir.mkdirs()) { "No se pudo crear la carpeta de audio de $songFolder." }
    fun output(label: String, engines: String, target: File, action: () -> String?) {
        progress("Preparando $label para $engines en $songFolder…")
        try {
            if (target.exists() && !target.delete()) error("No se pudo reemplazar el archivo temporal.")
            val error = action()
            if (error != null) throw IllegalStateException(error)
            check(target.isFile && target.length() > 0L) { "El archivo de salida falta o está vacío." }
        } catch (e: Exception) {
            target.delete()
            throw IllegalStateException("No se pudo preparar $label de '$songFolder' para $engines: ${e.message ?: "error de audio"}. No se generó el ZIP.", e)
        }
    }
    fun encode(source: AudioSource, label: String, engines: String, filename: String) {
        val target = File(songDir, filename)
        output(label, engines, target) {
            require(source.file.isFile && source.file.length() > 0L) { "El archivo de origen falta o está vacío." }
            if (source.isOgg) { source.file.copyTo(target, overwrite = false); null }
            else encoder.convert(source.file, target)
        }
    }
    encode(instrumental, "la instrumental", engineLabel, "Inst.ogg")
    if (player != null && opponent != null) {
        val playerName = "Voices-${characterVoiceFileId(playerId)}.ogg"
        val opponentName = "Voices-${characterVoiceFileId(opponentId)}.ogg"
        // Psych-only needs temporary converted sources so each failing voice is identified separately.
        val pName = if (engine.includesVSlice) playerName else ".player-temp.ogg"
        val oName = if (engine.includesVSlice) opponentName else ".opponent-temp.ogg"
        val voiceEngines = if (engine == TargetEngine.BOTH) "V-Slice y Psych Engine" else engineLabel
        try {
            encode(player, "la voz del jugador", voiceEngines, pName)
            encode(opponent, "la voz del rival", voiceEngines, oName)
            if (engine.includesPsych) {
                output("la mezcla de las voces del jugador y del rival", "Psych Engine", File(songDir, "Voices.ogg")) {
                    encoder.mix(File(songDir, pName), File(songDir, oName), File(songDir, "Voices.ogg"))
                }
            }
        } finally {
            if (!engine.includesVSlice) { File(songDir, pName).delete(); File(songDir, oName).delete() }
        }
    } else {
        val single = player ?: opponent
        if (single != null) encode(single, if (player != null) "la voz del jugador" else "la voz del rival", engineLabel, "Voices.ogg")
    }
}
