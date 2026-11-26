package com.fnfmodmaker.model

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.annotations.JsonAdapter
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

/** Individual chart identity: BOTH is a project destination, never a chart engine. */
enum class ChartEngine { PSYCH, VSLICE }

/** Stable identity of one editable chart: song + engine + difficulty. */
data class IndependentChartKey(
    val songId: String,
    val engine: ChartEngine,
    val difficulty: String
) {
    init {
        require(songId.matches(Regex("[A-Za-z0-9_-]{1,80}")) && isValidDifficulty(difficulty)) {
            "Identidad de chart inválida."
        }
    }
}

/**
 * A media reference that can represent either an image or an audio original.
 * Paths are archive paths within the project, never local file paths.
 * The `kind` field distinguishes image vs audio without guessing from property names.
 */
data class ProjectMediaReference(
    val uri: String,
    val displayName: String,
    val path: String,
    val kind: String
) {
    init {
        require(kind in setOf("image", "audio")) { "Tipo de medio inválido: $kind." }
        require(path.isNotEmpty() && path.split('/').none { it == "." || it == ".." }) {
            "Ruta de medio insegura: $path."
        }
    }
}

/**
 * An original file preserved from the legacy project.
 *
 * Either [asset] is set (a managed media file: image/audio) or [contentJson] is set
 * (an opaque JSON document such as V-Slice metadata or chart JSON). Never both.
 * Documents are never classified as media and never interpreted as paths.
 */
data class IndependentOriginal(
    val originalPath: String,
    val asset: ProjectMediaReference? = null,
    val contentJson: String = "",
    val editable: Boolean = false
) {
    init {
        require((asset == null) xor contentJson.isNotEmpty()) {
            "Un original debe ser medio o documento opaco, no ambos ni ninguno."
        }
        asset?.let { ref ->
            require(ref.kind in setOf("image", "audio")) { "Tipo de medio inválido en original." }
        }
    }
}

/**
 * An explicit engine variant. Song metadata, audio, V-Slice events and tempo belong
 * to this variant; Psych sections and events belong to its individual ChartDrafts.
 * Raw source JSON is opaque and must not be interpreted as local resource paths.
 */
data class IndependentVariant(
    val metadata: SongConfig,
    val difficulties: Set<String> = emptySet(),
    val charts: Map<String, ChartDraft> = emptyMap(),
    val histories: Map<String, ChartPersistentHistory> = emptyMap(),
    val uneditableDifficulties: Map<String, String> = emptyMap(),
    val originals: List<IndependentOriginal> = emptyList(),
    val unknownMetadataJson: String = ""
)

data class IndependentSong(
    val id: String,
    val psych: IndependentVariant? = null,
    @SerializedName("vslice") val vSlice: IndependentVariant? = null
) {
    fun variant(engine: ChartEngine): IndependentVariant? = if (engine == ChartEngine.PSYCH) psych else vSlice
    fun withVariant(engine: ChartEngine, value: IndependentVariant): IndependentSong =
        if (engine == ChartEngine.PSYCH) copy(psych = value) else copy(vSlice = value)
}

/**
 * The v2 independent-chart model. Integrated into [ModConfig] as the source of truth
 * for projects with [ModConfig.chartSchemaVersion] == 2. Legacy JSON is retained as an
 * opaque document until a lossless, validated migration has been implemented.
 */
/**
 * Canonical JSON shape of the v2 collection: every song id is a root key of the
 * `independentCharts` object, with an optional sibling `legacyDocumentJson` deposit
 * preserving the original document verbatim. The dedicated adapter keeps model
 * round trips compatible with the validator, the migration and the portable flows,
 * which all read that flattened shape (never a `songs` wrapper).
 */
internal object IndependentChartCollectionFormat {
    internal class Factory : TypeAdapterFactory {
        override fun <T : Any?> create(gson: Gson, typeToken: TypeToken<T>): TypeAdapter<T>? {
            if (typeToken.rawType != IndependentChartCollection::class.java) return null
            val songAdapter = gson.getAdapter(IndependentSong::class.java)
            return object : TypeAdapter<IndependentChartCollection>() {
                override fun write(out: JsonWriter, value: IndependentChartCollection) {
                    out.beginObject()
                    value.songs.forEach { (songId, song) ->
                        out.name(songId); songAdapter.write(out, song)
                    }
                    if (value.legacyDocumentJson.isNotEmpty()) {
                        out.name("legacyDocumentJson"); out.value(value.legacyDocumentJson)
                    }
                    out.endObject()
                }

                override fun read(input: JsonReader): IndependentChartCollection {
                    if (input.peek() == JsonToken.NULL) {
                        input.nextNull(); return IndependentChartCollection()
                    }
                    val songs = linkedMapOf<String, IndependentSong>()
                    var legacy = ""
                    input.beginObject()
                    while (input.hasNext()) {
                        when (val songId = input.nextName()) {
                            "legacyDocumentJson" -> legacy = input.nextString() ?: ""
                            else -> {
                                val song = songAdapter.read(input)
                                    ?: throw IllegalArgumentException("Canción inválida: $songId.")
                                // The id inside the song node is redundant; the map key is
                                // authoritative and the model keeps both consistent.
                                songs[songId] = if (song.id == songId) song else song.copy(id = songId)
                            }
                        }
                    }
                    input.endObject()
                    return IndependentChartCollection(songs = songs, legacyDocumentJson = legacy)
                }
            } as TypeAdapter<T>
        }
    }
}

@JsonAdapter(IndependentChartCollectionFormat.Factory::class)
data class IndependentChartCollection(
    val songs: Map<String, IndependentSong> = emptyMap(),
    val legacyDocumentJson: String = ""
) {
    fun chart(key: IndependentChartKey): ChartDraft? =
        songs[key.songId]?.variant(key.engine)?.charts?.get(key.difficulty)

    /** Edit exactly one variant and record its own history, never synthesize the other. */
    fun edit(key: IndependentChartKey, next: ChartDraft): IndependentChartCollection {
        val song = songs[key.songId] ?: error("Canción no encontrada.")
        require(song.id == key.songId) { "Identidad de canción inconsistente." }
        val variant = song.variant(key.engine) ?: error("Variante de motor no encontrada.")
        require(key.difficulty in variant.difficulties && key.difficulty !in variant.uneditableDifficulties) {
            "La dificultad no es editable."
        }
        require(next.songFolder == variant.metadata.songFolder && next.difficulty == key.difficulty) {
            "El chart pertenece a otra canción o dificultad."
        }
        val history = recordChartHistory(variant.histories[key.difficulty] ?: ChartPersistentHistory(),
            variant.charts[key.difficulty], next)
        val revised = variant.copy(
            charts = variant.charts + (key.difficulty to next),
            histories = variant.histories + (key.difficulty to history)
        )
        return copy(songs = songs + (key.songId to song.withVariant(key.engine, revised)))
    }

    /** Replace the variant entirely (used by migration). */
    fun withVariant(engine: ChartEngine, songId: String, value: IndependentVariant): IndependentChartCollection {
        val song = songs[songId] ?: error("Canción no encontrada.")
        return copy(songs = songs + (songId to song.withVariant(engine, value)))
    }

    /** Add a song with no variants yet. */
    fun withSong(songId: String, metadata: SongConfig): IndependentChartCollection {
        require(songId !in songs) { "La canción ya existe en la colección independiente." }
        return copy(songs = songs + (songId to IndependentSong(songId)))
    }

    /** Set or replace a variant's metadata and difficulties without touching charts. */
    fun withVariantMetadata(songId: String, engine: ChartEngine, metadata: SongConfig,
        difficulties: Set<String>): IndependentChartCollection {
        val song = songs[songId] ?: error("Canción no encontrada.")
        val current = song.variant(engine)
        val next = current?.copy(metadata = metadata, difficulties = difficulties)
            ?: IndependentVariant(metadata = metadata, difficulties = difficulties)
        return withVariant(engine, songId, next)
    }
}

/**
 * Convert a [ProjectMediaReference] to a JSON object suitable for opaque storage.
 * Never interpreted as a path.
 */
internal fun mediaReferenceToJson(ref: ProjectMediaReference): JsonObject =
    JsonObject().apply {
        addProperty("uri", ref.uri)
        addProperty("displayName", ref.displayName)
        addProperty("path", ref.path)
        addProperty("kind", ref.kind)
    }

/** Parse a [ProjectMediaReference] from JSON; unknown fields are ignored, not treated as paths. */
internal fun mediaReferenceFromJson(obj: JsonObject): ProjectMediaReference =
    ProjectMediaReference(
        uri = obj.get("uri")?.asString ?: "",
        displayName = obj.get("displayName")?.asString ?: "",
        path = obj.get("path")?.asString ?: "",
        kind = obj.get("kind")?.asString?.takeIf { it in setOf("image", "audio") } ?: "image"
    )