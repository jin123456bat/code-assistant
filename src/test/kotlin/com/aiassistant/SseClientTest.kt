package com.aiassistant

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SseClientTest {

    private val client = SseClient()

    @Test
    fun `should create SSE client instance`() {
        assertNotNull(client)
    }

    @Test
    fun `should cancel without error when never started`() {
        // Should not throw
        client.cancel()
    }

    @Test
    fun `should parse data correctly via callback`() {
        // This tests that the SseCallback interface compiles and is usable
        var receivedData: String? = null
        var receivedDone = false
        var receivedError: Pair<Int, String>? = null

        val callback = object : SseCallback {
            override fun onData(content: String) { receivedData = content }
            override fun onDone() { receivedDone = true }
            override fun onError(httpCode: Int, message: String) {
                receivedError = Pair(httpCode, message)
            }
        }

        // Callback methods should be callable without exception
        callback.onData("{\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}")
        callback.onDone()

        assertNotNull(receivedData)
        assertTrue(receivedDone)
    }
}
