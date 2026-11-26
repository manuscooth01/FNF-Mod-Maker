package com.fnfmodmaker.project

import com.fnfmodmaker.model.AssetCategory
import com.fnfmodmaker.model.CatalogAsset
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale

/**
 * Pure catalog logic for Asset Library 1.0: storage layout, classification by
 * extension and path, and unambiguous Sparrow pairing. Nothing here touches the
 * filesystem beyond hashing a stream; the importer decides what is written.
 */
internal object AssetPackCatalog {
    /** Storage layout inside the project: <proyecto>/assets/library/<packId>/<jerarquía original>. */
    fun packDirectory(projectAssets: File, packId: String): File {
        require(packId.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))) {
            "Identificador de paquete inválido."
        }
        return File(projectAssets, "library/$packId")
    }

    fun sha256Of(input: InputStream, limit: Long): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer); if (n < 0) break
            total += n
            require(total <= limit) { "Un recurso supera el tamaño permitido." }
            if (n > 0) digest.update(buffer, 0, n)
        }
        return total to digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Classification from the ZIP path and extension. Path hints are suggestions, never proof. */
    fun classify(sourcePath: String): AssetCategory {
        val lower = sourcePath.lowercase(Locale.ROOT)
        val name = lower.substringAfterLast('/')
        val ext = name.substringAfterLast('.', "")
        val segments = lower.split('/')
        fun has(part: String) = segments.any { it == part }
        // Evidence of the engine's own folders is treated only as a hint below.
        return when {
            ext in setOf("ttf", "otf", "fnt") -> AssetCategory.FONT
            ext in setOf("ogg", "mp3", "wav", "flac") -> AssetCategory.AUDIO
            ext == "lua" || ext == "hx" || ext == "hscript" || ext == "js" -> AssetCategory.SCRIPT
            ext == "json" || ext == "txt" || ext == "md" || ext == "csv" || ext == "yaml" -> AssetCategory.JSON
            ext == "xml" -> AssetCategory.ATLAS
            ext == "png" || ext == "jpg" || ext == "jpeg" || ext == "webp" || ext == "gif" -> when {
                has("icons") && has("character") -> AssetCategory.CHARACTER_ICON
                has("icons") && has("menubuttons") -> AssetCategory.MENU_ICON
                has("icons") -> AssetCategory.CHARACTER_ICON
                has("characters") && ext == "png" -> AssetCategory.SPRITE
                has("stages") || has("stage") || has("backgrounds") -> AssetCategory.STAGE
                has("props") || has("objects") || has("decor") -> AssetCategory.PROPS
                has("week") || has("weeks") && ext == "png" -> AssetCategory.SONG_ART
                has("images") && (has("menus") || has("menu") || has("ui") || has("interface")) -> AssetCategory.UI
                has("songs") -> AssetCategory.SONG_ART
                else -> AssetCategory.UNCLASSIFIED
            }
            else -> AssetCategory.OTHER
        }
    }

    /** Image extensions that can carry dimensions; the importer decodes them off-thread. */
    fun isImage(sourcePath: String): Boolean {
        val ext = sourcePath.lowercase(Locale.ROOT).substringAfterLast('.', "")
        return ext in setOf("png", "jpg", "jpeg", "webp", "gif")
    }

    /**
     * Pair one XML with one PNG only when the pairing is unambiguous: a single PNG with
     * the same base name, in the same directory and in the whole pack. No invented links.
     */
    fun pairRelated(assets: List<CatalogAsset>): List<CatalogAsset> {
        val xmls = assets.filter { it.extension.equals("xml", true) }
        val pngs = assets.filter { it.extension.equals("png", true) }
        val related = mutableMapOf<String, String>()
        for (xml in xmls) {
            val base = xml.displayName.substringBeforeLast('.').lowercase(Locale.ROOT)
            val directory = xml.sourcePath.substringBeforeLast('/', "\u0000")
            val siblings = pngs.filter {
                it.sourcePath.substringBeforeLast('/', "\u0000") == directory &&
                    it.displayName.substringBeforeLast('.').lowercase(Locale.ROOT) == base
            }
            val anywhere = pngs.filter { it.displayName.substringBeforeLast('.').lowercase(Locale.ROOT) == base }
            if (siblings.size == 1 && anywhere.size == 1) {
                related[xml.id] = siblings.single().id
                related[siblings.single().id] = xml.id
            }
        }
        return assets.map { it.copy(relatedId = related[it.id] ?: "") }
    }
}
