package com.littlediary.app

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class DiaryStore(context: Context, folder: String = "diaries") {
    private val directory = File(context.filesDir, folder).apply { mkdirs() }
    private val removed = mutableSetOf<String>()

    private fun file(id: String): File {
        require(id.matches(Regex("[a-zA-Z0-9-]+")))
        return File(directory, "$id.json")
    }

    @Synchronized fun load(): List<Diary> = directory.listFiles().orEmpty()
        .filter { it.extension == "json" || it.name.endsWith(".json.bak") }
        .map { it.name.removeSuffix(".bak").removeSuffix(".json") }.distinct()
        .map { id -> decode(JSONObject(AtomicFile(file(id)).readFully().toString(Charsets.UTF_8))) }
        .sortedByDescending { it.createdAt }

    @Synchronized fun save(diary: Diary) {
        if (diary.id in removed) return
        val atomic = AtomicFile(file(diary.id))
        if (atomic.baseFile.exists()) {
            val previous = JSONObject(atomic.readFully().toString(Charsets.UTF_8))
            if (previous.optLong("revision") > diary.revision) return
        }
        val stream = atomic.startWrite()
        try {
            stream.write(encode(diary).toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            throw e
        }
    }

    @Synchronized fun delete(diary: Diary) {
        AtomicFile(file(diary.id)).delete()
        check(!file(diary.id).exists()) { "日记删除失败" }
        removed.add(diary.id)
        directory.listFiles().orEmpty().filter { it.name.startsWith("${diary.id}-") }.forEach { it.delete() }
    }

    @Synchronized fun saveCover(id: String, revision: Long, bytes: ByteArray): String {
        file(id)
        check(id !in removed) { "日记已删除" }
        val name = "$id-$revision.jpg"
        val atomic = AtomicFile(File(directory, name))
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            throw e
        }
        return name
    }

    fun cover(name: String): File? = if (name.isNotBlank() && File(name).name == name) {
        File(directory, name).takeIf { it.exists() }
    } else null

    companion object {
        fun encode(d: Diary): JSONObject = JSONObject().apply {
            put("id", d.id); put("createdAt", d.createdAt); put("revision", d.revision)
            put("title", d.title); put("original", d.original); put("body", d.body)
            put("suggestedTitle", d.suggestedTitle); put("suggestedBody", d.suggestedBody)
            put("questions", JSONArray(d.questions)); put("confirmed", d.confirmed)
            put("coverFile", d.coverFile); put("coverBody", d.coverBody); put("coverPrompt", d.coverPrompt)
        }

        fun decode(j: JSONObject): Diary = Diary(
            id = j.getString("id"), createdAt = j.getLong("createdAt"), revision = j.optLong("revision"),
            title = j.optString("title"), original = j.optString("original"), body = j.optString("body"),
            suggestedTitle = j.optString("suggestedTitle"), suggestedBody = j.optString("suggestedBody"),
            questions = j.optJSONArray("questions")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty(),
            confirmed = j.optBoolean("confirmed"), coverFile = j.optString("coverFile"),
            coverBody = j.optString("coverBody"), coverPrompt = j.optString("coverPrompt"),
        )
    }
}
