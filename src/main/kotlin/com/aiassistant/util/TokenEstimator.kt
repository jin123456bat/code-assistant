package com.aiassistant.util

import com.aiassistant.agent.TokenEstimator as AgentTokenEstimator

/**
 * Token 估算委托类（向后兼容）。
 *
 * 实际实现见 [com.aiassistant.agent.TokenEstimator]。
 */
object TokenEstimator {

    fun estimateTokens(text: String): Int = AgentTokenEstimator.estimateTokens(text)

    fun estimateTokensAsLong(text: String): Long = AgentTokenEstimator.estimateTokensAsLong(text)
}
