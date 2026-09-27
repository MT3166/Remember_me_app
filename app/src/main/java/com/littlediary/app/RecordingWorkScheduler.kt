package com.littlediary.app

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

class RecordingWorkScheduler(
    context: Context,
    private val enqueue: (String, ExistingWorkPolicy, OneTimeWorkRequest) -> Unit = { name, policy, request ->
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(name, policy, request)
    },
) {

    fun enqueueTranscription(segmentId: String) {
        require(segmentId.isNotBlank()) { "录音片段 ID 不能为空" }
        val request = OneTimeWorkRequestBuilder<RecordingWorker>()
            .setInputData(workDataOf(RecordingWorker.INPUT_SEGMENT_ID to segmentId))
            .setConstraints(Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TRANSCRIPTION_TAG)
            .build()
        enqueue(workName(segmentId), ExistingWorkPolicy.KEEP, request)
    }

    companion object {
        const val TRANSCRIPTION_TAG = "recording-transcription"

        fun workName(segmentId: String): String = "recording-transcription-$segmentId"
    }
}
