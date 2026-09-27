package com.littlediary.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

internal enum class RecordingWorkOutcome { SUCCESS, RETRY, FAILURE }

internal class RecordingWorkerProcessor(
    private val repository: RecordingRepository,
    private val settings: () -> ModelSettings,
    private val transcribe: suspend (ModelSettings, File) -> String,
    private val dailyStoryRepository: DailyStoryRepository? = null,
    private val enqueueDraft: ((String) -> Unit)? = null,
) {
    suspend fun process(segmentId: String): RecordingWorkOutcome {
        val segment = repository.get(segmentId) ?: return RecordingWorkOutcome.FAILURE
        if (segment.transcript.isNotBlank() &&
            segment.state in setOf(RecordingState.TRANSCRIBED, RecordingState.ATTACHED_TO_DAY)
        ) {
            attachToDailyStory(segment)
            return RecordingWorkOutcome.SUCCESS
        }

        val transcribing = segment.copy(
            state = RecordingState.TRANSCRIBING,
            error = "",
            revision = segment.revision + 1,
        )
        repository.update(transcribing)

        return try {
            val transcript = transcribe(settings(), repository.audioFile(transcribing))
            val current = repository.get(segmentId) ?: transcribing
            if (current.transcript.isNotBlank() &&
                current.state in setOf(RecordingState.TRANSCRIBED, RecordingState.ATTACHED_TO_DAY)
            ) {
                attachToDailyStory(current)
                RecordingWorkOutcome.SUCCESS
            } else {
                repository.update(current.copy(
                    state = RecordingState.TRANSCRIBED,
                    transcript = transcript,
                    error = "",
                    revision = current.revision + 1,
                ))
                attachToDailyStory(current.copy(
                    state = RecordingState.TRANSCRIBED,
                    transcript = transcript,
                    error = "",
                    revision = current.revision + 1,
                ))
                RecordingWorkOutcome.SUCCESS
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val current = repository.get(segmentId) ?: transcribing
            if (current.transcript.isNotBlank() &&
                current.state in setOf(RecordingState.TRANSCRIBED, RecordingState.ATTACHED_TO_DAY)
            ) {
                return RecordingWorkOutcome.SUCCESS
            }
            if (error.isNetworkFailure() || error.isTemporaryServiceFailure()) {
                repository.update(current.copy(
                    state = RecordingState.TRANSCRIBING,
                    error = error.message.orEmpty(),
                    retryCount = current.retryCount + 1,
                    revision = current.revision + 1,
                ))
                RecordingWorkOutcome.RETRY
            } else {
                repository.update(current.copy(
                    state = RecordingState.RETRYABLE_ERROR,
                    error = error.message ?: "语音识别配置或录音内容无效",
                    retryCount = current.retryCount + 1,
                    revision = current.revision + 1,
                ))
                RecordingWorkOutcome.FAILURE
            }
        }
    }

    private fun attachToDailyStory(segment: RecordingSegment) {
        val stories = dailyStoryRepository ?: return
        val alreadyAttached = stories.get(segment.dayKey)?.segmentIds?.contains(segment.id) == true
        if (!alreadyAttached) stories.appendSegment(segment.dayKey, segment.id, segment.transcript, segment.createdAt)
        val current = repository.get(segment.id) ?: segment
        if (current.state != RecordingState.ATTACHED_TO_DAY) {
            repository.update(current.copy(
                state = RecordingState.ATTACHED_TO_DAY,
                revision = current.revision + 1,
            ))
        }
        if (!alreadyAttached) enqueueDraft?.invoke(segment.dayKey)
    }

    private fun Throwable.isNetworkFailure(): Boolean = generateSequence(this) { it.cause }
        .any { it is IOException }

    private fun Throwable.isTemporaryServiceFailure(): Boolean = generateSequence(this) { it.cause }
        .mapNotNull { it.message }
        .any { TEMPORARY_HTTP_STATUS.containsMatchIn(it) }

    companion object {
        private val TEMPORARY_HTTP_STATUS = Regex("HTTP\\s+(429|5\\d{2})\\b")
    }
}

internal fun RecordingWorkOutcome.toWorkerResult(): ListenableWorker.Result = when (this) {
    RecordingWorkOutcome.SUCCESS -> ListenableWorker.Result.success()
    RecordingWorkOutcome.RETRY -> ListenableWorker.Result.retry()
    RecordingWorkOutcome.FAILURE -> ListenableWorker.Result.failure()
}

class RecordingWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val segmentId = inputData.getString(INPUT_SEGMENT_ID) ?: return Result.failure()
        val dailyStoryRepository = DailyStoryRepository(applicationContext)
        val dailyStoryScheduler = DailyStoryWorkScheduler(applicationContext)
        dailyStoryScheduler.enqueueFinalForPreviousDays()
        val outcome = RecordingWorkerProcessor(
            repository = RecordingRepository(applicationContext),
            settings = { SettingsStore(applicationContext).load() },
            transcribe = SpeechClient()::transcribe,
            dailyStoryRepository = dailyStoryRepository,
            enqueueDraft = dailyStoryScheduler::enqueueDraft,
        ).process(segmentId)
        return outcome.toWorkerResult()
    }

    companion object {
        const val INPUT_SEGMENT_ID = "segment_id"
    }
}
