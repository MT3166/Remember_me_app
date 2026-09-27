package com.littlediary.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertThrows

class StoryPromptTest {
    @Test
    fun promptForbidsInventionAndPreservesFirstPerson() {
        val prompt = buildChildStoryPrompt("我今天在公园看到了小鸟。", final = false)

        assertTrue(prompt.contains("不得编造"))
        assertTrue(prompt.contains("第一人称"))
        assertTrue(prompt.contains("按素材出现顺序"))
        assertTrue(prompt.contains("我今天在公园看到了小鸟。"))
    }

    @Test
    fun draftAndFinalPromptsDescribeDifferentStoryStagesInChildFriendlyChinese() {
        val draft = buildChildStoryPrompt("我画了一朵花。", final = false)
        val final = buildChildStoryPrompt("我画了一朵花。", final = true)

        assertTrue(draft.contains("临时故事"))
        assertTrue(draft.contains("还会有新的片段"))
        assertTrue(final.contains("最终故事"))
        assertTrue(final.contains("当天的素材已经收齐"))
        assertTrue(final.contains("适合5岁儿童听读"))
    }

    @Test
    fun parsesOnlyTitleAndBodyStrings() {
        assertEquals(
            StoryResult("我的小鸟", "我在公园看到了小鸟。"),
            parseStoryResult("{\"title\":\"我的小鸟\",\"body\":\"我在公园看到了小鸟。\"}"),
        )
    }

    @Test
    fun rejectsBlankFieldsUnknownFieldsAndUnknownShapes() {
        val invalid = listOf(
            "{}",
            "{\"title\":\"\",\"body\":\"有内容\"}",
            "{\"title\":\"有内容\",\"body\":\"   \"}",
            "{\"title\":\"标题\",\"body\":\"正文\",\"summary\":\"多余字段\"}",
            "{\"title\":123,\"body\":\"正文\"}",
            "[\"标题\",\"正文\"]",
            "这是故事：{\"title\":\"标题\",\"body\":\"正文\"}",
        )

        invalid.forEach { raw ->
            assertThrows(IllegalArgumentException::class.java) { parseStoryResult(raw) }
        }
    }

    @Test
    fun rejectsReasoningTagsEvenWhenJsonLooksValid() {
        val validJson = "{\"title\":\"标题\",\"body\":\"正文\"}"

        assertThrows(IllegalArgumentException::class.java) {
            parseStoryResult("<think>还没有结束$validJson")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseStoryResult("<think>内部推理</think>$validJson")
        }
    }

}
