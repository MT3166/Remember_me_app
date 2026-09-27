package com.littlediary.app

import android.content.ContextWrapper
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyStoryRepositoryTest {
    @Test
    fun localDayKeyUsesConfiguredZoneAtMidnightBoundary() {
        val repository = DailyStoryRepository(TestContext(), ZoneId.of("Asia/Shanghai"))
        val beforeMidnight = Instant.parse("2026-09-19T15:59:59Z").toEpochMilli()
        val afterMidnight = Instant.parse("2026-09-19T16:00:00Z").toEpochMilli()

        assertEquals("2026-09-19", repository.dayKey(beforeMidnight))
        assertEquals("2026-09-20", repository.dayKey(afterMidnight))
    }

    @Test
    fun appendUsesCreationOrderAndUpdatesSourceFingerprint() {
        val repository = DailyStoryRepository(TestContext(), ZoneId.of("UTC"))
        val later = Instant.parse("2026-09-19T10:01:00Z").toEpochMilli()
        val earlier = Instant.parse("2026-09-19T10:00:00Z").toEpochMilli()

        repository.appendSegment("later", later, "后来")
        val story = repository.appendSegment("earlier", earlier, "先前")

        assertEquals(listOf("earlier", "later"), story.segmentIds)
        assertEquals("先前\n后来", story.sourceText)
        assertTrue(story.sourceFingerprint.isNotBlank())
        assertEquals(2L, story.revision)
    }

    @Test
    fun duplicateSegmentIsIdempotentAcrossRepositoryInstances() {
        val context = TestContext()
        val first = DailyStoryRepository(context, ZoneId.of("UTC"))
        val second = DailyStoryRepository(context, ZoneId.of("UTC"))
        val createdAt = Instant.parse("2026-09-19T10:00:00Z").toEpochMilli()

        val original = first.appendSegment("segment-1", createdAt, "今天去了公园")
        val duplicate = second.appendSegment("segment-1", createdAt + 1_000, "不应重复")

        assertEquals(original, duplicate)
        assertEquals(listOf("segment-1"), second.get("2026-09-19")?.segmentIds)
        assertEquals(1L, second.get("2026-09-19")?.revision)
    }

    @Test
    fun staleRevisionCannotOverwriteNewerStory() {
        val context = TestContext()
        val first = DailyStoryRepository(context, ZoneId.of("UTC"))
        val second = DailyStoryRepository(context, ZoneId.of("UTC"))
        val createdAt = Instant.parse("2026-09-19T10:00:00Z").toEpochMilli()

        val original = first.appendSegment("segment-1", createdAt, "原始内容")
        val newer = original.copy(revision = 2, draftBody = "较新的草稿")
        val stale = original.copy(revision = 1, draftBody = "过期草稿")

        first.save(newer)
        second.save(stale)

        assertEquals(newer, first.get("2026-09-19"))
    }

    @Test
    fun storySurvivesAtomicJsonRoundTrip() {
        val context = TestContext()
        val repository = DailyStoryRepository(context, ZoneId.of("UTC"))
        val createdAt = Instant.parse("2026-09-19T10:00:00Z").toEpochMilli()
        val saved = repository.appendSegment("segment-1", createdAt, "今天去了公园")

        val restored = DailyStoryRepository(context, ZoneId.of("UTC"))

        assertEquals(saved, restored.get("2026-09-19"))
        assertNotNull(File(context.filesDir, "voice-diary/stories/2026-09-19.json").takeIf { it.isFile })
    }

    @Test
    fun newSourceProducesDifferentFingerprint() {
        val repository = DailyStoryRepository(TestContext(), ZoneId.of("UTC"))
        val first = repository.appendSegment("segment-1", 1_000L, "第一段")
        val second = repository.appendSegment("segment-2", 2_000L, "第二段")

        assertNotEquals(first.sourceFingerprint, second.sourceFingerprint)
    }

    @Test
    fun replaceIfCurrentRequiresBothRevisionAndFingerprint() {
        val repository = DailyStoryRepository(TestContext(), ZoneId.of("UTC"))
        val original = repository.appendSegment("segment-1", 1_000L, "第一段")
        val replacement = original.copy(
            revision = original.revision + 1,
            draftBody = "新草稿",
        )

        assertFalse(
            repository.replaceIfCurrent(
                original.dayKey,
                original.revision - 1,
                original.sourceFingerprint,
                replacement,
            ),
        )
        assertFalse(
            repository.replaceIfCurrent(
                original.dayKey,
                original.revision,
                "tampered-fingerprint",
                replacement,
            ),
        )
        assertTrue(
            repository.replaceIfCurrent(
                original.dayKey,
                original.revision,
                original.sourceFingerprint,
                replacement,
            ),
        )
        assertEquals(replacement, repository.get(original.dayKey))
    }

    @Test
    fun loadAllSkipsFilesWithInvalidDayKeys() {
        val context = TestContext()
        val repository = DailyStoryRepository(context, ZoneId.of("UTC"))
        val directory = File(context.filesDir, "voice-diary/stories").apply { mkdirs() }

        File(directory, "not-a-day.json").writeText("{}")
        File(directory, "2026-99-99.json").writeText("{}")

        assertTrue(repository.loadAll().isEmpty())
        assertEquals(null, repository.get("2026-09-19"))
    }

    @Test
    fun maliciousStoryWithMismatchedOrDuplicateSegmentsIsIgnored() {
        val context = TestContext()
        val repository = DailyStoryRepository(context, ZoneId.of("UTC"))
        val directory = File(context.filesDir, "voice-diary/stories").apply { mkdirs() }
        val json = """
            {
              "dayKey":"2026-09-19",
              "segmentIds":["real-segment"],
              "sourceText":"真实内容",
              "revision":1,
              "sourceFingerprint":"",
              "state":"COLLECTING",
              "segments":[{"id":"forged-segment","text":"伪造内容","createdAt":1}]
            }
        """.trimIndent()
        File(directory, "2026-09-19.json").writeText(json)

        assertEquals(null, repository.get("2026-09-19"))

        File(directory, "2026-09-20.json").writeText(
            json.replace("2026-09-19", "2026-09-20")
                .replace("real-segment", "duplicate-segment\",\"duplicate-segment")
                .replace(
                    "{\"id\":\"forged-segment\",\"text\":\"伪造内容\",\"createdAt\":1}",
                    "{\"id\":\"duplicate-segment\",\"text\":\"重复内容\",\"createdAt\":1},{\"id\":\"duplicate-segment\",\"text\":\"重复内容\",\"createdAt\":2}",
                ),
        )
        assertEquals(null, repository.get("2026-09-20"))
    }

    @Test
    fun maliciousStoryWithWrongFingerprintIsIgnored() {
        val context = TestContext()
        val repository = DailyStoryRepository(context, ZoneId.of("UTC"))
        val directory = File(context.filesDir, "voice-diary/stories").apply { mkdirs() }
        val json = """
            {
              "dayKey":"2026-09-19",
              "segmentIds":["segment-1"],
              "sourceText":"真实内容",
              "revision":1,
              "sourceFingerprint":"tampered-fingerprint",
              "state":"COLLECTING",
              "segments":[{"id":"segment-1","text":"真实内容","createdAt":1}]
            }
        """.trimIndent()
        File(directory, "2026-09-19.json").writeText(json)

        assertEquals(null, repository.get("2026-09-19"))
    }

    @Test
    fun validLegacyStoryWithoutSegmentMetadataStillLoads() {
        val context = TestContext()
        val repository = DailyStoryRepository(context, ZoneId.of("UTC"))
        val directory = File(context.filesDir, "voice-diary/stories").apply { mkdirs() }
        File(directory, "2026-09-21.json").writeText(
            """
                {
                  "dayKey":"2026-09-21",
                  "segmentIds":["legacy-segment"],
                  "sourceText":"旧内容",
                  "revision":1,
                  "sourceFingerprint":"",
                  "state":"COLLECTING"
                }
            """.trimIndent(),
        )

        assertEquals(listOf("legacy-segment"), repository.get("2026-09-21")?.segmentIds)
        assertEquals("旧内容", repository.get("2026-09-21")?.sourceText)
    }

    private class TestContext : ContextWrapper(null) {
        private val root = Files.createTempDirectory("daily-story-repository-test").toFile()

        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
    }
}
