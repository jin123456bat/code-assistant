package com.aiassistant.ui.chat

import java.awt.Image
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import javax.imageio.ImageIO
import javax.swing.ImageIcon

/**
 * 从一次剪贴板快照中读取图片，统一兼容本地图片文件、系统截图和浏览器复制图片。
 *
 * 本地文件优先于 imageFlavor：Finder/Explorer 复制图片时通常同时暴露两种 flavor，
 * 优先文件列表才能保留原文件名并支持一次复制多张图片，同时避免同一图片被添加两次。
 */
internal object ClipboardImageReader {

    data class ClipboardImage(
        val image: BufferedImage,
        val sourceFileName: String? = null
    )

    fun read(transferable: Transferable): List<ClipboardImage> {
        val fileImages = readImageFiles(transferable)
        if (fileImages.isNotEmpty()) return fileImages

        readStandardImage(transferable)?.let { return listOf(ClipboardImage(it)) }

        // 部分浏览器/桌面环境只提供 image/png 等 MIME flavor，数据形态可能是流或字节数组。
        return transferable.transferDataFlavors.asSequence()
            .filter { it != DataFlavor.imageFlavor && it.primaryType.equals("image", ignoreCase = true) }
            .mapNotNull { flavor -> readEncodedImage(transferable, flavor) }
            .map { ClipboardImage(it) }
            .take(1)
            .toList()
    }

    private fun readImageFiles(transferable: Transferable): List<ClipboardImage> {
        if (!transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return emptyList()
        val files = runCatching {
            @Suppress("UNCHECKED_CAST")
            transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>
        }.getOrNull() ?: return emptyList()

        return files.asSequence()
            .filterIsInstance<File>()
            .mapNotNull { file ->
                val image = runCatching { ImageIO.read(file) }.getOrNull() ?: return@mapNotNull null
                ClipboardImage(image = image, sourceFileName = file.name)
            }
            .toList()
    }

    private fun readStandardImage(transferable: Transferable): BufferedImage? {
        if (!transferable.isDataFlavorSupported(DataFlavor.imageFlavor)) return null
        val image = runCatching {
            transferable.getTransferData(DataFlavor.imageFlavor) as? Image
        }.getOrNull() ?: return null
        return image.toBufferedImage()
    }

    private fun readEncodedImage(transferable: Transferable, flavor: DataFlavor): BufferedImage? {
        val data = runCatching { transferable.getTransferData(flavor) }.getOrNull() ?: return null
        return when (data) {
            is InputStream -> data.use(ImageIO::read)
            is ByteArray -> data.inputStream().use(ImageIO::read)
            is ByteBuffer -> {
                val copy = data.slice()
                val bytes = ByteArray(copy.remaining())
                copy.get(bytes)
                bytes.inputStream().use(ImageIO::read)
            }
            is Image -> data.toBufferedImage()
            else -> null
        }
    }

    /** 浏览器常返回 ToolkitImage，而不是 BufferedImage；ImageIcon 会同步完成像素加载。 */
    private fun Image.toBufferedImage(): BufferedImage? {
        if (this is BufferedImage) return this
        val loadedImage = ImageIcon(this)
        val width = loadedImage.iconWidth
        val height = loadedImage.iconHeight
        if (width <= 0 || height <= 0) return null

        return BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).apply {
            val graphics = createGraphics()
            graphics.drawImage(loadedImage.image, 0, 0, width, height, null)
            graphics.dispose()
        }
    }
}
