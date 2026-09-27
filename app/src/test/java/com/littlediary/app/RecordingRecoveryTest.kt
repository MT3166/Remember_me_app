package com.littlediary.app

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingRecoveryTest {
    @Test
    fun modelSettingsDefaultToPermanentAudioRetention() {
        assertEquals(AudioRetention.FOREVER, ModelSettings().audioRetention)
    }

    @Test
    fun audioRetentionNamesSupportConfiguredAndLegacyValues() {
        assertEquals(AudioRetention.THIRTY, SettingsStore.decodeAudioRetention("THIRTY"))
        assertEquals(AudioRetention.FOREVER, SettingsStore.decodeAudioRetention(""))
        assertEquals(AudioRetention.FOREVER, SettingsStore.decodeAudioRetention("unknown"))
    }

    @Test
    fun recoveryIncludesSavedTranscribingAndRetryableSegments() {
        assertTrue(RecordingState.SAVED.needsTranscriptionRecovery())
        assertTrue(RecordingState.TRANSCRIBING.needsTranscriptionRecovery())
        assertTrue(RecordingState.RETRYABLE_ERROR.needsTranscriptionRecovery())
        assertFalse(RecordingState.TRANSCRIBED.needsTranscriptionRecovery())
        assertFalse(RecordingState.ATTACHED_TO_DAY.needsTranscriptionRecovery())
    }

    @Test
    fun cleanupDeletesOnlyAttachedAudioAfterRetentionWindow() {
        val context = TestContext()
        val repository = RecordingRepository(context, AudioRetention.SEVEN)
        val now = 8 * DAY
        val attached = sampleSegment("attached", RecordingState.ATTACHED_TO_DAY, createdAt = 0L)
        val pending = sampleSegment("pending", RecordingState.TRANSCRIBING, createdAt = 0L)
        val failed = sampleSegment("failed", RecordingState.RETRYABLE_ERROR, createdAt = 0L)
        listOf(attached, pending, failed).forEach {
            repository.insert(it)
            repository.audioFile(it).writeBytes(byteArrayOf(1, 2, 3))
        }

        assertEquals(1, repository.cleanExpiredAudio(now))
        assertFalse(repository.audioFile(attached).exists())
        assertTrue(repository.audioFile(pending).exists())
        assertTrue(repository.audioFile(failed).exists())
    }

    private fun sampleSegment(id: String, state: RecordingState, createdAt: Long) = RecordingSegment(
        id = id,
        dayKey = "2026-09-19",
        createdAt = createdAt,
        startedAt = createdAt,
        endedAt = createdAt + 1,
        audioFile = "$id.aac",
        state = state,
    )

    private class TestContext : ContextWrapper(null) {
        private val root = Files.createTempDirectory("recording-recovery-test").toFile()

        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        override fun getNoBackupFilesDir(): File = File(root, "no-backup").apply { mkdirs() }
    }

    companion object {
        private const val DAY = 24 * 60 * 60 * 1000L
    }
}
