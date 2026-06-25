package com.aiassistant.ui

import com.intellij.openapi.project.Project
import java.util.concurrent.CopyOnWriteArrayList

// ponytail: simple event bus for page sync — uses IntelliJ messageBus.connect() in production

object MessageBus {

    private val listeners = CopyOnWriteArrayList<MessageBusListener>()

    interface MessageBusListener {
        fun onSessionChanged(sessionId: String, type: String) {}
        fun onAgentStateChanged(sessionId: String, newState: String) {}
        fun onTokenUsageUpdated(sessionId: String, delta: Long) {}
        fun onMcpServerStateChanged(serverId: String, newState: String) {}
        fun onPlanStateChanged(sessionId: String, status: String) {}
    }

    fun register(listener: MessageBusListener) {
        listeners.add(listener)
    }

    fun unregister(listener: MessageBusListener) {
        listeners.remove(listener)
    }

    fun publishSessionChanged(sessionId: String, type: String) =
        listeners.forEach { it.onSessionChanged(sessionId, type) }

    fun publishAgentStateChanged(sessionId: String, newState: String) =
        listeners.forEach { it.onAgentStateChanged(sessionId, newState) }

    fun publishTokenUsageUpdated(sessionId: String, delta: Long) =
        listeners.forEach { it.onTokenUsageUpdated(sessionId, delta) }

    fun publishMcpServerStateChanged(serverId: String, newState: String) =
        listeners.forEach { it.onMcpServerStateChanged(serverId, newState) }

    fun publishPlanStateChanged(sessionId: String, status: String) =
        listeners.forEach { it.onPlanStateChanged(sessionId, status) }
}
