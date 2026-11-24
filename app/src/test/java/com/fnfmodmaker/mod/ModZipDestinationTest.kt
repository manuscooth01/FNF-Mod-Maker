package com.fnfmodmaker.mod

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

class ModZipDestinationTest {
    @Test fun onlyDestinationErrorsGetTheSelectorWarning() {
        var opened = false
        val buildError = assertThrows(IllegalArgumentException::class.java) {
            buildAndWriteModZip<Unit>({ throw IllegalArgumentException("rival cambió") }, { opened = true; ByteArrayOutputStream() }) { _, _ -> }
        }
        assertEquals("rival cambió", buildError.message); assertFalse(opened)
        val openError = assertThrows(ModZipDestinationException::class.java) { writeModZipToDestination({ null }) { } }
        assertTrue(openError.message!!.contains("destino elegido"))
        val writeError = assertThrows(ModZipDestinationException::class.java) {
            writeModZipToDestination({ object : OutputStream() { override fun write(b: Int) { throw IOException("sin espacio") } } }) { it.write(1) }
        }
        assertTrue(writeError.message!!.contains("sin espacio")); assertTrue(writeError.message!!.contains("incompleto"))
        val out = ByteArrayOutputStream(); writeModZipToDestination({ out }) { it.write(42) }
        assertArrayEquals(byteArrayOf(42), out.toByteArray())
    }
}
