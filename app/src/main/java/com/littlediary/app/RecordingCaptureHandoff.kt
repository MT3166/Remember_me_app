package com.littlediary.app

import java.io.File
import java.util.UUID

internal class RecordingCaptureHandoff(
    private val repository: RecordingRepository,
    private val scheduler: RecordingWorkScheduler,
) {
    fun prepare(file: File, startedAt: Long, endedAt: Long): RecordingSegment {
        val id = UUID.randomUUID().toString()
        val durableAudio = repository.persistAudio(file, id)
        return RecordingSegment(
            id = id,
            dayKey = repository.dayKey(startedAt),
            createdAt = endedAt,
            startedAt = startedAt,
            endedAt = endedAt,
            audioFile = durableAudio.name,
        )
    }

    fun finishRecording(segment: RecordingSegment) {
        repository.insert(segment)
        scheduler.enqueueTranscription(segment.id)
    }
}
