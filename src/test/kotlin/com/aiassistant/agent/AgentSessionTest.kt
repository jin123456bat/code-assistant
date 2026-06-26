package com.aiassistant.agent

import kotlin.test.Test
import kotlin.test.assertEquals

class AgentSessionTest {

    @Test
    fun `rejected approval remains processing until turn completion`() {
        val session = AgentSession()

        session.startExecuting()
        session.requireApproval()
        session.approvalRejected()
        session.doneExecuting()

        assertEquals(AgentSession.State.PROCESSING, session.state)
        session.finishTurn()
        assertEquals(AgentSession.State.IDLE, session.state)
    }
}
