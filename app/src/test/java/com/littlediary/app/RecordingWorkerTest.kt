package com.littlediary.app

import android.content.Context
import android.content.ContextWrapper
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
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
    fun http429ServiceFailureReturnsRetryWithoutPermanentError() = runBlocking {
        assertTemporaryServiceFailureRetries(429)
    }

    @Test
    fun http503ServiceFailureReturnsRetryWithoutPermanentError() = runBlocking {
        assertTemporaryServiceFailureRetries(503)
    }

    @Test
    fun schedulerBuildsRealRequestAndEnqueuesStableUniqueWorkWithKeepPolicy() {
        val names = mutableListOf<String>()
        val policies = mutableListOf<ExistingWorkPolicy>()
        val requests = mutableListOf<OneTimeWorkRequest>()
        val scheduler = RecordingWorkScheduler(TestContext()) { name, policy, request ->
            names += name
            policies += policy
            requests += request
        }

        scheduler.enqueueTranscription("segment-42")
        scheduler.enqueueTranscription("segment-42")

        assertEquals(listOf("recording-transcription-segment-42", "recording-transcription-segment-42"), names)
        assertEquals(listOf(ExistingWorkPolicy.KEEP, ExistingWorkPolicy.KEEP), policies)
        assertEquals(2, requests.size)
        requests.forEach { request ->
            assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
            assertEquals(BackoffPolicy.EXPONENTIAL, request.workSpec.backoffPolicy)
            assertEquals(30_000L, request.workSpec.backoffDelayDuration)
            assertEquals("segment-42", request.workSpec.input.getString(RecordingWorker.INPUT_SEGMENT_ID))
        }
    }

    @Test
    fun workerOutcomeMapsToWorkManagerResults() {
        assertTrue(RecordingWorkOutcome.SUCCESS.toWorkerResult() is ListenableWorker.Result.Success)
        assertTrue(RecordingWorkOutcome.RETRY.toWorkerResult() is ListenableWorker.Result.Retry)
        assertTrue(RecordingWorkOutcome.FAILURE.toWorkerResult() is ListenableWorker.Result.Failure)
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

    private suspend fun assertTemporaryServiceFailureRetries(status: Int) {
        val context = TestContext()
        val repository = RecordingRepository(context)
        val segment = sampleSegment()
        repository.insert(segment)
        repository.audioFile(segment).writeBytes(byteArrayOf(1, 2, 3))
        val transcribe: suspend (ModelSettings, File) -> String = { _, _ ->
            throw IllegalStateException("模型服务暂时无法完成请求（HTTP $status）")
        }

        val result = RecordingWorkerProcessor(repository, { ModelSettings(textKey = "test-key") }, transcribe)
            .process(segment.id)

        assertEquals(RecordingWorkOutcome.RETRY, result)
        assertEquals(RecordingState.TRANSCRIBING, repository.get(segment.id)?.state)
        assertEquals(1, repository.get(segment.id)?.retryCount)
    }

    private class TestContext : ContextWrapper(null) {
        private val root = Files.createTempDirectory("recording-worker-test").toFile()

        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
    }
}
