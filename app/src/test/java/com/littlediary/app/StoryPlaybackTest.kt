package com.littlediary.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryPlaybackTest {
    @Test
    fun finalBodyIsPreferredOverDraftBody() {
        val speech = RecordingSpeech()
        val playback = playback(speech)

        playback.playStory(DailyStory("2026-09-28", draftBody = "草稿", finalBody = "最终故事"))

        assertEquals(listOf("最终故事"), speech.spoken)
    }

    @Test
    fun draftBodyIsUsedWhenFinalBodyIsMissing() {
        val speech = RecordingSpeech()
        val playback = playback(speech)

        playback.playStory(DailyStory("2026-09-28", draftBody = "先听到的草稿"))

        assertEquals(listOf("先听到的草稿"), speech.spoken)
    }

    @Test
    fun originalSegmentsAreFallbackWhenStoryBodiesAreMissing() {
        val speech = RecordingSpeech()
        val players = mutableListOf<RecordingPlayer>()
        val segments = listOf(segment("first", startedAt = 100L))
        val playback = playback(speech, players, fallback = { segments })

        playback.playStory(DailyStory("2026-09-28", segmentIds = listOf("first")))

        assertEquals(listOf("first.aac"), players.map { it.path })
        assertTrue(speech.spoken.isEmpty())
    }

    @Test
    fun originalSegmentsPlayInStartTimeOrder() {
        val speech = RecordingSpeech()
        val players = mutableListOf<RecordingPlayer>()
        val playback = playback(speech, players)

        playback.playOriginalSegments(listOf(
            segment("late", startedAt = 300L),
            segment("early", startedAt = 100L),
        ))
        players.single().complete()

        assertEquals(listOf("early.aac", "late.aac"), players.map { it.path })
    }

    @Test
    fun switchingSourcesStopsCurrentSourceBeforeStartingNext() {
        val speech = RecordingSpeech()
        val players = mutableListOf<RecordingPlayer>()
        val playback = playback(speech, players)

        playback.playOriginalSegments(listOf(segment("first", 100L)))
        playback.playStory(DailyStory("2026-09-28", finalBody = "故事"))

        assertTrue(players.single().stopped)
        assertEquals(listOf("故事"), speech.spoken)
    }

    @Test
    fun pauseAndStopAreForwardedToActiveSource() {
        val speech = RecordingSpeech()
        val players = mutableListOf<RecordingPlayer>()
        val playback = playback(speech, players)

        playback.playOriginalSegments(listOf(segment("first", 100L)))
        playback.pause()
        playback.stop()

        assertTrue(players.single().paused)
        assertTrue(players.single().stopped)
        assertEquals(0, speech.stopCalls)
    }

    @Test
    fun staleTtsCallbacksCannotCompleteOrFailTheNextStory() {
        val session = StorySpeechSession()
        session.begin("old")
        val oldDone = session.callbackToken("old-end", terminal = true)
        val oldError = session.callbackToken("old-0")

        session.begin("new")

        assertFalse(session.isCurrent(oldDone!!, "old-end", terminal = true))
        assertFalse(session.isCurrent(oldError!!, "old-0"))
        assertNull(session.callbackToken("old-end", terminal = true))
        assertEquals("new", session.callbackToken("new-end", terminal = true))
    }

    @Test
    fun playerErrorStopsReleasesAndEndsTheCurrentPlayback() {
        val speech = RecordingSpeech()
        val players = mutableListOf<RecordingPlayer>()
        val states = mutableListOf<Boolean>()
        val errors = mutableListOf<String>()
        val playback = playback(
            speech = speech,
            players = players,
            onPlaybackChanged = states::add,
            onPlaybackError = errors::add,
        )

        playback.playOriginalSegments(listOf(segment("first", 100L), segment("second", 200L)))
        players.single().fail()

        assertTrue(players.single().stopped)
        assertTrue(players.single().released)
        assertEquals(listOf(false, true, false), states)
        assertEquals(listOf("原始录音暂时无法播放"), errors)
        assertEquals(1, players.size)
    }

    @Test
    fun playbackStoryKeepsDailyStorySegmentIds() {
        val dayKey = "2026-09-28"
        val story = DailyStory(dayKey = dayKey, segmentIds = listOf("story-segment"), finalBody = "故事")
        val diary = Diary(createdAt = dayStart(dayKey), body = "日记正文")

        val playbackStory = storyForPlayback(diary, DiaryStage.READ, story, emptyList())

        assertEquals(listOf("story-segment"), playbackStory.segmentIds)
        assertEquals("日记正文", playbackStory.finalBody)
    }

    @Test
    fun playbackStoryUsesCurrentDayRecordingsWhenStoryBodyIsMissing() {
        val dayKey = "2026-09-28"
        val diary = Diary(createdAt = dayStart(dayKey), body = "")
        val recordings = listOf(
            segment("later", 300L),
            segment("other-day", 100L).copy(dayKey = "2026-09-27"),
            segment("earlier", 100L),
        )

        val playbackStory = storyForPlayback(diary, DiaryStage.READ, null, recordings)

        assertEquals(listOf("earlier", "later"), playbackStory.segmentIds)
        assertTrue(playbackStory.finalBody.isBlank())
    }

    private fun playback(
        speech: RecordingSpeech,
        players: MutableList<RecordingPlayer> = mutableListOf(),
        fallback: (DailyStory) -> List<RecordingSegment> = { emptyList() },
        onPlaybackChanged: (Boolean) -> Unit = {},
        onPlaybackError: (String) -> Unit = {},
    ) = StoryPlaybackController(
        speech = speech,
        playerFactory = { path, onComplete, onError -> RecordingPlayer(path, onComplete, onError).also(players::add) },
        fallbackSegments = fallback,
        audioPath = { it.audioFile },
        onPlaybackChanged = onPlaybackChanged,
        onPlaybackError = onPlaybackError,
    )

    private fun dayStart(dayKey: String): Long = java.time.LocalDate.parse(dayKey)
        .atStartOfDay(DEFAULT_RECORDING_ZONE).toInstant().toEpochMilli()

    private fun segment(id: String, startedAt: Long) = RecordingSegment(
        id = id,
        dayKey = "2026-09-28",
        createdAt = startedAt,
        startedAt = startedAt,
        endedAt = startedAt + 10,
        audioFile = "$id.aac",
    )

    private class RecordingSpeech : StorySpeech {
        val spoken = mutableListOf<String>()
        var stopCalls = 0

        override fun speak(text: String) { spoken += text }
        override fun pause() = Unit
        override fun stop() { stopCalls++ }
    }

    private class RecordingPlayer(
        val path: String,
        private val onComplete: () -> Unit,
        private val onError: () -> Unit,
    ) : StoryAudioPlayer {
        var paused = false
        var stopped = false
        var released = false

        override fun prepare() = Unit
        override fun start() = Unit
        override fun pause() { paused = true }
        override fun stop() { stopped = true }
        override fun release() { released = true }
        fun complete() = onComplete()
        fun fail() = onError()
    }
}
