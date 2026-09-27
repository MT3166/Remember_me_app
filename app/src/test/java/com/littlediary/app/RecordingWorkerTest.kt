package com.littlediary.app

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingWorkerTest {
    @Test
    fun successfulTranscriptionPersistsTranscriptAndCompletedState() = runBlocking {
        val context = TestContext()
        val repository = RecordingRepository(context)
        val segment = sampleSegment()
        repository.insert(segment)
        repository.audioFile(segment).writeBytes(byteArrayOf(1, 2, 3))
        val calls = AtomicInteger()
        val transcribe: suspend (ModelSettings, File) -> String = { _, _ ->
            calls.incrementAndGet()
            "今天去了公园"
        }

        val result = RecordingWorkerProcessor(repository, { ModelSettings(textKey = "test-key") }, transcribe)
            .process(segment.id)

        assertEquals(RecordingWorkOutcome.SUCCESS, result)
        assertEquals(1, calls.get())
        assertEquals(
            segment.copy(
                state = RecordingState.TRANSCRIBED,
                transcript = "今天去了公园",
                revision = 2,
            ),
            repository.get(segment.id),
        )
    }

    @Test
    fun networkFailureReturnsRetryAndLeavesSegmentTranscribing() = runBlocking {
        val context = TestContext()
        val repository = RecordingRepository(context)
        val segment = sampleSegment()
        repository.insert(segment)
        repository.audioFile(segment).writeBytes(byteArrayOf(1, 2, 3))
        val transcribe: suspend (ModelSettings, File) -> String = { _, _ -> throw IOException("offline") }

        val result = RecordingWorkerProcessor(repository, { ModelSettings(textKey = "test-key") }, transcribe)
            .process(segment.id)

        assertEquals(RecordingWorkOutcome.RETRY, result)
        assertEquals(
            segment.copy(
                state = RecordingState.TRANSCRIBING,
                error = "offline",
                retryCount = 1,
                revision = 2,
            ),
            repository.get(segment.id),
        )
    }

    @Test
    fun alreadyTranscribedSegmentIsIdempotentAndDoesNotCallSpeechClient() = runBlocking {
        val context = TestContext()
        val repository = RecordingRepository(context)
        val segment = sampleSegment(
            state = RecordingState.TRANSCRIBED,
            transcript = "已经记下来了",
            revision = 3,
        )
        repository.insert(segment)
        val calls = AtomicInteger()
        val transcribe: suspend (ModelSettings, File) -> String = { _, _ ->
            calls.incrementAndGet()
            "不应再次识别"
        }

        val result = RecordingWorkerProcessor(repository, { ModelSettings(textKey = "test-key") }, transcribe)
            .process(segment.id)

        assertEquals(RecordingWorkOutcome.SUCCESS, result)
        assertEquals(0, calls.get())
        assertEquals(segment, repository.get(segment.id))
    }

    @Test
    fun permanentConfigurationFailureIsPersistedAsRetryableError() = runBlocking {
        val context = TestContext()
        val repository = RecordingRepository(context)
        val segment = sampleSegment()
        repository.insert(segment)
        repository.audioFile(segment).writeBytes(byteArrayOf(1, 2, 3))
        val transcribe: suspend (ModelSettings, File) -> String = { _, _ ->
            throw IllegalArgumentException("请填写语音密钥")
        }

        val result = RecordingWorkerProcessor(repository, { ModelSettings() }, transcribe)
            .process(segment.id)

        assertEquals(RecordingWorkOutcome.FAILURE, result)
        assertEquals(RecordingState.RETRYABLE_ERROR, repository.get(segment.id)?.state)
        assertEquals(1, repository.get(segment.id)?.retryCount)
        assertTrue(repository.get(segment.id)?.error?.isNotBlank() == true)
    }

    private fun sampleSegment(
        state: RecordingState = RecordingState.SAVED,
        transcript: String = "",
        revision: Long = 0,
    ) = RecordingSegment(
        id = "segment-1",
        dayKey = "2026-09-27",
        createdAt = 1_000L,
        startedAt = 2_000L,
        endedAt = 3_000L,
        audioFile = "segment-1.aac",
        state = state,
        transcript = transcript,
        revision = revision,
    )

    private class TestContext : ContextWrapper(null) {
        private val root = Files.createTempDirectory("recording-worker-test").toFile()

        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
    }
}
