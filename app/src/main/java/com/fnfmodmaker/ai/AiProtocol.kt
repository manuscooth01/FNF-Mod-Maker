package com.fnfmodmaker.ai

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI

/** Interoperable subset of the OpenAI-compatible API. Never contains an API key. */
object AiProtocol {
    fun endpoint(base: String, suffix: String): URI {
        val uri = try { URI(base.trim().trimEnd('/')) } catch (_: Exception) { throw AiException("La URL del proveedor no es válida.") }
        if (!uri.scheme.equals("https", true) || uri.host.isNullOrBlank() || uri.userInfo != null || uri.query != null || uri.fragment != null || uri.port > 65535) {
            throw AiException("Usa una URL HTTPS válida, sin usuario, contraseña, consulta ni fragmento.")
        }
        if (suffix !in listOf("chat/completions", "images/generations")) throw AiException("Ruta de IA no válida.")
        return URI(uri.toASCIIString() + "/" + suffix)
    }

    fun chatBody(model: String, question: String, context: String, imageDataUrl: String?): String {
        val messages = JsonArray()
        messages.add(JsonObject().apply {
            addProperty("role", "system")
            addProperty("content", "Eres un asistente para crear personajes de Friday Night Funkin'. Responde en español, de forma breve y práctica. Las posiciones son orientativas, dependen del escenario y deben probarse en el juego. No afirmes haber visto imágenes si no las recibes. No afirmes que se hayan aplicado cambios.")
        })
        messages.add(JsonObject().apply {
            addProperty("role", "user")
            val text = "Datos proporcionados por la app:\n$context\n\nPregunta del usuario:\n$question"
            if (imageDataUrl == null) addProperty("content", text) else add("content", JsonArray().apply {
                add(JsonObject().apply { addProperty("type", "text"); addProperty("text", text) })
                add(JsonObject().apply {
                    addProperty("type", "image_url")
                    add("image_url", JsonObject().apply { addProperty("url", imageDataUrl) })
                })
            })
        })
        return JsonObject().apply { addProperty("model", model); add("messages", messages); addProperty("stream", false) }.toString()
    }

    fun imageBody(model: String, prompt: String): String = JsonObject().apply {
        addProperty("model", model)
        addProperty("prompt", prompt)
        addProperty("n", 1)
        addProperty("size", "1024x1024")
    }.toString()

    fun parseText(json: String): String = try {
        val value = JsonParser.parseString(json).asJsonObject.getAsJsonArray("choices")[0].asJsonObject
            .getAsJsonObject("message").get("content")
        val text = value.asString.trim().takeIf { it.isNotEmpty() } ?: throw IllegalArgumentException()
        if (text.length > 16000) text.take(16000) + "\n[Respuesta recortada]" else text
    } catch (_: Exception) { throw AiException("El proveedor no devolvió una respuesta de texto compatible.") }

    data class ImageResult(val base64: String? = null, val url: String? = null)
    fun parseImage(json: String): ImageResult = try {
        val item = JsonParser.parseString(json).asJsonObject.getAsJsonArray("data")[0].asJsonObject
        val b64 = item.get("b64_json")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }
        val url = item.get("url")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }
        if (b64 == null && url == null) throw IllegalArgumentException()
        ImageResult(b64, url)
    } catch (_: Exception) { throw AiException("El proveedor no devolvió una imagen compatible.") }
}

class AiException(message: String) : Exception(message)
