package com.aiassistant.ui

import com.aiassistant.review.Finding
import com.aiassistant.review.Severity
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement
import java.awt.Color
import javax.swing.Icon

/**
 * 编辑器 gutter 审查标记——在行号旁显示彩色圆点图标。
 * 通过 LineMarkerProvider EP 注册到 plugin.xml。
 */
class ReviewAnnotationGutter : LineMarkerProvider {

    companion object {
        /** per-project 审查结果缓存 (Project basePath → findings) */
        private val projectFindings = java.util.concurrent.ConcurrentHashMap<String, List<Finding>>()

        /** 写入 project 的审查结果 */
        fun setFindings(projectBasePath: String?, findings: List<Finding>) {
            val key = projectBasePath ?: return
            projectFindings[key] = findings
        }

        /** 读取 project 的审查结果 */
        fun getFindings(projectBasePath: String?): List<Finding> {
            val key = projectBasePath ?: return emptyList()
            return projectFindings[key] ?: emptyList()
        }

        /** 清除 project 的审查标记 */
        fun clear(projectBasePath: String?) {
            val key = projectBasePath ?: return
            projectFindings.remove(key)
        }

        /** 兼容旧接口 */
        @Deprecated("用 setFindings/getFindings 替代")
        var currentFindings: List<Finding>
            get() = getFindings("")
            set(value) = setFindings("", value)
    }

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        return null  // 按文件粒度处理更高效
    }

    override fun collectSlowLineMarkers(
        elements: MutableList<out PsiElement>,
        result: MutableCollection<in LineMarkerInfo<*>>
    ) {
        val psiFile = elements.firstOrNull()?.containingFile ?: return
        val projectBasePath = psiFile.project.basePath ?: return

        val findings = getFindings(projectBasePath)
        if (findings.isEmpty()) return

        // 提取匹配当前文件的 findings
        val filePath = psiFile.virtualFile.path
        val fileFindings = findings.filter { finding ->
            val fullPath = "$projectBasePath/${finding.file}"
            filePath == fullPath || filePath.endsWith(finding.file)
        }

        if (fileFindings.isEmpty()) return

        val document = psiFile.viewProvider.document ?: return

        for (finding in fileFindings) {
            if (finding.line < 1 || finding.line > document.lineCount) continue

            val lineStartOffset = document.getLineStartOffset(finding.line - 1)
            val element = psiFile.findElementAt(lineStartOffset) ?: continue

            val icon = createIcon(finding.severity)
            val tooltip = buildString {
                appendLine(finding.title)
                appendLine("${finding.severity} | ${finding.category} | 置信度: ${finding.confidence}/10")
                if (finding.description.isNotBlank()) appendLine(finding.description)
            }

            result.add(
                LineMarkerInfo(
                    element,
                    element.textRange,
                    icon,
                    { tooltip },
                    null,
                    GutterIconRenderer.Alignment.RIGHT,
                    { finding.title }
                )
            )
        }
    }

    private fun createIcon(severity: Severity): Icon {
        val color = when (severity) {
            Severity.CRITICAL -> Color(220, 50, 50)
            Severity.WARNING -> Color(220, 170, 30)
            Severity.INFO -> Color(60, 120, 210)
        }
        return ColorDotIcon(color, 10)
    }

    /** 简单的彩色圆点图标 */
    private class ColorDotIcon(private val color: Color, private val size: Int) : Icon {
        override fun paintIcon(c: java.awt.Component?, g: java.awt.Graphics, x: Int, y: Int) {
            val g2 = g.create() as java.awt.Graphics2D
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = color
            g2.fillOval(x + 2, y + 4, size, size)
            g2.dispose()
        }

        override fun getIconWidth() = size + 4

        override fun getIconHeight() = size + 4
    }

}
