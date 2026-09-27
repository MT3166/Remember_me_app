package com.littlediary.app

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.time.LocalDate
import java.util.concurrent.TimeUnit

class DailyStoryWorkScheduler(
    context: Context,
    private val enqueue: (String, ExistingWorkPolicy, OneTimeWorkRequest) -> Unit = { name, policy, request ->
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(name, policy, request)
    },
    private val stories: DailyStoryRepository = DailyStoryRepository(context),
) {
    fun enqueueDraft(dayKey: String) {
        enqueueStory(dayKey, DailyStoryWorkType.DRAFT, ExistingWorkPolicy.REPLACE, DRAFT_DEBOUNCE_MS)
    }

    fun enqueueFinal(dayKey: String) {
        val story = stories.get(dayKey) ?: return
        if (story.state == DailyStoryState.READY) return
        enqueueStory(dayKey, DailyStoryWorkType.FINAL, ExistingWorkPolicy.REPLACE, 0L)
    }

    fun enqueueCover(dayKey: String) {
        val story = stories.get(dayKey) ?: return
        if (story.state != DailyStoryState.READY || story.finalBody.isBlank()) return
        enqueueStory(dayKey, DailyStoryWorkType.COVER, ExistingWorkPolicy.KEEP, 0L)
    }

    fun enqueueFinalForPreviousDays(now: Long = System.currentTimeMillis()) {
        val today = LocalDate.parse(localDayKey(now))
        stories.loadAll()
            .filter { LocalDate.parse(it.dayKey).isBefore(today) }
            .filter { it.state != DailyStoryState.READY }
            .forEach { enqueueFinal(it.dayKey) }
    }

    private fun enqueueStory(
        dayKey: String,
        type: DailyStoryWorkType,
        policy: ExistingWorkPolicy,
        delayMs: Long,
    ) {
        require(dayKey.isNotBlank()) { "故事日期不能为空" }
        val story = stories.get(dayKey) ?: return
        val request = OneTimeWorkRequestBuilder<DailyStoryWorker>()
            .setInputData(workDataOf(
                DailyStoryWorker.INPUT_DAY_KEY to dayKey,
                DailyStoryWorker.INPUT_EXPECTED_REVISION to story.revision,
                DailyStoryWorker.INPUT_EXPECTED_FINGERPRINT to story.sourceFingerprint,
                DailyStoryWorker.INPUT_WORK_TYPE to type.name,
            ))
            .setConstraints(Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .addTag(type.tag)
            .build()
        enqueue(type.workName(dayKey), policy, request)
    }

    companion object {
        const val DRAFT_DEBOUNCE_MS = 2_000L
        const val DRAFT_TAG = "daily-story-draft"
        const val FINAL_TAG = "daily-story-final"
        const val COVER_TAG = "daily-story-cover"

        fun draftWorkName(dayKey: String): String = "daily-story-draft-$dayKey"
        fun finalWorkName(dayKey: String): String = "daily-story-final-$dayKey"
        fun coverWorkName(dayKey: String): String = "daily-story-cover-$dayKey"

        private val DailyStoryWorkType.tag: String get() = when (this) {
            DailyStoryWorkType.DRAFT -> DRAFT_TAG
            DailyStoryWorkType.FINAL -> FINAL_TAG
            DailyStoryWorkType.COVER -> COVER_TAG
        }

        private fun DailyStoryWorkType.workName(dayKey: String): String = when (this) {
            DailyStoryWorkType.DRAFT -> draftWorkName(dayKey)
            DailyStoryWorkType.FINAL -> finalWorkName(dayKey)
            DailyStoryWorkType.COVER -> coverWorkName(dayKey)
        }
    }
}
