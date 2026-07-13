package com.aiassistant.ui.chat

import com.aiassistant.agent.ImageRef
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.actionSystem.ex.ActionUtil
import java.awt.Container
import java.awt.Dimension
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.KeyStroke
import javax.swing.Popup
import javax.swing.SwingUtilities
import javax.swing.TransferHandler
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatInputAreaTest {

    @Test
    fun `disables send button with input`() {
        val inputArea = ChatInputArea(onSend = {})
        val sendButton = findSendButton(inputArea)

        inputArea.setInputEnabled(false)
        assertFalse(sendButton.isEnabled)

        inputArea.setInputEnabled(true)
        assertTrue(sendButton.isEnabled)
    }

    @Test
    fun `shows add file button`() {
        val inputArea = ChatInputArea(onSend = {})

        assertTrue(buttonsIn(inputArea).any { it.text == "+" })
    }

    @Test
    fun `does not send placeholder text`() {
        var sendCount = 0
        val inputArea = ChatInputArea(onSend = { sendCount++ })

        findSendButton(inputArea).doClick()

        assertEquals(0, sendCount)
    }

    @Test
    fun `sends selected file tag as file reference`() {
        var sent = ""
        val inputArea = ChatInputArea(onSend = { sent = it })

        ChatInputArea::class.java
            .getDeclaredMethod("addFileReference", String::class.java)
            .apply { isAccessible = true }
            .invoke(inputArea, "@README.md")

        findSendButton(inputArea).doClick()

        assertContains(sent, "@README.md")
        assertTrue(labelsIn(inputArea).none { it.text?.contains("README.md") == true })
    }

    @Test
    fun `sends pasted images with message`() {
        var sentImages = emptyList<ImageRef>()
        val inputArea = ChatInputArea(
            onSend = {},
            onSendWithImages = { _, images -> sentImages = images }
        )

        @Suppress("UNCHECKED_CAST")
        val imageRefs = ChatInputArea::class.java
            .getDeclaredField("imageRefs")
            .apply { isAccessible = true }
            .get(inputArea) as MutableList<ImageRef>
        imageRefs.add(testImageRef())

        findSendButton(inputArea).doClick()

        assertEquals(1, sentImages.size)
        assertEquals("paste.png", sentImages.single().fileName)
    }

    @Test
    fun `pasted image file is displayed as file chip and sent`() {
        val imageFile = File.createTempFile("browser-shot", ".png").apply {
            deleteOnExit()
            ImageIO.write(BufferedImage(12, 8, BufferedImage.TYPE_INT_ARGB), "png", this)
        }
        val transferable = object : Transferable {
            override fun getTransferDataFlavors(): Array<DataFlavor> =
                arrayOf(DataFlavor.javaFileListFlavor)

            override fun isDataFlavorSupported(flavor: DataFlavor): Boolean =
                flavor == DataFlavor.javaFileListFlavor

            override fun getTransferData(flavor: DataFlavor): Any = listOf(imageFile)
        }
        var sentImages = emptyList<ImageRef>()
        val inputArea = ChatInputArea(
            onSend = {},
            onSendWithImages = { _, images -> sentImages = images }
        )

        assertTrue(inputArea.pasteImagesFromTransferable(transferable))
        assertTrue(labelsIn(inputArea).any {
            it.text?.startsWith("📎 ") == true && it.toolTipText?.startsWith(imageFile.name) == true
        })
        assertFalse(labelsIn(inputArea).any { it.text?.startsWith("PNG · ") == true })

        findSendButton(inputArea).doClick()

        assertEquals(imageFile.name, sentImages.single().fileName)
        assertEquals("image/png", sentImages.single().mimeType)
    }

    @Test
    fun `paste shortcut invokes image paste action`() {
        val image = BufferedImage(6, 4, BufferedImage.TYPE_INT_ARGB)
        val transferable = object : Transferable {
            override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.imageFlavor)
            override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.imageFlavor
            override fun getTransferData(flavor: DataFlavor): Any = image
        }
        val inputArea = ChatInputArea(
            onSend = {},
            clipboardContentsProvider = { transferable }
        )

        invokeKeyAction(inputArea, KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK))

        assertEquals(1, imageRefs(inputArea).size)
    }

    @Test
    fun `mac paste shortcut invokes image paste action`() {
        val image = BufferedImage(6, 4, BufferedImage.TYPE_INT_ARGB)
        val transferable = object : Transferable {
            override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.imageFlavor)
            override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.imageFlavor
            override fun getTransferData(flavor: DataFlavor): Any = image
        }
        val inputArea = ChatInputArea(
            onSend = {},
            clipboardContentsProvider = { transferable }
        )

        invokeKeyAction(inputArea, KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.META_DOWN_MASK))

        assertEquals(1, imageRefs(inputArea).size)
    }

    @Test
    fun `popup arrow actions change selected item`() {
        val inputArea = ChatInputArea(onSend = {})
        popupMenuItems(inputArea).addAll(
            listOf(JMenuItem("/plan"), JMenuItem("/clear"), JMenuItem("/new"))
        )
        invokePrivateMethod(inputArea, "activatePopup", object : Popup() {})
        SwingUtilities.invokeAndWait { }

        invokeKeyAction(inputArea, KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0))
        assertEquals(1, privateField(inputArea, "popupIndex"))

        invokeKeyAction(inputArea, KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0))
        assertEquals(0, privateField(inputArea, "popupIndex"))
    }

    @Test
    fun `popup closes when input anchor is not displayable`() {
        val inputArea = ChatInputArea(onSend = {})
        setPrivateField(inputArea, "activePopup", object : Popup() {
            override fun hide() = Unit
        })
        val entryClass = ChatInputArea::class.java.declaredClasses.single {
            it.simpleName == "ProjectFileEntry"
        }
        val constructor = entryClass.declaredConstructors.single().apply { isAccessible = true }
        val entry = constructor.newInstance("README.md", "README.md")

        invokePrivateMethod(inputArea, "showPopup", "", listOf(entry))

        val activePopup: Popup? = privateField(inputArea, "activePopup")
        assertEquals(null, activePopup)
    }

    @Test
    fun `popup arrow navigation keeps selected item visible`() {
        val inputArea = ChatInputArea(onSend = {})
        lateinit var scrollPane: JScrollPane
        lateinit var menuItems: List<JMenuItem>

        SwingUtilities.invokeAndWait {
            val menuPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
            }
            menuItems = (1..12).map { index ->
                JMenuItem("item-$index").apply {
                    preferredSize = Dimension(140, 24)
                    maximumSize = Dimension(140, 24)
                }
            }
            menuItems.forEach(menuPanel::add)
            popupMenuItems(inputArea).addAll(menuItems)

            scrollPane = JScrollPane(menuPanel).apply {
                setSize(160, 72)
                doLayout()
            }
            menuPanel.setSize(menuPanel.preferredSize)
            menuPanel.doLayout()
            invokePrivateMethod(inputArea, "activatePopup", object : Popup() {})
        }
        SwingUtilities.invokeAndWait { }

        repeat(4) {
            SwingUtilities.invokeAndWait {
                invokeKeyAction(inputArea, KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0))
            }
        }

        val selectedIndex: Int = privateField(inputArea, "popupIndex")
        val selectedBounds = menuItems[selectedIndex].bounds
        assertTrue(
            scrollPane.viewport.viewRect.contains(selectedBounds),
            "选中项应始终位于 Popup 可视区域内"
        )

        SwingUtilities.invokeAndWait {
            invokePrivateMethod(inputArea, "selectFirstPopupItem")
            invokeKeyAction(inputArea, KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0))
        }
        val lastItemBounds = menuItems.last().bounds
        assertTrue(
            scrollPane.viewport.viewRect.contains(lastItemBounds),
            "从首项向上循环到末项时，末项应自动滚入可视区域"
        )

        SwingUtilities.invokeAndWait {
            invokeKeyAction(inputArea, KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0))
        }
        assertTrue(
            scrollPane.viewport.viewRect.contains(menuItems.first().bounds),
            "从末项向下循环到首项时，首项应自动滚回可视区域"
        )
    }

    @Test
    fun `file and image chips use the same compact style`() {
        val inputArea = ChatInputArea(onSend = {})
        invokePrivateMethod(inputArea, "addFileReference", "@README.md")
        imageRefs(inputArea).add(testImageRef())
        invokePrivateMethod(inputArea, "refreshTags")

        val chips = tagsPanel(inputArea).components.filterIsInstance<JPanel>()
        assertEquals(2, chips.size)
        val fileChip = chips[0]
        val imageChip = chips[1]

        assertEquals(fileChip.background, imageChip.background)
        assertEquals(fileChip.border.javaClass, imageChip.border.javaClass)
        assertEquals(fileChip.preferredSize.height, imageChip.preferredSize.height)
        assertEquals(buttonsIn(fileChip).size, buttonsIn(imageChip).size)
        assertFalse(labelsIn(imageChip).any { it.text?.startsWith("PNG · ") == true })
    }

    @Test
    fun `file and image chip clicks open their IDEA targets`() {
        val openedPaths = mutableListOf<String>()
        val inputArea = ChatInputArea(
            onSend = {},
            fileOpenHandler = openedPaths::add
        )
        invokePrivateMethod(inputArea, "addFileReference", "@README.md")
        imageRefs(inputArea).add(testImageRef())
        invokePrivateMethod(inputArea, "refreshTags")
        val chips = tagsPanel(inputArea).components.filterIsInstance<JPanel>()

        clickPrimaryLabel(chips[0])
        clickPrimaryLabel(chips[1])

        assertEquals("README.md", openedPaths[0])
        val imagePreview = File(openedPaths[1])
        assertTrue(imagePreview.isFile)
        assertEquals("png", imagePreview.extension)
        assertTrue(ImageIO.read(imagePreview) != null)
    }

    @Test
    fun `image chip remove button does not open preview`() {
        val openedPaths = mutableListOf<String>()
        val inputArea = ChatInputArea(
            onSend = {},
            fileOpenHandler = openedPaths::add
        )
        imageRefs(inputArea).add(testImageRef())
        invokePrivateMethod(inputArea, "refreshTags")
        val imageChip = tagsPanel(inputArea).components.single() as JPanel

        buttonsIn(imageChip).single().doClick()

        assertTrue(openedPaths.isEmpty())
        assertTrue(imageRefs(inputArea).isEmpty())
    }

    @Test
    fun `removing image chip deletes generated preview file`() {
        val openedPaths = mutableListOf<String>()
        val inputArea = ChatInputArea(
            onSend = {},
            fileOpenHandler = openedPaths::add
        )
        imageRefs(inputArea).add(testImageRef())
        invokePrivateMethod(inputArea, "refreshTags")
        val imageChip = tagsPanel(inputArea).components.single() as JPanel
        clickPrimaryLabel(imageChip)
        val previewFile = File(openedPaths.single())
        assertTrue(previewFile.isFile)

        buttonsIn(imageChip).single().doClick()

        assertFalse(previewFile.exists())
        assertFalse(previewFile.parentFile.exists())
    }

    @Test
    fun `image preview uses sanitized MIME extension`() {
        val openedPaths = mutableListOf<String>()
        val inputArea = ChatInputArea(
            onSend = {},
            fileOpenHandler = openedPaths::add
        )
        imageRefs(inputArea).add(
            testImageRef().copy(fileName = "../CON?.jpeg", mimeType = "image/png")
        )
        invokePrivateMethod(inputArea, "refreshTags")

        clickPrimaryLabel(tagsPanel(inputArea).components.single() as JPanel)

        assertEquals("image_CON.png", File(openedPaths.single()).name)
        inputArea.dispose()
    }

    @Test
    fun `IDE local down shortcut is registered on text area`() {
        val inputArea = ChatInputArea(onSend = {})
        findIdeShortcutAction(
            inputArea,
            KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0)
        )
    }

    @Test
    fun `text area transfer handler imports image clipboard data`() {
        val image = BufferedImage(6, 4, BufferedImage.TYPE_INT_ARGB)
        val transferable = object : Transferable {
            override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.imageFlavor)
            override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.imageFlavor
            override fun getTransferData(flavor: DataFlavor): Any = image
        }
        val inputArea = ChatInputArea(onSend = {})
        val textArea = textArea(inputArea)

        val imported = textArea.transferHandler.importData(
            TransferHandler.TransferSupport(textArea, transferable)
        )

        assertTrue(imported)
        assertEquals(1, imageRefs(inputArea).size)
    }

    @Test
    fun `popup enter activates initially selected item`() {
        val inputArea = ChatInputArea(onSend = {})
        var clickCount = 0
        popupMenuItems(inputArea).add(JMenuItem("/plan").apply {
            addActionListener { clickCount++ }
        })
        invokePrivateMethod(inputArea, "activatePopup", object : Popup() {})
        SwingUtilities.invokeAndWait { }

        invokeKeyAction(inputArea, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0))

        assertEquals(1, clickCount)
    }

    @Test
    fun `at trigger is evaluated after caret moves`() {
        val inputArea = ChatInputArea(onSend = {})
        val entryClass = ChatInputArea::class.java.declaredClasses.single { it.simpleName == "ProjectFileEntry" }
        val constructor = entryClass.declaredConstructors.single().apply { isAccessible = true }
        val entry = constructor.newInstance("README.md", "README.md")
        setPrivateField(inputArea, "cachedFiles", listOf(entry))
        val textArea = textArea(inputArea)

        SwingUtilities.invokeAndWait {
            textArea.text = "@"
            textArea.caretPosition = 1
        }
        SwingUtilities.invokeAndWait { }

        assertEquals(1, popupMenuItems(inputArea).size)
    }

    @Test
    fun `slash trigger is evaluated after caret moves`() {
        val inputArea = ChatInputArea(onSend = {})
        val textArea = textArea(inputArea)

        SwingUtilities.invokeAndWait {
            textArea.text = "/"
            textArea.caretPosition = 1
        }
        SwingUtilities.invokeAndWait { }

        assertTrue(popupMenuItems(inputArea).any { it.text == "/plan" })
    }

    @Test
    fun `shows and clears selection tag`() {
        val inputArea = ChatInputArea(onSend = {})

        inputArea.setSelectionReference(fileName = "UserService.kt", lineRange = "40-60")
        assertTrue(labelsIn(inputArea).any { it.text?.contains("UserService.kt:40-60") == true })

        inputArea.setSelectionReference(fileName = null)
        assertTrue(labelsIn(inputArea).none { it.text?.contains("UserService.kt:40-60") == true })
    }

    @Test
    fun `dispose stops error recovery timer`() {
        val inputArea = ChatInputArea(onSend = {})

        inputArea.showError()
        val timer = ChatInputArea::class.java
            .getDeclaredField("errorRecoveryTimer")
            .apply { isAccessible = true }
            .get(inputArea) as javax.swing.Timer

        assertTrue(timer.isRunning)
        inputArea.dispose()

        assertFalse(timer.isRunning)
    }

    private fun findSendButton(inputArea: ChatInputArea): JButton =
        buttonsIn(inputArea).single { it.accessibleContext.accessibleDescription == "发送消息" }

    private fun invokeKeyAction(inputArea: ChatInputArea, keyStroke: KeyStroke) {
        val textArea = textArea(inputArea)
        val actionKey = textArea.getInputMap(JComponent.WHEN_FOCUSED).get(keyStroke)
        val action = textArea.actionMap.get(actionKey)
        requireNotNull(action) { "按键 $keyStroke 没有绑定 Action" }
        action.actionPerformed(ActionEvent(textArea, ActionEvent.ACTION_PERFORMED, actionKey.toString()))
    }

    private fun findIdeShortcutAction(inputArea: ChatInputArea, keyStroke: KeyStroke) =
        ActionUtil.getActions(textArea(inputArea)).single { action ->
            action.shortcutSet.shortcuts.any { shortcut ->
                shortcut is KeyboardShortcut && shortcut.firstKeyStroke == keyStroke
            }
        }

    private fun textArea(inputArea: ChatInputArea): javax.swing.JTextArea =
        privateField(inputArea, "textArea")

    private fun tagsPanel(inputArea: ChatInputArea): JPanel =
        privateField(inputArea, "tagsPanel")

    @Suppress("UNCHECKED_CAST")
    private fun imageRefs(inputArea: ChatInputArea): MutableList<ImageRef> =
        privateField(inputArea, "imageRefs")

    @Suppress("UNCHECKED_CAST")
    private fun popupMenuItems(inputArea: ChatInputArea): MutableList<JMenuItem> =
        privateField(inputArea, "popupMenuItems")

    private fun setPrivateField(inputArea: ChatInputArea, name: String, value: Any?) {
        ChatInputArea::class.java.getDeclaredField(name).apply { isAccessible = true }.set(inputArea, value)
    }

    private fun invokePrivateMethod(inputArea: ChatInputArea, name: String, vararg args: Any): Any? {
        val method = ChatInputArea::class.java.declaredMethods.single {
            it.name == name && it.parameterCount == args.size
        }.apply { isAccessible = true }
        return method.invoke(inputArea, *args)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> privateField(inputArea: ChatInputArea, name: String): T =
        ChatInputArea::class.java.getDeclaredField(name).apply { isAccessible = true }.get(inputArea) as T

    private fun buttonsIn(container: Container): List<JButton> =
        container.components.flatMap { child ->
            when (child) {
                is JButton -> listOf(child)
                is Container -> buttonsIn(child)
                else -> emptyList()
            }
        }

    private fun labelsIn(container: Container): List<JLabel> =
        container.components.flatMap { child ->
            when (child) {
                is JLabel -> listOf(child)
                is Container -> labelsIn(child)
                else -> emptyList()
            }
        }

    private fun clickPrimaryLabel(chip: JPanel) {
        val label = labelsIn(chip).single { it.text?.startsWith("📎 ") == true }
        val event = MouseEvent(
            label,
            MouseEvent.MOUSE_CLICKED,
            System.currentTimeMillis(),
            0,
            1,
            1,
            1,
            false,
            MouseEvent.BUTTON1
        )
        label.mouseListeners.forEach { it.mouseClicked(event) }
    }

    private fun testImageRef(): ImageRef {
        val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        val encodedImage = ByteArrayOutputStream().use { output ->
            ImageIO.write(image, "png", output)
            Base64.getEncoder().encodeToString(output.toByteArray())
        }
        return ImageRef(
            id = "img-1",
            fileName = "paste.png",
            base64Data = encodedImage,
            mimeType = "image/png",
            thumbnail = image,
            width = 1,
            height = 1,
            sizeBytes = 1
        )
    }
}
