package com.aiassistant.ui

/** 气泡尺寸纯计算逻辑（无 Swing 依赖，便于单测）。 */
object BubbleMetrics {
    /** viewport 占比上限 */
    const val RATIO = 0.78

    /**
     * 可用宽度上限（调用方需再扣除容器/行边距）。
     * 注意：返回值是 viewport 占比与绝对上限中的较小值，
     * 调用方（BubbleFactory.fitWidth）会在此基础上再减去行/容器边距，
     * 因此本函数返回的并非最终渲染气泡宽度。
     * @param viewportWidth 滚动视口宽度；≤10 视为未就绪。
     * @param absCap 绝对上限（调用方传 JBUI.scale(560)）。
     */
    fun maxBubbleWidth(viewportWidth: Int, absCap: Int): Int {
        if (viewportWidth <= 10) return absCap
        return minOf(absCap, (viewportWidth * RATIO).toInt())
    }

    /**
     * 按角色占比计算气泡最大宽度上限。
     * @param viewportWidth 滚动视口宽度；≤10 视为未就绪，退回 absCap。
     * @param absCap 绝对上限。
     * @param fraction viewport 占比（用户 0.8、AI 1.0）。
     */
    fun maxBubbleWidth(viewportWidth: Int, absCap: Int, fraction: Double): Int {
        if (viewportWidth <= 10) return absCap
        return minOf(absCap, (viewportWidth * fraction).toInt())
    }
}
