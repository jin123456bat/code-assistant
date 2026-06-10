package com.aiassistant.agent

/**
 * 模型路由：根据任务复杂度自动选择 deepseek-v4-flash 或 deepseek-v4-pro。
 *
 * @deprecated 模型选择已简化为默认 deepseek-v4-flash，用户可在设置中手动切换。
 * AgentLoop 不再调用 selectModel()。
 */
@Deprecated("模型选择已简化为默认 deepseek-v4-flash，由用户在设置中切换")
object ModelRouter {

    fun selectModel(userInput: String): String {
        val lower = userInput.lowercase().trim()
        // 复杂编码相关 → pro
        val proKeywords = listOf(
            "实现", "重构", "优化性能", "算法", "设计模式", "架构",
            "加密", "解密", "并发", "多线程", "内存泄漏", "死锁",
            "复杂查询", "存储过程", "正则", "解析", "编译",
            "implement", "refactor", "optimize", "algorithm", "architecture",
            "encrypt", "decrypt", "concurrent", "multithread", "deadlock"
        )
        for (kw in proKeywords) {
            if (lower.contains(kw)) return "deepseek-v4-pro"
        }
        // 默认 → flash
        return "deepseek-v4-flash"
    }
}
