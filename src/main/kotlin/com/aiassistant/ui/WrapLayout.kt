package com.aiassistant.ui

import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Insets

/**
 * 可换行的 FlowLayout 变体。
 *
 * 原生 FlowLayout.preferredLayoutSize() 永远按单行计算高度，即使实际容器宽度不够、
 * layoutContainer 已经将组件换行排列。这导致：
 * - 气泡下方 chips 被裁切（ChatBubble 测量高度时拿到单行高）
 * - 输入区 chips 面板高度不足（超出部分被遮挡）
 *
 * WrapLayout 的 preferredLayoutSize/minimumLayoutSize 基于容器当前宽度
 * 模拟换行计算真实高度，消除高度不足导致的裁切问题。
 */
class WrapLayout(align: Int = FlowLayout.LEFT, hgap: Int = 4, vgap: Int = 2) : FlowLayout(align, hgap, vgap) {

    override fun preferredLayoutSize(target: Container): Dimension {
        return wrapSize(target) { it.preferredSize }
    }

    override fun minimumLayoutSize(target: Container): Dimension {
        return wrapSize(target) { it.minimumSize }
    }

    private fun wrapSize(target: Container, sizeFn: (Component) -> Dimension): Dimension {
        val targetWidth = target.width
        // 容器宽度未就绪时退化到父容器宽度，若仍无效则回退到标准 FlowLayout 单行尺寸
        val effectiveWidth = if (targetWidth > 0) targetWidth
        else target.parent?.width?.takeIf { it > 0 } ?: return super.preferredLayoutSize(target)

        val insets: Insets = target.insets
        val innerWidth = effectiveWidth - insets.left - insets.right
        if (innerWidth <= 0) return super.preferredLayoutSize(target)

        val n = target.componentCount
        if (n == 0) return Dimension(insets.left + insets.right, insets.top + insets.bottom)

        var x = 0
        var rowHeight = 0
        var totalHeight = insets.top
        var maxRowWidth = 0

        for (i in 0 until n) {
            val comp = target.getComponent(i)
            if (!comp.isVisible) continue
            val d = sizeFn(comp)
            val compW = d.width + if (x > 0) hgap else 0

            if (x > 0 && x + compW > innerWidth) {
                // 换行
                totalHeight += rowHeight + vgap
                maxRowWidth = maxOf(maxRowWidth, x)
                x = d.width
                rowHeight = d.height
            } else {
                x += compW
                rowHeight = maxOf(rowHeight, d.height)
            }
        }
        totalHeight += rowHeight + insets.bottom
        maxRowWidth = maxOf(maxRowWidth, x)

        return Dimension(maxRowWidth + insets.left + insets.right, totalHeight)
    }
}
