package com.aiassistant.shared

import java.io.File

/**
 * 路径安全工具 — 统一路径穿越检测，供 AgentLoop 审批和工具执行层复用。
 */
object PathUtils {

    /**
     * 检测目标路径是否在项目目录内。
     * 通过 canonical path 前缀比对防止 `../` 和符号链接绕过。
     *
     * @param path     相对或绝对路径
     * @param basePath 项目根目录
     * @return true = 在项目目录内；false = 在目录外或路径异常
     */
    fun isInsideProject(path: String, basePath: String?): Boolean {
        if (basePath == null) return false
        val file = if (File(path).isAbsolute) File(path) else File(basePath, path)
        return try {
            val normalizedBase = File(basePath).canonicalPath
            val normalizedTarget = file.canonicalPath
            normalizedTarget == normalizedBase ||
                normalizedTarget.startsWith(normalizedBase + File.separator)
        } catch (_: Exception) {
            false // 路径异常（如无效字符），视为不安全
        }
    }
}
