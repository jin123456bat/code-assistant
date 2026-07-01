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
    val primaryHover = JBColor(0x2563EB, 0x93BBFD)
    val primaryPressed = JBColor(0x1D4ED8, 0xBFDBFE)
    val primaryLight = JBColor(0xEFF6FF, 0x1E3A5F)
    val success = JBColor(0x22C55E, 0x4ADE80)
    val error = JBColor(0xEF4444, 0xF87171)
    val warning = JBColor(0xF59E0B, 0xFBBF24)

    // ---- 中性色阶（文档 §一 定义） ----
    val gray900 = JBColor(0x111827, 0xE5E7EB)   // 标题、正文
    val gray700 = JBColor(0x374151, 0xD1D5DB)   // 次要文字
    val gray500 = JBColor(0x6B7280, 0x9CA3AF)   // 辅助文字、placeholder
    val gray400 = JBColor(0x9CA3AF, 0x6B7280)   // 禁用文字、时间戳、系统消息
    val gray300 = JBColor(0xD1D5DB, 0x4B5563)   // 边框
    val gray200 = JBColor(0xE5E7EB, 0x374151)   // 分割线
    val gray100 = JBColor(0xF3F4F6, 0x1F2937)   // 卡片背景
    val gray50 = JBColor(0xF9FAFB, 0x111827)    // 页面背景

    // ---- 文字（语义化别名，关联到中性色阶） ----
    val textSecondary = gray500
    val textTertiary = gray400

    // ---- 边框/分割线（语义化别名） ----
    val border = gray200
    val borderTransparent = JBColor(0x00000000, 0x00000000)

    // ---- 表面（语义化别名） ----
    val cardBg = JBColor(0xFFFFFF, 0x1F2937)
    val pageBg = gray50
    val headerBg = JBColor(0xF9FAFB, 0x25282D)
    val hoverBg = JBColor(0xF3F4F6, 0x3A404A)

    // ---- 聊天气泡 ----
    val userBubbleBg = JBColor(0xE8F0FE, 0x1E3A5F)
    val codeBg = JBColor(0xF6F8FA, 0x1E1E2E)
    val codeBorder = JBColor(0xE1E4E8, 0x2D2D3F)
    val inlineCodeBg = JBColor(0xF6F8FA, 0x21252B)
    val inlineCodeBorder = JBColor(0xE1E4E8, 0x374151)
    val quoteBg = JBColor(0xF9FAFB, 0x24272D)
    val quoteBorder = JBColor(0xD1D5DB, 0x4B5563)
    val errorBg = JBColor(0xFEE2E2, 0x7F1D1D)

    // ---- 思考过程 ----
    val thinkingBg = JBColor(0xFFF8F0, 0x422006)
    val thinkingBorder = JBColor(0xFDE8D0, 0x4A3820)
    val thinkingTimeFg = JBColor(0xB45309, 0xF59E0B)
    val thinkingBodyFg = JBColor(0x92400E, 0xFBBF24)
    val thinkingAccent = JBColor(0xFDE68A, 0x78350F)   // amber-200 强调色

    // ---- 工具调用 ----
    val toolPlaceholderBg = JBColor(0xF3F4F6, 0x282D35)

    // ---- 标签芯片 ----
    val tagBg = JBColor(0xEFF6FF, 0x1E3A5F)
    val tagBorder = JBColor(0xBFDBFE, 0x2563EB)

    // ---- 多 Agent 调度卡片 ----
    // 文档要求背景: #F0F7FF（亮）/#0F1D2F（暗），border=#BFDBFE
    val multiAgentBg = JBColor(0xF0F7FF, 0x0F1D2F)
    val multiAgentBorder = JBColor(0xBFDBFE, 0x1C3A5F)

    // ---- Badge 角标 ----
    val badgeBg = JBColor(0xEF4444, 0xF87171)
    val badgeFg = JBColor(0xFFFFFF, 0xFFFFFF)

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
