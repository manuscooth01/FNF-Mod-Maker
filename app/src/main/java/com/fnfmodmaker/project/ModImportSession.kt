package com.fnfmodmaker.project

import com.google.gson.JsonObject
import java.io.File

/** Retains the published id when opening fails, so a retry never republishes consumed staging. */
internal class ModImportSession(private val stage: File, private val source: JsonObject) {
    var publishedId: String? = null
        private set

    data class Opened<T>(val id: String, val config: T, val projects: List<ProjectSummary>, val record: ProjectRecord)

    // Caller serializes this entire operation, including applying Opened to the UI, with projectMutex.
    fun <T> publishAndOpen(
        store: ProjectStore,
        oldId: String,
        oldConfig: JsonObject,
        expectedVersion: ProjectVersion,
        onOldSaved: (ProjectVersion) -> Unit,
        validate: (JsonObject) -> Unit,
        parse: (JsonObject) -> T
    ): Opened<T> {
        val id = publishedId ?: store.publishModImport(stage, source, validate).also { publishedId = it }
        val record = store.load(id)
        validate(record.config)
        val config = parse(record.config)
        val projects = store.list()
        store.prepareActive(id).use { selection ->
            // Check even a no-op against the version held by the old session.
            val version = store.save(oldId, oldConfig, expectedVersion)
            // The old session must retain this version even if installation fails.
            onOldSaved(version)
            store.installPreparedActive(selection)
        }
        return Opened(id, config, projects, record)
    }
}
