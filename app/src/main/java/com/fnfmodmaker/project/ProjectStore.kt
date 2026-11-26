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
    /** Read-only asset location, without creating directories during validation/export. */
    fun assetsLocation(id: String): File = File(dir(id), "assets")
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
            val problem = runCatching { requireSupportedChartSchema(record.config) }.exceptionOrNull()?.let {
                "Charts incompatibles: ${it.message}"
            }
            ProjectSummary(record.id, record.config.get("modTitle")?.asString?.takeIf { it.isNotBlank() } ?: "Proyecto sin nombre", record.updatedAt, problem)
        }, { ProjectSummary(folder.name, "Proyecto no legible", folder.lastModified(), "No se pudo abrir; sus archivos se conservan.") })
    }.sortedByDescending { it.updatedAt }

    /** Hidden staging lives beside final projects: publication is one same-filesystem rename. */
    fun newImportStage(): File = File(root, ".pending-${newId()}")
    fun discardImportStage(stage: File) {
        require(stage.parentFile.canonicalFile == root.canonicalFile && stage.name.startsWith(".pending-") && idPattern.matches(stage.name.removePrefix(".pending-")))
        stage.deleteRecursively()
    }
    fun clearAbandonedImports() {
        root.listFiles().orEmpty().filter { it.name.startsWith(".pending-") && idPattern.matches(it.name.removePrefix(".pending-")) }.forEach { discardImportStage(it) }
    }
    fun publishImport(prepared: PreparedPortable, portable: PortableProject, validate: (JsonObject) -> Unit): String {
        val stage = prepared.directory
        require(stage.parentFile.canonicalFile == root.canonicalFile && stage.name.startsWith(".pending-") && idPattern.matches(stage.name.removePrefix(".pending-")) && stage.isDirectory)
        val id = newId()
        val finalDir = dir(id)
        require(!finalDir.exists()) { "No se pudo crear un identificador único." }
        val config = portable.materialize(prepared, finalDir)
        validate(config)
        val wrapper = JsonObject().apply {
            addProperty("version", 1); addProperty("id", id); addProperty("updatedAt", System.currentTimeMillis()); add("config", config)
        }
        SafeFile(File(stage, "project.json")).write(wrapper.toString())
        check(stage.renameTo(finalDir)) { "No se pudo incorporar el proyecto; los proyectos existentes no cambiaron." }
        return id
    }

    /** Publish an already converted mod as a new project; all references must stay in its staged assets. */
    fun publishModImport(stage: File, source: JsonObject, validate: (JsonObject) -> Unit): String {
        require(stage.parentFile.canonicalFile == root.canonicalFile && stage.name.startsWith(".pending-") && idPattern.matches(stage.name.removePrefix(".pending-")) && stage.isDirectory)
        val id = newId(); val finalDir = dir(id)
        require(!finalDir.exists())
        val output = source.deepCopy()
        val canonicalStage = stage.canonicalFile
        // Stage and assets themselves must not be links to a different directory.
        require(canonicalStage.parentFile == root.canonicalFile && canonicalStage.name == stage.name) { "Carpeta temporal insegura." }
        val absoluteAssets = File(stage, "assets").absoluteFile
        val stagedAssets = absoluteAssets.canonicalFile
        require(stagedAssets.parentFile == canonicalStage && stagedAssets.name == "assets" && stagedAssets.isDirectory) { "Carpeta de recursos temporal insegura o ausente." }

        // File parent traversal compares path components without requiring java.nio.file (Android API 26).
        fun isDescendant(file: File, directory: File): Boolean {
            var parent = file.parentFile
            while (parent != null) {
                if (parent == directory) return true
                parent = parent.parentFile
            }
            return false
        }
        visitProjectAssets(output) { obj ->
            val nameValue = obj.get("displayName")
            val name = if (nameValue?.isJsonPrimitive == true && nameValue.asJsonPrimitive.isString)
                nameValue.asString.filter { !it.isISOControl() }.take(120).ifBlank { "sin nombre" } else "sin nombre"
            val pathValue = obj.get("path")
            require(pathValue?.isJsonPrimitive == true && pathValue.asJsonPrimitive.isString) { "Recurso '$name': ruta insegura o inválida." }
            val text = pathValue.asString
            require(text.isNotEmpty() && '\u0000' !in text && '\\' !in text &&
                text.split('/').none { it == "." || it == ".." }) { "Recurso '$name': ruta insegura (traversal o componentes inválidos)." }
            val path = File(text)
            require(path.isAbsolute) { "Recurso '$name': ruta insegura (debe ser absoluta)." }
            val absolute = path.absoluteFile
            // Accept the stage's own spelling and its canonical spelling, but not arbitrary
            // external aliases. Reject an external path even if a symlink leads inside.
            require(isDescendant(absolute, absoluteAssets) || isDescendant(absolute, stagedAssets)) {
                "Recurso '$name': ruta externa a los recursos temporales."
            }
            val canonical = try { path.canonicalFile } catch (e: java.io.IOException) {
                throw IllegalArgumentException("Recurso '$name': no se pudo resolver la ruta segura.", e)
            }
            require(isDescendant(canonical, stagedAssets)) { "Recurso '$name': ruta insegura (un enlace sale de los recursos temporales)." }
            require(canonical.isFile) { "Recurso '$name': archivo ausente o no es un archivo regular." }
            // Relocate the resolved target, not an inner symlink that might break on rename.
            val relative = canonical.relativeTo(canonicalStage).invariantSeparatorsPath
            // Store a canonical spelling so downstream export checks do not mistake an
            // Android data-directory alias for a symlink inside the project's assets.
            obj.addProperty("path", File(finalDir, relative).canonicalPath)
            obj.addProperty("uri", "")

        }
        validate(output)
        val wrapper = JsonObject().apply { addProperty("version", 1); addProperty("id", id); addProperty("updatedAt", System.currentTimeMillis()); add("config", output) }
        SafeFile(File(stage, "project.json")).write(wrapper.toString())
        check(stage.renameTo(finalDir)) { "No se pudo publicar el proyecto importado." }
        return id
    }

    /** Copy known assets; reject malformed or missing references before publishing a copy.
     *  When [sourceAssets] is provided, files inside it keep their relative path in the copy
     *  (Asset Library keeps library/<packId>/<jerarquía> instead of being flattened). */
    fun copyAssets(config: JsonObject, id: String, sourceAssets: File? = null): CopiedAssets {
        val result = config.deepCopy()
        val refs = mutableListOf<JsonObject>()
        visitProjectAssets(result) { refs.add(it) }
        val appRoot = permittedAssetRoot.canonicalFile
        val sources = refs.map { obj ->
            val text = obj.get("path").asString
            val file = File(text)
            require(file.isAbsolute && '\u0000' !in text && '\\' !in text && text.split('/').none { it == "." || it == ".." }) {
                "Recurso ${obj.get("displayName").asString}: ruta inválida."
            }
            val source = file.canonicalFile
            require(source.path.startsWith(appRoot.path + File.separator) && source.isFile) {
                "Falta el recurso ${obj.get("displayName").asString} o está fuera del almacenamiento de la app."
            }
            source
        }
        val projectDir = dir(id).canonicalFile
        require(projectDir.parentFile == root.canonicalFile && projectDir.name == id) { "Carpeta de proyecto insegura." }
        val targetDir = assets(id).canonicalFile
        require(targetDir.parentFile == projectDir && targetDir.name == "assets") { "Carpeta de recursos insegura." }
        val copied = mutableMapOf<String, String>()
        val created = mutableListOf<File>()
        val sourceRoot = sourceAssets?.canonicalFile?.takeIf { it.isDirectory }
        fun relativeTarget(source: File): File? {
            val root = sourceRoot ?: return null
            val canonical = source.canonicalFile
            if (!canonical.path.startsWith(root.path + File.separator)) return null
            return File(targetDir, canonical.relativeTo(root).path)
        }
        try {
            refs.zip(sources).forEach { (obj, source) ->
                val destination = copied.getOrPut(source.path) {
                    // Already inside the target assets tree (e.g. Asset Library hierarchy
                    // under assets/library/<packId>/): keep it in place with its structure.
                    if (source.canonicalPath.startsWith(targetDir.canonicalPath + File.separator)) source.path else {
                        val extension = source.extension.filter { it.isLetterOrDigit() }.take(10)
                        val relative = relativeTarget(source)
                        if (relative != null) {
                            // Library layout: copy to the same relative path and reject a
                            // genuine collision instead of silently overwriting it.
                            require(!relative.exists()) { "Colisión al copiar el recurso ${source.name}." }
                            created.add(relative)
                            check(relative.parentFile.isDirectory || relative.parentFile.mkdirs()) { "No se pudo crear la carpeta de recursos." }
                            source.inputStream().use { input -> relative.outputStream().use { input.copyTo(it) } }
                            relative.absolutePath
                        } else {
                            // Legacy flat copy: createTempFile already creates the empty file.
                            val out = File.createTempFile("asset_", if (extension.isEmpty()) ".dat" else ".$extension", targetDir)
                            created.add(out)
                            source.inputStream().use { input -> out.outputStream().use { input.copyTo(it) } }
                            out.absolutePath
                        }
                    }
                }
                obj.addProperty("path", destination)
            }
            return CopiedAssets(result, emptyList())
        } catch (e: Throwable) {
            created.forEach { it.delete() }
            throw e
        }
    }

}
