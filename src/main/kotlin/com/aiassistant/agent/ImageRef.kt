package com.aiassistant.agent

import com.anthropic.models.beta.messages.BetaBase64ImageSource
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaImageBlockParam
import java.awt.image.BufferedImage
import java.util.UUID

/**
 * 图片引用，对齐文档 docs/agent/images.md §五 ImageRef 数据结构。
 * 粘贴图片经缩放（长边 ≤2048px）、PNG 编码、Base64 编码后封装为此结构，
 * 存入 ChatViewModel.images[]，发送时组装为独立的 image content block。
 *
 * 图片 → API ImageBlock 的转换收敛到此方法，所有路径（粘贴、Read 工具等）
 * 统一通过 [toBetaImageBlockParam] 构建 API 参数，消除分散的 MIME 映射。
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
    /** 缩略图（48x48，TagsRow 展示用） */
    val thumbnail: BufferedImage? = null,
    /** 缩放后宽度（px） */
    val width: Int = 0,
    /** 缩放后高度（px） */
    val height: Int = 0,
    /** Base64 编码前字节数 */
    val sizeBytes: Long = 0
) {
    /** 图片转换为 API BetaImageBlockParam，所有图片路径的统一转换入口（含粘贴、Read 工具等） */
    fun toBetaImageBlockParam(): BetaImageBlockParam {
        val mediaType = when (mimeType) {
            "image/jpeg" -> BetaBase64ImageSource.MediaType.IMAGE_JPEG
            "image/gif" -> BetaBase64ImageSource.MediaType.IMAGE_GIF
            "image/webp" -> BetaBase64ImageSource.MediaType.IMAGE_WEBP
            else -> BetaBase64ImageSource.MediaType.IMAGE_PNG
        }
        val source = BetaBase64ImageSource.builder()
            .mediaType(mediaType)
            .data(base64Data)
            .build()
        return BetaImageBlockParam.builder().source(source).build()
    }

    /** 图片转换为 API BetaContentBlockParam（用于 user message content 数组） */
    fun toBetaContentBlockParam(): BetaContentBlockParam =
        BetaContentBlockParam.ofImage(toBetaImageBlockParam())
}
