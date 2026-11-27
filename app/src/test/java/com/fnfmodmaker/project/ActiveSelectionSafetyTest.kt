package com.fnfmodmaker.project

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Files of an interrupted selection are evidence, not implicit recovery inputs. */
class ActiveSelectionSafetyTest {
    @get:Rule val temp = TemporaryFolder()

    private fun config() = JsonParser.parseString("""{"chartSchemaVersion":1,"modTitle":"legacy"}""").asJsonObject
    private fun bytes(vararg files: File) = files.associate { it.name to if (it.isFile) it.readBytes().toList() else null }

    @Test fun missingPrimaryDoesNotRestoreOrSelectBackup() {
        val root = File(temp.root, "projects"); val store = ProjectStore(root, temp.root)
        val id = store.newId(); store.save(id, config())
        val primary = File(root, "active.txt"); val backup = File(root, "active.txt.bak").apply { writeText(id) }
        val temporary = File(root, "active.txt.tmp").apply { writeText("interrupted") }
        val before = bytes(primary, backup, temporary)
        assertNull("No se debe elegir el backup", store.activeId())
        assertEquals(before, bytes(primary, backup, temporary))
    }

    @Test fun corruptPrimaryDoesNotRestoreOrSelectBackup() {
        val root = File(temp.root, "projects"); val store = ProjectStore(root, temp.root)
        val id = store.newId(); store.save(id, config())
        val primary = File(root, "active.txt").apply { writeBytes(byteArrayOf(0xff.toByte(), 0x00)) }
        val backup = File(root, "active.txt.bak").apply { writeText(id) }
        val temporary = File(root, "active.txt.tmp").apply { writeText("interrupted") }
        val before = bytes(primary, backup, temporary)
        val failure = assertThrows(IllegalStateException::class.java) { store.activeId() }
        assertTrue(failure.message.orEmpty().contains("inválido"))
        assertEquals(before, bytes(primary, backup, temporary))
    }

    @Test fun validPrimaryAndOldBackupAreReadWithoutModifyingEither() {
        val root = File(temp.root, "projects"); val store = ProjectStore(root, temp.root)
        val id = store.newId(); val old = store.newId(); store.save(id, config()); store.save(old, config())
        val primary = File(root, "active.txt").apply { writeText(id) }
        val backup = File(root, "active.txt.bak").apply { writeText(old) }
        val temporary = File(root, "active.txt.tmp").apply { writeText("evidence") }
        val before = bytes(primary, backup, temporary)
        assertEquals(id, store.activeId())
        assertEquals(before, bytes(primary, backup, temporary))
    }

    @Test fun explicitCompatibleSelectionDoesNotConsumeExistingBackupOrTemporary() {
        val root = File(temp.root, "projects"); val store = ProjectStore(root, temp.root)
        val old = store.newId(); val target = store.newId(); store.save(old, config()); store.save(target, config())
        val primary = File(root, "active.txt").apply { writeText(old) }
        val backup = File(root, "active.txt.bak").apply { writeText(store.newId()) }
        val temporary = File(root, "active.txt.tmp").apply { writeText("preserve me") }
        val oldBackup = backup.readBytes(); val oldTemp = temporary.readBytes()
        store.setActive(target)
        assertEquals(target, store.activeId()); assertEquals(target, primary.readText())
        assertArrayEquals(oldBackup, backup.readBytes()); assertArrayEquals(oldTemp, temporary.readBytes())
    }

    @Test fun pointerToMissingOrIncompatibleProjectMustNotBeReturned() {
        val root = File(temp.root, "projects"); val store = ProjectStore(root, temp.root)
        val id = store.newId(); assertTrue(root.mkdirs())
        val primary = File(root, "active.txt").apply { writeText(id) }
        val before = primary.readBytes()
        val missing = assertThrows(IllegalStateException::class.java) { store.activeId() }
        assertTrue(missing.message.orEmpty().contains("proyecto"))
        assertArrayEquals(before, primary.readBytes())
        val folder = File(root, id).apply { mkdirs() }
        File(folder, "project.json").writeText("""{"version":1,"id":"$id","updatedAt":1,"config":{"chartSchemaVersion":2,"independentCharts":{}}}""")
        val incompatible = assertThrows(IllegalArgumentException::class.java) { store.activeId() }
        assertTrue(incompatible.message.orEmpty().contains("compatible"))
        assertArrayEquals(before, primary.readBytes())
    }
    @Test fun writeAndInstallFailuresPreserveAllNamedCandidates() {
        val root = File(temp.root, "projects"); val store = ProjectStore(root, temp.root)
        val old = store.newId(); val target = store.newId(); store.save(old, config()); store.save(target, config())
        val primary = File(root, "active.txt").apply { writeText(old) }
        val backup = File(root, "active.txt.bak").apply { writeText("backup evidence") }
        val temporary = File(root, "active.txt.tmp").apply { writeText("temporary evidence") }
        val before = bytes(primary, backup, temporary)
        val writeFailure = ProjectStore(root, temp.root, activeTempWrite = { staged, _ ->
            staged.writeText("partial"); throw java.io.IOException("injected write failure")
        })
        val writeError = assertThrows(java.io.IOException::class.java) { writeFailure.setActive(target) }
        assertEquals("injected write failure", writeError.message); assertEquals(before, bytes(primary, backup, temporary))
        val installFailure = ProjectStore(root, temp.root, activeReplace = { _, _ -> throw java.io.IOException("injected install failure") })
        val installError = assertThrows(java.io.IOException::class.java) { installFailure.setActive(target) }
        assertEquals("injected install failure", installError.message); assertEquals(before, bytes(primary, backup, temporary))
        assertEquals(old, store.activeId())
    }

    @Test fun externalChangeBeforeInstallationIsDetectedWithoutOverwritingIt() {
        val root = File(temp.root, "projects"); val store = ProjectStore(root, temp.root)
        val old = store.newId(); val other = store.newId(); val target = store.newId()
        listOf(old, other, target).forEach { store.save(it, config()) }; store.setActive(old)
        val primary = File(root, "active.txt"); val backup = File(root, "active.txt.bak").apply { writeText("backup") }
        val temporary = File(root, "active.txt.tmp").apply { writeText("temp") }
        val backupBytes = backup.readBytes(); val tempBytes = temporary.readBytes()
        val racing = ProjectStore(root, temp.root, afterActivePrepared = { primary.writeText(other) })
        val error = assertThrows(IllegalStateException::class.java) { racing.setActive(target) }
        assertTrue(error.message.orEmpty().contains("cambió")); assertEquals(other, primary.readText())
        assertArrayEquals(backupBytes, backup.readBytes()); assertArrayEquals(tempBytes, temporary.readBytes())
    }

    @Test fun v2SelectionRejectedWithoutModifyingCandidates() {
        val root = File(temp.root, "projects"); val store = ProjectStore(root, temp.root)
        val current = store.newId(); store.save(current, config()); store.setActive(current)
        val target = store.newId(); val folder = File(root, target).apply { mkdirs() }
        File(folder, "project.json").writeText("""{"version":1,"id":"$target","updatedAt":1,"config":{"chartSchemaVersion":2,"independentCharts":{}}}""")
        val p = File(root, "active.txt"); val b = File(root, "active.txt.bak").apply { writeText("backup") }
        val t = File(root, "active.txt.tmp").apply { writeText("temp") }; val before = bytes(p, b, t)
        val error = assertThrows(IllegalArgumentException::class.java) { store.setActive(target) }
        assertTrue(error.message.orEmpty().contains("compatible")); assertEquals(before, bytes(p, b, t))
        assertEquals(current, store.activeId())
    }

    @Test fun preparationErrorsAndVerificationFailureCleanOwnStageOnly() {
        val root = File(temp.root, "projects"); val store = ProjectStore(root, temp.root)
        val old = store.newId(); val target = store.newId(); store.save(old, config()); store.save(target, config()); store.setActive(old)
        val p = File(root, "active.txt"); val b = File(root, "active.txt.bak").apply { writeText("backup") }
        val t = File(root, "active.txt.tmp").apply { writeText("temp") }; val before = bytes(p, b, t)
        fun stages() = root.listFiles().orEmpty().filter { it.name.startsWith("active-selection-") }.map { it.name }.toSet()
        val initialStages = stages()
        val failWrite = ProjectStore(root, temp.root, activeTempWrite = { file, _ -> file.writeText("partial"); throw java.io.IOException("write error") })
        assertEquals("write error", assertThrows(java.io.IOException::class.java) { failWrite.prepareActive(target) }.message)
        assertEquals(initialStages, stages()); assertEquals(before, bytes(p, b, t))
        val failVerify = ProjectStore(root, temp.root, activeTempWrite = { file, _ -> file.writeText("invalid") })
        assertTrue(assertThrows(IllegalStateException::class.java) { failVerify.prepareActive(target) }.message.orEmpty().contains("verificar"))
        assertEquals(initialStages, stages()); assertEquals(before, bytes(p, b, t))
    }

    @Test fun cancellationAndInstallFailureCleanStageAndPreserveCandidates() {
        val root = File(temp.root, "projects"); val store = ProjectStore(root, temp.root)
        val old = store.newId(); val target = store.newId(); store.save(old, config()); store.save(target, config()); store.setActive(old)
        val p = File(root, "active.txt"); val b = File(root, "active.txt.bak").apply { writeText("backup") }
        val t = File(root, "active.txt.tmp").apply { writeText("temp") }; val before = bytes(p, b, t)
        val cancelled = store.prepareActive(target); val owned = cancelled.stage; assertTrue(owned.exists())
        cancelled.close(); assertFalse(owned.exists()); assertEquals(before, bytes(p, b, t))
        assertThrows(IllegalStateException::class.java) { store.installPreparedActive(cancelled) }
        val failing = ProjectStore(root, temp.root, activeReplace = { _, _ -> throw java.io.IOException("install error") })
        var stage: File? = null
        val error = assertThrows(java.io.IOException::class.java) {
            failing.prepareActive(target).use { prepared -> stage = prepared.stage; failing.installPreparedActive(prepared) }
        }
        assertEquals("install error", error.message); assertFalse(stage!!.exists()); assertEquals(before, bytes(p, b, t))
        assertEquals(old, store.activeId())
    }

    @Test fun changesAfterPreparationAreRejectedAndStageIsCleaned() {
        val root = File(temp.root, "projects"); val store = ProjectStore(root, temp.root)
        val old = store.newId(); val external = store.newId(); val target = store.newId()
        listOf(old, external, target).forEach { store.save(it, config()) }; store.setActive(old)
        val p = File(root, "active.txt"); val b = File(root, "active.txt.bak").apply { writeText("backup") }
        val t = File(root, "active.txt.tmp").apply { writeText("temp") }; val backup = b.readBytes(); val tmp = t.readBytes()
        var stage: File? = null
        val failure = assertThrows(IllegalStateException::class.java) {
            store.prepareActive(target).use { prepared ->
                stage = prepared.stage; p.writeText(external); store.installPreparedActive(prepared)
            }
        }
        assertTrue(failure.message.orEmpty().contains("cambió")); assertEquals(external, p.readText())
        assertArrayEquals(backup, b.readBytes()); assertArrayEquals(tmp, t.readBytes()); assertFalse(stage!!.exists())
    }

    @Test fun failedPreviousProjectSaveAfterPreparationDoesNotInstallSelection() {
        val root = File(temp.root, "projects"); val store = ProjectStore(root, temp.root)
        val old = store.newId(); val target = store.newId(); store.save(old, config()); store.save(target, config()); store.setActive(old)
        val p = File(root, "active.txt"); val b = File(root, "active.txt.bak").apply { writeText("backup") }
        val t = File(root, "active.txt.tmp").apply { writeText("temp") }; val before = bytes(p, b, t)
        val oldPrimary = File(root, "$old/project.json"); val original = oldPrimary.readBytes(); var stage: File? = null
        val error = assertThrows(IllegalStateException::class.java) {
            store.prepareActive(target).use { prepared ->
                stage = prepared.stage
                File(root, "$old/project.json.tmp").writeText("interrupted project write")
                store.save(old, config().apply { addProperty("modTitle", "pending") })
                store.installPreparedActive(prepared)
            }
        }
        assertTrue(error.message.orEmpty().contains("temporal")); assertArrayEquals(original, oldPrimary.readBytes())
        assertEquals(before, bytes(p, b, t)); assertFalse(stage!!.exists()); assertEquals(old, p.readText())
    }

}
