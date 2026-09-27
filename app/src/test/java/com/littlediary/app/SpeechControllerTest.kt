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
        assertTrue(recorder.outputFile != null)
        assertTrue(!File(recorder.outputFile!!).exists())
        controller.close()
    }

    @Test
    fun eightMinuteTimeoutStopsAndSavesRecording() {
        val recorder = FakeRecorder(writeAudio = true)
        val handler = TestHandler()
        var saved = 0
        val controller = controller(recorder, scheduler = handler)

        controller.startRecording({ _, _, _ -> saved++ }, {})
        assertEquals(480000L, handler.delayMillis)
        handler.delayedRunnable!!.run()

        assertEquals(1, saved)
        assertEquals(1, recorder.stopCalls)
        assertTrue(!controller.listening)
        controller.close()
    }

    @Test
    fun recorderStartFailureReportsMicrophoneError() {
        val recorder = FakeRecorder(writeAudio = true, failOnPrepare = true)
        var error = ""
        val controller = controller(recorder)

        controller.startRecording({ _, _, _ -> }, { error = it })

        assertEquals("无法开始录音，请检查麦克风权限并重试", error)
        assertTrue(!controller.listening)
        controller.close()
    }

    @Test
    fun stoppingRecordingTwiceSavesAtMostOnce() {
        val recorder = FakeRecorder(writeAudio = true)
        var saved = 0
        val controller = controller(recorder)

        controller.startRecording({ _, _, _ -> saved++ }, {})
        controller.stopRecording()
        controller.stopRecording()

        assertEquals(1, saved)
        assertEquals(1, recorder.stopCalls)
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

    private fun controller(
        recorder: FakeRecorder,
        client: SpeechClient = SpeechClient(),
        scheduler: RecordingScheduler? = null,
    ) = SpeechController(TestContext(), client, AudioRecorderFactory { recorder }, scheduler) { System.currentTimeMillis() }

    private class FakeRecorder(
        private val writeAudio: Boolean,
        private val failOnPrepare: Boolean = false,
    ) : AudioRecorder {
        private var output: String = ""
        var stopCalls = 0
        val outputFile: String? get() = output.takeIf { it.isNotBlank() }

        override fun setOutputFile(path: String) { output = path }
        override fun prepare() { if (failOnPrepare) error("permission denied") }
        override fun start() = Unit
        override fun stop() {
            stopCalls++
            if (writeAudio) File(output).writeBytes(byteArrayOf(1, 2, 3))
        }
        override fun release() = Unit
        override val maxAmplitude: Int get() = 0
    }

    private class TestHandler : RecordingScheduler {
        var delayedRunnable: Runnable? = null
        var delayMillis = 0L

        override fun post(runnable: Runnable): Boolean = true
        override fun postDelayed(runnable: Runnable, delayMillis: Long): Boolean {
            delayedRunnable = runnable
            this.delayMillis = delayMillis
            return true
        }
        override fun removeCallbacksAndMessages(token: Any?) {
            delayedRunnable = null
        }
    }

    private class TestContext : ContextWrapper(null) {
        private val root = Files.createTempDirectory("speech-controller-test").toFile()
        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
    }
}
