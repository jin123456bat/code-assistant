package com.aiassistant.agent

import kotlin.test.Test
import kotlin.test.assertEquals

class MultiAgentManagerTest {

    @Test
    fun `0 concurrency setting creates unbounded semaphore`() {
        assertEquals(Int.MAX_VALUE, MultiAgentManager.semaphorePermitsForConcurrency(0))
    }
}
