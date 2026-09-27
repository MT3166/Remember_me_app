package com.littlediary.app

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class RecordingRepository(
    context: Context,
    private val retention: AudioRetention = AudioRetention.FOREVER,
    private val zone: ZoneId = DEFAULT_RECORDING_ZONE,
) {
    private val directory = File(context.filesDir, "voice-diary/segments").apply { mkdirs() }

    fun dayKey(timestamp: Long): String = localDayKey(timestamp, zone)

    fun insert(segment: RecordingSegment) {
        synchronized(PROCESS_LOCK) {
            validateId(segment.id)
            val existing = read(segment.id)
            if (existing != null && existing.revision >= segment.revision) return
            persist(prepare(segment))
        }
    }

    @Synchronized
    fun loadAll(): List<RecordingSegment> = directory.listFiles().orEmpty()
        .filter { it.name.endsWith(".json") }
        .mapNotNull { file -> read(file.name.removeSuffix(".json")) }
        .sortedWith(compareBy<RecordingSegment> { it.createdAt }.thenBy { it.id })

    @Synchronized
    fun get(id: String): RecordingSegment? {
        validateId(id)
        return read(id)
    }

    fun update(segment: RecordingSegment) {
        synchronized(PROCESS_LOCK) {
            validateId(segment.id)
            val existing = read(segment.id)
            if (existing != null && existing.revision >= segment.revision) return
            persist(prepare(segment))
        }
    }

    fun audioFile(segment: RecordingSegment): File {
        validateId(segment.id)
        val name = File(segment.audioFile).name
        require(name.isNotBlank() && name != "." && name != "..")
        return File(directory, name)
    }

    fun persistAudio(source: File, id: String): File = synchronized(PROCESS_LOCK) {
        validateId(id)
        require(source.isFile && source.length() > 0) { "录音文件不存在或为空" }
        val target = File(directory, "$id.aac")
        if (source.absoluteFile == target.absoluteFile) return@synchronized target
        val temporary = File(directory, ".${target.name}.tmp")
        FileInputStream(source).use { input ->
            FileOutputStream(temporary).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
        check(temporary.renameTo(target)) { "Unable to atomically save recording audio" }
        check(target.length() > 0) { "Unable to save recording audio" }
        source.delete()
        target
    }

    fun recoverOrphanAudio(now: Long): List<RecordingSegment> = synchronized(PROCESS_LOCK) {
        val knownIds = loadAll().mapTo(mutableSetOf()) { it.id }
        directory.listFiles().orEmpty()
            .filter { it.name.matches(Regex("[a-zA-Z0-9-]+\\.aac")) }
            .forEach { file ->
                val id = file.name.removeSuffix(".aac")
                if (id !in knownIds && file.length() > 0) {
                    val timestamp = file.lastModified().takeIf { it > 0 } ?: now
                    persist(RecordingSegment(
                        id = id,
                        dayKey = dayKey(timestamp),
                        createdAt = timestamp,
                        startedAt = timestamp,
                        endedAt = timestamp,
                        audioFile = file.name,
                    ))
                }
            }
        loadAll()
    }

    fun deleteAudioIfEligible(segment: RecordingSegment, now: Long): Boolean {
        synchronized(PROCESS_LOCK) {
            val current = read(segment.id) ?: return false
            val days = retention.days ?: return false
            if (current.state != RecordingState.ATTACHED_TO_DAY || now < current.createdAt) return false
            if (now - current.createdAt < TimeUnit.DAYS.toMillis(days)) return false
            return audioFile(current).delete()
        }
    }

    fun cleanExpiredAudio(now: Long): Int = synchronized(PROCESS_LOCK) {
        loadAll().count { deleteAudioIfEligible(it, now) }
    }

    private fun prepare(segment: RecordingSegment): RecordingSegment {
        val source = File(segment.audioFile)
        val target = audioFile(segment)
        if (source.exists() && source.absoluteFile != target.absoluteFile) {
            source.copyTo(target, overwrite = true)
        }
        return if (segment.audioFile == target.name) segment else segment.copy(audioFile = target.name)
    }

    private fun persist(segment: RecordingSegment) {
        val target = metadataFile(segment.id)
        val temporary = File(directory, "${target.name}.tmp")
        FileOutputStream(temporary).use { stream ->
            stream.write(encode(segment).toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        check(temporary.renameTo(target)) { "Unable to atomically save recording metadata" }
    }

    private fun read(id: String): RecordingSegment? {
        val file = metadataFile(id)
        if (!file.exists()) return null
        return try {
            decode(file.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    private fun metadataFile(id: String): File = File(directory, "$id.json")

    companion object {
        private val PROCESS_LOCK = Any()

        fun encode(segment: RecordingSegment): String = buildString {
            append('{')
            appendJsonField("id", segment.id)
            appendJsonField("dayKey", segment.dayKey)
            appendJsonField("createdAt", segment.createdAt)
            appendJsonField("startedAt", segment.startedAt)
            appendJsonField("endedAt", segment.endedAt)
            appendJsonField("audioFile", segment.audioFile)
            appendJsonField("state", segment.state.name)
            appendJsonField("transcript", segment.transcript)
            appendJsonField("error", segment.error)
            appendJsonField("retryCount", segment.retryCount.toLong())
            appendJsonField("revision", segment.revision, last = true)
            append('}')
        }

        fun decode(json: String): RecordingSegment {
            val fields = JsonFields(json)
            return RecordingSegment(
                id = fields.string("id"), dayKey = fields.string("dayKey"),
                createdAt = fields.long("createdAt"), startedAt = fields.long("startedAt"),
                endedAt = fields.long("endedAt"), audioFile = fields.string("audioFile"),
                state = RecordingState.valueOf(fields.string("state")),
                transcript = fields.string("transcript"), error = fields.string("error"),
                retryCount = fields.long("retryCount").toInt(), revision = fields.long("revision"),
            )
        }

        private fun validateId(id: String) {
            require(id.matches(Regex("[a-zA-Z0-9-]+"))) { "Invalid recording id" }
        }

        private fun StringBuilder.appendJsonField(name: String, value: String, last: Boolean = false) {
            append('"').append(name).append("\":\"").append(escapeJson(value)).append('"')
            if (!last) append(',')
        }

        private fun StringBuilder.appendJsonField(name: String, value: Long, last: Boolean = false) {
            append('"').append(name).append("\":").append(value)
            if (!last) append(',')
        }

        private fun escapeJson(value: String): String = buildString {
            value.forEach { character ->
                when (character) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
                }
            }
        }
    }
}

private class JsonFields(json: String) {
    private val values = linkedMapOf<String, String>()
    private var nextIndex: Int = 0

    init {
        var index = json.indexOf('{') + 1
        while (index in 1 until json.length - 1) {
            while (json[index].isWhitespace() || json[index] == ',') index++
            if (json[index] == '}') break
            val key = readString(json, index + 1).also { index = nextIndex }
            while (json[index].isWhitespace() || json[index] == ':') index++
            if (json[index] == '"') {
                values[key] = readString(json, index + 1).also { index = nextIndex }
            } else {
                val start = index
                while (index < json.length && json[index] != ',' && json[index] != '}') index++
                values[key] = json.substring(start, index).trim()
            }
        }
    }

    private fun readString(json: String, start: Int): String {
        val value = StringBuilder()
        var index = start
        while (index < json.length) {
            when (val character = json[index++]) {
                '"' -> {
                    nextIndex = index
                    return value.toString()
                }
                '\\' -> when (val escaped = json[index++]) {
                    'n' -> value.append('\n')
                    'r' -> value.append('\r')
                    't' -> value.append('\t')
                    '"', '\\' -> value.append(escaped)
                    'u' -> value.append(json.substring(index, index + 4).toInt(16).toChar()).also { index += 4 }
                    else -> error("Invalid JSON escape")
                }
                else -> value.append(character)
            }
        }
        error("Unterminated JSON string")
    }

    fun string(name: String): String = values[name] ?: error("Missing JSON field: $name")
    fun long(name: String): Long = string(name).toLong()
}
