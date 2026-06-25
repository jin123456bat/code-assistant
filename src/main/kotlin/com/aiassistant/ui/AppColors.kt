package com.aiassistant.ui

import com.intellij.ui.JBColor
import java.awt.Color

/**
 * 统一颜色令牌 — 所有 UI 颜色集中管理，保证亮/暗主题一致。
 *
 * 命名规则：
 * - xxxLight/xxxDark 仅在定义 JBColor 时使用
 * - 其他地方引用 AppColors.xxx，不要直接 new JBColor()
 */
object AppColors {
    // ---- 主题色 ----
    val primary = JBColor(0x3B82F6, 0x60A5FA)
    val primaryHover = JBColor(0x2563EB, 0x3B82F6)
    val primaryLight = JBColor(0xEFF6FF, 0x1C2E4A)
    val success = JBColor(0x22C55E, 0x4ADE80)
    val error = JBColor(0xEF4444, 0xF87171)
    val warning = JBColor(0xF59E0B, 0xFBBF24)

    // ---- 文字 ----
    val textSecondary = JBColor(0x6B7280, 0x9CA3AF)
    val textTertiary = JBColor(0x94A3B8, 0x6B7280)

    // ---- 边框/分割线 ----
    val border = JBColor(0xE5E7EB, 0x374151)
    val borderTransparent = JBColor(0x00000000, 0x00000000)

    // ---- 表面 ----
    val cardBg = JBColor(0xFFFFFF, 0x32363E)
    val pageBg = JBColor(0xF9FAFB, 0x2B2D30)
    val headerBg = JBColor(0xF9FAFB, 0x25282D)
    val hoverBg = JBColor(0xF3F4F6, 0x3A404A)

    // ---- 聊天气泡 ----
    val userBubbleBg = JBColor(0xE8F0FE, 0x1E3250)
    val codeBg = JBColor(0xF6F8FA, 0x1A1D23)
    val codeBorder = JBColor(0xE1E4E8, 0x30363D)
    val inlineCodeBg = JBColor(0xF6F8FA, 0x21252B)
    val inlineCodeBorder = JBColor(0xE1E4E8, 0x374151)
    val quoteBg = JBColor(0xF9FAFB, 0x24272D)
    val quoteBorder = JBColor(0xD1D5DB, 0x4B5563)
    val errorBg = JBColor(0xFEE2E2, 0x5C1A1A)

    // ---- 思考过程 ----
    val thinkingBg = JBColor(0xFFF8F0, 0x2A2418)
    val thinkingBorder = JBColor(0xFDE8D0, 0x4A3820)
    val thinkingTimeFg = JBColor(0xB45309, 0xF59E0B)
    val thinkingBodyFg = JBColor(0x92400E, 0xFBBF24)

    // ---- 工具调用 ----
    val toolPlaceholderBg = JBColor(0xF3F4F6, 0x282D35)

    // ---- 标签芯片 ----
    val tagBg = JBColor(0xEFF6FF, 0x1C2E4A)
    val tagBorder = JBColor(0xBFDBFE, 0x2563EB)

    // ---- 状态指示灯（非 JBColor，仅用于 HTML 颜色转换） ----
    val statusGreen = success
    val statusAmber = warning
    val statusRed = error
    val statusGray = textSecondary
}

/** 将 Color 转换为 HTML 可用的 #RRGGBB 字符串（取亮色值作为默认） */
fun Color.toHtmlColor(): String {
    // JBColor.getRGB() 已根据当前主题返回正确值
    val rgb = this.rgb and 0xFFFFFF
    return "#${rgb.toString(16).padStart(6, '0')}"
}
