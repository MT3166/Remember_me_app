package com.littlediary.app

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechControllerTest {
    @Test
    fun completedRecordingIsDeliveredExactlyOnceWithTimestamps() {
        val recorder = FakeRecorder(writeAudio = true)
        val saved = mutableListOf<Triple<File, Long, Long>>()
        var error = ""
        val controller = controller(recorder)

        controller.startRecording({ file, startedAt, endedAt -> saved += Triple(file, startedAt, endedAt) }, { error = it })
        assertTrue(error, controller.listening)
        controller.stopRecording()

        assertEquals(1, saved.size)
        val (file, startedAt, endedAt) = saved.single()
        assertTrue(file.name.endsWith(".aac"))
        assertTrue(file.length() > 0)
        assertTrue(startedAt > 0)
        assertTrue(endedAt >= startedAt)
        assertEquals(1, recorder.stopCalls)
        assertTrue(error.isBlank())
        controller.close()
    }

    @Test
    fun zeroByteRecordingIsRejectedWithoutSavedCallback() {
        val recorder = FakeRecorder(writeAudio = false)
        var saved = 0
        var error = ""
        val controller = controller(recorder)

        controller.startRecording({ _, _, _ -> saved++ }, { error = it })
        controller.stopRecording()

        assertEquals(0, saved)
        assertTrue(error.contains("太短"))
        controller.close()
    }

    @Test
    fun stoppingRecordingDoesNotCallSpeechClient() {
        val requests = AtomicInteger()
        val client = SpeechClient {
            requests.incrementAndGet()
            JSONObject().put("text", "不应被调用")
        }
        val controller = controller(FakeRecorder(writeAudio = true), client)

        controller.startRecording({ _, _, _ -> }, {})
        controller.stopRecording()

        assertEquals(0, requests.get())
        controller.close()
    }

    private fun controller(recorder: FakeRecorder, client: SpeechClient = SpeechClient()) =
        SpeechController(TestContext(), client, AudioRecorderFactory { recorder }, null) { System.currentTimeMillis() }

    private class FakeRecorder(private val writeAudio: Boolean) : AudioRecorder {
        private var output: String = ""
        var stopCalls = 0

        override fun setOutputFile(path: String) { output = path }
        override fun prepare() = Unit
        override fun start() = Unit
        override fun stop() {
            stopCalls++
            if (writeAudio) File(output).writeBytes(byteArrayOf(1, 2, 3))
        }
        override fun release() = Unit
        override val maxAmplitude: Int get() = 0
    }

    private class TestContext : ContextWrapper(null) {
        private val root = Files.createTempDirectory("speech-controller-test").toFile()
        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
    }
}
