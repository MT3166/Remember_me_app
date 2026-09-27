package com.littlediary.app

data class DailyStory(
    val dayKey: String,
    val segmentIds: List<String> = emptyList(),
    val sourceText: String = "",
    val draftTitle: String = "",
    val draftBody: String = "",
    val finalTitle: String = "",
    val finalBody: String = "",
    val coverFile: String = "",
    val revision: Long = 0,
    val sourceFingerprint: String = "",
    val state: DailyStoryState = DailyStoryState.COLLECTING,
)

enum class DailyStoryState {
    COLLECTING,
    DRAFT_READY,
    FINALIZING,
    READY,
    RETRYABLE_ERROR,
}
