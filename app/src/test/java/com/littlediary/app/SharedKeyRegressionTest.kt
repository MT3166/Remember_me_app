package com.littlediary.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SharedKeyRegressionTest {
    @Test fun sharedCredentialCanBeUpdatedAndClearedFromEitherTab() {
        val settings = ModelSettings(textKey = "old", reuseTextKeyForImages = true).withImageKey("new")
        assertEquals("new", settings.requireKey(ModelKind.TEXT))
        assertEquals("new", settings.requireKey(ModelKind.IMAGE))
        val cleared = settings.withTextKey("")
        val error = assertThrows(IllegalArgumentException::class.java) { cleared.requireKey(ModelKind.IMAGE) }
        assertEquals("请填写文本与图片共用的 API Key", error.message)
    }

    @Test fun disabledSharingDoesNotUseTheOtherSlotsKey() {
        val settings = ModelSettings(textKey = "text-only", reuseTextKeyForImages = false)
        assertThrows(IllegalArgumentException::class.java) { settings.requireKey(ModelKind.IMAGE) }
    }

    @Test fun sharedKeyCanBeEnteredOnImageSide() {
        val settings = ModelSettings(imageKey = "minimax-shared", reuseTextKeyForImages = true)
        assertEquals("minimax-shared", settings.imageKeyForRequest)
    }

    @Test fun switchingTextSupplierKeepsTheOnlyImageCredential() {
        val settings = ModelSettings(textKey = "minimax-shared", reuseTextKeyForImages = true)
            .selectTextProvider(Provider.OPENAI)
        assertEquals("minimax-shared", settings.imageKeyForRequest)
        assertEquals("", settings.textKey)
    }

    @Test fun switchingImageSupplierKeepsTheOnlyTextCredential() {
        val settings = ModelSettings(imageKey = "minimax-shared", reuseTextKeyForImages = true)
            .selectImageProvider(Provider.OPENAI)
        assertEquals("minimax-shared", settings.textKey)
        assertEquals("", settings.imageKey)
    }

    @Test fun imageSupplierRoundTripRestoresTheSharedCredential() {
        val settings = ModelSettings(textKey = "minimax-shared", reuseTextKeyForImages = true)
            .selectImageProvider(Provider.OPENAI).selectImageProvider(Provider.MINIMAX)
        assertEquals("minimax-shared", settings.imageKeyForRequest)
    }
}
