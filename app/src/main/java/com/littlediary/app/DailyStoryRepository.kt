package com.littlediary.app

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId

class DailyStoryRepository(
    context: Context,
    private val zone: ZoneId = DEFAULT_RECORDING_ZONE,
) {
    private val directory = File(context.filesDir, "voice-diary/stories").apply { mkdirs() }

    fun dayKey(timestamp: Long): String = localDayKey(timestamp, zone)

    fun appendSegment(segment: RecordingSegment): DailyStory =
        appendSegment(segment.id, segment.createdAt, segment.transcript)

    fun appendSegment(segmentId: String, createdAt: Long, sourceText: String): DailyStory =
        appendSegment(dayKey(createdAt), segmentId, sourceText, createdAt)

    fun appendSegment(dayKey: String, segmentId: String, sourceText: String): DailyStory =
        synchronized(PROCESS_LOCK) {
            val current = readRecord(dayKey)
            val nextCreatedAt = current?.segments?.maxOfOrNull { it.createdAt }?.plus(1) ?: 0L
            appendSegmentLocked(dayKey, segmentId, sourceText, nextCreatedAt, current)
        }

    fun appendSegment(
        dayKey: String,
        segmentId: String,
        sourceText: String,
        createdAt: Long,
    ): DailyStory = synchronized(PROCESS_LOCK) {
        appendSegmentLocked(dayKey, segmentId, sourceText, createdAt, readRecord(dayKey))
    }

    @Synchronized
    fun get(dayKey: String): DailyStory? = readRecord(dayKey)?.story

    @Synchronized
    fun loadAll(): List<DailyStory> = directory.listFiles().orEmpty()
        .filter { it.extension == "json" }
        .mapNotNull { readRecord(it.name.removeSuffix(".json"))?.story }
        .sortedBy { it.dayKey }

    fun save(story: DailyStory) = synchronized(PROCESS_LOCK) {
        validateDayKey(story.dayKey)
        val current = readRecord(story.dayKey)?.story
        if (current != null && current.revision > story.revision) return@synchronized

        val entries = readRecord(story.dayKey)?.segments.orEmpty()
        persist(story, entries)
    }

    fun update(story: DailyStory) = save(story)

    fun replaceIfCurrent(
        dayKey: String,
        expectedRevision: Long,
        expectedFingerprint: String,
        replacement: DailyStory,
    ): Boolean = synchronized(PROCESS_LOCK) {
        require(dayKey == replacement.dayKey) { "Story day key mismatch" }
        val current = readRecord(dayKey)?.story ?: return@synchronized false
        if (current.revision != expectedRevision || current.sourceFingerprint != expectedFingerprint) {
            return@synchronized false
        }
        if (replacement.revision < current.revision) return@synchronized false
        persist(replacement, readRecord(dayKey)?.segments.orEmpty())
        true
    }

    private fun appendSegmentLocked(
        dayKey: String,
        segmentId: String,
        sourceText: String,
        createdAt: Long,
        current: PersistedStory?,
    ): DailyStory {
        validateDayKey(dayKey)
        validateSegmentId(segmentId)
        val currentStory = current?.story ?: DailyStory(dayKey = dayKey)
        val existingEntries = current?.segments.orEmpty()
        if (segmentId in currentStory.segmentIds) return currentStory

        val entries = (existingEntries + StorySegment(segmentId, sourceText, createdAt))
            .sortedWith(compareBy<StorySegment> { it.createdAt }.thenBy { it.id })
        val story = currentStory.copy(
            segmentIds = entries.map { it.id },
            sourceText = entries.joinToString("\n") { it.text },
            coverFile = "",
            revision = currentStory.revision + 1,
            sourceFingerprint = fingerprint(dayKey, entries),
            state = DailyStoryState.COLLECTING,
        )
        persist(story, entries)
        return story
    }

    private fun persist(story: DailyStory, entries: List<StorySegment>) {
        val target = metadataFile(story.dayKey)
        val temporary = File(directory, "${target.name}.tmp")
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(encode(story, entries).toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            check(temporary.renameTo(target)) { "Unable to atomically save daily story" }
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    private fun readRecord(dayKey: String): PersistedStory? {
        return try {
            validateDayKey(dayKey)
            val file = metadataFile(dayKey)
            if (!file.exists()) return null
            val json = file.readText(Charsets.UTF_8)
            val root = JsonParser(json).parseObject()
            val story = decode(root)
            require(story.dayKey == dayKey) { "Story day key does not match its file" }
            val entries = validateDecodedStory(root, story)
            PersistedStory(story, entries)
        } catch (_: Exception) {
            null
        }
    }

    private fun metadataFile(dayKey: String): File = File(directory, "$dayKey.json")

    private fun validateDayKey(dayKey: String) {
        require(dayKey.matches(DAY_KEY_PATTERN)) { "Invalid story day key" }
        require(runCatching { LocalDate.parse(dayKey) }.isSuccess) { "Invalid story calendar date" }
    }

    private fun validateSegmentId(segmentId: String) {
        require(segmentId.matches(SEGMENT_ID_PATTERN)) { "Invalid story segment id" }
    }

    private data class StorySegment(val id: String, val text: String, val createdAt: Long)

    private data class PersistedStory(
        val story: DailyStory,
        val segments: List<StorySegment>,
    )

    companion object {
        private val PROCESS_LOCK = Any()
        private val DAY_KEY_PATTERN = Regex("\\d{4}-\\d{2}-\\d{2}")
        private val SEGMENT_ID_PATTERN = Regex("[a-zA-Z0-9-]+")

        fun encode(story: DailyStory): String = encode(story, emptyList())

        fun decode(json: String): DailyStory {
            val root = JsonParser(json).parseObject()
            val story = decode(root)
            validateDecodedStory(root, story)
            return story
        }

        private fun decode(value: Map<String, Any?>): DailyStory {
            return DailyStory(
                dayKey = value.requiredString("dayKey"),
                segmentIds = value.stringList("segmentIds"),
                sourceText = value.stringValue("sourceText"),
                draftTitle = value.stringValue("draftTitle"),
                draftBody = value.stringValue("draftBody"),
                finalTitle = value.stringValue("finalTitle"),
                finalBody = value.stringValue("finalBody"),
                coverFile = value.stringValue("coverFile"),
                revision = value.longValue("revision"),
                sourceFingerprint = value.stringValue("sourceFingerprint"),
                state = DailyStoryState.valueOf(value.stringValue("state", DailyStoryState.COLLECTING.name)),
            )
        }

        private fun encode(story: DailyStory, entries: List<StorySegment>): String =
            buildString {
                append('{')
                appendJsonField("dayKey", story.dayKey)
                appendJsonStringArrayField("segmentIds", story.segmentIds)
                appendJsonField("sourceText", story.sourceText)
                appendJsonField("draftTitle", story.draftTitle)
                appendJsonField("draftBody", story.draftBody)
                appendJsonField("finalTitle", story.finalTitle)
                appendJsonField("finalBody", story.finalBody)
                appendJsonField("coverFile", story.coverFile)
                appendJsonField("revision", story.revision)
                appendJsonField("sourceFingerprint", story.sourceFingerprint)
                appendJsonField("state", story.state.name)
                append('"').append("segments").append("\":[")
                entries.forEachIndexed { index, segment ->
                    if (index > 0) append(',')
                    append('{')
                    appendJsonField("id", segment.id)
                    appendJsonField("text", segment.text)
                    appendJsonField("createdAt", segment.createdAt, last = true)
                    append('}')
                }
                append("]}")
            }

        private fun decodeSegments(root: Map<String, Any?>, story: DailyStory): List<StorySegment> {
            val segments = root["segments"] as? List<*> ?: return story.segmentIds.mapIndexed { index, id ->
                StorySegment(id, story.sourceText.split('\n').getOrNull(index).orEmpty(), index.toLong())
            }
            return segments.mapIndexed { index, raw ->
                val segment = raw as? Map<*, *> ?: error("Invalid story segment")
                StorySegment(
                    id = segment["id"] as? String ?: error("Missing story segment id"),
                    text = segment["text"] as? String ?: "",
                    createdAt = (segment["createdAt"] as? Number)?.toLong() ?: index.toLong(),
                )
            }
        }

        private fun validateDecodedStory(
            root: Map<String, Any?>,
            story: DailyStory,
        ): List<StorySegment> {
            val entries = decodeSegments(root, story)
            val entryIds = entries.map { it.id }
            require(entryIds.all { it.matches(SEGMENT_ID_PATTERN) }) { "Invalid story segment ID" }
            require(story.segmentIds.all { it.matches(SEGMENT_ID_PATTERN) }) { "Invalid story segment ID" }
            require(entryIds.size == entryIds.toSet().size) { "Duplicate story segment data" }
            require(story.segmentIds.size == story.segmentIds.toSet().size) { "Duplicate story segment ID" }
            require(story.segmentIds == entryIds) { "Story segment data does not match IDs" }
            require(story.sourceText == entries.joinToString("\n") { it.text }) {
                "Story source text does not match segments"
            }
            if (story.sourceFingerprint.isNotBlank()) {
                require(story.sourceFingerprint == fingerprint(story.dayKey, entries)) {
                    "Story source fingerprint does not match segments"
                }
            }
            return entries
        }

        private fun StringBuilder.appendJsonField(name: String, value: String, last: Boolean = false) {
            append('"').append(name).append("\":\"").append(escapeJson(value)).append('"')
            if (!last) append(',')
        }

        private fun StringBuilder.appendJsonField(name: String, value: Long, last: Boolean = false) {
            append('"').append(name).append("\":").append(value)
            if (!last) append(',')
        }

        private fun StringBuilder.appendJsonStringArrayField(name: String, values: List<String>) {
            append('"').append(name).append("\":[")
            values.forEachIndexed { index, value ->
                if (index > 0) append(',')
                append('"').append(escapeJson(value)).append('"')
            }
            append("],")
        }

        private fun escapeJson(value: String): String = buildString {
            value.forEach { character ->
                when (character) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    else -> if (character.code < 0x20) {
                        append("\\u%04x".format(character.code))
                    } else {
                        append(character)
                    }
                }
            }
        }

        private fun fingerprint(dayKey: String, entries: List<StorySegment>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            entries.forEach { segment ->
                digest.update(dayKey.toByteArray(Charsets.UTF_8))
                digest.update(0)
                digest.update(segment.id.toByteArray(Charsets.UTF_8))
                digest.update(0)
                digest.update(segment.createdAt.toString().toByteArray(Charsets.UTF_8))
                digest.update(0)
                digest.update(segment.text.toByteArray(Charsets.UTF_8))
                digest.update(0)
            }
            return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        }
    }
}

private fun Map<String, Any?>.requiredString(name: String): String =
    this[name] as? String ?: error("Missing JSON field: $name")

private fun Map<String, Any?>.stringValue(name: String, default: String = ""): String =
    this[name] as? String ?: default

private fun Map<String, Any?>.stringList(name: String): List<String> =
    (this[name] as? List<*>)?.map { it as? String ?: error("Invalid JSON array field: $name") }.orEmpty()

private fun Map<String, Any?>.longValue(name: String, default: Long = 0L): Long =
    (this[name] as? Number)?.toLong() ?: default

private class JsonParser(private val input: String) {
    private var index = 0

    fun parseObject(): Map<String, Any?> = parseObject(requireEnd = true)

    private fun parseObject(requireEnd: Boolean): Map<String, Any?> {
        skipWhitespace()
        expect('{')
        val result = linkedMapOf<String, Any?>()
        skipWhitespace()
        if (peek() == '}') {
            index++
            if (requireEnd) ensureEnd()
            return result
        }
        while (true) {
            skipWhitespace()
            val key = parseString()
            skipWhitespace()
            expect(':')
            result[key] = parseValue()
            skipWhitespace()
            when (peek()) {
                ',' -> index++
                '}' -> {
                    index++
                    if (requireEnd) ensureEnd()
                    return result
                }
                else -> error("Invalid JSON object")
            }
        }
    }

    private fun parseValue(): Any? {
        skipWhitespace()
        return when (peek()) {
            '{' -> parseObject(requireEnd = false)
            '[' -> parseArray()
            '"' -> parseString()
            't' -> parseLiteral("true", true)
            'f' -> parseLiteral("false", false)
            'n' -> parseLiteral("null", null)
            else -> parseNumber()
        }
    }

    private fun parseArray(): List<Any?> {
        expect('[')
        val result = mutableListOf<Any?>()
        skipWhitespace()
        if (peek() == ']') {
            index++
            return result
        }
        while (true) {
            result += parseValue()
            skipWhitespace()
            when (peek()) {
                ',' -> index++
                ']' -> {
                    index++
                    return result
                }
                else -> error("Invalid JSON array")
            }
        }
    }

    private fun parseString(): String {
        expect('"')
        val result = StringBuilder()
        while (index < input.length) {
            when (val character = input[index++]) {
                '"' -> return result.toString()
                '\\' -> {
                    when (val escaped = input.getOrNull(index++) ?: error("Invalid JSON escape")) {
                        '"', '\\', '/' -> result.append(escaped)
                        'b' -> result.append('\b')
                        'f' -> result.append('\u000C')
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        'u' -> {
                            val code = input.substring(index, index + 4).toInt(16)
                            result.append(code.toChar())
                            index += 4
                        }
                        else -> error("Invalid JSON escape")
                    }
                }
                else -> {
                    if (character.code < 0x20) error("Invalid JSON control character")
                    result.append(character)
                }
            }
        }
        error("Unterminated JSON string")
    }

    private fun parseNumber(): Long {
        val start = index
        if (peek() == '-') index++
        while (peek()?.isDigit() == true) index++
        val next = peek()
        if (start == index || (next != null && next != ',' && next != '}' && next != ']' && !next.isWhitespace())) {
            error("Invalid JSON number")
        }
        return input.substring(start, index).toLong()
    }

    private fun parseLiteral(literal: String, value: Any?): Any? {
        check(input.startsWith(literal, index)) { "Invalid JSON literal" }
        index += literal.length
        return value
    }

    private fun skipWhitespace() {
        while (peek()?.isWhitespace() == true) index++
    }

    private fun expect(character: Char) {
        check(peek() == character) { "Expected '$character' in JSON" }
        index++
    }

    private fun peek(): Char? = input.getOrNull(index)

    private fun ensureEnd() {
        skipWhitespace()
        check(index == input.length) { "Trailing JSON content" }
    }
}
