package com.aiassistant.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBFont
import java.awt.Color
import java.awt.Font

/**
 * 设计 token 单一来源 — 所有颜色、间距、圆角、字号、宽度约束集中管理。
 *
 * 颜色用 JBColor(lightHex, darkHex)，跟随 IDE 主题自动切换。
 * 间距/尺寸为逻辑 px，由 JBUI.scale() 缩放适配 HiDPI。
 *
 * 规则：UI 代码中禁止出现硬编码的颜色 Hex、magic number 间距/尺寸，
 *       一律引用此文件的 token。
 */
object ChatTheme {

    // ═══════════════════════════════════════════════════════════════
    // 基础色 — 窗口/面板/文字
    // ═══════════════════════════════════════════════════════════════

    /** 窗口/面板背景 — ChatToolWindow、PlanBar 步骤列表 */
    val winBg = JBColor(0xFFFFFF, 0x2B2D30)

    /** 分割线 — conversationHeader 底边、PlanBar 步骤列表边框、Markdown 表格线 */
    val divider = JBColor(0xEBECF0, 0x393B40)

    /** 正文文字 — 气泡内容、工具行/审批选项行默认文字 */
    val textPrimary = JBColor(0x27282B, 0xDFE1E5)

    /** 次要文字 — 标签、角色名、工具行折叠摘要、审批选项非默认文字 */
    val textSecondary = JBColor(0x6C707E, 0x9DA0A8)

    /** 辅助/弱化文字 — 折叠文字、时间戳、args 预览、Markdown 引用块 */
    val textMuted = JBColor(0x989AA2, 0x7A7D85)


    // ═══════════════════════════════════════════════════════════════
    // 气泡 — 用户/AI 对话气泡
    // ═══════════════════════════════════════════════════════════════

    /** 用户气泡背景（蓝色实心）— ChatBubble.paintComponent */
    val userBg = JBColor(0x3574F0, 0x2F5E8F)

    /** 用户气泡文字（白色）— ChatBubble */
    val userFg = JBColor(0xFFFFFF, 0xFFFFFF)

    /** AI 回复气泡背景（浅灰）— ChatBubble.paintComponent */
    val aiBg = JBColor(0xF5F5F5, 0x37373C)

    /** AI 回复气泡边框 — ChatBubble.paintComponent */
    val aiBorder = JBColor(0xDCDCDC, 0x41414B)


    // ═══════════════════════════════════════════════════════════════
    // 工具行 — 统一蓝色强调
    // ═══════════════════════════════════════════════════════════════

    /** 工具行左栏 3px 竖线 + 盲文 spinner — leftBarPanel、BrailleSpinnerLabel */
    val toolBar = JBColor(0x3574F0, 0x5C8FD6)

    /** 工具名称/标题文字 + 运行中状态标签 — toolNameLabel、statusLabel */
    val toolFg = JBColor(0x2F6FE0, 0x84ACDF)

    /** 工具行淡蓝背景 + 审批选项 hover 高亮 — leftBarPanel、buildApprovalOptions */
    val toolBg = JBColor(Color(53, 116, 240, 20), Color(92, 143, 214, 33))


    // ═══════════════════════════════════════════════════════════════
    // 代码/输入区
    // ═══════════════════════════════════════════════════════════════

    /** 代码块/工具结果背景 — MarkdownRenderer.buildCodeBlock、ToolRowFactory 结果区 */
    val codeBg = JBColor(0xF7F8FA, 0x1E1F22)

    /** 代码块边框 — MarkdownRenderer 代码块、ToolRowFactory 结果区分隔线 */
    val codeBorder = JBColor(0xEBECF0, 0x393B40)

    /** 代码块头部栏背景（语言标签+复制按钮所在行）— MarkdownRenderer.buildCodeBlock */
    val codeHeaderBg = JBColor(0xF0F0F0, 0x32323A)

    /** 代码块语言标签色（如 "JAVA"）— MarkdownRenderer.buildCodeBlock */
    val codeLangFg = JBColor(0x888888, 0x999999)

    /** 代码块编辑器域背景 — MarkdownRenderer.buildCodeBlock EditorTextField */
    val codeEditorBg = JBColor(0xFAFAFA, 0x2B2B2B)

    /** 输入文本区背景 — ChatToolWindow inputPanel */
    val inputBg = JBColor(0xFFFFFF, 0x1E1F22)

    /** 输入面板边框（默认）— ChatToolWindow inputPanel */
    val inputBorder = JBColor(0xC9CCD6, 0x4E5157)

    /** 输入面板边框（聚焦）— ChatToolWindow inputPanel */
    val inputFocus = JBColor(0x4A90D9, 0x4A90D9)


    // ═══════════════════════════════════════════════════════════════
    // Markdown 内联样式（HTMLEditorKit CSS 中使用）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 内联代码背景（<code> / <pre> 块）— MarkdownRenderer.buildStyledHtml。
     * 由 Swing 背景亮度动态选择，此处为静态 fallback 值。
     */
    val inlineCodeBgLight = 0xF0F0F0
    val inlineCodeBgDark = 0x3C3C3C

    /** Markdown 链接色 — MarkdownRenderer.buildStyledHtml CSS */
    val markdownLinkFg = 0x2674B4


    // ═══════════════════════════════════════════════════════════════
    // 状态/语义色
    // ═══════════════════════════════════════════════════════════════

    /** diff 删除行 — SimpleDiff LCS 算法 */
    val diffDelFg = JBColor(0xC0392B, 0xD97D7D)

    /** diff 添加行 — SimpleDiff LCS 算法 */
    val diffAddFg = JBColor(0x2E7D32, 0x7BBD86)

    /** 危险操作边框（execute_command 等）— permission/approval card 边框 */
    val danger = JBColor(0xD98A3D, 0xD98A3D)

    /** 错误文字 + 错误卡左栏 — errorCardRow 标题、LeftBarBorder */
    val error = JBColor(0xB5503E, 0xE08A72)

    /** 错误卡淡红背景 — errorCardRow 整体背景 */
    val errorCardBg = JBColor(Color(181, 80, 62, 15), Color(224, 138, 114, 25))

    /** 已完成/已确认绿勾 — PlanBar DONE 步骤、审批选项 ✓、SelectionCard 已选 */
    val doneCheck = JBColor(0x5AA86A, 0x5AA86A)

    /**
     * 审批拒绝色 — buildApprovalOptions 点击「拒绝」后的文字色。
     * Light: 暖浅红（区别于 error 边框的深红 #B5503E）
     * Dark:  与 error 边框 dark 一致
     */
    val rejectedFg = JBColor(0xC0392B, 0xE08A72)


    // ═══════════════════════════════════════════════════════════════
    // 间距（逻辑 px，JBUI.scale() 自动缩放适配 HiDPI）
    // ═══════════════════════════════════════════════════════════════

    /** 气泡之间垂直间距 — BubbleFactory、ChatToolWindow.rebuildConversation */
    const val GAP_BUBBLE = 10

    /** 不同角色切换额外留白 — BubbleFactory */
    const val GAP_ROLE = 16

    /** 气泡内上下 padding — ChatBubble HTML body */
    const val PAD_BUBBLE_V = 8

    /** 气泡内左右 padding — ChatBubble HTML body */
    const val PAD_BUBBLE_H = 12


    // ═══════════════════════════════════════════════════════════════
    // 圆角
    // ═══════════════════════════════════════════════════════════════

    /** 气泡/卡片四角统一圆角 — ChatBubble、SelectionCard、PlanBar 步骤列表 */
    const val RADIUS = 14

    /** 微信式小尖角 — ChatBubble 特殊场景 */
    const val RADIUS_TIGHT = 5

    /**
     * 内部元素圆角（tool 行 hover 高亮、审批选项 hover、SelectionCard 选项 hover）。
     * 比 RADIUS 更小，用于内层 UI 元素。
     */
    const val RADIUS_INNER = 8

    /** PlanBar 进度条圆角 */
    const val RADIUS_PROGRESS = 4


    // ═══════════════════════════════════════════════════════════════
    // 图标/控件尺寸
    // ═══════════════════════════════════════════════════════════════

    /** 箭头/chevron 图标最小宽度 — ToolRowFactory.arrowLabel、SelectionCard chevron 标签 */
    const val ARROW_WIDTH = 14

    /** 选择卡复选框宽度 — SelectionCard 多选模式 */
    const val CHECK_WIDTH = 16

    /** 盲文 spinner 标签最小宽度（防抖动）— BrailleSpinnerLabel */
    const val SPINNER_MIN_W = 14


    // ═══════════════════════════════════════════════════════════════
    // PlanBar 尺寸约束
    // ═══════════════════════════════════════════════════════════════

    /** PlanBar 弹出步骤列表最大高度 — PlanBar.buildStepList */
    const val PLAN_STEP_MAX_H = 168

    /** PlanBar 单步最大高度 — PlanBar.buildStepList 单行布局 */
    const val PLAN_STEP_ROW_H = 24

    /** PlanBar 迷你进度条宽度 — PlanBar.buildSummary */
    const val PLAN_PROGRESS_W = 60

    /** PlanBar 迷你进度条高度 — PlanBar.buildSummary */
    const val PLAN_PROGRESS_H = 12


    // ═══════════════════════════════════════════════════════════════
    // 气泡宽度约束
    // ═══════════════════════════════════════════════════════════════

    /** 气泡内容绝对宽度上限（逻辑 px）— ChatBubble.getPreferredSize */
    const val ABS_CAP = 560

    /** 用户气泡最大宽度占可用宽度比例 — ChatBubble.getPreferredSize */
    const val USER_FRACTION = 0.80

    /** AI 气泡最大宽度占可用宽度比例 — ChatBubble.getPreferredSize */
    const val AI_FRACTION = 1.0

    /** BubbleFactory 中气泡宽度扣除量 — BubbleFactory.createAIBubble */
    const val BUBBLE_WIDTH_DEDUCT = 20

    /** ToolRowFactory args 预览扣除量 — ToolRowFactory argsPreviewLabel */
    const val TOOL_PREVIEW_DEDUCT = 24


    // ═══════════════════════════════════════════════════════════════
    // 字号（Font）
    // ═══════════════════════════════════════════════════════════════

    /** 正文字号 — ChatBubble HTML、对话内容 */
    val bodyFont: Font get() = JBFont.regular()

    /** 标签/工具行/元信息字号（~11pt）— ToolRowFactory、SelectionCard、PlanBar 标签 */
    val metaFont: Font get() = JBFont.small()

    /** 代码等宽字体 — MarkdownRenderer、ToolRowFactory 结果区 */
    val codeFont: Font get() = Font(Font.MONOSPACED, Font.PLAIN, JBFont.regular().size)

    /** Markdown 代码块语言标签/复制按钮字号（10pt）— MarkdownRenderer.buildCodeBlock */
    const val CODE_LANG_FONT_SIZE = 10

    /** HTML CSS 中 heading 字号偏移 — MarkdownRenderer.buildStyledHtml h1/h2/h3 */
    const val HEADING_FONT_OFFSET_H1 = 3
    const val HEADING_FONT_OFFSET_H2 = 2
    const val HEADING_FONT_OFFSET_H3 = 1

    /** 工具行字号相对于编辑器字号的偏移 — ToolRowFactory.toolFont/thinkFont */
    const val TOOL_FONT_OFFSET = 1

    /** PlanBar 小字相对于 metaFont 的偏移 — PlanBar 描述步骤文字 */
    const val META_FONT_OFFSET = 2


    // ═══════════════════════════════════════════════════════════════
    // 文本截断/预览长度
    // ═══════════════════════════════════════════════════════════════

    /** 工具 args 参数预览最大字符数 — ToolRowFactory.argsPreviewLabel、toolCallRow */
    const val ARGS_PREVIEW_MAX_CHARS = 120

    /** 工具 result 内容展示最大字符数 — ToolRowFactory.toolResultRow */
    const val RESULT_MAX_CHARS = 2000

    /** 思考行折叠摘要最大字符数（双行截断，每行 50）— ToolRowFactory.thinkingRow */
    const val THINKING_PREVIEW_MAX_CHARS = 100
}
