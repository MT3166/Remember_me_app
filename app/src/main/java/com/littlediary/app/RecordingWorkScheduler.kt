package com.littlediary.app

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

internal data class RecordingWorkSpec(
    val workName: String,
    val segmentId: String,
    val networkType: NetworkType,
    val backoffPolicy: BackoffPolicy,
    val backoffDelaySeconds: Long,
    val existingWorkPolicy: ExistingWorkPolicy,
)

class RecordingWorkScheduler(context: Context) {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    fun enqueueTranscription(segmentId: String) {
        val spec = workSpec(segmentId)
        val request = OneTimeWorkRequestBuilder<RecordingWorker>()
            .setInputData(workDataOf(RecordingWorker.INPUT_SEGMENT_ID to spec.segmentId))
            .setConstraints(Constraints.Builder()
                .setRequiredNetworkType(spec.networkType)
                .build())
            .setBackoffCriteria(spec.backoffPolicy, spec.backoffDelaySeconds, TimeUnit.SECONDS)
            .addTag(TRANSCRIPTION_TAG)
            .build()
        workManager.enqueueUniqueWork(spec.workName, spec.existingWorkPolicy, request)
    }

    companion object {
        const val TRANSCRIPTION_TAG = "recording-transcription"

        internal fun workSpec(segmentId: String): RecordingWorkSpec {
            require(segmentId.isNotBlank()) { "录音片段 ID 不能为空" }
            return RecordingWorkSpec(
                workName = workName(segmentId),
                segmentId = segmentId,
                networkType = NetworkType.CONNECTED,
                backoffPolicy = BackoffPolicy.EXPONENTIAL,
                backoffDelaySeconds = 30,
                existingWorkPolicy = ExistingWorkPolicy.KEEP,
            )
        }

        fun workName(segmentId: String): String = "recording-transcription-$segmentId"
    }
}
