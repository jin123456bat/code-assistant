package com.aiassistant.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ImageRefTest {

    @Test
    fun `默认 mimeType 为 image-png`() {
        val img = ImageRef(
            fileName = "paste_20260706_143000.png",
            base64Data = "iVBORw0KGgo="
        )
        assertEquals("image/png", img.mimeType)
    }

    @Test
    fun `id 自动生成 UUID`() {
        val img = ImageRef(
            fileName = "test.png",
            base64Data = "abc123"
        )
        assertNotNull(img.id)
        assertTrue(img.id.length >= 32, "UUID 长度至少 32 字符")
    }

    @Test
    fun `所有字段赋值正常`() {
        val img = ImageRef(
            id = "custom-id-001",
            fileName = "screenshot.jpeg",
            base64Data = "base64EncodedDataHere",
            mimeType = "image/jpeg",
            thumbnail = null,
            width = 1920,
            height = 1080,
            sizeBytes = 204800
        )
        assertEquals("custom-id-001", img.id)
        assertEquals("screenshot.jpeg", img.fileName)
        assertEquals("image/jpeg", img.mimeType)
        assertEquals(1920, img.width)
        assertEquals(1080, img.height)
        assertEquals(204800, img.sizeBytes)
    }

    @Test
    fun `不同实例的 id 不同`() {
        val a = ImageRef(fileName = "a.png", base64Data = "data_a")
        val b = ImageRef(fileName = "b.png", base64Data = "data_b")
        assertTrue(a.id != b.id, "两个 ImageRef 应生成不同的 UUID")
    }

    @Test
    fun `支持 webp 格式`() {
        val img = ImageRef(
            fileName = "icon.webp",
            base64Data = "webpdata",
            mimeType = "image/webp",
            width = 64,
            height = 64,
            sizeBytes = 1024
        )
        assertEquals("image/webp", img.mimeType)
        assertEquals(64, img.width)
        assertEquals(1024, img.sizeBytes)
    }
}
