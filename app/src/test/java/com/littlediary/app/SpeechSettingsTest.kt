package com.littlediary.app

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.ByteArrayOutputStream

class SpeechSettingsTest {
    @Test fun defaultsAndSharedImageKey() {
        val s = ModelSettings(imageKey = "mini-image")
        assertTrue(s.useMiniMaxSpeech)
        assertTrue(s.reuseMiniMaxKeyForSpeech)
        assertEquals("mini-image", s.requireSpeechKey())
        assertEquals("mini-text", s.copy(textKey = "mini-text").requireSpeechKey())
    }
    @Test fun providerSwitchRetainsMiniMaxWithoutBorrowingOpenAI() {
        val s = ModelSettings(textKey = "mini").selectTextProvider(Provider.OPENAI).withTextKey("gpt")
        assertEquals("mini", s.requireSpeechKey())
        assertEquals("", ModelSettings().selectTextProvider(Provider.OPENAI).withTextKey("gpt").speechKeyForRequest)
    }
    @Test fun clearedActiveKeyDoesNotReturnFromCache() {
        val s = ModelSettings(textKey = "old").rememberProfiles().withTextKey("")
        assertEquals("", s.speechKeyForRequest)
        assertThrows(IllegalArgumentException::class.java) { s.requireSpeechKey() }
    }
    @Test fun independentKeyAndInvalidKey() {
        assertEquals("speech", ModelSettings(textKey = "text", speechKey = "speech", reuseMiniMaxKeyForSpeech = false).requireSpeechKey())
        assertThrows(IllegalArgumentException::class.java) { ModelSettings(speechKey = "bad\nkey", reuseMiniMaxKeyForSpeech = false).requireSpeechKey() }
    }
    @Test fun multipartMatchesDocumentedContract() {
        val file = File.createTempFile("speech-test", ".aac")
        try {
            file.writeBytes(byteArrayOf(1, 2, 3))
            val request = SpeechRequest("mini", file, "test-boundary")
            assertEquals("https://api.minimaxi.com/v1/speech_to_text", request.url)
            assertEquals("zh", request.headers["language"])
            assertEquals("Bearer mini", request.headers["Authorization"])
            val output = ByteArrayOutputStream().also(request::writeTo).toString("UTF-8")
            assertTrue(output.contains("name=\"model\"\r\n\r\nasr-1.0\r\n"))
            assertTrue(output.contains("name=\"stream\"\r\n\r\nfalse\r\n"))
            assertTrue(output.contains("filename=\"recording.aac\"\r\nContent-Type: audio/aac"))
            assertTrue(output.endsWith("\r\n--test-boundary--\r\n"))
        } finally { file.delete() }
    }
}
