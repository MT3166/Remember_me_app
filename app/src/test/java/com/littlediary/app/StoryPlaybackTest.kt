package com.littlediary.app

import org.junit.Assert.assertEquals
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

    private fun playback(
        speech: RecordingSpeech,
        players: MutableList<RecordingPlayer> = mutableListOf(),
        fallback: (DailyStory) -> List<RecordingSegment> = { emptyList() },
    ) = StoryPlaybackController(
        speech = speech,
        playerFactory = { path, onComplete -> RecordingPlayer(path, onComplete).also(players::add) },
        fallbackSegments = fallback,
        audioPath = { it.audioFile },
    )

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
    ) : StoryAudioPlayer {
        var paused = false
        var stopped = false

        override fun prepare() = Unit
        override fun start() = Unit
        override fun pause() { paused = true }
        override fun stop() { stopped = true }
        override fun release() = Unit
        fun complete() = onComplete()
    }
}
