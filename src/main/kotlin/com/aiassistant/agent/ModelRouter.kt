package com.aiassistant.agent

/**
 * 模型路由：根据任务复杂度自动选择 deepseek-v4-flash 或 deepseek-v4-pro。
 * - v4-flash: 快速响应、工具调用、简单任务
 * - v4-pro: 复杂编码、大型重构、深度推理
 */
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
