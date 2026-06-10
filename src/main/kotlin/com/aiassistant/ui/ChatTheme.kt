package com.aiassistant.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Font

/**
 * 聊天 UI 设计 token 单一来源（A 中性方向）。
 * 颜色用 JBColor(lightHex, darkHex)，跟随 IDE 主题。
 */
object ChatTheme {
    // ---- 基础 ----
    val winBg = JBColor(0xFFFFFF, 0x2B2D30)
    val divider = JBColor(0xEBECF0, 0x393B40)
    val textPrimary = JBColor(0x27282B, 0xDFE1E5)
    val textSecondary = JBColor(0x6C707E, 0x9DA0A8)
    val textMuted = JBColor(0x989AA2, 0x7A7D85)

    // ---- 气泡 ----
    val userBg = JBColor(0x3574F0, 0x2F5E8F)
    val userFg = JBColor(0xFFFFFF, 0xFFFFFF)
    val aiBg = JBColor(0xF2F3F5, 0x313338)
    val aiBorder = JBColor(0xE3E5E9, 0x3C3F44)

    // ---- 工具（强调蓝，统一语义色）----
    val toolBar = JBColor(0x3574F0, 0x5C8FD6)
    val toolFg = JBColor(0x2F6FE0, 0x84ACDF)
    val toolBg = JBColor(Color(53, 116, 240, 20), Color(92, 143, 214, 33))

    // ---- 代码 / 输入 ----
    val codeBg = JBColor(0xF7F8FA, 0x1E1F22)
    val codeBorder = JBColor(0xEBECF0, 0x393B40)
    val inputBg = JBColor(0xFFFFFF, 0x1E1F22)
    val inputBorder = JBColor(0xC9CCD6, 0x4E5157)
    val inputFocus = JBColor(0x4A90D9, 0x4A90D9)

    // ---- diff / 状态 ----
    val diffDelFg = JBColor(0xC0392B, 0xD97D7D)
    val diffAddFg = JBColor(0x2E7D32, 0x7BBD86)
    val danger = JBColor(0xD98A3D, 0xD98A3D)
    val error = JBColor(0xB5503E, 0xE08A72)
    val doneCheck = JBColor(0x5AA86A, 0x5AA86A)

    // ---- 间距（逻辑 px，交给 JBUI.Borders/scale 再缩放）----
    const val GAP_BUBBLE = 10
    const val GAP_ROLE = 16
    const val PAD_BUBBLE_V = 8
    const val PAD_BUBBLE_H = 12
    const val RADIUS = 14
    const val RADIUS_TIGHT = 5

    // ---- 字体 ----
    val bodyFont: Font get() = JBFont.regular()
    val metaFont: Font get() = JBFont.small()
    val codeFont: Font get() = Font(Font.MONOSPACED, Font.PLAIN, JBFont.regular().size)
}
