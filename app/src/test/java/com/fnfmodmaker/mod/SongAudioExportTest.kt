package com.fnfmodmaker.mod

import com.fnfmodmaker.model.TargetEngine
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SongAudioExportTest {
    @get:Rule val temp = TemporaryFolder()
    private fun input(name: String, ogg: Boolean = false): AudioSource = AudioSource(File(temp.root, name).apply { writeText("source-$name") }, ogg)
    private class FakeEncoder(var failConvert: String? = null, var failMix: Boolean = false, var emptyOutput: Boolean = false) : AudioEncoder {
        override fun convert(input: File, output: File): String? {
            if (input.name == failConvert) { output.writeText("partial"); return "falló conversión" }
            output.writeText(if (emptyOutput) "" else "converted-${input.name}"); return null
        }
        override fun mix(first: File, second: File, output: File): String? {
            if (failMix) { output.writeText("partial"); return "falló mezcla" }
            output.writeText(if (emptyOutput) "" else "mixed-${first.name}-${second.name}"); return null
        }
    }
    private fun runExport(engine: TargetEngine, inst: AudioSource, player: AudioSource? = null, opponent: AudioSource? = null,
        encoder: FakeEncoder = FakeEncoder(), playerId: String = "bf", opponentId: String = "dad"): File {
        val dir = File(temp.root, "out-${System.nanoTime()}")
        exportSongAudio(dir, "track", engine, playerId, opponentId, inst, player, opponent, encoder)
        return dir
    }
    @Test fun instrumentalFailureStopsAllEnginesAndNamesTrackAndEngine() {
        val inst = input("inst.wav")
        for (engine in TargetEngine.entries) {
            val failure = assertThrows(IllegalStateException::class.java) { runExport(engine, inst, encoder = FakeEncoder(failConvert = "inst.wav")) }
            assertTrue(failure.message!!.contains("instrumental")); assertTrue(failure.message!!.contains(if (engine == TargetEngine.PSYCH) "Psych" else "V-Slice"))
        }
    }
    @Test fun vslicePlayerAndOpponentFailuresAreReportedSeparately() {
        val inst = input("inst.ogg", true); val p = input("player.wav"); val o = input("opponent.wav")
        for ((file, label) in listOf("player.wav" to "jugador", "opponent.wav" to "rival")) {
            val failure = assertThrows(IllegalStateException::class.java) { runExport(TargetEngine.VSLICE, inst, p, o, FakeEncoder(failConvert = file)) }
            assertTrue(failure.message!!.contains(label)); assertTrue(failure.message!!.contains("V-Slice"))
        }
    }
    @Test fun psychAndBothReportWhichSplitVoiceFailedBeforeMixing() {
        val inst = input("inst.ogg", true); val p = input("player.wav"); val o = input("opponent.wav")
        for (engine in listOf(TargetEngine.PSYCH, TargetEngine.BOTH)) {
            for ((file, label) in listOf("player.wav" to "jugador", "opponent.wav" to "rival")) {
                val failure = assertThrows(IllegalStateException::class.java) { runExport(engine, inst, p, o, FakeEncoder(failConvert = file)) }
                assertTrue(failure.message!!.contains(label)); assertTrue(failure.message!!.contains("Psych"))
            }
        }
    }
    @Test fun psychMixFailureAndEmptyOutputBlockExport() {
        val inst = input("inst.ogg", true); val p = input("player.wav"); val o = input("opponent.wav")
        for (engine in listOf(TargetEngine.PSYCH, TargetEngine.BOTH)) {
            val failure = assertThrows(IllegalStateException::class.java) { runExport(engine, inst, p, o, FakeEncoder(failMix = true)) }
            assertTrue(failure.message!!.contains("mezcla")); assertTrue(failure.message!!.contains("Psych"))
        }
        assertThrows(IllegalStateException::class.java) { runExport(TargetEngine.PSYCH, input("inst.wav"), encoder = FakeEncoder(emptyOutput = true)) }
    }
    @Test fun identicalAndSanitizedVoiceNamesBlockOnlyVsliceAndBoth() {
        val inst = input("inst.ogg", true); val p = input("p.ogg", true); val o = input("o.ogg", true)
        for (engine in listOf(TargetEngine.VSLICE, TargetEngine.BOTH)) {
            val failure = assertThrows(IllegalArgumentException::class.java) { runExport(engine, inst, p, o, playerId = "B/f", opponentId = "bf") }
            assertTrue(failure.message!!.contains("V-Slice"))
        }
        assertTrue(File(runExport(TargetEngine.PSYCH, inst, p, o, playerId = "bf", opponentId = "bf"), "Voices.ogg").isFile)
    }
    @Test fun bothProducesEveryRequiredTrackAndNoVoicesIsAllowed() {
        val inst = input("inst.wav"); val p = input("p.wav"); val o = input("o.wav")
        val dir = runExport(TargetEngine.BOTH, inst, p, o)
        assertEquals(setOf("Inst.ogg", "Voices-bf.ogg", "Voices-dad.ogg", "Voices.ogg"), dir.list()!!.toSet())
        assertTrue(File(dir, "Voices.ogg").readText().startsWith("mixed"))
        for (engine in TargetEngine.entries) assertEquals(setOf("Inst.ogg"), runExport(engine, inst).list()!!.toSet())
    }
    @Test fun singleVoiceFailureInBothIdentifiesVoiceAndBothEngines() {
        val failure = assertThrows(IllegalStateException::class.java) { runExport(TargetEngine.BOTH, input("inst.ogg", true), opponent = input("voice.wav"), encoder = FakeEncoder(failConvert = "voice.wav")) }
        assertTrue(failure.message!!.contains("rival")); assertTrue(failure.message!!.contains("V-Slice y Psych Engine"))
    }
    @Test fun missingOrEmptyCopiedOggFailsAndIsNotPublishedAsATrack() {
        val empty = File(temp.root, "empty.ogg").apply { writeText("") }
        val out = File(temp.root, "failed")
        assertThrows(IllegalStateException::class.java) { exportSongAudio(out, "x", TargetEngine.BOTH, "bf", "dad", AudioSource(empty, true), null, null, FakeEncoder()) }
        assertFalse(File(out, "Inst.ogg").exists())
    }
}
