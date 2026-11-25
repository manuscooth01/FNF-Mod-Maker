package com.fnfmodmaker.project

import com.google.gson.JsonObject
import java.io.File

/** Retains the published id when opening fails, so a retry never republishes consumed staging. */
internal class ModImportSession(private val stage: File, private val source: JsonObject) {
    var publishedId: String? = null
        private set

    data class Opened<T>(val id: String, val config: T, val projects: List<ProjectSummary>)

    // Caller serializes this entire operation, including applying Opened to the UI, with projectMutex.
    fun <T> publishAndOpen(
        store: ProjectStore,
        oldId: String,
        oldConfig: JsonObject,
        validate: (JsonObject) -> Unit,
        parse: (JsonObject) -> T
    ): Opened<T> {
        val id = publishedId ?: store.publishModImport(stage, source, validate).also { publishedId = it }
        val record = store.load(id)
        validate(record.config)
        val config = parse(record.config)
        val projects = store.list()
        // Do not alter the old snapshot if validation or opening the import fails.
        if (store.load(oldId).config != oldConfig) store.save(oldId, oldConfig)
        // This must be the final disk operation that can fail before applying the opened project.
        store.setActive(id)
        return Opened(id, config, projects)
    }
}
