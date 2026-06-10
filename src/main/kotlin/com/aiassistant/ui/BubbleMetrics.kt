package com.aiassistant.ui

/** 气泡尺寸纯计算逻辑（无 Swing 依赖，便于单测）。 */
object BubbleMetrics {
    /** viewport 占比上限 */
    const val RATIO = 0.78

    /**
     * 气泡最大宽度（上限，非固定值）。
     * @param viewportWidth 滚动视口宽度；≤10 视为未就绪。
     * @param absCap 绝对上限（调用方传 JBUI.scale(560)）。
     */
    fun maxBubbleWidth(viewportWidth: Int, absCap: Int): Int {
        if (viewportWidth <= 10) return absCap
        return minOf(absCap, (viewportWidth * RATIO).toInt())
    }
}
