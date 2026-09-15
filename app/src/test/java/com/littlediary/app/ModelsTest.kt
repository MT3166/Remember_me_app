package com.littlediary.app

import org.junit.Assert.*
import org.junit.Test

class ModelsTest {
    @Test fun longNarrationPreservesTextAndSurrogatePairs() {
        val text = "今天\uD83C\uDF1E画画了".repeat(5)
        val chunks = splitForSpeech(text, 3)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 3 && !it.last().isHighSurrogate() && !it.first().isLowSurrogate() })
        assertTrue(splitForSpeech("", 3).isEmpty())
    }
    @Test fun reopeningDiaryResumesAppropriateStage() {
        assertEquals(DiaryStage.RECORD, Diary(body = "草稿").initialStage)
        assertEquals(DiaryStage.READ, Diary(body = "已保存", confirmed = true).initialStage)
        assertEquals(DiaryStage.REVIEW, Diary(body = "原文", suggestedBody = "待确认", confirmed = true).initialStage)
    }

    @Test fun savingSuggestionPreservesOriginalAndInvalidatesOldCover() {
        val d = Diary(body = "大太杨", original = "大太杨", suggestedBody = "大太阳", suggestedTitle = "我的画",
            questions = listOf("和谁画的？"), coverFile = "old.jpg", coverBody = "大太杨")
        val accepted = d.acceptedVersion()
        assertEquals("大太阳", accepted.body)
        assertEquals("我的画", accepted.title)
        assertEquals("大太杨", accepted.original)
        assertEquals(d.questions, accepted.questions)
        assertTrue(accepted.suggestedBody.isEmpty())
        assertTrue(accepted.coverOutdated)
    }

    @Test fun savingUncorrectedTextDoesNotClearIt() {
        val d = Diary(title = "今天", body = "原来的话")
        assertEquals(d, d.acceptedVersion())
        assertEquals(d.body, d.reviewBody)
        assertEquals(d.title, d.reviewTitle)
    }

    @Test fun openAiPathDoesNotDuplicateSuffix() {
        assertEquals("https://example.com/v1/chat/completions", endpoint("https://example.com/v1/", Protocol.OPENAI))
        assertEquals("https://example.com/v1/chat/completions", endpoint("https://example.com/v1/chat/completions", Protocol.OPENAI))
    }

    @Test fun anthropicSupportsRootVersionAndFullEndpoint() {
        val expected = "https://api.minimax.cn/anthropic/v1/messages"
        for (base in listOf("https://api.minimax.cn/anthropic", "https://api.minimax.cn/anthropic/v1", expected)) {
            assertEquals(expected, endpoint(base, Protocol.ANTHROPIC))
        }
    }

    @Test fun unsafeEndpointsAreRejected() {
        for (url in listOf("http://example.com", "https://key@example.com", "https://example.com?key=secret",
            "https://example.com#part", "file:///etc/passwd", "not a url")) {
            assertThrows(IllegalArgumentException::class.java) { requireHttps(url) }
        }
    }

    @Test fun thinkingAndMarkdownAreNotDiaryContent() {
        assertEquals("{\"text\":\"今天很开心\"}", cleanModelText("<think>private\nreasoning</think>\n```json\n{\"text\":\"今天很开心\"}\n```"))
        assertThrows(IllegalArgumentException::class.java) { cleanModelText("<think>unfinished") }
    }

    @Test fun editingBodyInvalidatesCoverButEditingTitleDoesNot() {
        val diary = Diary(body = "今天画画了", coverBody = "今天画画了", coverFile = "cover.jpg")
        assertFalse(diary.copy(title = "我的画").coverOutdated)
        assertTrue(diary.copy(body = "今天唱歌了").coverOutdated)
    }
}
