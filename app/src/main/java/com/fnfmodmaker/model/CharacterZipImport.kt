package com.fnfmodmaker.model

import org.json.JSONObject

/**
 * Analysis of a character ZIP before anything is imported. Pure JVM logic over the
 * entry list so the same rules run on device and in unit tests: nothing is written
 * and nothing touches the project until the user confirms the candidates.
 */
object CharacterZipImport {

    /** One ZIP entry; [name] is the normalized archive path. */
    internal data class Entry(val name: String, val bytes: ByteArray)

    /** One importable character with its sheet, atlas XML and optional health icon. */
    internal data class Candidate(
        val format: String,
        val id: String,
        val name: String,
        val jsonName: String,
        val sourceJson: String,
        val sheet: Entry,
        val xml: Entry,
        val icon: Entry?,
        val warnings: List<String>
    )

    /** Full analysis: importable characters plus explicit reasons for everything else. */
    internal data class Analysis(
        val candidates: List<Candidate>,
        val ignored: List<String>,
        val problems: List<String>
    ) {
        val importable: Boolean get() = candidates.isNotEmpty() && problems.isEmpty()
    }

    internal const val MAX_ENTRIES = 4096
    internal const val MAX_JSON_BYTES = 512_000
    internal const val MAX_XML_BYTES = 4_000_000
    internal const val MAX_IMAGE_BYTES = 64L * 1024 * 1024

    /**
     * Unsafe or ambiguous archives are rejected whole: path traversal, absolute
     * entries, backslashes, duplicate paths and unreadable JSON never reach the
     * project. Missing resources reject only their own candidate character.
     */
    fun analyze(entries: List<Entry>): Analysis {
        val problems = mutableListOf<String>()
        if (entries.size > MAX_ENTRIES) problems.add("El ZIP supera $MAX_ENTRIES entradas.")
        val seen = mutableSetOf<String>()
        val safe = mutableListOf<Entry>()
        for (entry in entries) {
            val path = entry.name
            if (path.isEmpty() || path.startsWith("/") || path.contains('\\') ||
                path.split('/').any { it == "." || it == ".." } || path.any { it.code < 32 }) {
                problems.add("Ruta peligrosa en el ZIP: ${JSONObject.quote(path)}.")
                continue
            }
            if (!seen.add(path.lowercase())) {
                problems.add("Entrada duplicada en el ZIP: ${JSONObject.quote(path)}.")
                continue
            }
            safe.add(entry)
        }
        val jsonEntries = safe.filter { it.name.endsWith(".json", true) && it.bytes.size <= MAX_JSON_BYTES }
        val images = safe.filter { it.name.endsWith(".png", true) && it.bytes.size <= MAX_IMAGE_BYTES }
        val xmls = safe.filter { it.name.endsWith(".xml", true) && it.bytes.size <= MAX_XML_BYTES }
        val candidates = mutableListOf<Candidate>()
        for (jsonEntry in jsonEntries) {
            val text = runCatching { String(jsonEntry.bytes, Charsets.UTF_8) }.getOrNull()
            val parsed = text?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (parsed == null) {
                problems.add("JSON malformado en el ZIP: ${jsonEntry.name}.")
                continue
            }
            val psych = parsed.has("image")
            val vSlice = parsed.has("assetPath")
            if (!(psych xor vSlice) || !parsed.has("animations")) {
                problems.add("JSON no reconocido como personaje Psych ni V-Slice: ${jsonEntry.name}.")
                continue
            }
            val format = if (vSlice) "V-Slice" else "Psych"
            val id = slugify(jsonEntry.name.substringAfterLast('/').substringBeforeLast('.')).take(64).ifBlank { "personaje" }
            val asset = parsed.optString(if (vSlice) "assetPath" else "image").trim()
                .removePrefix("shared:").trim().trimStart('/')
            if (asset.isEmpty()) {
                problems.add("El personaje de ${jsonEntry.name} no declara su ruta de imagen.")
                continue
            }
            val sheet = resolveAsset(images, asset, "png")
            if (sheet == null) {
                problems.add("Falta el PNG del personaje de ${jsonEntry.name} (buscado junto a $asset).")
                continue
            }
            val xml = resolveAsset(xmls, asset, "xml")
            if (xml == null) {
                problems.add("Falta el XML Sparrow del personaje de ${jsonEntry.name} (buscado junto a $asset).")
                continue
            }
            val icon = iconEntry(safe, parsed, id)
            val warnings = buildList {
                add("El personaje trae sus PNG, XML, JSON e icono; el resto de archivos se listan como ignorados.")
                if (icon == null) add("Sin icono de vida en el ZIP; se usará el icono del juego base.")
            }
            candidates.add(Candidate(format, id,
                parsed.optString(if (vSlice) "name" else "healthicon", id).take(100), jsonEntry.name,
                text ?: "", sheet, xml, icon, warnings))
        }
        val used = candidates.flatMap { candidate ->
            listOf(candidate.jsonName, candidate.sheet.name, candidate.xml.name) + listOfNotNull(candidate.icon?.name)
        }.map { it.lowercase() }.toSet()
        val ignored = safe.map { it.name }.filter { it.lowercase() !in used }
        return Analysis(candidates, ignored, problems.distinct())
    }

    /**
     * Engine asset paths point at the character image without extension, sometimes
     * without the images/ root and sometimes with a scheme prefix. Resolve direct,
     * images/ prefixed, and finally by basename.
     */
    private fun resolveAsset(entries: List<Entry>, asset: String, extension: String): Entry? {
        val wanted = buildList {
            add("$asset.$extension")
            add("images/$asset.$extension")
            add("images/${asset.substringAfterLast('/')}.$extension")
        }.distinct()
        for (candidate in wanted) entries.firstOrNull { it.name.equals(candidate, true) }?.let { return it }
        val base = asset.substringAfterLast('/')
        return entries.firstOrNull { it.name.substringAfterLast('/').equals("$base.$extension", true) }
    }

    /** Psych "healthicon" holds the id; V-Slice nests it in healthIcon.id. */
    private fun iconEntry(entries: List<Entry>, json: JSONObject, id: String): Entry? {
        val iconId = when {
            json.optJSONObject("healthIcon")?.optString("id", "").orEmpty().isNotEmpty() ->
                json.getJSONObject("healthIcon").optString("id")
            json.optString("healthicon", "").isNotEmpty() -> json.optString("healthicon")
            else -> id
        }
        if (!iconId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) return null
        return entries.firstOrNull { entry ->
            val name = entry.name.substringAfterLast('/')
            name.equals("icon-$iconId.png", true) ||
                (name.equals("$iconId.png", true) && entry.name.startsWith("images/icons/", true))
        }
    }
}
