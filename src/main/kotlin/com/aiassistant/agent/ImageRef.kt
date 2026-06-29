package com.aiassistant.agent

import java.awt.image.BufferedImage
import java.util.UUID

/**
 * 图片引用，对齐文档 docs/agent/images.md §五 ImageRef 数据结构。
 * 粘贴图片经缩放（长边 ≤2048px）、PNG 编码、Base64 编码后封装为此结构，
 * 存入 ChatViewModel.images[]，发送时组装为独立的 image content block。
 */
data class ImageRef(
    /** 唯一标识（UUID） */
    val id: String = UUID.randomUUID().toString(),
    /** 文件名，粘贴图片生成时间戳名如 "paste_20260628_143000.png" */
    val fileName: String,
    /** Base64 编码数据（不含 data: URI 前缀） */
    val base64Data: String,
    /** MIME 类型，"image/png" / "image/jpeg" / "image/gif" / "image/webp" */
    val mimeType: String = "image/png",
    /** 缩略图（64x64，TagsRow 展示用） */
    val thumbnail: BufferedImage? = null,
    /** 缩放后宽度（px） */
    val width: Int = 0,
    /** 缩放后高度（px） */
    val height: Int = 0,
    /** Base64 编码前字节数 */
    val sizeBytes: Long = 0
)
