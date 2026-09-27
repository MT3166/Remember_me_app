package com.littlediary.app

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingRepositoryTest {
    @Test
    fun localDayKeyUsesDeviceZoneAndCrossesMidnightCorrectly() {
        val beforeMidnight = Instant.parse("2026-09-19T15:59:59Z").toEpochMilli()
        val afterMidnight = Instant.parse("2026-09-19T16:00:00Z").toEpochMilli()
        val zone = ZoneId.of("Asia/Shanghai")

        assertEquals("2026-09-19", localDayKey(beforeMidnight, zone))
        assertEquals("2026-09-20", localDayKey(afterMidnight, zone))
    }

    @Test
    fun repositoryUsesConfiguredZoneForDayGrouping() {
        val repository = RecordingRepository(TestContext(), zone = ZoneId.of("UTC"))
        val timestamp = Instant.parse("2026-09-19T23:30:00Z").toEpochMilli()

        assertEquals("2026-09-19", repository.dayKey(timestamp))
    }

    @Test
    fun recordingSegmentSurvivesJsonRoundTrip() {
        val segment = sampleSegment(
            state = RecordingState.RETRYABLE_ERROR,
            transcript = "今天去了公园 🌳",
            error = "offline",
            retryCount = 2,
            revision = 4,
        )

        assertEquals(segment, RecordingRepository.decode(RecordingRepository.encode(segment)))
    }

    @Test
    fun staleRevisionCannotOverwriteNewerSegment() {
        val context = TestContext()
        val repository = RecordingRepository(context)
        val original = sampleSegment()
        val newer = original.copy(state = RecordingState.TRANSCRIBING, revision = 2)
        val stale = original.copy(state = RecordingState.RETRYABLE_ERROR, revision = 1)

        repository.insert(original)
        repository.update(newer)
        repository.update(stale)

        assertEquals(newer, repository.get(original.id))
    }

    @Test
    fun staleRevisionFromAnotherRepositoryCannotOverwritePersistedState() {
        val context = TestContext()
        val first = RecordingRepository(context)
        val second = RecordingRepository(context)
        val original = sampleSegment()
        val firstUpdate = original.copy(state = RecordingState.TRANSCRIBING, revision = 1)
        val staleUpdate = original.copy(state = RecordingState.RETRYABLE_ERROR, revision = 1)

        first.insert(original)
        second.get(original.id)
        first.update(firstUpdate)
        second.update(staleUpdate)

        assertEquals(firstUpdate, first.get(original.id))
    }

    @Test
    fun expiredAttachedAudioIsDeletedButPendingAudioIsProtected() {
        val context = TestContext()
        val repository = RecordingRepository(context, AudioRetention.SEVEN)
        val now = Instant.parse("2026-09-20T00:00:00Z").toEpochMilli()
        val expired = sampleSegment(
            createdAt = now - 8 * 24 * 60 * 60 * 1000L,
            state = RecordingState.ATTACHED_TO_DAY,
        )
        val pending = expired.copy(id = "pending", audioFile = "pending.m4a", state = RecordingState.TRANSCRIBING)

        repository.insert(expired)
        repository.insert(pending)
        repository.audioFile(expired).writeText("audio")
        repository.audioFile(pending).writeText("audio")

        assertTrue(repository.deleteAudioIfEligible(expired, now))
        assertFalse(repository.audioFile(expired).exists())
        assertFalse(repository.deleteAudioIfEligible(pending, now))
        assertTrue(repository.audioFile(pending).exists())
    }

    @Test
    fun retryableErrorAndStaleCleanupObjectsCannotDeleteAudio() {
        val context = TestContext()
        val repository = RecordingRepository(context, AudioRetention.SEVEN)
        val now = Instant.parse("2026-09-20T00:00:00Z").toEpochMilli()
        val attached = sampleSegment(createdAt = now - 8 * 24 * 60 * 60 * 1000L, state = RecordingState.ATTACHED_TO_DAY)
        val retryable = attached.copy(state = RecordingState.RETRYABLE_ERROR, revision = 1)

        repository.insert(attached)
        repository.audioFile(attached).writeText("audio")
        repository.update(retryable)

        assertFalse(repository.deleteAudioIfEligible(attached, now))
        assertTrue(repository.audioFile(attached).exists())
    }

    @Test
    fun persistedStateProtectsAudioFromStaleAttachedCleanupObject() {
        val context = TestContext()
        val repository = RecordingRepository(context, AudioRetention.SEVEN)
        val now = Instant.parse("2026-09-20T00:00:00Z").toEpochMilli()
        val saved = sampleSegment(createdAt = now - 8 * 24 * 60 * 60 * 1000L, state = RecordingState.SAVED)
        val staleAttached = saved.copy(state = RecordingState.ATTACHED_TO_DAY)

        repository.insert(saved)
        repository.audioFile(saved).writeText("audio")

        assertFalse(repository.deleteAudioIfEligible(staleAttached, now))
        assertTrue(repository.audioFile(saved).exists())
    }

    @Test
    fun foreverRetentionNeverDeletesExpiredAttachedAudio() {
        val context = TestContext()
        val repository = RecordingRepository(context)
        val now = Instant.parse("2026-09-20T00:00:00Z").toEpochMilli()
        val segment = sampleSegment(createdAt = now - 365 * 24 * 60 * 60 * 1000L, state = RecordingState.ATTACHED_TO_DAY)

        repository.insert(segment)
        repository.audioFile(segment).writeText("audio")

        assertFalse(repository.deleteAudioIfEligible(segment, now))
        assertTrue(repository.audioFile(segment).exists())
    }

    @Test
    fun malformedMetadataDoesNotPreventLoadingValidSegments() {
        val context = TestContext()
        val repository = RecordingRepository(context)
        val valid = sampleSegment()
        val directory = File(context.filesDir, "voice-diary/segments").apply { mkdirs() }

        File(directory, "broken.json").writeText("{not valid json")
        repository.insert(valid)

        assertEquals(listOf(valid), repository.loadAll())
        assertEquals(null, repository.get("broken"))
    }

    private fun sampleSegment(
        id: String = "segment-1",
        createdAt: Long = 1_000L,
        state: RecordingState = RecordingState.SAVED,
        transcript: String = "",
        error: String = "",
        retryCount: Int = 0,
        revision: Long = 0,
    ) = RecordingSegment(
        id = id,
        dayKey = "1970-01-01",
        createdAt = createdAt,
        startedAt = 2_000L,
        endedAt = 3_000L,
        audioFile = "$id.m4a",
        state = state,
        transcript = transcript,
        error = error,
        retryCount = retryCount,
        revision = revision,
    )

    private class TestContext : ContextWrapper(null) {
        private val root = Files.createTempDirectory("recording-repository-test").toFile()

        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
    }
}
