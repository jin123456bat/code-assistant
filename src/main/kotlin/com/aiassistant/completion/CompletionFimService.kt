package com.aiassistant.completion

import com.aiassistant.util.OkioWatchdogCleaner
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service

@Service(Service.Level.APP)
class CompletionFimService : Disposable {
    val client = DeepSeekFimClient()

    override fun dispose() {
        client.close()
        OkioWatchdogCleaner.shutdownForPluginUnload()
    }
}
