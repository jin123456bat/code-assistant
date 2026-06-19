package com.aiassistant.hooks

import com.google.gson.Gson
import java.net.HttpURLConnection
import java.net.URI

object HttpHookRunner {
    fun run(url: String, bodyJson: String, timeoutSec: Int): HookDecision? {
        return try {
            val conn = URI.create(url).toURL().openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = timeoutSec * 1000
            conn.readTimeout = timeoutSec * 1000
            conn.outputStream.bufferedWriter().use { it.write(bodyJson) }
            val response = conn.inputStream.bufferedReader().use { it.readText() }.trim()
            if (response.isEmpty()) null
            else try { Gson().fromJson(response, HookDecision::class.java) } catch (_: Exception) { HookDecision(content = response) }
        } catch (_: Exception) { null }
    }
}
