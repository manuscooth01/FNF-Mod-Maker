package com.fnfmodmaker.project

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.util.UUID

/** Internal projects only. ZIP mods are deliberately not handled by this class. */
internal data class ProjectRecord(val id: String, val config: JsonObject, val updatedAt: Long)
internal data class ProjectSummary(val id: String, val title: String, val updatedAt: Long, val problem: String? = null)
internal data class CopiedAssets(val config: JsonObject, val missing: List<String>)

/** Replace via same-directory renames. Keep the last successful version for recovery. */
internal class SafeFile(private val file: File) {
    private val temp = File(file.parentFile, file.name + ".tmp")
    private val backup = File(file.parentFile, file.name + ".bak")

    fun write(text: String) {
        check(file.parentFile.isDirectory || file.parentFile.mkdirs()) { "No se pudo crear la carpeta de guardado." }
        if (!file.exists() && backup.exists()) check(backup.renameTo(file)) { "No se pudo recuperar el guardado anterior." }
        temp.outputStream().use { stream -> stream.write(text.toByteArray(Charsets.UTF_8)); stream.fd.sync() }
        if (file.exists()) {
            check(!backup.exists() || backup.delete()) { "No se pudo actualizar la copia de seguridad." }
            check(file.renameTo(backup)) { "No se pudo proteger el guardado anterior." }
        }
        if (!temp.renameTo(file)) {
            if (backup.exists()) backup.renameTo(file)
            temp.delete()
            error("No se pudo completar el guardado; se conservó la copia anterior.")
        }
    }

    fun <T> read(parse: (String) -> T): T {
        var primaryError: Exception? = null
        if (file.exists()) try { return parse(file.readText(Charsets.UTF_8)) } catch (e: Exception) { primaryError = e }
        if (backup.exists()) try {
            val text = backup.readText(Charsets.UTF_8)
            val parsed = parse(text)
            // Restore before the next write, so a corrupt primary cannot replace the good backup.
            temp.outputStream().use { stream -> stream.write(text.toByteArray(Charsets.UTF_8)); stream.fd.sync() }
            check(temp.renameTo(file)) { "No se pudo restaurar la copia anterior." }
            return parsed
        } catch (e: Exception) { if (primaryError == null) primaryError = e }
        throw IllegalStateException("No se pudo leer el proyecto ni su copia anterior.", primaryError)
    }
    fun exists(): Boolean = file.exists() || backup.exists()
}

internal class ProjectStore(private val root: File, private val permittedAssetRoot: File) {
    private val idPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    fun newId(): String = UUID.randomUUID().toString()
    private fun dir(id: String): File {
        require(idPattern.matches(id)) { "Identificador de proyecto inválido." }
        return File(root, id)
    }
    fun assets(id: String): File = File(dir(id), "assets").also { check(it.isDirectory || it.mkdirs()) { "No se pudo crear la carpeta de recursos." } }
    private fun projectFile(id: String) = SafeFile(File(dir(id), "project.json"))
    private fun activeFile() = SafeFile(File(root, "active.txt"))
    fun activeId(): String? = if (activeFile().exists()) activeFile().read { text -> text.trim().also { require(idPattern.matches(it)) } } else null
    fun setActive(id: String) { require(projectFile(id).exists()); activeFile().write(id) }
    fun save(id: String, config: JsonObject) {
        val wrapper = JsonObject().apply {
            addProperty("version", 1); addProperty("id", id); addProperty("updatedAt", System.currentTimeMillis()); add("config", config)
        }
        projectFile(id).write(wrapper.toString())
    }
    fun load(id: String): ProjectRecord = projectFile(id).read { raw ->
        val doc = JsonParser.parseString(raw).asJsonObject
        require(doc.get("version")?.asInt == 1) { "Versión de proyecto no compatible." }
        require(doc.get("id")?.asString == id) { "El proyecto no coincide con su carpeta." }
        ProjectRecord(id, doc.getAsJsonObject("config") ?: error("Falta la configuración."), doc.get("updatedAt")?.asLong ?: 0L)
    }
    fun list(): List<ProjectSummary> = root.listFiles().orEmpty().filter { it.isDirectory && idPattern.matches(it.name) && projectFile(it.name).exists() }.map { folder ->
        runCatching { load(folder.name) }.fold({ record ->
            ProjectSummary(record.id, record.config.get("modTitle")?.asString?.takeIf { it.isNotBlank() } ?: "Proyecto sin nombre", record.updatedAt)
        }, { ProjectSummary(folder.name, "Proyecto no legible", folder.lastModified(), "No se pudo abrir; sus archivos se conservan.") })
    }.sortedByDescending { it.updatedAt }

    /** Copy all recognized PickedImage/PickedAudio objects. Missing legacy assets remain referenced and are reported. */
    fun copyAssets(config: JsonObject, id: String): CopiedAssets {
        val result = config.deepCopy()
        val copied = mutableMapOf<String, String>()
        val missing = linkedSetOf<String>()
        val appRoot = permittedAssetRoot.canonicalFile
        val targetDir = assets(id).canonicalFile
        fun visit(node: com.google.gson.JsonElement) {
            if (node.isJsonArray) { node.asJsonArray.forEach(::visit); return }
            if (!node.isJsonObject) return
            val obj = node.asJsonObject
            if (obj.has("path") && obj.has("displayName") && obj.has("uri") && obj.get("path").isJsonPrimitive) {
                val old = obj.get("path").asString
                val source = File(old).canonicalFile
                if (!(source.path == appRoot.path || source.path.startsWith(appRoot.path + File.separator)) || !source.isFile) {
                    missing.add(obj.get("displayName").asString); return
                }
                val destination = copied.getOrPut(source.path) {
                    if (source.parentFile == targetDir) source.path else {
                        val extension = source.extension.filter { it.isLetterOrDigit() }.take(10)
                        val out = File.createTempFile("asset_", if (extension.isEmpty()) ".dat" else ".$extension", targetDir)
                        try { source.inputStream().use { input -> out.outputStream().use { input.copyTo(it) } } }
                        catch (e: Exception) { out.delete(); throw e }
                        out.absolutePath
                    }
                }
                obj.addProperty("path", destination)
                return
            }
            obj.entrySet().forEach { visit(it.value) }
        }
        visit(result)
        return CopiedAssets(result, missing.toList())
    }
}
