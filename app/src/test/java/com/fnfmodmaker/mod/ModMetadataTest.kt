package com.fnfmodmaker.mod

import com.fnfmodmaker.model.ModConfig
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Test

class ModMetadataTest {
    @Test fun polymodDoesNotInventLicense() {
        assertFalse(JSONObject(JsonBuilders.polymodMeta(ModConfig())).has("license"))
    }
}
