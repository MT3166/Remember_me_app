package com.littlediary.app

import org.junit.Assert.*
import org.junit.Test

class ProviderSettingsTest {
    @Test fun officialProvidersNeedNoUrlEntry() {
        val gpt = ModelSettings().selectTextProvider(Provider.OPENAI).selectImageProvider(Provider.OPENAI)
        assertEquals("https://api.openai.com/v1/chat/completions", endpoint(gpt.textBaseUrl, gpt.protocol))
        assertEquals("https://api.openai.com/v1/images/generations", gpt.imageUrl)
        assertEquals("gpt-4.1-mini", gpt.textModel)
        assertEquals("gpt-image-2", gpt.imageModel)
        assertEquals(ImageApi.OPENAI, gpt.imageApi)
        gpt.validate()
    }

    @Test fun textSwitchDoesNotTransferKeyOrModifyImageConfig() {
        val original = ModelSettings(textKey = "minimax-text", imageKey = "minimax-image", textModel = "MiniMax-M2.7")
        val switched = original.selectTextProvider(Provider.OPENAI)
        assertEquals("", switched.textKey)
        assertEquals(original.imageKey, switched.imageKey)
        assertEquals(original.imageProvider, switched.imageProvider)
        val returned = switched.copy(textKey = "openai-text").selectTextProvider(Provider.MINIMAX)
        assertEquals("minimax-text", returned.textKey)
        assertEquals("MiniMax-M2.7", returned.textModel)
        assertEquals("openai-text", returned.selectTextProvider(Provider.OPENAI).textKey)
    }

    @Test fun imageSwitchRemembersSeparateKeyAndProtocol() {
        val original = ModelSettings(textKey = "text", imageKey = "minimax-image")
        val switched = original.selectImageProvider(Provider.OPENAI)
        assertEquals("", switched.imageKey)
        assertEquals("text", switched.textKey)
        assertEquals(ImageApi.OPENAI, switched.imageApi)
        val returned = switched.copy(imageKey = "openai-image").selectImageProvider(Provider.MINIMAX)
        assertEquals("minimax-image", returned.imageKeyForRequest)
        assertEquals(ImageApi.MINIMAX, returned.imageApi)
        assertEquals("openai-image", returned.selectImageProvider(Provider.OPENAI).imageKey)
    }

    @Test fun sharedKeyIsOnlyUsedWithinSameOfficialProvider() {
        val shared = ModelSettings(textKey = "shared", imageKey = "separate", reuseTextKeyForImages = true)
        assertEquals("shared", shared.imageKeyForRequest)
        val mixed = shared.selectTextProvider(Provider.OPENAI)
        assertFalse(mixed.reuseTextKeyForImages)
        assertEquals("separate", mixed.imageKeyForRequest)
        assertThrows(IllegalArgumentException::class.java) { mixed.copy(reuseTextKeyForImages = true).validate(ModelKind.IMAGE) }
    }

    @Test fun customAnthropicConfigSurvivesProviderRoundTrip() {
        val custom = ModelSettings(protocol = Protocol.ANTHROPIC, textBaseUrl = "https://example.org/anthropic", textKey = "custom")
        assertEquals(Provider.CUSTOM, custom.textProvider)
        val returned = custom.selectTextProvider(Provider.OPENAI).selectTextProvider(Provider.CUSTOM)
        assertEquals(custom.textBaseUrl, returned.textBaseUrl)
        assertEquals(Protocol.ANTHROPIC, returned.protocol)
        assertEquals("custom", returned.textKey)
    }

    @Test fun officialProviderCannotBeRedirectedByAProfileUrl() {
        val s = ModelSettings(textProfiles = mapOf(Provider.OPENAI to TextProviderConfig("https://example.org/steal", "gpt-4.1-mini", "key")))
            .selectTextProvider(Provider.OPENAI)
        assertEquals(textPreset(Provider.OPENAI).url, s.textBaseUrl)
    }

    @Test fun independentImageValidationDoesNotRequireTextSetup() {
        val imageOnly = ModelSettings(textProvider = Provider.CUSTOM, textBaseUrl = "", textModel = "", imageKey = "image")
        imageOnly.validate(ModelKind.IMAGE)
        assertThrows(IllegalArgumentException::class.java) { imageOnly.validate(ModelKind.TEXT) }
    }
}
