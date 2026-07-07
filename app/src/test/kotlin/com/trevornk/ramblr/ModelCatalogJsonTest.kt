package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogJsonTest {

    private fun sampleEntries() = listOf(
        ModelCatalogEntry(
            provider = ProviderKind.OPENAI,
            modelId = "gpt-5.4-nano",
            displayName = "GPT-5.4 Nano",
            description = "Fastest, cheapest.",
            tier = ModelTier.RECOMMENDED,
            useCase = ModelUseCase.CLEANUP,
            costPer1MInputUsd = 0.05,
            costPer1MOutputUsd = 0.40,
        ),
        ModelCatalogEntry(
            provider = ProviderKind.OMNIROUTE,
            modelId = "gemini/gemini-flash-latest",
            displayName = "OmniRoute: Gemini Flash (auto-upgrading)",
            description = "Auto-upgrading alias.",
            tier = ModelTier.RECOMMENDED,
            useCase = ModelUseCase.CLEANUP,
            costPer1MInputUsd = 0.30,
            costPer1MOutputUsd = 2.50,
        ),
    )

    @Test fun `round trips a multi-entry multi-provider catalog through serialize and deserialize`() {
        val entries = sampleEntries()
        val json = ModelCatalogJson.serialize(entries)
        val parsed = ModelCatalogJson.deserialize(json)
        assertEquals(entries, parsed)
    }

    @Test fun `deserialize returns null for a blank string`() {
        assertNull(ModelCatalogJson.deserialize(""))
        assertNull(ModelCatalogJson.deserialize(null))
    }

    @Test fun `deserialize returns null for malformed json`() {
        assertNull(ModelCatalogJson.deserialize("not json"))
    }

    @Test fun `deserialize returns null for an empty array -- treated as malformed, not a deliberate empty catalog`() {
        assertNull(ModelCatalogJson.deserialize("[]"))
    }

    @Test fun `deserialize returns null for an unknown provider name`() {
        assertNull(ModelCatalogJson.deserialize(
            """[{"provider":"BOGUS","modelId":"m","displayName":"d","description":"desc",""" +
                """"tier":"RECOMMENDED","useCase":"CLEANUP","costPer1MInputUsd":0.1,"costPer1MOutputUsd":0.2}]"""
        ))
    }

    @Test fun `deserialize returns null for an unknown tier name`() {
        assertNull(ModelCatalogJson.deserialize(
            """[{"provider":"OPENAI","modelId":"m","displayName":"d","description":"desc",""" +
                """"tier":"ULTRA","useCase":"CLEANUP","costPer1MInputUsd":0.1,"costPer1MOutputUsd":0.2}]"""
        ))
    }

    @Test fun `deserialize returns null when a required field is missing`() {
        assertNull(ModelCatalogJson.deserialize(
            """[{"provider":"OPENAI","modelId":"m","tier":"RECOMMENDED","useCase":"CLEANUP",""" +
                """"costPer1MInputUsd":0.1,"costPer1MOutputUsd":0.2}]"""
        ))
    }

    @Test fun `single entry with all fields parses correctly`() {
        val entries = sampleEntries()
        val parsed = ModelCatalogJson.deserialize(ModelCatalogJson.serialize(entries))
        assertTrue(parsed != null && parsed.size == 2)
        assertEquals(ProviderKind.OMNIROUTE, parsed!![1].provider)
        assertEquals("gemini/gemini-flash-latest", parsed[1].modelId)
    }
}
