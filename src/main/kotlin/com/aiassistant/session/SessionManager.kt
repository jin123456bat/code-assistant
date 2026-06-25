package com.aiassistant.session

import com.aiassistant.agent.AgentSession
import com.intellij.openapi.project.Project

// ponytail: session CRUD + search wrapper over SessionStore

class SessionManager(private val project: Project) {

    private val store = SessionStore(project)
    var currentSession: AgentSession? = null

    fun createSession(title: String = "新会话"): AgentSession {
        val session = AgentSession(title = title)
        currentSession = session
        return session
    }

    fun getSession(id: String): AgentSession? = store.load(id)

    fun getAllSessions(): List<SessionIndex> = store.listAll()

    fun deleteSession(id: String) {
        store.delete(id)
        if (currentSession?.id == id) currentSession = null
    }

    fun saveSession(session: AgentSession) = store.save(session)

    fun searchSessions(query: String): List<SessionIndex> =
        getAllSessions().filter { it.title.contains(query, ignoreCase = true) }
}
