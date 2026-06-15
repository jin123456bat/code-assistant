package com.aiassistant.ui

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.awt.Cursor
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.io.File
import javax.swing.JTextArea
import javax.swing.JTextPane
import javax.swing.text.JTextComponent

/**
 * 为文本组件添加文件路径点击跳转功能。
 * 检测文本中的文件路径（如 src/main/File.kt:42），
 * 点击时在 IDE 中打开对应文件并跳转到指定行。
 * 鼠标悬停在文件路径上时显示手型光标。
 */
object FilePathNavigator {

    /**
     * 匹配链接：
     * - 文件路径：path/to/file.ext 或 file.ext:42
     * - HTTP/HTTPS/FTP URL
     * - www. 开头的 URL
     */
    private val LINK_REGEX = Regex(
        """\b(?:https?|ftp)://[^\s<>"`]+|\bwww\.[^\s<>"`]+|\b([\w./-]+\.\w{1,10})(:\d+)?(?::\d+)?\b"""
    )

    /**
     * 为 JTextPane 添加文件路径点击跳转支持。
     * 适用于 Markdown 渲染的 HTML 内容。
     */
    fun attach(textPane: JTextPane, project: Project, basePath: String? = null) {
        val resolvedBase = basePath ?: project.basePath ?: return
        addListeners(textPane, project, resolvedBase)
    }

    /**
     * 为 JTextArea 添加文件路径点击跳转支持。
     * 适用于工具结果的纯文本内容。
     */
    fun attach(textArea: JTextArea, project: Project, basePath: String? = null) {
        val resolvedBase = basePath ?: project.basePath ?: return
        addListeners(textArea, project, resolvedBase)
    }

    private fun addListeners(component: JTextComponent, project: Project, basePath: String) {
        var lastCursor = component.cursor

        // 鼠标移动：检测是否悬停在文件路径上
        component.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val link = findLinkAt(component, e.point)
                if (link != null) {
                    if (component.cursor != Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)) {
                        lastCursor = component.cursor
                        component.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    }
                } else {
                    component.cursor = lastCursor
                }
            }
        })

        // 鼠标点击：跳转到文件或打开 URL
        component.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.button != MouseEvent.BUTTON1) return
                val link = findLinkAt(component, e.point) ?: return
                when {
                    link.startsWith("http://", ignoreCase = true) || link.startsWith("https://", ignoreCase = true) || link.startsWith("ftp://", ignoreCase = true) -> {
                        com.intellij.ide.BrowserUtil.browse(link)
                    }
                    link.startsWith("www.", ignoreCase = true) -> {
                        com.intellij.ide.BrowserUtil.browse("https://$link")
                    }
                    else -> navigate(link, basePath, project)
                }
            }
        })
    }

    /** 在文本组件的指定坐标位置查找链接，未找到返回 null */
    private fun findLinkAt(component: JTextComponent, point: Point): String? {
        val offset = try {
            component.viewToModel2D(point)
        } catch (_: Exception) { return null }
        if (offset < 0) return null

        val text = try {
            component.document.getText(0, component.document.length)
        } catch (_: Exception) { return null }

        return LINK_REGEX.findAll(text).find { offset in it.range }?.value
    }

    /** 解析文件路径和行号，在 IDE 中打开 */
    fun navigate(raw: String, basePath: String, project: Project) {
        val (filePath, line) = parsePathAndLine(raw)

        val file = when {
            File(filePath).isAbsolute -> File(filePath)
            else -> File(basePath, filePath)
        }

        if (!file.exists()) return

        val virtualFile = LocalFileSystem.getInstance().findFileByIoFile(file) ?: return
        // line=1 表示无行号（默认），line>1 表示有行号
        val line0 = if (line > 0) line - 1 else 0
        val descriptor = OpenFileDescriptor(project, virtualFile, line0)
        FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
    }

    /** 解析 "path/file.ext:42:10" → ("path/file.ext", 42) */
    private fun parsePathAndLine(raw: String): Pair<String, Int> {
        val parts = raw.split(":")
        return if (parts.size >= 2 && parts[1].toIntOrNull() != null) {
            parts[0] to (parts[1].toIntOrNull() ?: 0)
        } else {
            raw to 0
        }
    }
}
