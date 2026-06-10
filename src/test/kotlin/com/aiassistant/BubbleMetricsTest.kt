package com.aiassistant

import com.aiassistant.ui.BubbleMetrics
import kotlin.test.Test
import kotlin.test.assertEquals

class BubbleMetricsTest {
    @Test fun viewportNotReady_returnsAbsCap() {
        assertEquals(560, BubbleMetrics.maxBubbleWidth(viewportWidth = 0, absCap = 560))
        assertEquals(560, BubbleMetrics.maxBubbleWidth(viewportWidth = 10, absCap = 560))
    }

    @Test fun wideViewport_cappedByAbs() {
        // 1000*0.78 = 780 > 560 → 取 560
        assertEquals(560, BubbleMetrics.maxBubbleWidth(viewportWidth = 1000, absCap = 560))
    }

    @Test fun narrowViewport_hugsViewportRatio() {
        // 400*0.78 = 312 < 560 → 取 312
        assertEquals(312, BubbleMetrics.maxBubbleWidth(viewportWidth = 400, absCap = 560))
    }
}
