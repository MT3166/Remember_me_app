package com.littlediary.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import android.media.MediaRecorder
import java.io.File
import kotlinx.coroutines.*

class SpeechController(private val context: Context, private val client: SpeechClient = SpeechClient()) {
    var listening by mutableStateOf(false); private set
    var processing by mutableStateOf(false); private set
    var partial by mutableStateOf(""); private set
    var startedAt by mutableStateOf(0L); private set
    var levels by mutableStateOf(List(17) { 0f }); private set
    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var generation = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var upload: Job? = null
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var diaryId = ""
    private var settings = ModelSettings()
    private var deliver: (String) -> Unit = {}
    private val pending = mutableStateMapOf<String, File>()
    fun hasPending(id: String) = pending[id]?.exists() == true
    fun discard(id: String) { pending.remove(id)?.delete() }
    fun close() { cancel(); pending.values.forEach { it.delete() }; pending.clear(); scope.cancel() }
    val active: Boolean get() = listening || processing
    val available: Boolean get() = settings.useMiniMaxSpeech || SpeechRecognizer.isRecognitionAvailable(context)

    fun start(id: String, config: ModelSettings, onText: (String) -> Unit, onError: (String) -> Unit) {
        cancel()
        settings = config
        if (!config.useMiniMaxSpeech) { start(onText, onError); return }
        try { config.requireSpeechKey() } catch (e: IllegalArgumentException) { onError(e.message.orEmpty()); return }
        diaryId = id
        deliver = onText
        if (hasPending(id)) { transcribe(onError); return }
        val token = generation
        try {
            recordingFile = File.createTempFile("speech-", ".aac", context.cacheDir)
            @Suppress("DEPRECATION")
            val device = MediaRecorder()
            recorder = device
            device.setAudioSource(MediaRecorder.AudioSource.MIC)
            device.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
            device.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            device.setAudioChannels(1)
            device.setAudioSamplingRate(16000)
            device.setAudioEncodingBitRate(64000)
            device.setOutputFile(recordingFile!!.absolutePath)
            device.prepare()
            device.start()
            listening = true
            startedAt = SystemClock.elapsedRealtime()
            handler.post(object : Runnable {
                override fun run() {
                    if (generation != token || !listening) return
                    levels = levels.drop(1) + ((recorder?.maxAmplitude ?: 0) / 16000f).coerceIn(0f, 1f)
                    handler.postDelayed(this, 150)
                }
            })
            handler.postDelayed({ if (generation == token && listening) stop(onError) }, 480000)
        } catch (_: Exception) {
            recorder?.release(); recorder = null
            recordingFile?.delete(); recordingFile = null
            cancel()
            onError("无法开始录音，请检查麦克风权限并重试")
        }
    }

    private fun finishRecording(): Boolean {
        val device = recorder ?: return false
        recorder = null
        val file = recordingFile
        recordingFile = null
        val valid = try { device.stop(); file != null && file.length() > 0 } catch (_: Exception) { false }
        device.release()
        listening = false
        if (valid && file != null) pending.put(diaryId, file)?.delete() else file?.delete()
        return valid
    }

    private fun transcribe(onError: (String) -> Unit) {
        val file = pending[diaryId] ?: return
        val owner = diaryId
        val token = generation
        val callback = deliver
        val config = settings
        processing = true
        upload = scope.launch {
            try {
                val text = client.transcribe(config, file)
                if (generation == token) {
                    discard(owner)
                    processing = false
                    callback(text)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (generation == token) onError("${e.message ?: "语音识别失败"}。录音已保留，可重试。")
            } finally { if (generation == token) processing = false }
        }
    }

    fun start(onText: (String) -> Unit, onError: (String) -> Unit) {
        if (!available) {
            onError("平板没有可用的系统语音识别服务，可以先使用键盘语音输入")
            return
        }
        cancel()
        val token = generation
        listening = true
        startedAt = SystemClock.elapsedRealtime()
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).also { service ->
                service.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) = Unit
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) {
                        if (token == generation && listening) levels = levels.drop(1) + (rmsdB / 12f).coerceIn(0f, 1f)
                    }
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() {
                        if (token == generation) { listening = false; processing = true }
                    }
                    override fun onError(error: Int) {
                        if (token != generation) return
                        cancel()
                        onError(when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "这次没有听清楚，再说一次吧"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "需要麦克风权限才能录音"
                            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音识别网络连接失败，可以稍后再试"
                            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "当前语音服务不支持普通话，可以使用键盘语音输入"
                            else -> "语音识别暂时不可用，可以重试或使用键盘输入"
                        })
                    }
                    override fun onResults(results: Bundle?) {
                        if (token != generation) return
                        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        cancel()
                        if (!text.isNullOrBlank()) onText(text) else onError("这次没有听清楚，再说一次吧")
                    }
                    override fun onPartialResults(partialResults: Bundle?) {
                        if (token == generation) partial = partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })
                service.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                })
            }
            handler.postDelayed({ if (token == generation && active) stop(onError) }, 60000)
        } catch (_: Exception) {
            cancel()
            onError("无法启动语音识别，可以使用键盘语音输入")
        }
    }

    fun stop(onError: (String) -> Unit) {
        if (settings.useMiniMaxSpeech) {
            if (!listening) return
            handler.removeCallbacksAndMessages(null)
            if (finishRecording()) transcribe(onError) else onError("录音太短，请再说一次")
            return
        }
        if (!active) return
        listening = false
        processing = true
        recognizer?.stopListening()
        val token = generation
        handler.postDelayed({
            if (generation == token && processing) {
                cancel()
                onError("语音识别等待超时，请再试一次")
            }
        }, 15000)
    }

    fun cancel() {
        generation++
        upload?.cancel(); upload = null
        finishRecording()
        handler.removeCallbacksAndMessages(null)
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        listening = false
        processing = false
        partial = ""
        startedAt = 0L
        levels = List(17) { 0f }
    }
}
