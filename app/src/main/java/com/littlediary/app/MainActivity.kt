package com.littlediary.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { DiaryTheme { DiaryApplication() } }
    }
}

@Composable
private fun DiaryApplication(vm: DiaryViewModel = viewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val speech = vm.speech
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var providerOpen by rememberSaveable { mutableStateOf(false) }
    var providerExitDialog by remember { mutableStateOf(false) }
    var parentGate by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }
    var permissionDiaryId by remember { mutableStateOf<String?>(null) }
    var speaking by remember { mutableStateOf(false) }
    var lastLocalDayKey by remember { mutableStateOf(localDayKey(System.currentTimeMillis())) }
    val storyPlayback = remember(context) {
        AndroidStoryPlayback(context, onPlaybackChanged = { speaking = it }, onError = vm::notify)
    }

    fun stopReading() {
        speaking = false
        storyPlayback.stop()
    }

    DisposableEffect(storyPlayback) {
        onDispose { storyPlayback.close() }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                val currentDayKey = localDayKey(System.currentTimeMillis())
                if (currentDayKey != lastLocalDayKey) {
                    lastLocalDayKey = currentDayKey
                    vm.enqueueFinalForPreviousDays()
                }
            }
            if (event == Lifecycle.Event.ON_STOP && (context as? android.app.Activity)?.isChangingConfigurations != true) { speech.cancel(); stopReading() }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    fun startRecordingForDiary(id: String) {
        if (vm.settings.useMiniMaxSpeech) {
            try {
                vm.settings.requireSpeechKey()
                speech.startRecording(vm.settings, vm::finishRecording, vm::notify)
            } catch (e: IllegalArgumentException) { vm.notify(e.message.orEmpty()) }
        } else {
            speech.start(id, vm.settings, { if (vm.appendSpeech(id, it)) vm.correct() }, vm::notify)
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val id = permissionDiaryId
        permissionDiaryId = null
        if (granted && id != null && vm.selectedId == id && vm.stage == DiaryStage.RECORD && !settingsOpen) {
            startRecordingForDiary(id)
        } else if (!granted) vm.notify("麦克风权限未开启，仍然可以输入文字")
    }

    fun startSpeech() {
        val id = vm.selectedId ?: return
        stopReading()
        vm.cancelOperation()
        if (!vm.settings.useMiniMaxSpeech && !android.speech.SpeechRecognizer.isRecognitionAvailable(context)) {
            vm.notify("平板没有可用的系统语音识别服务，可以先使用键盘语音输入")
        } else if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startRecordingForDiary(id)
        } else {
            permissionDiaryId = id
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    fun back() {
        if (providerOpen && vm.hasUnsavedProviderChanges) { providerExitDialog = true; return }
        speech.cancel(); stopReading(); vm.cancelOperation()
        if (providerOpen) { providerOpen = false; vm.leaveProviderSettings() }
        else if (settingsOpen) settingsOpen = false
        else if (vm.stage == DiaryStage.REVIEW) vm.showRecord(useVoice = false)
        else vm.open(null)
    }

    fun readDiary() {
        speech.cancel()
        val text = if (vm.stage == DiaryStage.REVIEW) vm.selected?.reviewBody.orEmpty() else vm.selected?.body.orEmpty()
        if (text.isBlank()) return
        stopReading()
        val diary = vm.selected ?: return
        storyPlayback.playStory(DailyStory(dayKey = localDayKey(diary.createdAt), finalBody = text))
    }
    BackHandler(enabled = settingsOpen || vm.selectedId != null) { back() }

    DiaryScaffold(vm.message, vm::dismissMessage) {
        when {
            vm.loading -> LoadingScreen()
            providerOpen -> ProviderSettingsScreen(vm.providerDraft ?: vm.settings, vm.busy, vm.providerTestImage,
                onBack = { back() }, onChange = vm::changeProviderDraft,
                onSave = { vm.saveSettings(vm.providerDraft ?: vm.settings) { providerOpen = false } },
                onTest = { kind -> vm.testSettings(vm.providerDraft ?: vm.settings, kind) })
            settingsOpen -> SettingsScreen(vm.settings, speech.available, onBack = { back() },
                onProviders = { vm.beginProviderSettings(); providerOpen = true }, onRetentionChange = vm::saveAudioRetention)
            vm.loadFailed -> LoadFailureScreen(vm::load)
            vm.selected != null -> EditorScreen(
                diary = vm.selected!!, vm = vm, speech = speech, speaking = speaking,
                onBack = { back() },
                onHome = { speech.cancel(); stopReading(); vm.open(null) },
                onRecord = { if (speech.active) speech.stop(vm::notify) else startSpeech() },
                onDelete = { deleteDialog = true },
                onSettings = { speech.cancel(); stopReading(); vm.cancelOperation(); parentGate = true },
                onRead = { readDiary() }, onStopReading = { stopReading() },
                onAppend = { speech.cancel(); stopReading(); vm.showRecord() },
                onMode = { speech.cancel(); stopReading(); vm.chooseInputMode(it) },
            )
            else -> HomeScreen(vm.entries, vm.store,
                onNew = { vm.newDiary(useVoice = false) }, onRecord = { vm.newDiary() },
                onOpen = vm::open, onSettings = { parentGate = true })
        }
    }
    if (parentGate) ParentGate(onDismiss = { parentGate = false }, onConfirm = {
        parentGate = false; settingsOpen = true
    })
    if (deleteDialog) ConfirmDelete(onDismiss = { deleteDialog = false }, onConfirm = {
        speech.cancel(); vm.selectedId?.let(speech::discard); stopReading(); deleteDialog = false; vm.delete()
    })
    if (providerExitDialog) ProviderExitDialog(
        busy = vm.busy.isNotBlank(),
        onSave = { vm.saveSettings(vm.providerDraft ?: vm.settings) { providerExitDialog = false; providerOpen = false } },
        onDiscard = { vm.leaveProviderSettings(); providerExitDialog = false; providerOpen = false },
        onDismiss = { providerExitDialog = false },
    )
}
