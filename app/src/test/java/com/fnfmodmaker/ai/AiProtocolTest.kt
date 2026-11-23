package com.fnfmodmaker.ai

import com.fnfmodmaker.model.CharacterSide
import com.fnfmodmaker.model.GUIDE_COMPANION
import com.fnfmodmaker.model.GUIDE_OPPONENT
import com.fnfmodmaker.model.GUIDE_PLAYER
import com.fnfmodmaker.model.characterGuideAnchor
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class AiProtocolTest {
    @Test fun endpointRequiresHttpsAndDoesNotAcceptCredentialsOrQuery() {
        assertEquals("https://example.com/v1/chat/completions", AiProtocol.endpoint("https://example.com/v1/", "chat/completions").toString())
        listOf("http://example.com/v1", "https://user:pass@example.com/v1", "https://example.com/v1?token=x", "https://example.com/v1#x", "invalid")
            .forEach { assertTrue(runCatching { AiProtocol.endpoint(it, "chat/completions") }.isFailure) }
        assertTrue(runCatching { AiProtocol.endpoint("https://example.com", "other") }.isFailure)
    }

    @Test fun adviceAndVisionUseDifferentPayloads() {
        val text = JsonParser.parseString(AiProtocol.chatBody("model", "hola", "jugador", null)).asJsonObject
        val textContent = text.getAsJsonArray("messages")[1].asJsonObject.get("content")
        assertTrue(textContent.isJsonPrimitive)
        val image = JsonParser.parseString(AiProtocol.chatBody("vision", "revisa", "rival", "data:image/jpeg;base64,AA==")).asJsonObject
        val content = image.getAsJsonArray("messages")[1].asJsonObject.getAsJsonArray("content")
        assertEquals("image_url", content[1].asJsonObject.get("type").asString)
        assertEquals("data:image/jpeg;base64,AA==", content[1].asJsonObject.getAsJsonObject("image_url").get("url").asString)
        assertFalse(text.toString().contains("image_url"))
    }

    @Test fun parsesResponsesAndRejectsIncompatibleBodies() {
        assertEquals("Hola", AiProtocol.parseText("""{"choices":[{"message":{"content":"Hola"}}]}"""))
        assertEquals("abcd", AiProtocol.parseImage("""{"data":[{"b64_json":"abcd"}]}""").base64)
        assertEquals("https://example.com/i.png", AiProtocol.parseImage("""{"data":[{"url":"https://example.com/i.png"}]}""").url)
        assertTrue(runCatching { AiProtocol.parseText("{}") }.isFailure)
        assertTrue(runCatching { AiProtocol.parseImage("{}") }.isFailure)
        val image = JsonParser.parseString(AiProtocol.imageBody("img", "personaje")).asJsonObject
        assertEquals("img", image.get("model").asString)
        assertEquals("personaje", image.get("prompt").asString)
    }

    @Test fun referenceSceneSeparatesRolesAndFollowsSelectedSide() {
        assertEquals(GUIDE_PLAYER, characterGuideAnchor(CharacterSide.PLAYER))
        assertEquals(GUIDE_OPPONENT, characterGuideAnchor(CharacterSide.OPPONENT))
        assertTrue(GUIDE_OPPONENT.x < GUIDE_COMPANION.x && GUIDE_COMPANION.x < GUIDE_PLAYER.x)
    }
}
