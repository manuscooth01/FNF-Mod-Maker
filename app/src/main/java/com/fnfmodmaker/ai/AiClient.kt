package com.fnfmodmaker.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

/** Network requests never follow redirects, so Authorization cannot be forwarded. */
object AiClient {
    private const val MAX_RESPONSE = 12 * 1024 * 1024

    private fun boundedBytes(input: java.io.InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (out.size() + count > maxBytes) throw AiException("La respuesta del proveedor es demasiado grande.")
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    private fun request(uri: URI, key: String?, body: String?): ByteArray {
        val connection = uri.toURL().openConnection() as? HttpURLConnection ?: throw AiException("URL no compatible.")
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 20_000
            connection.readTimeout = 120_000
            connection.requestMethod = if (body == null) "GET" else "POST"
            if (key != null) connection.setRequestProperty("Authorization", "Bearer $key")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                val message = when (code) {
                    401, 403 -> "El proveedor rechazó la clave o los permisos (HTTP $code)."
                    404 -> "No se encontró la ruta o el modelo (HTTP 404). Comprueba la URL base y el modelo."
                    429 -> "El proveedor rechazó la solicitud por límite o cuota (HTTP 429)."
                    in 300..399 -> "El proveedor respondió con una redirección, que se bloqueó por seguridad."
                    else -> "El proveedor no pudo completar la solicitud (HTTP $code). Comprueba si el modelo admite esta función."
                }
                throw AiException(message)
            }
            return connection.inputStream.use { boundedBytes(it, MAX_RESPONSE) }
        } finally { connection.disconnect() }
    }

    fun chat(base: String, key: String, model: String, question: String, context: String, analysisImage: File?): String {
        val dataUrl = analysisImage?.let { "data:image/jpeg;base64," + Base64.encodeToString(it.readBytes(), Base64.NO_WRAP) }
        val body = AiProtocol.chatBody(model, question, context, dataUrl)
        return AiProtocol.parseText(request(AiProtocol.endpoint(base, "chat/completions"), key, body).toString(Charsets.UTF_8))
    }

    fun propose(base: String, key: String, model: String, question: String, context: String): AiProtocol.ProjectResponse {
        val body = AiProtocol.projectToolsBody(model, question, context)
        return AiProtocol.parseProjectResponse(request(AiProtocol.endpoint(base, "chat/completions"), key, body).toString(Charsets.UTF_8))
    }

    fun generate(base: String, key: String, model: String, prompt: String, destination: File) {
        val body = AiProtocol.imageBody(model, prompt)
        val result = AiProtocol.parseImage(request(AiProtocol.endpoint(base, "images/generations"), key, body).toString(Charsets.UTF_8))
        val bytes = if (result.base64 != null) {
            if (result.base64.length > MAX_RESPONSE) throw AiException("La imagen recibida es demasiado grande.")
            try { Base64.decode(result.base64, Base64.DEFAULT) } catch (_: Exception) { throw AiException("La imagen recibida no es válida.") }
        } else {
            val url = try { URI(result.url) } catch (_: Exception) { throw AiException("La URL de la imagen no es válida.") }
            if (!url.scheme.equals("https", true) || url.host.isNullOrBlank() || url.userInfo != null) throw AiException("La URL de la imagen debe usar HTTPS.")
            // Never let a provider's response make requests to private/local networks.
            val addresses = java.net.InetAddress.getAllByName(url.host)
            if (addresses.isEmpty() || addresses.any { address ->
                    address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress ||
                        (address is java.net.Inet6Address && (address.address[0].toInt() and 0xfe) == 0xfc)
                }) throw AiException("La URL de la imagen no puede apuntar a una red privada.")
            // Image URLs may be signed. Do not send the API key to this host.
            request(url, null, null)
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..2048 || bounds.outHeight !in 1..2048) throw AiException("La imagen recibida es inválida o supera 2048 px por lado.")
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw AiException("No se pudo leer la imagen recibida.")
        try {
            destination.parentFile?.mkdirs()
            destination.outputStream().use { if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) throw AiException("No se pudo preparar la imagen generada.") }
        } finally { bitmap.recycle() }
    }
}
