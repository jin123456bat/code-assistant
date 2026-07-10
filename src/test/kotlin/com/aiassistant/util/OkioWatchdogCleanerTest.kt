package com.aiassistant.util

import okio.AsyncTimeout
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OkioWatchdogCleanerTest {
    @Test
    fun `shutdown terminates idle okio watchdog thread`() {
        val timeout = AsyncTimeout().timeout(1, TimeUnit.SECONDS) as AsyncTimeout
        timeout.enter()
        timeout.exit()

        assertTrue(okioWatchdogThreads().any { it.isAlive })

        OkioWatchdogCleaner.shutdownForPluginUnload()

        val stopped = waitUntil(2_000) {
            okioWatchdogThreads().none { it.isAlive }
        }
        assertTrue(stopped)
    }

    @Test
    fun `shutdown is safe when watchdog was never started`() {
        OkioWatchdogCleaner.shutdownForPluginUnload()

        assertFalse(okioWatchdogThreads().any { it.isAlive })
    }

    private fun okioWatchdogThreads(): List<Thread> =
        Thread.getAllStackTraces().keys.filter { it.name == "Okio Watchdog" }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(25)
        }
        return condition()
    }
}
