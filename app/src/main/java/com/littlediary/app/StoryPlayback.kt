package com.littlediary.app

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.Locale
import java.util.UUID

interface StoryPlayback {
    fun playStory(story: DailyStory)
    fun playOriginalSegments(segments: List<RecordingSegment>)
    fun pause()
    fun stop()
}

internal interface StorySpeech {
    fun speak(text: String)
    fun pause()
    fun stop()
}

internal interface StoryAudioPlayer {
    fun prepare()
    fun start()
    fun pause()
    fun stop()
    fun release()
}

internal class StorySpeechSession {
    private var currentToken: String? = null

    fun begin(token: String) {
        currentToken = token
    }

    fun callbackToken(utteranceId: String?, terminal: Boolean = false): String? {
        val token = currentToken ?: return null
        if (utteranceId == null || !utteranceId.startsWith("$token-")) return null
        if (terminal && !utteranceId.endsWith("-end")) return null
        return token
    }

    fun isCurrent(token: String, utteranceId: String?, terminal: Boolean = false): Boolean =
        callbackToken(utteranceId, terminal) == token

    fun finish(token: String) {
        if (currentToken == token) currentToken = null
    }

    fun clear() {
        currentToken = null
    }
}

internal class StoryPlaybackController(
    private val speech: StorySpeech,
    private val playerFactory: (String, () -> Unit, () -> Unit) -> StoryAudioPlayer,
    private val fallbackSegments: (DailyStory) -> List<RecordingSegment> = { emptyList() },
    private val audioPath: (RecordingSegment) -> String = { it.audioFile },
    private val onPlaybackChanged: (Boolean) -> Unit = {},
    private val onPlaybackError: (String) -> Unit = {},
) : StoryPlayback {
    private val pendingSegments = ArrayDeque<RecordingSegment>()
    private var activePlayer: StoryAudioPlayer? = null
    private var speechActive = false

    override fun playStory(story: DailyStory) {
        stop()
        val body = story.finalBody.ifBlank { story.draftBody }
        if (body.isNotBlank()) {
            speechActive = true
            onPlaybackChanged(true)
            speech.speak(body)
        } else {
            playOriginalSegmentsInternal(fallbackSegments(story))
        }
    }

    override fun playOriginalSegments(segments: List<RecordingSegment>) {
        stop()
        playOriginalSegmentsInternal(segments)
    }

    override fun pause() {
        if (activePlayer != null) activePlayer?.pause()
        else if (speechActive) speech.pause()
        onPlaybackChanged(false)
    }

    override fun stop() {
        pendingSegments.clear()
        activePlayer?.let { player ->
            activePlayer = null
            runCatching { player.stop() }
            player.release()
        }
        if (speechActive) speech.stop()
        speechActive = false
        onPlaybackChanged(false)
    }

    private fun playOriginalSegmentsInternal(segments: List<RecordingSegment>) {
        pendingSegments.addAll(segments.sortedWith(compareBy<RecordingSegment> { it.startedAt }.thenBy { it.id }))
        playNextSegment()
    }

    private fun playNextSegment() {
        val segment = pendingSegments.removeFirstOrNull()
        if (segment == null) {
            onPlaybackChanged(false)
            return
        }

        lateinit var player: StoryAudioPlayer
        player = try {
            playerFactory(
                audioPath(segment),
                { if (activePlayer === player) {
                    activePlayer = null
                    player.release()
                    playNextSegment()
                } },
                { handlePlayerError(player) },
            )
        } catch (_: Exception) {
            playNextSegment()
            return
        }
        activePlayer = player
        try {
            player.prepare()
            player.start()
            if (activePlayer === player) onPlaybackChanged(true)
        } catch (_: Exception) {
            if (activePlayer === player) {
                activePlayer = null
                runCatching { player.stop() }
                player.release()
                playNextSegment()
            }
        }
    }

    private fun handlePlayerError(player: StoryAudioPlayer) {
        if (activePlayer !== player) return
        activePlayer = null
        pendingSegments.clear()
        runCatching { player.stop() }
        player.release()
        onPlaybackChanged(false)
        onPlaybackError("原始录音暂时无法播放")
    }
}

class AndroidStoryPlayback(
    context: Context,
    private val recordingRepository: RecordingRepository = RecordingRepository(context),
    private val onPlaybackChanged: (Boolean) -> Unit = {},
    private val onError: (String) -> Unit = {},
) : StoryPlayback, AutoCloseable {
    private lateinit var controller: StoryPlaybackController
    private val speech = AndroidStorySpeech(context, onPlaybackChanged, onError)

    init {
        controller = StoryPlaybackController(
            speech = speech,
            playerFactory = { path, onComplete, onPlayerError ->
                AndroidStoryAudioPlayer(path, onComplete, onPlayerError)
            },
            fallbackSegments = { story ->
                val ids = story.segmentIds.toSet()
                recordingRepository.loadAll().filter { it.dayKey == story.dayKey && it.id in ids }
            },
            audioPath = recordingRepository::audioFilePath,
            onPlaybackChanged = onPlaybackChanged,
            onPlaybackError = onError,
        )
    }

    override fun playStory(story: DailyStory) = controller.playStory(story)
    override fun playOriginalSegments(segments: List<RecordingSegment>) = controller.playOriginalSegments(segments)
    override fun pause() = controller.pause()
    override fun stop() = controller.stop()
    override fun close() {
        controller.stop()
        speech.close()
    }
}

private fun RecordingRepository.audioFilePath(segment: RecordingSegment): String = audioFile(segment).absolutePath

private class AndroidStorySpeech(
    context: Context,
    private val onPlaybackChanged: (Boolean) -> Unit,
    private val onError: (String) -> Unit,
) : StorySpeech {
    private val main = Handler(Looper.getMainLooper())
    private val session = StorySpeechSession()
    private var ready = false
    private lateinit var engine: TextToSpeech

    init {
        engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ready = engine.setLanguage(Locale.SIMPLIFIED_CHINESE) >= 0
                engine.setSpeechRate(.85f)
            }
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                val token = session.callbackToken(utteranceId, terminal = true) ?: return
                main.post {
                    if (session.isCurrent(token, utteranceId, terminal = true)) {
                        session.finish(token)
                        onPlaybackChanged(false)
                    }
                }
            }

            @Deprecated("Platform callback")
            override fun onError(utteranceId: String?) {
                val token = session.callbackToken(utteranceId) ?: return
                main.post {
                    if (session.isCurrent(token, utteranceId)) {
                        session.finish(token)
                        onPlaybackChanged(false)
                        onError("朗读暂时不可用，请检查设备的中文音色")
                    }
                }
            }
        })
    }

    override fun speak(text: String) {
        if (!ready) {
            session.clear()
            onPlaybackChanged(false)
            onError("当前设备没有可用的中文朗读音色")
            return
        }
        val token = UUID.randomUUID().toString()
        session.begin(token)
        val chunks = splitForSpeech(text, TextToSpeech.getMaxSpeechInputLength() - 1)
        chunks.forEachIndexed { index, chunk ->
            val id = "$token-${if (index == chunks.lastIndex) "end" else index}"
            val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            if (engine.speak(chunk, queueMode, null, id) != TextToSpeech.SUCCESS) {
                stop()
                onError("朗读暂时不可用，请检查设备的中文音色")
                return
            }
        }
    }

    override fun pause() { session.clear(); engine.stop() }
    override fun stop() { session.clear(); engine.stop() }

    fun close() {
        engine.stop()
        engine.shutdown()
        main.removeCallbacksAndMessages(null)
    }
}

private class AndroidStoryAudioPlayer(
    path: String,
    onComplete: () -> Unit,
    onError: () -> Unit,
) : StoryAudioPlayer {
    private val player = MediaPlayer()

    init {
        player.setDataSource(File(path).absolutePath)
        player.setOnCompletionListener { onComplete() }
        player.setOnErrorListener { _, _, _ -> onError(); true }
    }

    override fun prepare() = player.prepare()
    override fun start() = player.start()
    override fun pause() = player.pause()
    override fun stop() = player.stop()
    override fun release() = player.release()
}
