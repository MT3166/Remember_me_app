package com.littlediary.app

import android.content.Context
import android.content.ContextWrapper
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyStoryWorkerTest {
    @Test
    fun transcribedSegmentIsAttachedOnceAndSchedulesDebouncedDraft() = runBlocking {
        val context = TestContext()
        val recordings = RecordingRepository(context)
        val stories = DailyStoryRepository(context)
        val segment = sampleSegment(state = RecordingState.SAVED)
        recordings.insert(segment)
        recordings.audioFile(segment).writeBytes(byteArrayOf(1, 2, 3))
        val scheduled = mutableListOf<String>()

        val processor = RecordingWorkerProcessor(
            repository = recordings,
            settings = { ModelSettings(textKey = "test-key") },
            transcribe = { _, _ -> "我今天去了公园" },
            dailyStoryRepository = stories,
            enqueueDraft = { scheduled += it },
        )

        assertEquals(RecordingWorkOutcome.SUCCESS, processor.process(segment.id))
        assertEquals(listOf(segment.dayKey), scheduled)
        val attached = stories.get(segment.dayKey)
        assertEquals(listOf(segment.id), attached?.segmentIds)
        assertEquals("我今天去了公园", attached?.sourceText)

        assertEquals(RecordingWorkOutcome.SUCCESS, processor.process(segment.id))
        assertEquals(listOf(segment.dayKey), scheduled)
        assertEquals(1, stories.get(segment.dayKey)?.segmentIds?.size)
    }

    @Test
    fun draftSchedulerReplacesSameDayWorkAndCarriesRevisionAndFingerprint() {
        val context = TestContext()
        val stories = DailyStoryRepository(context)
        val story = stories.appendSegment("2026-09-27", "segment-1", "我看到了小鸟", 1_000L)
        val names = mutableListOf<String>()
        val policies = mutableListOf<ExistingWorkPolicy>()
        val requests = mutableListOf<OneTimeWorkRequest>()
        val scheduler = DailyStoryWorkScheduler(context, { name, policy, request ->
            names += name
            policies += policy
            requests += request
        }, stories)

        scheduler.enqueueDraft(story.dayKey)
        scheduler.enqueueDraft(story.dayKey)

        assertEquals(listOf(DailyStoryWorkScheduler.draftWorkName(story.dayKey), DailyStoryWorkScheduler.draftWorkName(story.dayKey)), names)
        assertEquals(listOf(ExistingWorkPolicy.REPLACE, ExistingWorkPolicy.REPLACE), policies)
        assertEquals(2, requests.size)
        requests.forEach { request ->
            assertEquals(story.dayKey, request.workSpec.input.getString(DailyStoryWorker.INPUT_DAY_KEY))
            assertEquals(story.revision, request.workSpec.input.getLong(DailyStoryWorker.INPUT_EXPECTED_REVISION, -1L))
            assertEquals(story.sourceFingerprint, request.workSpec.input.getString(DailyStoryWorker.INPUT_EXPECTED_FINGERPRINT))
            assertEquals(DailyStoryWorkScheduler.DRAFT_DEBOUNCE_MS, request.workSpec.initialDelay)
        }
    }

    @Test
    fun staleStoryWorkCannotOverwriteNewerSourceRevision() = runBlocking {
        val context = TestContext()
        val stories = DailyStoryRepository(context)
        val original = stories.appendSegment("2026-09-27", "segment-1", "第一段", 1_000L)
        val processor = DailyStoryWorkerProcessor(
            repository = stories,
            settings = { ModelSettings(textKey = "test-key") },
            generateStory = { _, _, _ -> StoryResult("旧故事", "旧版本") },
            generateCover = { _, _ -> error("不应生成封面") },
        )
        stories.appendSegment(original.dayKey, "segment-2", "第二段", 2_000L)

        assertEquals(
            DailyStoryWorkOutcome.SUCCESS,
            processor.process(original.dayKey, original.revision, original.sourceFingerprint, final = false),
        )
        val current = stories.get(original.dayKey)!!
        assertEquals(2L, current.revision)
        assertEquals("", current.draftBody)
        assertTrue(current.sourceText.contains("第二段"))
    }

    @Test
    fun startupFinalizationOnlyEnqueuesPreviousDaysThatAreNotReady() {
        val context = TestContext()
        val stories = DailyStoryRepository(context)
        val pending = stories.appendSegment("2026-09-26", "pending", "昨天的素材", 1_000L)
        val ready = stories.appendSegment("2026-09-25", "ready", "更早的素材", 2_000L)
            .copy(state = DailyStoryState.READY, finalTitle = "已经完成", finalBody = "已经完成")
        stories.save(ready)
        val finalized = mutableListOf<String>()
        val scheduler = DailyStoryWorkScheduler(context, { name, _, _ ->
            if (name.startsWith("daily-story-final-")) finalized += name.removePrefix("daily-story-final-")
        }, stories)

        scheduler.enqueueFinalForPreviousDays(now = Instant.parse("2026-09-28T00:00:00Z").toEpochMilli())

        assertEquals(listOf(pending.dayKey), finalized)
    }

    @Test
    fun storyFailurePreservesPreviousPlayableVersion() = runBlocking {
        val context = TestContext()
        val stories = DailyStoryRepository(context)
        val previous = stories.appendSegment("2026-09-27", "segment-1", "原始素材", 1_000L)
            .copy(
                draftTitle = "上一版标题",
                draftBody = "上一版正文",
                finalTitle = "可播放标题",
                finalBody = "可播放正文",
                state = DailyStoryState.READY,
            )
        stories.save(previous)
        val processor = DailyStoryWorkerProcessor(
            repository = stories,
            settings = { ModelSettings(textKey = "test-key") },
            generateStory = { _, _, _ -> throw IOException("offline") },
            generateCover = { _, _ -> error("不应生成封面") },
        )

        assertEquals(
            DailyStoryWorkOutcome.RETRY,
            processor.process(previous.dayKey, previous.revision, previous.sourceFingerprint, final = true),
        )
        assertEquals(previous, stories.get(previous.dayKey))
    }

    @Test
    fun finalStorySchedulesCoverAndCoverFailureLeavesDefaultIllustration() = runBlocking {
        val context = TestContext()
        val stories = DailyStoryRepository(context)
        val source = stories.appendSegment("2026-09-27", "segment-1", "我和妈妈看云", 1_000L)
        val scheduledCovers = mutableListOf<String>()
        var coverCalls = 0
        val processor = DailyStoryWorkerProcessor(
            repository = stories,
            settings = { ModelSettings(textKey = "test-key") },
            generateStory = { _, _, _ -> StoryResult("云朵故事", "我和妈妈看云。") },
            generateCover = { _, _ ->
                coverCalls++
                throw IOException("image offline")
            },
            enqueueCover = { scheduledCovers += it.dayKey },
            writeCover = { _, _ -> "generated.png" },
        )

        assertEquals(
            DailyStoryWorkOutcome.SUCCESS,
            processor.process(source.dayKey, source.revision, source.sourceFingerprint, final = true),
        )
        val finalStory = stories.get(source.dayKey)!!
        assertEquals(DailyStoryState.READY, finalStory.state)
        assertEquals("我和妈妈看云。", finalStory.finalBody)
        assertEquals(listOf(source.dayKey), scheduledCovers)

        assertEquals(
            DailyStoryWorkOutcome.RETRY,
            processor.processCover(finalStory.dayKey, finalStory.revision, finalStory.sourceFingerprint),
        )
        assertEquals(1, coverCalls)
        assertFalse(stories.get(source.dayKey)!!.coverFile.isNotBlank())
    }

    private fun sampleSegment(state: RecordingState) = RecordingSegment(
        id = "segment-1",
        dayKey = "2026-09-27",
        createdAt = 1_000L,
        startedAt = 1_000L,
        endedAt = 2_000L,
        audioFile = "segment-1.aac",
        state = state,
    )

    private class TestContext : ContextWrapper(null) {
        private val root = Files.createTempDirectory("daily-story-worker-test").toFile()

        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        override fun getNoBackupFilesDir(): File = File(root, "no-backup").apply { mkdirs() }
    }
}
