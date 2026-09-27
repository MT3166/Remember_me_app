package com.littlediary.app

import java.time.Instant
import java.time.ZoneId

enum class RecordingState { SAVED, TRANSCRIBING, TRANSCRIBED, ATTACHED_TO_DAY, RETRYABLE_ERROR }

enum class AudioRetention(val days: Long?) { SEVEN(7), THIRTY(30), NINETY(90), FOREVER(null) }

val DEFAULT_RECORDING_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

data class RecordingSegment(
    val id: String,
    val dayKey: String,
    val createdAt: Long,
    val startedAt: Long,
    val endedAt: Long,
    val audioFile: String,
    val state: RecordingState = RecordingState.SAVED,
    val transcript: String = "",
    val error: String = "",
    val retryCount: Int = 0,
    val revision: Long = 0,
)

fun localDayKey(timestamp: Long, zone: ZoneId = DEFAULT_RECORDING_ZONE): String =
    Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate().toString()
