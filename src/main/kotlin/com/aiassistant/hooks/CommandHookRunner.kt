package com.aiassistant.hooks

import com.google.gson.Gson
import java.util.concurrent.TimeUnit

object CommandHookRunner {
    fun run(command: String, stdinJson: String, timeoutSec: Int): HookDecision? {
        return try {
            val shell = if (System.getProperty("os.name").lowercase().contains("win"))
                arrayOf("cmd.exe", "/c", command) else arrayOf("/bin/bash", "-c", command)
            val process = ProcessBuilder(*shell).redirectErrorStream(true).start()
            process.outputStream.bufferedWriter().use { it.write(stdinJson); it.newLine() }
            val finished = process.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)
            if (!finished) { process.destroyForcibly(); return null }
            if (process.exitValue() != 0) {
                com.aiassistant.AppLogger.warn("Hook 命令非零退出: ${process.exitValue()}")
                return null  // 非零退出码视为失败
            }
            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            val trimmed = stdout.trim()
            if (trimmed.isEmpty()) null
            else try { Gson().fromJson(trimmed, HookDecision::class.java) } catch (_: Exception) { HookDecision(content = trimmed) }
        } catch (_: Exception) { null }
    }
}
