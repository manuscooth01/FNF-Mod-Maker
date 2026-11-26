package com.fnfmodmaker.model

import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/** A single Sparrow SubTexture. Trimmed frame coordinates are retained for preview.
 *  [rotated] frames are stored turned 90° clockwise in the sheet; Psych and V-Slice
 *  (HaxeFlixel `fromSparrow`) ignore that attribute, so they are previewable here but
 *  must never be exported: the builder blocks them with an explicit error. */
data class SparrowFrame(
    val name: String, val x: Int, val y: Int, val width: Int, val height: Int,
    val frameX: Int = 0, val frameY: Int = 0, val frameWidth: Int = width, val frameHeight: Int = height,
    val rotated: Boolean = false
) {
    /** Size the frame occupies once drawn upright (swapped when stored rotated). */
    val drawnWidth: Int get() = if (rotated) height else width
    val drawnHeight: Int get() = if (rotated) width else height
}

private fun readAtlasDocument(xml: String): org.w3c.dom.Document {
    require(xml.length <= 4_000_000) { "El XML es demasiado grande (máximo 4 MB)." }
    // Android's XML providers do not all support the same optional SAX features.
    // Reject any DTD before parsing and never resolve an external entity.
    require(!Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(xml)) {
        "El XML no puede contener DTD ni entidades externas."
    }
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { isExpandEntityReferences = false }
    }
    val builder = factory.newDocumentBuilder()
    builder.setEntityResolver { _, _ -> throw IllegalArgumentException("Las entidades externas no están permitidas.") }
    val document = builder.parse(InputSource(StringReader(xml)))
    require(document.documentElement.tagName == "TextureAtlas") { "El XML debe ser un atlas Sparrow TextureAtlas." }
    return document
}

/** Parses Sparrow XML without DTDs or external entities. Rotated frames are read
 *  and flagged (never silently dropped); the export path decides whether they are safe. */
fun parseSparrowAtlas(xml: String): List<SparrowFrame> {
    val document = readAtlasDocument(xml)
    val nodes = document.documentElement.getElementsByTagName("SubTexture")
    require(nodes.length in 1..5000) { "El atlas debe contener entre 1 y 5000 SubTexture." }
    val frames = (0 until nodes.length).map { index ->
        val node = nodes.item(index) as org.w3c.dom.Element
        fun required(key: String): Int = node.getAttribute(key).toIntOrNull()
            ?: throw IllegalArgumentException("SubTexture ${index + 1}: falta $key o no es un número entero.")
        fun optional(key: String, fallback: Int): Int = if (node.hasAttribute(key)) required(key) else fallback
        val name = node.getAttribute("name")
        require(name.isNotBlank()) { "SubTexture ${index + 1}: falta el nombre." }
        val rotatedFlag = node.getAttribute("rotated").lowercase()
        require(rotatedFlag.isEmpty() || rotatedFlag == "true" || rotatedFlag == "false" || rotatedFlag == "0" || rotatedFlag == "1") {
            "SubTexture $name: atributo rotated inválido (${node.getAttribute("rotated")})."
        }
        val rotated = rotatedFlag == "true" || rotatedFlag == "1"
        val x = required("x"); val y = required("y"); val width = required("width"); val height = required("height")
        // A rotated frame stores the region transposed: the written width/height are the
        // region inside the sheet and the upright drawing swaps them. Both must be positive.
        require(x >= 0 && y >= 0 && width > 0 && height > 0) { "SubTexture $name tiene dimensiones o coordenadas inválidas." }
        val frameX = optional("frameX", 0); val frameY = optional("frameY", 0)
        val frameWidth = optional("frameWidth", width); val frameHeight = optional("frameHeight", height)
        require(frameWidth > 0 && frameHeight > 0) { "SubTexture $name tiene un tamaño de frame inválido." }
        SparrowFrame(name, x, y, width, height, frameX, frameY, frameWidth, frameHeight, rotated)
    }
    require(frames.map { it.name }.toSet().size == frames.size) { "El XML contiene nombres de frame duplicados." }
    return frames
}

fun atlasFramesForPrefix(frames: List<SparrowFrame>, prefix: String): List<SparrowFrame> =
    if (prefix.isBlank()) emptyList() else frames.filter { it.name.startsWith(prefix) }.sortedBy { it.name }

fun atlasFramesFit(frames: List<SparrowFrame>, width: Int, height: Int): Boolean =
    width > 0 && height > 0 && frames.all { it.x.toLong() + it.width <= width && it.y.toLong() + it.height <= height }

/** Rewrite the image reference while preserving frame metadata (including trimming). */
fun rewriteSparrowImagePath(xml: String, imageName: String): String {
    parseSparrowAtlas(xml)
    val document = readAtlasDocument(xml)
    document.documentElement.setAttribute("imagePath", imageName)
    val transformer = javax.xml.transform.TransformerFactory.newInstance().newTransformer()
    transformer.setOutputProperty(javax.xml.transform.OutputKeys.ENCODING, "UTF-8")
    val writer = java.io.StringWriter()
    transformer.transform(javax.xml.transform.dom.DOMSource(document), javax.xml.transform.stream.StreamResult(writer))
    return writer.toString()
}
