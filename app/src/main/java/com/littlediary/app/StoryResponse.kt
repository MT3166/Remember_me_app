package com.littlediary.app

data class StoryResult(val title: String, val body: String)

private val reasoningTag = Regex("<\\s*/?\\s*(?:think|analysis|reasoning)\\b[^>]*>", RegexOption.IGNORE_CASE)

fun parseStoryResult(raw: String): StoryResult {
    val candidate = raw
    require(candidate.isNotBlank()) { "故事结果为空" }
    require(!reasoningTag.containsMatchIn(candidate)) { "故事结果包含推理标签" }
    val result = StrictStoryJsonParser(candidate).parse()
    require(!reasoningTag.containsMatchIn(result.title) && !reasoningTag.containsMatchIn(result.body)) {
        "故事结果包含推理标签"
    }
    return result
}

private class StrictStoryJsonParser(private val input: String) {
    private var position = 0

    fun parse(): StoryResult {
        skipWhitespace()
        expect('{')
        skipWhitespace()

        var title: String? = null
        var body: String? = null
        val keys = mutableSetOf<String>()
        require(peek() != '}') { "故事结果不能为空对象" }

        while (true) {
            val key = parseString()
            require(keys.add(key)) { "故事结果包含重复字段" }
            skipWhitespace()
            expect(':')
            val value = parseString()
            when (key) {
                "title" -> title = value
                "body" -> body = value
                else -> throw IllegalArgumentException("故事结果只能包含 title 和 body")
            }
            skipWhitespace()
            when (peek()) {
                ',' -> {
                    position++
                    skipWhitespace()
                    require(peek() != '}') { "故事结果包含多余逗号" }
                }
                '}' -> {
                    position++
                    break
                }
                else -> throw IllegalArgumentException("故事结果不是合法 JSON")
            }
        }

        skipWhitespace()
        require(position == input.length) { "故事结果必须是单个 JSON 对象" }
        val parsedTitle = title?.trim().orEmpty()
        val parsedBody = body?.trim().orEmpty()
        require(keys == setOf("title", "body") && parsedTitle.isNotBlank() && parsedBody.isNotBlank()) {
            "故事标题和正文不能为空"
        }
        return StoryResult(parsedTitle, parsedBody)
    }

    private fun parseString(): String {
        expect('"')
        val result = StringBuilder()
        while (position < input.length) {
            when (val character = input[position++]) {
                '"' -> return result.toString()
                '\\' -> result.append(parseEscape())
                else -> {
                    require(character >= ' ') { "故事结果包含无效控制字符" }
                    result.append(character)
                }
            }
        }
        throw IllegalArgumentException("故事结果包含未结束的字符串")
    }

    private fun parseEscape(): Char {
        require(position < input.length) { "故事结果包含未完成的转义字符" }
        return when (val escaped = input[position++]) {
            '"' -> '"'
            '\\' -> '\\'
            '/' -> '/'
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> {
                require(position + 4 <= input.length) { "故事结果包含无效 Unicode 转义" }
                val digits = input.substring(position, position + 4)
                require(digits.all { it in "0123456789abcdefABCDEF" }) {
                    "故事结果包含无效 Unicode 转义"
                }
                position += 4
                digits.toInt(16).toChar()
            }
            else -> throw IllegalArgumentException("故事结果包含无效转义字符")
        }
    }

    private fun skipWhitespace() {
        while (position < input.length && input[position] in setOf(' ', '\t', '\n', '\r')) position++
    }

    private fun expect(expected: Char) {
        require(position < input.length && input[position] == expected) { "故事结果不是合法 JSON" }
        position++
    }

    private fun peek(): Char = if (position < input.length) input[position] else '\u0000'
}
