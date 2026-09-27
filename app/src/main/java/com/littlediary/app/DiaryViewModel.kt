package com.littlediary.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.SocketTimeoutException
import java.util.UUID

class DiaryViewModel(application: Application) : AndroidViewModel(application) {
    val speech = SpeechController(application)
    override fun onCleared() { speech.close(); super.onCleared() }
    val store = DiaryStore(application)
    private val settingsStore = SettingsStore(application)
    private val client = ModelClient()
    private val writes = Channel<Diary>(Channel.UNLIMITED)
    private var operation: Job? = null
    private var operationId = 0L
    private var pendingWrites = 0
    var entries by mutableStateOf<List<Diary>>(emptyList()); private set
    var selectedId by mutableStateOf<String?>(null); private set
    var stage by mutableStateOf(DiaryStage.RECORD); private set
    var inputMode by mutableStateOf(InputMode.VOICE); private set
    var settings by mutableStateOf(ModelSettings()); private set
    var providerDraft by mutableStateOf<ModelSettings?>(null); private set
    var providerTestImage by mutableStateOf<ByteArray?>(null); private set
    var loading by mutableStateOf(true); private set
    var loadFailed by mutableStateOf(false); private set
    var saving by mutableStateOf(false); private set
    var saveFailed by mutableStateOf(false); private set
    var busy by mutableStateOf(""); private set
    var correctionError by mutableStateOf<String?>(null); private set
    var coverError by mutableStateOf<String?>(null); private set
    var message by mutableStateOf<String?>(null); private set
    var recordingNotice by mutableStateOf<String?>(null); private set
    var recordings by mutableStateOf<List<RecordingSegment>>(emptyList()); private set
    val selected: Diary? get() = entries.firstOrNull { it.id == selectedId }
    val hasUnsavedProviderChanges: Boolean get() = providerDraft?.let {
        it.normalized().rememberProfiles() != settings.normalized().rememberProfiles()
    } ?: false

    init {
        viewModelScope.launch {
            for (diary in writes) {
                try {
                    withContext(Dispatchers.IO) { store.save(diary) }
                } catch (e: Exception) {
                    saveFailed = true
                    message = "本机保存失败，请保留当前页面并重试"
                } finally {
                    pendingWrites--
                    saving = pendingWrites > 0
                }
            }
        }
        load()
    }

    fun load() {
        loading = true
        viewModelScope.launch {
            try {
                entries = withContext(Dispatchers.IO) { store.load() }
                loadFailed = false
            } catch (_: Exception) {
                loadFailed = true
                message = "本机日记读取失败，原文件未改动，请重试"
            }
            try { settings = withContext(Dispatchers.IO) { settingsStore.load() } }
            catch (_: Exception) { message = "模型设置无法读取，请家长重新保存设置" }
            try {
                recordings = withContext(Dispatchers.IO) { recoverRecordings(settings.audioRetention) }
                recordingNotice = recordings.latestRecordingNotice()
            } catch (_: Exception) {
                message = "录音队列读取失败，原始录音未改动"
            }
            loading = false
        }
    }

    fun notify(text: String) { message = text }
    fun dismissMessage() { message = null }

    fun finishRecording(file: File, startedAt: Long, endedAt: Long) {
        val segment = RecordingSegment(
            id = UUID.randomUUID().toString(),
            dayKey = localDayKey(startedAt),
            createdAt = endedAt,
            startedAt = startedAt,
            endedAt = endedAt,
            audioFile = file.absolutePath,
        )
        viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    val repository = recordingRepository(settings.audioRetention)
                    repository.insert(segment)
                    repository.cleanExpiredAudio(System.currentTimeMillis())
                    repository.get(segment.id) ?: error("录音元数据保存失败")
                }
                recordingScheduler().enqueueTranscription(saved.id)
                file.delete()
                recordings = withContext(Dispatchers.IO) { recordingRepository(settings.audioRetention).loadAll() }
                recordingNotice = "录音已收好，正在等待识别"
                message = "收好啦"
            } catch (_: Exception) {
                recordingNotice = "录音保存失败，请重试"
                message = "录音保存失败，请检查存储空间后重试"
            }
        }
    }

    fun saveAudioRetention(value: AudioRetention) {
        if (busy.isNotBlank() || value == settings.audioRetention) return
        val next = settings.copy(audioRetention = value)
        val token = operationId
        busy = "正在保存录音设置"
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { settingsStore.save(next) }
                settings = next.normalized().rememberProfiles()
                recordings = withContext(Dispatchers.IO) { recoverRecordings(value) }
                recordingNotice = recordings.latestRecordingNotice()
                message = "录音保留设置已保存"
            } catch (e: Exception) { message = errorMessage(e) }
            finally { if (operationId == token) busy = "" }
        }
    }

    fun newDiary(useVoice: Boolean = true) {
        if (loading || loadFailed) return
        cancelOperation()
        val diary = Diary()
        entries = listOf(diary) + entries
        selectedId = diary.id
        stage = DiaryStage.RECORD
        inputMode = if (useVoice) InputMode.VOICE else InputMode.TEXT
        correctionError = null
        coverError = null
        queueSave(diary)
    }

    fun open(id: String?) {
        cancelOperation()
        selectedId = id
        stage = selected?.initialStage ?: DiaryStage.RECORD
        inputMode = if (selected?.body.isNullOrBlank()) InputMode.VOICE else InputMode.TEXT
        correctionError = null
        coverError = null
    }

    fun showRecord(useVoice: Boolean = true) {
        cancelOperation()
        stage = DiaryStage.RECORD
        inputMode = if (useVoice) InputMode.VOICE else InputMode.TEXT
    }

    fun chooseInputMode(mode: InputMode) { inputMode = mode }

    fun editSavedDiary() {
        cancelOperation()
        stage = DiaryStage.REVIEW
    }

    private fun replace(d: Diary) {
        entries = entries.map { if (it.id == d.id) d else it }
    }

    private fun queueSave(d: Diary) {
        pendingWrites++
        saving = true
        writes.trySend(d)
    }

    private fun update(transform: (Diary) -> Diary) {
        val previous = selected ?: return
        cancelOperation()
        val next = transform(previous).copy(revision = previous.revision + 1)
        replace(next)
        queueSave(next)
    }

    fun editBody(value: String) {
        if (value.length > 12000) { message = "一篇日记最多 12000 字"; return }
        update { it.copy(body = value, confirmed = false, suggestedBody = "", questions = emptyList()) }
    }

    fun editTitle(value: String) = update { it.copy(title = value.take(40)) }

    fun appendSpeech(id: String, value: String): Boolean {
        if (selectedId != id || stage != DiaryStage.RECORD) return false
        val d = selected ?: return false
        val joined = if (d.body.isBlank()) value else "${d.body}\n$value"
        if (joined.length > 12000) { message = "一篇日记最多 12000 字"; return false }
        editBody(joined)
        return true
    }

    fun acceptCorrection() {
        if (selected?.suggestedBody.isNullOrBlank()) return
        update { it.acceptedVersion().copy(confirmed = false) }
    }

    fun rejectCorrection() = update { it.copy(suggestedBody = "", questions = emptyList()) }

    fun cancelOperation() {
        operationId++
        operation?.cancel()
        operation = null
        busy = ""
    }

    fun correct() {
        val d = selected ?: return
        if (d.body.isBlank()) { message = "先写下或说一段日记吧"; return }
        cancelOperation()
        stage = DiaryStage.REVIEW
        correctionError = null
        val snapshot = d.copy(original = d.body, suggestedBody = "", questions = emptyList(), revision = d.revision + 1)
        replace(snapshot)
        queueSave(snapshot)
        val config = settings
        val token = operationId
        busy = "正在整理文字"
        operation = viewModelScope.launch {
            try {
                val result = client.correct(config, snapshot.body)
                ensureActive()
                if (selected?.revision == snapshot.revision && selectedId == snapshot.id) {
                    val next = snapshot.copy(suggestedTitle = result.title, suggestedBody = result.text,
                        questions = result.questions, revision = snapshot.revision + 1)
                    replace(next); queueSave(next)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                correctionError = errorMessage(e)
                message = correctionError
            }
            finally { if (operationId == token) busy = "" }
        }
    }

    fun confirmAndCover(generate: Boolean = true) {
        val d = selected ?: return
        if (d.body.isBlank()) { message = "日记还是空的"; return }
        cancelOperation()
        val snapshot = d.acceptedVersion().copy(confirmed = true, revision = d.revision + 1)
        replace(snapshot)
        coverError = null
        val config = settings
        val token = operationId
        busy = "正在保存"
        operation = viewModelScope.launch {
            try {
                try { withContext(Dispatchers.IO) { store.save(snapshot) } }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    saveFailed = true
                    if (selectedId == snapshot.id && selected?.revision == snapshot.revision) {
                        replace(snapshot.copy(confirmed = d.confirmed))
                    }
                    throw e
                }
                saveFailed = false
                ensureActive()
                if (selectedId != snapshot.id || selected?.revision != snapshot.revision) return@launch
                stage = DiaryStage.READ
                if (generate) {
                    busy = "正在画封面"
                    val (prompt, bytes) = client.cover(config, snapshot.body)
                    ensureActive()
                    if (selectedId == snapshot.id && selected?.revision == snapshot.revision) {
                        val name = withContext(Dispatchers.IO) { store.saveCover(snapshot.id, snapshot.revision, bytes) }
                        ensureActive()
                        if (selectedId == snapshot.id && selected?.revision == snapshot.revision) {
                            val next = snapshot.copy(coverFile = name, coverBody = snapshot.body,
                                coverPrompt = prompt, revision = snapshot.revision + 1)
                            replace(next)
                            queueSave(next)
                        }
                    }
                }
                message = if (generate) "封面画好了" else "日记已保存"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (stage == DiaryStage.READ) coverError = errorMessage(e)
                message = errorMessage(e)
            }
            finally { if (operationId == token) busy = "" }
        }
    }

    fun delete() {
        val d = selected ?: return
        cancelOperation()
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { store.delete(d) }
                entries = entries.filterNot { it.id == d.id }
                if (selectedId == d.id) selectedId = null
            } catch (_: Exception) { message = "删除失败，请重试" }
        }
    }

    fun saveSettings(value: ModelSettings, done: () -> Unit) {
        if (busy.isNotBlank()) return
        val token = operationId
        busy = "正在保存设置"
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { settingsStore.save(value) }
                settings = value.normalized().rememberProfiles()
                recordings = withContext(Dispatchers.IO) { recoverRecordings(settings.audioRetention) }
                recordingNotice = recordings.latestRecordingNotice()
                providerDraft = null
                providerTestImage = null
                message = "供应商配置已保存"
                if (operationId == token) done()
            } catch (e: Exception) { message = errorMessage(e) }
            finally { if (operationId == token) busy = "" }
        }
    }

    fun beginProviderSettings() { providerDraft = settings; providerTestImage = null }
    fun changeProviderDraft(value: ModelSettings) { providerDraft = value; providerTestImage = null }
    fun leaveProviderSettings() { cancelOperation(); providerDraft = null; providerTestImage = null }

    fun testSettings(value: ModelSettings, kind: ModelKind = ModelKind.TEXT) {
        if (busy.isNotBlank()) return
        val token = operationId
        busy = if (kind == ModelKind.TEXT) "正在测试文本连接" else "正在生成测试图片"
        providerTestImage = null
        operation = viewModelScope.launch {
            try {
                val normalized = value.normalized()
                normalized.validate(kind)
                if (kind == ModelKind.TEXT) { client.test(normalized); message = "文本接口连接成功" }
                else { providerTestImage = client.testImage(normalized); message = "图片接口测试成功" }
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = errorMessage(e) }
            finally { if (operationId == token) busy = "" }
        }
    }

    private fun errorMessage(e: Exception): String = when (e) {
        is SocketTimeoutException -> "等待模型超时了，输入已保留，可以稍后重试"
        is org.json.JSONException -> "模型返回格式不正确，输入已保留"
        is IllegalArgumentException, is IllegalStateException -> e.message ?: "操作未完成，请重试"
        else -> "连接或保存失败，输入已保留，请检查网络与存储空间"
    }

    private fun recordingRepository(retention: AudioRetention): RecordingRepository =
        RecordingRepository(getApplication(), retention)

    private fun recordingScheduler(): RecordingWorkScheduler = RecordingWorkScheduler(getApplication())

    private fun recoverRecordings(retention: AudioRetention): List<RecordingSegment> {
        val repository = recordingRepository(retention)
        val segments = repository.loadAll()
        segments.filter { it.state.needsTranscriptionRecovery() }
            .forEach { recordingScheduler().enqueueTranscription(it.id) }
        repository.cleanExpiredAudio(System.currentTimeMillis())
        return repository.loadAll()
    }
}

private fun List<RecordingSegment>.latestRecordingNotice(): String? = maxByOrNull { it.createdAt }?.let {
    when (it.state) {
        RecordingState.SAVED, RecordingState.TRANSCRIBING -> "录音已收好，正在等待网络识别"
        RecordingState.RETRYABLE_ERROR -> "这段录音稍后再试，原声已经保留"
        RecordingState.TRANSCRIBED, RecordingState.ATTACHED_TO_DAY -> null
    }
}
