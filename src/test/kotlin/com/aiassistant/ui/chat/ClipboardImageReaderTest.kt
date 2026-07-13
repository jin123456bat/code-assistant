package com.aiassistant.ui.chat

import java.awt.Image
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class ClipboardImageReaderTest {

    @Test
    fun `浏览器通用 Image flavor 会转换为 BufferedImage`() {
        val source = BufferedImage(7, 5, BufferedImage.TYPE_INT_ARGB)
        val browserImage: Image = source.getScaledInstance(7, 5, Image.SCALE_SMOOTH)
        val transferable = transferableOf(
            mapOf(DataFlavor.imageFlavor to browserImage)
        )

        val images = ClipboardImageReader.read(transferable)

        assertEquals(1, images.size)
        assertEquals(7, images.single().image.width)
        assertEquals(5, images.single().image.height)
        assertEquals(null, images.single().sourceFileName)
    }

    @Test
    fun `图片文件列表优先并保留多个原文件名`() {
        val directory = createTempDirectory().toFile()
        val first = writePng(File(directory, "first.png"), 4, 3)
        val second = writePng(File(directory, "second.png"), 6, 2)
        val duplicateImageFlavor = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        val transferable = transferableOf(
            linkedMapOf(
                DataFlavor.javaFileListFlavor to listOf(first, second),
                DataFlavor.imageFlavor to duplicateImageFlavor
            )
        )

        val images = ClipboardImageReader.read(transferable)

        assertEquals(listOf("first.png", "second.png"), images.map { it.sourceFileName })
        assertEquals(listOf(4, 6), images.map { it.image.width })
    }

    @Test
    fun `浏览器 image png 字节流 flavor 可以读取`() {
        val bytes = ByteArrayOutputStream().use { output ->
            ImageIO.write(BufferedImage(8, 9, BufferedImage.TYPE_INT_ARGB), "png", output)
            output.toByteArray()
        }
        val pngFlavor = DataFlavor("image/png;class=java.io.InputStream")
        val transferable = transferableOf(
            mapOf(pngFlavor to bytes.inputStream())
        )

        val images = ClipboardImageReader.read(transferable)

        assertEquals(1, images.size)
        assertEquals(8, images.single().image.width)
        assertEquals(9, images.single().image.height)
    }

    private fun writePng(file: File, width: Int, height: Int): File {
        ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", file)
        return file
    }

    private fun transferableOf(values: Map<DataFlavor, Any>): Transferable = object : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = values.keys.toTypedArray()

        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = values.containsKey(flavor)

        override fun getTransferData(flavor: DataFlavor): Any =
            values[flavor] ?: throw java.awt.datatransfer.UnsupportedFlavorException(flavor)
    }
}
