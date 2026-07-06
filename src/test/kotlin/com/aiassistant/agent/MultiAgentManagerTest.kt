package com.aiassistant.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MultiAgentManagerTest {

    @Test
    fun `0 concurrency setting creates unbounded semaphore`() {
        // 验证 0 并发 = 不限（等价于 Int.MAX_VALUE permits）
        assertTrue(MultiAgentManager.semaphorePermitsForConcurrency(0) > 1000)
    }
}
