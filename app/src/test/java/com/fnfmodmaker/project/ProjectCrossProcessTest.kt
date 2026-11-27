package com.fnfmodmaker.project

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.fnfmodmaker.model.ModConfig
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/** Separate JVM entry point: exercises the actual ProjectStore and its FileChannel locks. */
object ProjectProcessWorker {
    @JvmStatic fun main(args: Array<String>) {
        val root = File(args[0]); val id = args[1]; val operation = args[2]
        val store = ProjectStore(root, root.parentFile,
            recoveryInstall = { source, target -> java.nio.file.Files.createLink(target.toPath(), source.toPath()) },
            beforeFileLock = { name -> if (name == id) { println("ATTEMPT"); System.out.flush() } },
            afterInspection = { println("LOCKED"); System.out.flush(); check(readLine() == "CONTINUE") })
        println("READY"); System.out.flush(); check(readLine() == "START")
        try {
            if (operation == "recover") store.recoverBackup(id)
            else store.save(id, JsonParser.parseString("""{"chartSchemaVersion":1,"modTitle":"worker-saved"}""").asJsonObject)
            println("RESULT:OK")
        } catch (e: Throwable) { println("RESULT:FAIL:${e.javaClass.simpleName}") }
        System.out.flush()
    }
}

class ProjectCrossProcessTest {
    @get:Rule val temp = TemporaryFolder()

    private data class Child(val process: Process, val input: java.io.BufferedWriter, val output: java.io.BufferedReader) {
        fun send(text: String) { input.write(text); input.newLine(); input.flush() }
        fun line(): String = output.readLine() ?: error("El proceso terminó sin respuesta (exit=${process.exitValue()})")
        fun done(): String {
            var line = line()
            if (line == "LOCKED") { send("CONTINUE"); line = line() }
            check(line.startsWith("RESULT:")) { "Respuesta inesperada: $line" }
            check(process.waitFor(20, TimeUnit.SECONDS)) { "El proceso no terminó." }
            return line
        }
    }
    private fun worker(root: File, id: String, op: String): Child {
        val paths = linkedSetOf<String>()
        System.getProperty("java.class.path").split(File.pathSeparator).filterTo(paths) { it.isNotBlank() }
        var loader: ClassLoader? = javaClass.classLoader
        while (loader != null) {
            if (loader is URLClassLoader) loader.urLs.filter { it.protocol == "file" }.forEach { paths.add(File(it.toURI()).path) }
            loader = loader.parent
        }
        listOf(ProjectProcessWorker::class.java, ProjectStore::class.java, Gson::class.java, kotlin.Unit::class.java,
            android.net.Uri::class.java, ModConfig::class.java).forEach { cls ->
            cls.protectionDomain?.codeSource?.location?.takeIf { it.protocol == "file" }?.let { paths.add(File(it.toURI()).path) }
        }
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val process = ProcessBuilder(java, "-cp", paths.joinToString(File.pathSeparator), ProjectProcessWorker::class.java.name,
            root.absolutePath, id, op).redirectErrorStream(true).start()
        val child = Child(process, process.outputStream.bufferedWriter(), process.inputStream.bufferedReader())
        assertEquals("READY", child.line())
        return child
    }
    private fun fixture(): Triple<File, String, ByteArray> {
        val root = File(temp.root, "projects"); val store = ProjectStore(root, temp.root)
        val id = store.newId(); val config = Gson().toJsonTree(ModConfig()).asJsonObject
        config.addProperty("chartSchemaVersion", 1); config.addProperty("modTitle", "backup")
        store.save(id, config); store.save(id, config)
        val primary = File(root, "$id/project.json"); val backup = File(root, "$id/project.json.bak")
        val bytes = backup.readBytes(); assertTrue(primary.delete())
        return Triple(root, id, bytes)
    }
    private fun close(vararg children: Child) { children.forEach { if (it.process.isAlive) it.process.destroyForcibly() } }

    @Test fun saveAndRecoveryAreSerializedAcrossIndependentJvms() {
        for (firstOp in listOf("save", "recover")) {
            val (root, id, original) = fixture(); val backup = File(root, "$id/project.json.bak")
            val primary = File(root, "$id/project.json")
            val first = worker(root, id, firstOp); val second = worker(root, id, if (firstOp == "save") "recover" else "save")
            try {
                first.send("START"); assertEquals("ATTEMPT", first.line()); assertEquals("LOCKED", first.line())
                second.send("START"); assertEquals("ATTEMPT", second.line()); first.send("CONTINUE")
                val firstResult = first.done(); val secondResult = second.done()
                if (firstOp == "save") {
                    assertTrue(firstResult.startsWith("RESULT:FAIL:")); assertEquals("RESULT:OK", secondResult)
                    assertArrayEquals(original, primary.readBytes()); assertArrayEquals(original, backup.readBytes())
                } else {
                    assertEquals("RESULT:OK", firstResult); assertEquals("RESULT:OK", secondResult)
                    assertEquals("worker-saved", JsonParser.parseString(primary.readText()).asJsonObject.getAsJsonObject("config").get("modTitle").asString)
                    assertArrayEquals(original, backup.readBytes())
                }
            } finally { close(first, second) }
        }
    }

    @Test fun twoRecoveriesAndExternalChangesNeverOverwriteCandidates() {
        val (root, id, original) = fixture(); val primary = File(root, "$id/project.json"); val backup = File(root, "$id/project.json.bak")
        val first = worker(root, id, "recover"); val second = worker(root, id, "recover")
        try {
            first.send("START"); assertEquals("ATTEMPT", first.line()); assertEquals("LOCKED", first.line())
            second.send("START"); assertEquals("ATTEMPT", second.line()); first.send("CONTINUE")
            assertEquals("RESULT:OK", first.done()); assertTrue(second.done().startsWith("RESULT:FAIL:"))
            assertArrayEquals(original, primary.readBytes()); assertArrayEquals(original, backup.readBytes())
        } finally { close(first, second) }
        for (change in listOf("primary", "backup")) {
            val (r, key, bytes) = fixture(); val p = File(r, "$key/project.json"); val b = File(r, "$key/project.json.bak")
            val recovering = worker(r, key, "recover")
            try {
                recovering.send("START"); assertEquals("ATTEMPT", recovering.line()); assertEquals("LOCKED", recovering.line())
                if (change == "primary") p.writeText("external") else b.writeBytes(bytes + " ".toByteArray(StandardCharsets.UTF_8))
                recovering.send("CONTINUE"); assertTrue(recovering.done().startsWith("RESULT:FAIL:"))
                if (change == "primary") { assertEquals("external", p.readText()); assertArrayEquals(bytes, b.readBytes()) }
                else { assertFalse(p.exists()); assertArrayEquals(bytes + " ".toByteArray(StandardCharsets.UTF_8), b.readBytes()) }
            } finally { close(recovering) }
        }
    }
}
