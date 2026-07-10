package com.aiassistant.util

/**
 * Okio keeps a package-level daemon thread for async timeouts. During dynamic plugin unload that
 * thread can keep this plugin's classloader alive for up to Okio's 60s idle timeout.
 */
object OkioWatchdogCleaner {
    fun shutdownForPluginUnload() {
        runCatching {
            val watchdogThreads = Thread.getAllStackTraces().keys
                .filter { it.name == "Okio Watchdog" }
            if (watchdogThreads.isEmpty()) return

            val asyncTimeout = Class.forName("okio.AsyncTimeout")
            val lock = asyncTimeout.getDeclaredMethod("access\$getLock\$cp")
                .invoke(null) as java.util.concurrent.locks.ReentrantLock
            val condition = asyncTimeout.getDeclaredMethod("access\$getCondition\$cp")
                .invoke(null) as java.util.concurrent.locks.Condition
            val setHead = asyncTimeout.getDeclaredMethod("access\$setHead\$cp", asyncTimeout)

            lock.lock()
            try {
                setHead.invoke(null, null)
                condition.signalAll()
            } finally {
                lock.unlock()
            }

            watchdogThreads.forEach { it.interrupt() }
        }
    }
}
