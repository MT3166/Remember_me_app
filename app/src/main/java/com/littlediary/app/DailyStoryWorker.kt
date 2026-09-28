package com.littlediary.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

internal enum class DailyStoryWorkType { DRAFT, FINAL, COVER }

internal enum class DailyStoryWorkOutcome { SUCCESS, RETRY, FAILURE }

internal class DailyStoryWorkerProcessor(
    private val repository: DailyStoryRepository,
    private val settings: () -> ModelSettings,
    private val generateStory: suspend (ModelSettings, String, Boolean) -> StoryResult,
    private val generateCover: suspend (ModelSettings, String) -> Pair<String, ByteArray>,
    private val enqueueCover: (DailyStory) -> Unit = {},
    private val writeCover: (DailyStory, ByteArray) -> String = { _, _ -> "" },
    private val deleteCover: (String) -> Unit = {},
) {
    suspend fun process(
        dayKey: String,
        expectedRevision: Long,
        expectedFingerprint: String,
        final: Boolean,
    ): DailyStoryWorkOutcome = process(
        dayKey, expectedRevision, expectedFingerprint,
        if (final) DailyStoryWorkType.FINAL else DailyStoryWorkType.DRAFT,
    )

    suspend fun process(
        dayKey: String,
        expectedRevision: Long,
        expectedFingerprint: String,
        type: DailyStoryWorkType,
        expectedFinalFingerprint: String? = null,
        expectedCoverFile: String? = null,
    ): DailyStoryWorkOutcome {
        if (type == DailyStoryWorkType.COVER) {
            return processCover(dayKey, expectedRevision, expectedFingerprint, expectedFinalFingerprint, expectedCoverFile)
        }
        val current = repository.get(dayKey) ?: return DailyStoryWorkOutcome.FAILURE
        if (!current.matches(expectedRevision, expectedFingerprint) || current.sourceText.isBlank()) {
            return DailyStoryWorkOutcome.SUCCESS
        }
        if (type == DailyStoryWorkType.DRAFT && current.state != DailyStoryState.COLLECTING) {
            return DailyStoryWorkOutcome.SUCCESS
        }
        if (type == DailyStoryWorkType.FINAL && current.state == DailyStoryState.READY) {
            return DailyStoryWorkOutcome.SUCCESS
        }
        val expectedState = current.state

        return try {
            val result = generateStory(settings(), current.sourceText, type == DailyStoryWorkType.FINAL)
            val replacement = if (type == DailyStoryWorkType.FINAL) {
                current.copy(
                    finalTitle = result.title,
                    finalBody = result.body,
                    state = DailyStoryState.READY,
                )
            } else {
                current.copy(
                    draftTitle = result.title,
                    draftBody = result.body,
                    state = DailyStoryState.DRAFT_READY,
                )
            }
            if (!repository.replaceIfCurrent(
                dayKey,
                expectedRevision,
                expectedFingerprint,
                replacement,
                expectedState = expectedState,
            )) {
                DailyStoryWorkOutcome.SUCCESS
            } else {
                if (type == DailyStoryWorkType.FINAL) enqueueCover(replacement)
                DailyStoryWorkOutcome.SUCCESS
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            error.toDailyStoryOutcome()
        }
    }

    suspend fun processCover(
        dayKey: String,
        expectedRevision: Long,
        expectedFingerprint: String,
        expectedFinalFingerprint: String? = null,
        expectedCoverFile: String? = null,
    ): DailyStoryWorkOutcome {
        val current = repository.get(dayKey) ?: return DailyStoryWorkOutcome.FAILURE
        if (!current.matches(expectedRevision, expectedFingerprint) || current.finalBody.isBlank()) {
            return DailyStoryWorkOutcome.SUCCESS
        }
        val finalFingerprint = expectedFinalFingerprint ?: DailyStoryRepository.finalFingerprint(current)
        val coverFile = expectedCoverFile ?: current.coverFile
        if (DailyStoryRepository.finalFingerprint(current) != finalFingerprint || current.coverFile != coverFile) {
            return DailyStoryWorkOutcome.SUCCESS
        }

        return try {
            val (_, bytes) = generateCover(settings(), current.finalBody)
            require(bytes.isNotEmpty()) { "封面图片为空" }
            val name = writeCover(current, bytes)
            require(name.isNotBlank()) { "封面文件为空" }
            val replacement = current.copy(coverFile = name)
            if (!repository.replaceIfCurrent(
                dayKey,
                expectedRevision,
                expectedFingerprint,
                replacement,
                expectedState = DailyStoryState.READY,
                expectedFinalFingerprint = finalFingerprint,
                expectedCoverFile = coverFile,
            )) {
                deleteCover(name)
            }
            DailyStoryWorkOutcome.SUCCESS
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            error.toDailyStoryOutcome()
        }
    }

    private fun DailyStory.matches(revision: Long, fingerprint: String): Boolean =
        this.revision == revision && sourceFingerprint == fingerprint

    private fun Throwable.toDailyStoryOutcome(): DailyStoryWorkOutcome = when {
        this is IOException -> DailyStoryWorkOutcome.RETRY
        generateSequence(this) { it.cause }.any { cause ->
            cause.message?.let { Regex("HTTP\\s+(429|5\\d{2})\\b").containsMatchIn(it) } == true
        } -> DailyStoryWorkOutcome.RETRY
        else -> DailyStoryWorkOutcome.FAILURE
    }
}

internal fun DailyStoryWorkOutcome.toWorkerResult(): ListenableWorker.Result = when (this) {
    DailyStoryWorkOutcome.SUCCESS -> ListenableWorker.Result.success()
    DailyStoryWorkOutcome.RETRY -> ListenableWorker.Result.retry()
    DailyStoryWorkOutcome.FAILURE -> ListenableWorker.Result.failure()
}

class DailyStoryWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val dayKey = inputData.getString(INPUT_DAY_KEY) ?: return Result.failure()
        val fingerprint = inputData.getString(INPUT_EXPECTED_FINGERPRINT) ?: return Result.failure()
        val revision = inputData.getLong(INPUT_EXPECTED_REVISION, Long.MIN_VALUE)
        if (revision == Long.MIN_VALUE) return Result.failure()
        val type = runCatching {
            DailyStoryWorkType.valueOf(inputData.getString(INPUT_WORK_TYPE).orEmpty())
        }.getOrElse { return Result.failure() }
        val repository = DailyStoryRepository(applicationContext)
        val scheduler = DailyStoryWorkScheduler(applicationContext)
        val client = ModelClient()
        return DailyStoryWorkerProcessor(
            repository = repository,
            settings = { SettingsStore(applicationContext).load() },
            generateStory = { settings, source, final -> client.story(settings, source, final) },
            generateCover = client::cover,
            enqueueCover = { scheduler.enqueueCover(it.dayKey) },
            writeCover = { story, bytes -> writeCoverFile(applicationContext, story, bytes) },
            deleteCover = { name -> File(applicationContext.filesDir, "voice-diary/stories/$name").delete() },
        ).process(
            dayKey,
            revision,
            fingerprint,
            type,
            inputData.getString(INPUT_EXPECTED_FINAL_FINGERPRINT)?.takeIf { it.isNotBlank() },
            inputData.getString(INPUT_EXPECTED_COVER_FILE),
        ).toWorkerResult()
    }

    companion object {
        const val INPUT_DAY_KEY = "day_key"
        const val INPUT_EXPECTED_REVISION = "expected_revision"
        const val INPUT_EXPECTED_FINGERPRINT = "expected_fingerprint"
        const val INPUT_EXPECTED_FINAL_FINGERPRINT = "expected_final_fingerprint"
        const val INPUT_EXPECTED_COVER_FILE = "expected_cover_file"
        const val INPUT_WORK_TYPE = "work_type"

        private fun writeCoverFile(context: Context, story: DailyStory, bytes: ByteArray): String {
            val directory = File(context.filesDir, "voice-diary/stories").apply { mkdirs() }
            val fingerprint = story.sourceFingerprint.ifBlank { "unknown" }.take(16)
            val name = "${story.dayKey}-cover-${story.revision}-$fingerprint.png"
            val target = File(directory, name)
            val temporary = File(directory, ".${name}.tmp")
            try {
                FileOutputStream(temporary).use { stream ->
                    stream.write(bytes)
                    stream.fd.sync()
                }
                check(temporary.renameTo(target)) { "Unable to atomically save story cover" }
                return name
            } catch (error: Throwable) {
                temporary.delete()
                throw error
            }
        }
    }
}
