package com.fnfmodmaker.project

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.util.UUID

/** Internal projects only. ZIP mods are deliberately not handled by this class. */
internal data class ProjectVersion(val sha256: String)
internal class ProjectVersionConflict : IllegalStateException("Conflicto: el proyecto cambió en otra sesión; no se guardó el snapshot.")
internal data class ProjectRecord(val id: String, val config: JsonObject, val updatedAt: Long, val version: ProjectVersion? = null)
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

    fun <T> read(parse: (String) -> T): T = read(true, parse)

    fun <T> read(recover: Boolean, parse: (String) -> T): T {
        var primaryError: Exception? = null
        if (file.exists()) try { return parse(file.readText(Charsets.UTF_8)) } catch (e: Exception) { primaryError = e }
        if (backup.exists()) try {
            val text = backup.readText(Charsets.UTF_8)
            val parsed = parse(text)
            // Listing and inspection never restore or touch temporary files.
            if (recover) {
                temp.outputStream().use { stream -> stream.write(text.toByteArray(Charsets.UTF_8)); stream.fd.sync() }
                check(temp.renameTo(file)) { "No se pudo restaurar la copia anterior." }
            }
            return parsed
        } catch (e: Exception) { if (primaryError == null) primaryError = e }
        throw IllegalStateException("No se pudo leer el proyecto ni su copia anterior.", primaryError)
    }
    fun exists(): Boolean = file.exists() || backup.exists()
}

internal class ProjectStore(
    private val root: File,
    private val permittedAssetRoot: File,
    private val recoveryInstall: (File, File) -> Unit = { source, destination ->
        // link(2) is atomic and fails when the destination exists; renameTo is not.
        if (android.os.Build.VERSION.SDK_INT >= 26)
            java.nio.file.Files.createLink(destination.toPath(), source.toPath())
        else android.system.Os.link(source.absolutePath, destination.absolutePath)
    },
    private val beforeFileLock: ((String) -> Unit)? = null,
    private val activeTempWrite: ((File, String) -> Unit)? = null,
    private val activeReplace: ((File, File) -> Unit)? = null,
    private val afterActivePrepared: (() -> Unit)? = null,
    private val beforeSaveWrite: ((String) -> Unit)? = null,
    private val afterInspection: (() -> Unit)? = null
) {
    companion object { private val projectLocks = java.util.concurrent.ConcurrentHashMap<String, Any>() }
    private fun <T> withProjectLock(id: String, action: () -> T): T {
        val key = dir(id).canonicalPath
        return synchronized(projectLocks.computeIfAbsent(key) { Any() }) { action() }
    }
    // Advisory OS locks are shared by cooperating processes. Lock files are persistent:
    // removing them would allow two processes to lock different inodes for the same id.
    private fun <T> withFileLock(name: String, action: () -> T): T {
        val lockDir = File(root, ".project-locks")
        check(root.isDirectory || root.mkdirs()) { "No se pudo crear el directorio de proyectos." }
        check(lockDir.isDirectory || lockDir.mkdirs()) { "No se pudo crear la coordinación de proyectos." }
        check(lockDir.canonicalFile.parentFile == root.canonicalFile && lockDir.canonicalFile.name == lockDir.name) { "Directorio de bloqueo inseguro." }
        val file = File(lockDir, "$name.lock")
        check(file.canonicalFile.parentFile == lockDir.canonicalFile && file.canonicalFile.name == file.name) { "Archivo de bloqueo inseguro." }
        return java.io.RandomAccessFile(file, "rw").use { handle ->
            handle.channel.use { channel ->
                beforeFileLock?.invoke(name)
                channel.lock().use { action() }
            }
        }
    }
    private fun <T> withMutationLock(id: String, action: () -> T): T =
        withProjectLock(id) { withFileLock(id, action) }
    private fun <T> withActiveLock(action: () -> T): T {
        val key = File(root, ".project-locks/active.lock").canonicalPath
        return synchronized(projectLocks.computeIfAbsent(key) { Any() }) { withFileLock("active", action) }
    }
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
    private fun activeFile() = File(root, "active.txt")
    private fun readActiveCandidate(file: File): List<Byte>? {
        if (!file.exists()) {
            val names = if (root.exists()) root.list() ?: error("No se pudo inspeccionar la selección activa.") else null
            check(names == null || file.name !in names) { "No se pudo leer ${file.name}." }
            return null
        }
        check(file.canonicalFile.parentFile == root.canonicalFile && file.canonicalFile.name == file.name) {
            "Archivo de selección inseguro: ${file.name}."
        }
        return try { file.readBytes().toList() }
        catch (e: Exception) { throw IllegalStateException("No se pudo leer ${file.name}.", e) }
    }
    private fun activeSnapshot() = listOf(activeFile(), File(root, "active.txt.bak"), File(root, "active.txt.tmp"))
        .map(::readActiveCandidate)
    fun activeId(): String? {
        val bytes = readActiveCandidate(activeFile()) ?: return null
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString()
        } catch (e: Exception) { throw IllegalStateException("Identificador activo inválido.", e) }
        check(idPattern.matches(text)) { "Identificador activo inválido." }
        // The record and the presence of a primary are checked under the same project lock.
        // A backup-only record has no version and cannot be selected as the active project.
        check(load(text).version != null) { "El proyecto activo no existe o requiere recuperación manual." }
        return text
    }
    private fun installActive(source: File, target: File) {
        if (activeReplace != null) { activeReplace.invoke(source, target); return }
        // Android's Os.rename is POSIX rename(2): a same-filesystem replacement
        // is atomic and an error leaves the destination in place. On host JVMs,
        // request ATOMIC_MOVE explicitly and never fall back to a plain move.
        if (System.getProperty("java.vm.name") == "Dalvik") {
            android.system.Os.rename(source.absolutePath, target.absolutePath)
        } else {
            java.nio.file.Files.move(source.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }
    /** A caller owns this handle and must close it on success, failure or cancellation.
     * It holds no lock or file descriptor. Only the uniquely created stage is cleaned up. */
    internal class PreparedActive internal constructor(
        internal val owner: ProjectStore,
        internal val id: String,
        internal val stage: File,
        internal val activeBefore: List<List<Byte>?>,
        internal val projectBefore: List<Pair<String, List<Byte>>>
    ) : java.io.Closeable {
        internal var closed = false
        override fun close() {
            if (closed) return
            closed = true
            // After successful rename the stage does not exist; no candidate is touched.
            if (stage.exists()) check(stage.delete()) { "No se pudo limpiar el temporal de selección propio: ${stage.name}." }
        }
    }
    private fun projectCandidateSnapshot(id: String) = candidates(id).map { it.kind.name to it.bytes }
    private fun prepareActiveLocked(id: String): PreparedActive {
        val before = activeSnapshot()
        val projectBefore = projectCandidateSnapshot(id)
        val staged = File.createTempFile("active-selection-", ".tmp", root)
        try {
            if (activeTempWrite != null) activeTempWrite.invoke(staged, id)
            else staged.outputStream().use { out -> out.write(id.toByteArray(Charsets.UTF_8)); out.fd.sync() }
            val expected = id.toByteArray(Charsets.UTF_8)
            check(idPattern.matches(id) && staged.readBytes().contentEquals(expected)) { "No se pudo verificar la selección activa." }
            afterActivePrepared?.invoke()
            check(activeSnapshot() == before) { "La selección activa cambió durante la preparación." }
            check(projectCandidateSnapshot(id) == projectBefore) { "El proyecto destino cambió durante la preparación." }
            return PreparedActive(this, id, staged, before, projectBefore)
        } catch (e: Throwable) {
            if (staged.exists() && !staged.delete()) e.addSuppressed(IllegalStateException("No se pudo limpiar el temporal de selección propio."))
            throw e
        }
    }
    private fun installPreparedLocked(prepared: PreparedActive) {
        check(prepared.owner === this && !prepared.closed) { "Selección preparada inválida o cerrada." }
        check(activeSnapshot() == prepared.activeBefore) { "La selección activa cambió durante la preparación." }
        check(projectCandidateSnapshot(prepared.id) == prepared.projectBefore) { "El proyecto destino cambió durante la preparación." }
        check(prepared.stage.readBytes().contentEquals(prepared.id.toByteArray(Charsets.UTF_8))) {
            "El temporal de selección cambió durante la preparación."
        }
        installActive(prepared.stage, activeFile())
    }
    private fun <T> withValidatedActiveTarget(id: String, action: () -> T): T = withProjectLock(id) {
        // Do not create a project lock artifact for an incompatible target.
        checkCandidates(id)
        withFileLock(id) {
            loadLocked(id)
            check(classify(id, File(dir(id), "project.json")).kind == CandidateKind.V1) {
                "La copia requiere recuperación manual antes de activar el proyecto."
            }
            action()
        }
    }
    fun prepareActive(id: String): PreparedActive = withValidatedActiveTarget(id) {
        withActiveLock { prepareActiveLocked(id) }
    }
    fun installPreparedActive(prepared: PreparedActive) = withValidatedActiveTarget(prepared.id) {
        withActiveLock { installPreparedLocked(prepared) }
    }
    // Classification is read-only: no fallback can hide an unreadable candidate.
    private enum class CandidateKind { ABSENT, V1, V2, FUTURE, CORRUPT, INVALID_WRAPPER, READ_ERROR }
    private data class Candidate(val file: File, val kind: CandidateKind, val bytes: List<Byte> = emptyList())
    private fun classify(id: String, file: File): Candidate {
        if (!file.exists()) {
            val parent = file.parentFile
            if (parent.exists()) {
                val names = parent.list() ?: return Candidate(file, CandidateKind.READ_ERROR)
                if (file.name in names) return Candidate(file, CandidateKind.READ_ERROR)
            }
            return Candidate(file, CandidateKind.ABSENT)
        }
        val bytes = try { file.readBytes() } catch (_: Exception) { return Candidate(file, CandidateKind.READ_ERROR) }
        val doc = try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            // Strictly walk the entire document first; Gson's tree parser otherwise
            // silently accepts duplicate keys, which can hide a schema marker.
            val checker = com.google.gson.stream.JsonReader(java.io.StringReader(text)).apply { isLenient = false }
            fun checkValue() {
                when (checker.peek()) {
                    com.google.gson.stream.JsonToken.BEGIN_OBJECT -> {
                        checker.beginObject(); val names = mutableSetOf<String>()
                        while (checker.hasNext()) { check(names.add(checker.nextName())) { "Clave JSON duplicada." }; checkValue() }
                        checker.endObject()
                    }
                    com.google.gson.stream.JsonToken.BEGIN_ARRAY -> {
                        checker.beginArray(); while (checker.hasNext()) checkValue(); checker.endArray()
                    }
                    com.google.gson.stream.JsonToken.STRING, com.google.gson.stream.JsonToken.NUMBER -> checker.nextString()
                    com.google.gson.stream.JsonToken.BOOLEAN -> checker.nextBoolean()
                    com.google.gson.stream.JsonToken.NULL -> checker.nextNull()
                    else -> error("JSON inválido.")
                }
            }
            checkValue(); check(checker.peek() == com.google.gson.stream.JsonToken.END_DOCUMENT)
            val reader = com.google.gson.stream.JsonReader(java.io.StringReader(text)).apply { isLenient = false }
            val value = com.google.gson.internal.Streams.parse(reader)
            if (reader.peek() != com.google.gson.stream.JsonToken.END_DOCUMENT) return Candidate(file, CandidateKind.CORRUPT, bytes.toList())
            if (!value.isJsonObject) return Candidate(file, CandidateKind.CORRUPT, bytes.toList())
            value.asJsonObject
        } catch (_: Exception) { return Candidate(file, CandidateKind.CORRUPT, bytes.toList()) }
        val wrappedVersion = doc.get("version")
        if (wrappedVersion?.isJsonPrimitive != true || !wrappedVersion.asJsonPrimitive.isNumber || wrappedVersion.asString != "1")
            return Candidate(file, CandidateKind.INVALID_WRAPPER, bytes.toList())
        val wrappedId = doc.get("id")
        if (wrappedId?.isJsonPrimitive != true || !wrappedId.asJsonPrimitive.isString || wrappedId.asString != id || doc.get("config")?.isJsonObject != true)
            return Candidate(file, CandidateKind.INVALID_WRAPPER, bytes.toList())
        val config = doc.getAsJsonObject("config")
        val updatedAt = doc.get("updatedAt")
        if (updatedAt != null && (updatedAt.isJsonNull || !updatedAt.isJsonPrimitive || !updatedAt.asJsonPrimitive.isNumber ||
                runCatching { updatedAt.asString.toLong() }.isFailure))
            return Candidate(file, CandidateKind.INVALID_WRAPPER, bytes.toList())
        val version = config.get("chartSchemaVersion")
        if (version?.isJsonPrimitive == true && version.asJsonPrimitive.isNumber && version.asString == "2")
            return Candidate(file, CandidateKind.V2, bytes.toList())
        if (runCatching { requireReadableProjectSchema(config) }.isFailure)
            return Candidate(file, CandidateKind.FUTURE, bytes.toList())
        // A missing schema marker alone is not evidence: an identifiable legacy field
        // must be present. Full model validation is additionally required for recovery.
        val fields = mapOf("modTitle" to "string", "songs" to "array", "charts" to "object",
            "character" to "object", "characterLibrary" to "array", "stageDraft" to "object", "assetPacks" to "array", "audio" to "object")
        val present = fields.filterKeys { config.has(it) && !config.get(it).isJsonNull }
        if (present.isEmpty() || present.any { (name, type) ->
                val node = config.get(name)
                when (type) {
                    "string" -> !node.isJsonPrimitive || !node.asJsonPrimitive.isString
                    "array" -> !node.isJsonArray
                    else -> !node.isJsonObject
                }
            }) return Candidate(file, CandidateKind.INVALID_WRAPPER, bytes.toList())
        return Candidate(file, CandidateKind.V1, bytes.toList())
    }
    private fun candidates(id: String): List<Candidate> = listOf("project.json", "project.json.bak", "project.json.tmp")
        .map { classify(id, File(dir(id), it)) }
    private fun checkCandidates(id: String): List<Candidate> {
        val result = candidates(id)
        val incompatible = result.firstOrNull { it.kind == CandidateKind.V2 || it.kind == CandidateKind.FUTURE }
        if (incompatible != null) throw IllegalArgumentException("La versión de charts no es compatible; se conservan los archivos.")
        val unsafe = result.firstOrNull { it.kind !in listOf(CandidateKind.ABSENT, CandidateKind.V1) }
        if (unsafe != null) throw IllegalStateException("Proyecto requiere recuperación manual (${unsafe.file.name}: ${unsafe.kind}); se conservan los archivos.")
        // Any existing temp may contain an interrupted write and must never be replaced.
        if (result[2].kind != CandidateKind.ABSENT)
            throw IllegalStateException("Proyecto requiere recuperación manual: existe un archivo temporal; se conservan los archivos.")
        return result
    }
    private fun stable(id: String, previous: List<Candidate>) {
        check(candidates(id) == previous) { "El proyecto cambió durante la inspección; recuperación manual necesaria." }
    }
    fun setActive(id: String) = withValidatedActiveTarget(id) {
        withActiveLock { prepareActiveLocked(id).use { installPreparedLocked(it) } }
    }
    fun save(id: String, config: JsonObject) = withProjectLock(id) {
        requireReadableProjectSchema(config)
        checkCandidates(id)
        withFileLock(id) {
            val before = checkCandidates(id)
            afterInspection?.invoke()
            stable(id, before)
            beforeSaveWrite?.invoke(id)
            check(before[0].kind != CandidateKind.ABSENT || before[1].kind == CandidateKind.ABSENT) { "La copia requiere recuperación manual antes de guardar; se conservan los archivos." }
            val wrapper = JsonObject().apply {
                addProperty("version", 1); addProperty("id", id); addProperty("updatedAt", System.currentTimeMillis()); add("config", config)
            }
            projectFile(id).write(wrapper.toString())
        }
    }
    private fun readRecord(id: String, recover: Boolean): ProjectRecord = projectFile(id).read(recover) { raw ->
        val doc = JsonParser.parseString(raw).asJsonObject
        require(doc.get("version")?.asInt == 1) { "Versión de proyecto no compatible." }
        require(doc.get("id")?.asString == id) { "El proyecto no coincide con su carpeta." }
        ProjectRecord(id, doc.getAsJsonObject("config") ?: error("Falta la configuración."), doc.get("updatedAt")?.asLong ?: 0L)
    }
    private fun sha256(bytes: ByteArray): ProjectVersion = ProjectVersion(
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) })

    // Lock order: project JVM monitor -> per-project advisory file lock -> active lock.
    // Callers already holding the per-project file lock must use loadLocked, not load.
    fun load(id: String): ProjectRecord = withProjectLock(id) {
        withFileLock(id) { loadLocked(id) }
    }
    private fun loadLocked(id: String): ProjectRecord {
        val before = checkCandidates(id)
        afterInspection?.invoke()
        val primary = before[0]
        val backup = before[1]
        val chosen = if (primary.kind == CandidateKind.ABSENT && backup.kind == CandidateKind.V1) backup else primary
        check(chosen.kind == CandidateKind.V1) { "El proyecto no existe o requiere recuperación manual." }
        // Parse the identical bytes previously classified, not a second file read.
        val bytes = chosen.bytes.toByteArray()
        val doc = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
        val config = doc.getAsJsonObject("config") ?: error("Falta la configuración.")
        requireReadableProjectSchema(config)
        if (chosen === backup) {
            val validatorGson = com.google.gson.GsonBuilder().registerTypeAdapter(android.net.Uri::class.java,
                object : com.google.gson.TypeAdapter<android.net.Uri>() {
                    override fun write(out: com.google.gson.stream.JsonWriter, value: android.net.Uri?) { out.nullValue() }
                    override fun read(input: com.google.gson.stream.JsonReader): android.net.Uri? { input.skipValue(); return null }
                }.nullSafe()).create()
            try { ProjectConfigValidator(validatorGson).validate(config) }
            catch (e: Exception) { throw IllegalStateException("La copia requiere recuperación manual; se conservan los archivos.", e) }
        }
        stable(id, before)
        return ProjectRecord(id, config, doc.get("updatedAt")?.asLong ?: 0L,
            if (chosen === primary) sha256(bytes) else null)
    }
    /** Explicit create: never overwrite an existing candidate or recover a backup. */
    fun create(id: String, config: JsonObject): ProjectVersion = withProjectLock(id) {
        requireReadableProjectSchema(config)
        withFileLock(id) {
            val before = checkCandidates(id)
            check(before.all { it.kind == CandidateKind.ABSENT }) { "El proyecto ya existe; no se creó." }
            stable(id, before)
            val wrapper = JsonObject().apply {
                addProperty("version", 1); addProperty("id", id); addProperty("updatedAt", System.currentTimeMillis()); add("config", config)
            }
            val text = wrapper.toString()
            projectFile(id).write(text)
            val written = File(dir(id), "project.json").readBytes()
            check(written.contentEquals(text.toByteArray(Charsets.UTF_8))) { "No se pudo verificar el proyecto creado." }
            sha256(written)
        }
    }
    /** Compare the caller's original exact-byte version before touching any candidate. */
    fun save(id: String, config: JsonObject, expectedVersion: ProjectVersion): ProjectVersion = withProjectLock(id) {
        requireReadableProjectSchema(config)
        withFileLock(id) {
            val raw = candidates(id)
            afterInspection?.invoke()
            val primary = raw[0]
            if (primary.kind != CandidateKind.V1 || sha256(primary.bytes.toByteArray()) != expectedVersion)
                throw ProjectVersionConflict()
            val before = checkCandidates(id)
            check(before == raw) { "El proyecto cambió durante la inspección; recuperación manual necesaria." }
            stable(id, before)
            val doc = JsonParser.parseString(primary.bytes.toByteArray().toString(Charsets.UTF_8)).asJsonObject
            if (doc.getAsJsonObject("config") == config) return@withFileLock expectedVersion
            beforeSaveWrite?.invoke(id)
            val wrapper = JsonObject().apply {
                addProperty("version", 1); addProperty("id", id); addProperty("updatedAt", System.currentTimeMillis()); add("config", config)
            }
            val text = wrapper.toString()
            projectFile(id).write(text)
            val written = File(dir(id), "project.json").readBytes()
            check(written.contentEquals(text.toByteArray(Charsets.UTF_8))) { "No se pudo verificar el proyecto guardado." }
            sha256(written)
        }
    }
    /** Explicitly requested by the user. Never moves or removes the backup. */
    fun recoverBackup(id: String) = withProjectLock(id) {
        val initial = checkCandidates(id)
        check(initial[0].kind == CandidateKind.ABSENT && initial[1].kind == CandidateKind.V1) {
            "No hay una copia V1 recuperable con archivo principal ausente."
        }
        withFileLock(id) {
            val before = checkCandidates(id)
            check(before == initial && before[0].kind == CandidateKind.ABSENT && before[1].kind == CandidateKind.V1) {
                "El proyecto cambió; no se recuperó ningún archivo."
            }
            val raw = before[1].bytes.toByteArray()
            val doc = JsonParser.parseString(raw.toString(Charsets.UTF_8)).asJsonObject
            val config = doc.getAsJsonObject("config")
            requireReadableProjectSchema(config)
            val gson = com.google.gson.GsonBuilder().registerTypeAdapter(android.net.Uri::class.java,
                object : com.google.gson.TypeAdapter<android.net.Uri>() {
                    override fun write(out: com.google.gson.stream.JsonWriter, value: android.net.Uri?) { out.nullValue() }
                    override fun read(input: com.google.gson.stream.JsonReader): android.net.Uri? { input.skipValue(); return null }
                }.nullSafe()).create()
            ProjectConfigValidator(gson).validate(config)
            val appRoot = permittedAssetRoot.canonicalFile
            visitProjectAssets(config) { asset ->
                val path = asset.get("path").asString
                require(File(path).isAbsolute && '\u0000' !in path && '\\' !in path && path.split('/').none { it == "." || it == ".." }) { "Ruta de recurso inválida." }
                val resource = File(path).canonicalFile
                require(resource.path.startsWith(appRoot.path + File.separator) && resource.isFile && resource.canRead()) {
                    "Falta un recurso o no es accesible: ${asset.get("displayName").asString}."
                }
            }
            config.get("assetPacks")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { pack ->
                pack.asJsonObject.get("assets")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { entry ->
                    val item = entry.asJsonObject
                    val fileNode = item.get("file")?.takeIf { it.isJsonObject }?.asJsonObject
                    if (fileNode != null) {
                        val resource = File(fileNode.get("path").asString)
                        item.get("sizeBytes")?.takeUnless { it.isJsonNull }?.let { require(resource.length() == it.asLong) { "Tamaño de recurso incorrecto." } }
                        item.get("sha256")?.takeUnless { it.isJsonNull }?.let { expected ->
                            val digest = java.security.MessageDigest.getInstance("SHA-256")
                            resource.inputStream().use { stream ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) { val n = stream.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
                            }
                            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                            require(actual == expected.asString) { "Checksum de recurso incorrecto." }
                        }
                    }
                }
            }
            afterInspection?.invoke()
            stable(id, before)
            val temp = File.createTempFile("project.recovery-", ".tmp", dir(id))
            temp.outputStream().use { out -> out.write(raw); out.fd.sync() }
            check(temp.readBytes().contentEquals(raw)) { "La copia temporal no coincide con el backup." }
            check(classify(id, temp).kind == CandidateKind.V1) { "La copia temporal no es un proyecto V1 válido." }
            stable(id, before)
            val primary = File(dir(id), "project.json")
            recoveryInstall(temp, primary) // atomic no-replace; failure leaves the temp and backup intact
            temp.delete() // best effort; it is our own verified temporary, never the backup
        }
    }
    fun list(): List<ProjectSummary> = root.listFiles().orEmpty().filter { it.isDirectory && idPattern.matches(it.name) && projectFile(it.name).exists() }.map { folder ->
        runCatching { withProjectLock(folder.name) {
            val inspected = checkCandidates(folder.name)
            val record = readRecord(folder.name, false)
            val problem = if (inspected[0].kind == CandidateKind.ABSENT)
                "Recuperación manual necesaria: solo existe la copia anterior." else null
            ProjectSummary(record.id, record.config.get("modTitle")?.asString?.takeIf { it.isNotBlank() } ?: "Proyecto sin nombre", record.updatedAt, problem)
        } }.getOrElse {
            val incompatible = it is IllegalArgumentException && it.message.orEmpty().contains("no es compatible")
            ProjectSummary(folder.name, "Proyecto no legible", folder.lastModified(),
                if (incompatible) "Charts incompatibles: ${it.message}" else "Recuperación manual necesaria: ${it.message}. Los archivos se conservan.")
        }
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
        return withMutationLock(id) {
        val finalDir = dir(id)
        require(!finalDir.exists()) { "No se pudo crear un identificador único." }
        requireReadableProjectSchema(prepared.config)
        val config = portable.materialize(prepared, finalDir)
        requireReadableProjectSchema(config)
        validate(config)
        val wrapper = JsonObject().apply {
            addProperty("version", 1); addProperty("id", id); addProperty("updatedAt", System.currentTimeMillis()); add("config", config)
        }
        SafeFile(File(stage, "project.json")).write(wrapper.toString())
        check(stage.renameTo(finalDir)) { "No se pudo incorporar el proyecto; los proyectos existentes no cambiaron." }
        id
        }
    }

    /** Publish an already converted mod as a new project; all references must stay in its staged assets. */
    fun publishModImport(stage: File, source: JsonObject, validate: (JsonObject) -> Unit): String {
        require(stage.parentFile.canonicalFile == root.canonicalFile && stage.name.startsWith(".pending-") && idPattern.matches(stage.name.removePrefix(".pending-")) && stage.isDirectory)
        val id = newId()
        return withMutationLock(id) {
        val finalDir = dir(id)
        require(!finalDir.exists())
        requireReadableProjectSchema(source)
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
        id
        }
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
