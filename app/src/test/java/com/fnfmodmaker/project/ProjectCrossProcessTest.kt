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

/** Runs the real selection implementation in a second JVM. */
object ActiveProcessWorker {
    @JvmStatic fun main(args: Array<String>) {
        val root = File(args[0]); val id = args[1]; val operation = args[2]
        val store = ProjectStore(root, root.parentFile,
            beforeFileLock = { name -> if (name == "active") { println("ATTEMPT"); System.out.flush() } },
            afterActivePrepared = {
                if (operation == "activeCrashBefore") Runtime.getRuntime().halt(71)
                println("LOCKED"); System.out.flush(); check(readLine() == "CONTINUE")
            },
            activeReplace = if (operation == "activeCrashAfter") { source, target ->
                java.nio.file.Files.move(source.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                Runtime.getRuntime().halt(72)
            } else null)
        println("READY"); System.out.flush(); check(readLine() == "START")
        if (operation == "activeRead") { println("RESULT:${store.activeId()}"); System.out.flush(); return }
        try { store.setActive(id); println("RESULT:OK") }
        catch (e: Throwable) { println("RESULT:FAIL:${e.javaClass.simpleName}:${e.message}") }
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
        val entry = if (op.startsWith("active")) ActiveProcessWorker::class.java.name else ProjectProcessWorker::class.java.name
        val process = ProcessBuilder(java, "-cp", paths.joinToString(File.pathSeparator), entry,
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

    @Test fun activeChangesAndReadsAreSerializedWithoutTouchingAuxiliaryFiles() {
        val root = File(temp.root, "projects"); val store = ProjectStore(root, temp.root)
        val config = Gson().toJsonTree(ModConfig()).asJsonObject.apply { addProperty("chartSchemaVersion", 1) }
        val old = store.newId(); val a = store.newId(); val b = store.newId()
        listOf(old, a, b).forEach { store.save(it, config) }; store.setActive(old)
        val backup = File(root, "active.txt.bak").apply { writeText("backup") }
        val tmp = File(root, "active.txt.tmp").apply { writeText("temp") }
        val backupBytes = backup.readBytes(); val tempBytes = tmp.readBytes()
        val first = worker(root, a, "activeSelect"); val second = worker(root, b, "activeSelect")
        val reader = worker(root, old, "activeRead")
        try {
            first.send("START"); assertEquals("ATTEMPT", first.line()); assertEquals("LOCKED", first.line())
            reader.send("START"); assertEquals("RESULT:$old", reader.done())
            second.send("START"); assertEquals("ATTEMPT", second.line()); first.send("CONTINUE")
            assertEquals("RESULT:OK", first.done()); assertEquals("RESULT:OK", second.done())
            assertEquals(b, store.activeId()); assertArrayEquals(backupBytes, backup.readBytes()); assertArrayEquals(tempBytes, tmp.readBytes())
        } finally { close(first, second, reader) }
    }

    @Test fun activeSelectionDetectsExternalChangeAcrossProcesses() {
        val root = File(temp.root, "projects"); val store = ProjectStore(root, temp.root)
        val config = Gson().toJsonTree(ModConfig()).asJsonObject.apply { addProperty("chartSchemaVersion", 1) }
        val old = store.newId(); val external = store.newId(); val target = store.newId()
        listOf(old, external, target).forEach { store.save(it, config) }; store.setActive(old)
        val primary = File(root, "active.txt"); val backup = File(root, "active.txt.bak").apply { writeText("backup") }
        val tmp = File(root, "active.txt.tmp").apply { writeText("temp") }
        val b = backup.readBytes(); val t = tmp.readBytes()
        val child = worker(root, target, "activeSelect")
        try {
            child.send("START"); assertEquals("ATTEMPT", child.line()); assertEquals("LOCKED", child.line())
            // Deliberately ignore the advisory lock to test detection before the final check.
            primary.writeText(external); child.send("CONTINUE")
            assertTrue(child.done().startsWith("RESULT:FAIL:IllegalStateException:La selección activa cambió"))
            assertEquals(external, primary.readText()); assertArrayEquals(b, backup.readBytes()); assertArrayEquals(t, tmp.readBytes())
        } finally { close(child) }
    }

    @Test fun activePointerSurvivesProcessDeathBeforeAndAfterAtomicInstall() {
        val root = File(temp.root, "projects"); val store = ProjectStore(root, temp.root)
        val config = Gson().toJsonTree(ModConfig()).asJsonObject.apply { addProperty("chartSchemaVersion", 1) }
        val old = store.newId(); val target = store.newId(); store.save(old, config); store.save(target, config); store.setActive(old)
        val primary = File(root, "active.txt"); val backup = File(root, "active.txt.bak").apply { writeText("backup") }
        val tmp = File(root, "active.txt.tmp").apply { writeText("tmp") }
        val b = backup.readBytes(); val t = tmp.readBytes()
        val before = worker(root, target, "activeCrashBefore")
        try {
            before.send("START"); assertEquals("ATTEMPT", before.line())
            assertTrue(before.process.waitFor(20, TimeUnit.SECONDS)); assertEquals(71, before.process.exitValue())
            assertEquals(old, primary.readText()); assertArrayEquals(b, backup.readBytes()); assertArrayEquals(t, tmp.readBytes())
        } finally { close(before) }
        val after = worker(root, target, "activeCrashAfter")
        try {
            after.send("START"); assertEquals("ATTEMPT", after.line()); assertEquals("LOCKED", after.line()); after.send("CONTINUE")
            assertTrue(after.process.waitFor(20, TimeUnit.SECONDS)); assertEquals(72, after.process.exitValue())
            assertEquals(target, primary.readText()); assertArrayEquals(b, backup.readBytes()); assertArrayEquals(t, tmp.readBytes())
        } finally { close(after) }
    }

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
