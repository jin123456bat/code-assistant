package com.aiassistant.shared

/**
 * 公共 JSON 工具函数 — 避免在多个模块中重复定义。
 */
object JsonUtils {
    fun escapeJson(s: String): String {
        return s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    /**
     * 单趟扫描反转义。不能用链式 replace —— 若先 replace("\\n",...) 再 replace("\\\\",...)，
     * 含字面反斜杠的输入（如 "\\n" 表示反斜杠+n）会被错误还原。
     * 支持 \n \r \t \" \\ \/ \b \f 及 \uXXXX。
     */
    fun unescapeJson(s: String): String {
        if (s.indexOf('\\') < 0) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'b' -> { sb.append('\b'); i += 2 }
                    'f' -> { sb.append(''); i += 2 }
                    '"' -> { sb.append('"'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    '/' -> { sb.append('/'); i += 2 }
                    'u' -> {
                        // \uXXXX：取后 4 位十六进制
                        if (i + 5 < s.length) {
                            val hex = s.substring(i + 2, i + 6)
                            val code = hex.toIntOrNull(16)
                            if (code != null) {
                                sb.append(code.toChar()); i += 6
                            } else {
                                sb.append(c); i++
                            }
                        } else {
                            sb.append(c); i++
                        }
                    }
                    else -> { sb.append(c); i++ }  // 未知转义，原样保留反斜杠
                }
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
    }
}
