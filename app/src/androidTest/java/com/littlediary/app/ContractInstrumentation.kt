package com.littlediary.app

import android.app.Activity
import android.app.Instrumentation
import android.app.Application
import android.os.Bundle
import android.graphics.Bitmap
import android.util.Base64
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.ViewModelProvider
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

// Uses the platform runner so real Android JSON, AtomicFile and Keystore are exercised.
class ContractInstrumentation : Instrumentation() {
    private var seed = false
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        seed = arguments?.getString("seed") == "true"
        start()
    }

    override fun onStart() {
        var count = 0
        val folder = "contract-test-${UUID.randomUUID()}"
        val settingsName = "$folder.enc"
        try {
            if (seed) {
                val store = DiaryStore(targetContext)
                val now = System.currentTimeMillis()
                val samples = listOf(
                    Diary(id = "sample-park", title = "测试日记 · 我的大太阳", createdAt = now,
                        original = "今天我和妈妈去了公园 我画了一个大太阳 我好开心", body = "今天我和妈妈去了公园。我画了一个大太阳，我好开心！", confirmed = true),
                    Diary(id = "sample-leaf", title = "测试日记 · 小叶子长大了", createdAt = now - 86400000,
                        body = "今天窗台上的小叶子又长大了一点点。", confirmed = true),
                    Diary(id = "sample-breakfast", title = "测试日记 · 一起做早餐", createdAt = now - 172800000,
                        body = "今天我和家人一起准备早餐，面包香香的。", confirmed = true),
                    Diary(id = "sample-review", title = "测试待确认日记", createdAt = now + 1000,
                        body = "我画了大太杨", original = "我画了大太杨", suggestedTitle = "测试确认后的太阳", suggestedBody = "我画了大太阳。"),
                )
                val old = store.load().associateBy { it.id }
                samples.forEachIndexed { index, d ->
                    val revision = (old[d.id]?.revision ?: 0) + 1
                    val cover = if (index < 3) store.saveCover(d.id, revision,
                        context.assets.open(listOf("painting.jpg", "leaves.jpg", "bread.jpg")[index]).use { it.readBytes() }) else ""
                    store.save(d.copy(revision = revision, coverFile = cover, coverBody = if (cover.isBlank()) "" else d.body))
                }
                finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "Sample diary created for UI verification\n") })
                return
            }
            val settings = ModelSettings(textKey = "test-text-secret", imageKey = "test-image-secret")
            val openai = ModelClient.textRequest(settings, "system", "今天画画了")
            check(openai.getJSONArray("messages").getJSONObject(0).getString("role") == "system")
            check(openai.getBoolean("reasoning_split")); count++
            val anthropic = ModelClient.textRequest(settings.copy(protocol = Protocol.ANTHROPIC), "system", "今天画画了")
            check(anthropic.getString("system") == "system")
            check(anthropic.getJSONArray("messages").length() == 1); count++
            val parsed = ModelClient.parseText(JSONObject("""{"content":[{"type":"thinking","thinking":"秘密"},{"type":"text","text":"今天画画了。"}],"stop_reason":"end_turn"}"""), Protocol.ANTHROPIC)
            check(parsed == "今天画画了。"); count++
            val oa = ModelClient.parseText(JSONObject("""{"choices":[{"finish_reason":"stop","message":{"content":"<think>秘密</think>今天画画了。"}}]}"""), Protocol.OPENAI)
            check(oa == parsed); count++
            mustFail { ModelClient.parseText(JSONObject("""{"choices":[{"finish_reason":"length","message":{"content":"partial"}}]}"""), Protocol.OPENAI) }; count++
            mustFail { ModelClient.checkApiError(JSONObject("""{"base_resp":{"status_code":1008}}""")) }; count++
            val correction = ModelClient.parseCorrection("""{"title":"画画","text":"今天画画了。","questions":["是和妈妈一起吗？"]}""")
            check(correction.text == "今天画画了。" && correction.questions.size == 1); count++
            mustFail { ModelClient.parseCorrection("""{"title":"画画","text":"","questions":[]}""") }; count++
            mustFail { ModelClient.parseCorrection("""{"title":"画画","text":"今天画画了","questions":[12]}""") }; count++
            val store = DiaryStore(targetContext, folder)
            val d = Diary(body = "今天画画了。", original = "今天画画了", revision = 2, questions = listOf("和谁？"))
            store.save(d)
            check(DiaryStore(targetContext, folder).load().single() == d); count++
            store.save(d.copy(body = "旧内容", revision = 1))
            check(store.load().single().body == d.body); count++
            store.delete(d)
            store.save(d.copy(revision = 3))
            check(store.load().isEmpty()); count++
            mustFail { store.save(Diary(id = "../bad")) }; count++
            val secureStore = SettingsStore(targetContext, settingsName)
            secureStore.save(settings)
            check(SettingsStore(targetContext, settingsName).load() == settings.normalized().rememberProfiles()); count++
            val encryptedFile = File(targetContext.noBackupFilesDir, settingsName)
            val encrypted = encryptedFile.readBytes()
            check(!encrypted.toString(Charsets.UTF_8).contains(settings.textKey)); count++
            encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
            encryptedFile.writeBytes(encrypted)
            mustFail { secureStore.load() }; count++
            checkViewModelWorkflow(); count += 5
            checkProviderContracts(); count += 10
            checkModelMigration(secureStore); count += 6
            checkSharedKeyRequests(secureStore); count += 4
            checkSpeechContracts(); count += 5
            checkRecordingWorkflow(); count += 4
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "\n$count contract checks passed\n") })
        } catch (e: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "\nFAILED after $count checks: ${e.stackTraceToString()}\n") })
        } finally {
            File(targetContext.filesDir, folder).deleteRecursively()
            File(targetContext.noBackupFilesDir, settingsName).delete()
            java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("diary-$folder")
        }
    }

    private fun mustFail(block: () -> Unit) {
        var failed = false
        try { block() } catch (_: Exception) { failed = true }
        check(failed) { "Expected operation to fail" }
    }

    private fun checkViewModelWorkflow() {
        val owner = ViewModelStore()
        val store = DiaryStore(targetContext)
        val d = Diary(id = "workflow-${UUID.randomUUID()}", original = "大太杨", body = "大太杨",
            suggestedBody = "大太阳", suggestedTitle = "测试校准")
        store.save(d)
        lateinit var vm: DiaryViewModel
        try {
            runOnMainSync {
                val modelOwner = object : ViewModelStoreOwner { override val viewModelStore = owner }
                vm = ViewModelProvider(modelOwner, ViewModelProvider.AndroidViewModelFactory.getInstance(
                    targetContext.applicationContext as Application))[DiaryViewModel::class.java]
            }
            awaitMain { !vm.loading }
            runOnMainSync {
                vm.beginProviderSettings()
                check(!vm.hasUnsavedProviderChanges)
                vm.changeProviderDraft(vm.settings.copy(textKey = "draft-only", reuseTextKeyForImages = true))
                check(vm.hasUnsavedProviderChanges)
                vm.leaveProviderSettings()
                check(!vm.hasUnsavedProviderChanges && vm.providerDraft == null)
            }
            runOnMainSync { vm.open(d.id); check(vm.stage == DiaryStage.REVIEW); vm.confirmAndCover(false) }
            awaitMain { vm.stage == DiaryStage.READ && vm.busy.isBlank() }
            val saved = store.load().first { it.id == d.id }
            check(saved.body == "大太阳" && saved.original == "大太杨" && saved.confirmed && saved.suggestedBody.isBlank())
            runOnMainSync {
                check(!vm.appendSpeech(d.id, "迟到的录音"))
                vm.showRecord(useVoice = false)
                check(vm.inputMode == InputMode.TEXT && vm.appendSpeech(d.id, "新的一段"))
            }
            awaitMain { !vm.saving }
            runOnMainSync { vm.delete() }
            awaitMain { vm.selectedId == null }
            check(store.load().none { it.id == d.id })
        } finally {
            runOnMainSync { owner.clear() }
            store.delete(d)
        }
    }

    private fun checkSpeechContracts() = runBlocking {
        val legacy = SettingsStore.encode(ModelSettings(textKey = "mini"))
        legacy.remove("useMiniMaxSpeech"); legacy.remove("reuseMiniMaxKeyForSpeech"); legacy.remove("speechKey")
        check(SettingsStore.decode(legacy).requireSpeechKey() == "mini")
        val separate = ModelSettings(speechKey = "speech", reuseMiniMaxKeyForSpeech = false, useMiniMaxSpeech = false)
        check(SettingsStore.decode(SettingsStore.encode(separate)) == separate.normalized().rememberProfiles())
        val file = File.createTempFile("asr-contract", ".aac", targetContext.cacheDir)
        try {
            file.writeBytes(byteArrayOf(1, 2, 3))
            val client = SpeechClient { request ->
                check(request.headers["Authorization"] == "Bearer mini")
                check(request.headers["language"] == "zh")
                JSONObject().put("text", "今天画画了。")
            }
            check(client.transcribe(ModelSettings(imageKey = "mini"), file) == "今天画画了。")
            var failed = false
            try { SpeechClient { JSONObject().put("text", "") }.transcribe(ModelSettings(textKey = "mini"), file) }
            catch (_: IllegalStateException) { failed = true }
            check(failed)
            failed = false
            try { SpeechClient { JSONObject().put("error", JSONObject().put("message", "failed")) }.transcribe(ModelSettings(textKey = "mini"), file) }
            catch (_: IllegalStateException) { failed = true }
            check(failed)
        } finally { file.delete() }
    }

    private fun checkRecordingWorkflow() {
        uiAutomation.executeShellCommand("pm grant ${targetContext.packageName} android.permission.RECORD_AUDIO").close()
        var attempts = 0
        var delivered = ""
        var error = ""
        lateinit var speech: SpeechController
        runOnMainSync {
            speech = SpeechController(targetContext, SpeechClient {
                attempts++
                if (attempts == 1) error("simulated failure")
                kotlinx.coroutines.delay(300)
                JSONObject().put("text", "今天画画了。")
            })
            speech.start("first", ModelSettings(textKey = "fake"), { delivered = it }, { error = it })
            check(speech.listening) { error }
        }
        try {
            Thread.sleep(1200)
            runOnMainSync { speech.stop { error = it } }
            awaitMain { !speech.active }
            runOnMainSync { check(speech.hasPending("first")); check(delivered.isBlank()); check(error.contains("录音已保留")) }
            runOnMainSync {
                speech.start("first", ModelSettings(textKey = "fake"), { delivered = it }, { error = it })
                speech.cancel()
            }
            Thread.sleep(500)
            runOnMainSync { check(delivered.isBlank()); check(speech.hasPending("first")); check(!speech.hasPending("second")) }
            runOnMainSync { speech.start("first", ModelSettings(textKey = "fake"), { delivered = it }, { error = it }) }
            awaitMain { delivered.isNotBlank() }
            runOnMainSync { check(delivered == "今天画画了。"); check(!speech.hasPending("first")) }
        } finally { runOnMainSync { speech.close() } }
    }

    private fun checkSharedKeyRequests(store: SettingsStore) = runBlocking {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        bitmap.recycle()
        val image = output.toByteArray()
        val encoded = Base64.encodeToString(image, Base64.NO_WRAP)
        for (provider in listOf(Provider.MINIMAX, Provider.OPENAI)) {
            for (fromText in listOf(true, false)) {
                val shared = ModelSettings().selectTextProvider(provider).selectImageProvider(provider).copy(
                    textKey = if (fromText) "local-shared-test-key" else "",
                    imageKey = if (fromText) "" else "local-shared-test-key", reuseTextKeyForImages = true)
                store.save(shared)
                val restored = store.load()
                val requests = mutableListOf<ModelRequest>()
                val client = ModelClient { request ->
                    requests.add(request)
                    check(request.headers["Authorization"] == "Bearer local-shared-test-key")
                    if (request.body.has("prompt")) {
                        check(request.url == imagePreset(provider).url)
                        if (provider == Provider.MINIMAX) JSONObject().put("data", JSONObject().put("image_base64", JSONArray().put(encoded)))
                        else JSONObject().put("data", JSONArray().put(JSONObject().put("b64_json", encoded)))
                    } else {
                        check(request.url == endpoint(textPreset(provider).url, Protocol.OPENAI))
                        JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop")
                            .put("message", JSONObject().put("content", "一轮太阳"))))
                    }
                }
                check(client.testImage(restored).contentEquals(image))
                check(client.cover(restored, "今天画了太阳").second.contentEquals(image))
                check(requests.size == 3)
            }
        }
    }

    private fun checkProviderContracts() {
        val legacy = JSONObject().apply {
            put("protocol", "OPENAI"); put("textBaseUrl", "https://api.minimax.cn/v1")
            put("textModel", "MiniMax-M2.5"); put("textKey", "legacy-text")
            put("imageUrl", "https://api.minimax.cn/v1/image_generation"); put("imageModel", "image-01"); put("imageKey", "legacy-image")
        }
        val migrated = SettingsStore.decode(legacy)
        check(migrated.textModel == "MiniMax-M3")
        check(migrated.textProvider == Provider.MINIMAX && migrated.imageProvider == Provider.MINIMAX)
        check(migrated.textKey == "legacy-text" && migrated.imageKey == "legacy-image" && !migrated.reuseTextKeyForImages)
        val custom = SettingsStore.decode(JSONObject(legacy.toString()).put("protocol", "ANTHROPIC").put("textBaseUrl", "https://custom.example/anthropic"))
        check(custom.textProvider == Provider.CUSTOM && custom.protocol == Protocol.ANTHROPIC && custom.textBaseUrl == "https://custom.example/anthropic")
        val mixed = migrated.selectTextProvider(Provider.OPENAI).copy(textKey = "openai-text")
        val restored = SettingsStore.decode(SettingsStore.encode(mixed))
        check(restored.selectTextProvider(Provider.MINIMAX).textKey == "legacy-text" && restored.imageKeyForRequest == "legacy-image")
        val imageOpenai = restored.selectImageProvider(Provider.OPENAI).copy(imageKey = "openai-image")
        val request = ModelClient.imageRequest(imageOpenai, "测试画面")
        check(request.getString("size") == "1536x1024" && request.getString("output_format") == "jpeg" && !request.has("response_format") && !request.has("aspect_ratio"))
        val miniRequest = ModelClient.imageRequest(migrated, "测试画面")
        check(miniRequest.getString("aspect_ratio") == "4:3" && miniRequest.getString("response_format") == "base64" && !miniRequest.has("size"))
        check(ModelClient.parseImage(JSONObject("""{"data":[{"b64_json":"encoded-openai"}]}"""), ImageApi.OPENAI) == "encoded-openai")
        check(ModelClient.parseImage(JSONObject("""{"data":{"image_base64":["encoded-minimax"]}}"""), ImageApi.MINIMAX) == "encoded-minimax")
        mustFail { ModelClient.parseImage(JSONObject("""{"data":[{"url":"https://example.org/image.jpg"}]}"""), ImageApi.OPENAI) }
        val textRequest = ModelClient.textRequest(restored, "system", "input")
        check(textRequest.has("max_completion_tokens") && !textRequest.has("max_tokens") && !textRequest.has("reasoning_split"))
    }

    private fun checkModelMigration(store: SettingsStore) {
        val old = ModelSettings(textModel = "minimax-m2.5", textKey = "migration-text",
            imageKey = "migration-image", reuseTextKeyForImages = true)
        for (version in 1..3) {
            val migrated = SettingsStore.decode(SettingsStore.encode(old).put("version", version))
            check(migrated == old.copy(textModel = "MiniMax-M3").normalized().rememberProfiles())
            check(ModelClient.textRequest(migrated, "system", "test").getString("model") == "MiniMax-M3")
            store.save(migrated)
            check(store.load() == migrated)
        }
        val switched = old.selectTextProvider(Provider.OPENAI).withTextKey("gpt-key")
        val restored = SettingsStore.decode(SettingsStore.encode(switched).put("version", 3))
        check(restored.textModel == switched.textModel && restored.textKey == "gpt-key")
        check(restored.selectTextProvider(Provider.MINIMAX).textModel == "MiniMax-M3")
        check(restored.selectTextProvider(Provider.MINIMAX).textKey == "migration-text")
        for (provider in listOf(Provider.OPENAI, Provider.CUSTOM)) {
            val other = old.selectTextProvider(provider).copy(textModel = "MiniMax-M2.5")
            check(SettingsStore.decode(SettingsStore.encode(other).put("version", 3)).textModel == "MiniMax-M2.5")
        }
        val newerChoice = old.copy(textModel = "MiniMax-M2.7")
        check(SettingsStore.decode(SettingsStore.encode(newerChoice).put("version", 3)).textModel == "MiniMax-M2.7")
        store.save(old)
        check(store.load().textModel == "minimax-m2.5")
        check(SettingsStore.decode(SettingsStore.encode(ModelSettings())).textModel == "MiniMax-M3")
    }

    private fun awaitMain(predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10000
        while (System.currentTimeMillis() < deadline) {
            var passed = false
            runOnMainSync { passed = predicate() }
            if (passed) return
            Thread.sleep(30)
        }
        error("Timed out waiting for diary workflow")
    }
}
