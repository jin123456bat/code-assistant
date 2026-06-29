package com.aiassistant.completion

import com.intellij.psi.codeStyle.CommonCodeStyleSettings

/**
 * 对模型返回的所有候选文本做清理和验证。返回有效候选列表（无重复）。
 * 包含多行缩进处理：首行缩进继承、后续行偏移校正、Tab/空格检测、不一致候选降权。
 */
object CompletionPostProcessor {

    /**
     * @param choices 模型返回的原始候选列表
     * @param prefix 光标前的文本，用于裁剪开头重叠和提取光标行缩进
     * @param suffix 光标后的文本，用于裁剪结尾重叠
     * @param codeStyleSettings 当前文件的 CodeStyle 设置，用于判断 tab/空格缩进（可为 null）
     * @return 去重后的有效补全候选文本列表，缩进一致的在前、不一致的在后
     */
    fun process(
        choices: List<DeepSeekFimClient.FimChoice>,
        prefix: String,
        suffix: String,
        codeStyleSettings: CommonCodeStyleSettings.IndentOptions? = null
    ): List<String> {
        val seen = mutableSetOf<String>()
        val consistentResults = mutableListOf<String>()   // 缩进一致的候选
        val inconsistentResults = mutableListOf<String>()  // 缩进不一致的候选（降权）

        // 提取光标所在行的缩进信息
        val cursorLineIndent = extractCursorLineIndent(prefix)
        // 检测当前文件使用的缩进风格
        val fileIndentInfo = detectFileIndentStyle(codeStyleSettings, prefix)

        for (choice in choices) {
            // 内容过滤的候选直接跳过
            if (choice.finishReason == "content_filter") continue

            var text = choice.text

            // suffix 重叠裁剪：去掉补全文本末尾与 suffix 开头重叠的部分
            text = trimSuffixOverlap(text, suffix)

            // prefix 开头重叠裁剪：去掉补全文本开头与 prefix 结尾重叠的部分
            text = trimPrefixOverlap(text, prefix)

            // finish_reason == "length" → 截断到最后一个完整行，避免半行代码
            if (choice.finishReason == "length") {
                val lastNewline = text.lastIndexOf('\n')
                if (lastNewline >= 0) text = text.substring(0, lastNewline)
            }

            // 有效性过滤
            val trimmed = text.trim()
            if (trimmed.isEmpty()) continue
            // 如果补全内容和 suffix 开头完全相同则无意义
            if (trimmed == suffix.take(trimmed.length).trim()) continue
            if (seen.contains(trimmed)) continue

            // === 多行缩进处理 ===
            val processedText = if (text.contains('\n')) {
                // 多行补全：首行缩进继承 + 后续行偏移校正
                processMultiLineIndent(text, cursorLineIndent, fileIndentInfo)
            } else {
                trimmed
            }

            if (processedText.isEmpty()) continue
            if (seen.contains(processedText.trim())) continue

            seen.add(processedText.trim())

            // 检查缩进风格是否与文件一致
            val isIndentConsistent = checkIndentConsistency(processedText, fileIndentInfo)

            if (isIndentConsistent) {
                consistentResults.add(processedText.trim())
            } else {
                // 尝试用正则修正缩进
                val corrected = tryCorrectIndent(processedText, fileIndentInfo)
                if (corrected != null && corrected.isNotEmpty() && !seen.contains(corrected.trim())) {
                    seen.add(corrected.trim())
                    consistentResults.add(corrected.trim())
                } else {
                    // 修正失败，降权排在最后
                    inconsistentResults.add(processedText.trim())
                }
            }
        }

        // 缩进一致候选在前，不一致的降权在后
        consistentResults.addAll(inconsistentResults)
        return consistentResults
    }

    /**
     * 裁剪补全文本末尾与 [suffix] 开头的重叠部分。
     * 例如 text="hello world", suffix="world!" → 返回 "hello "
     */
    private fun trimSuffixOverlap(text: String, suffix: String): String {
        if (suffix.isEmpty()) return text
        val minLen = minOf(text.length, suffix.length)
        for (i in minLen downTo 1) {
            val tail = text.substring(text.length - i)
            if (suffix.startsWith(tail)) {
                return text.substring(0, text.length - i)
            }
        }
        return text
    }

    /**
     * 裁剪补全文本开头与 [prefix] 结尾的重叠部分。
     * 例如 text="world!", prefix="hello world" → 返回 "!"
     */
    private fun trimPrefixOverlap(text: String, prefix: String): String {
        if (prefix.isEmpty()) return text
        val minLen = minOf(text.length, prefix.length)
        for (i in minLen downTo 1) {
            val head = text.substring(0, i)
            if (prefix.endsWith(head)) {
                return text.substring(i)
            }
        }
        return text
    }

    // ========== 多行缩进处理 ==========

    /**
     * 从 prefix 中提取光标所在行的缩进字符串（空格/tab）。
     */
    private fun extractCursorLineIndent(prefix: String): String {
        val lastNewline = prefix.lastIndexOf('\n')
        val lineStart = if (lastNewline >= 0) lastNewline + 1 else 0
        val cursorLine = prefix.substring(lineStart)
        return extractIndent(cursorLine)
    }

    /**
     * 提取一行文本开头的缩进部分（仅空格和 tab）。
     */
    private fun extractIndent(line: String): String {
        var i = 0
        while (i < line.length && (line[i] == ' ' || line[i] == '\t')) {
            i++
        }
        return line.substring(0, i)
    }

    /**
     * 文件缩进信息：缩进字符类型和每级缩进宽度。
     */
    private data class FileIndentInfo(
        val useTab: Boolean,
        val indentSize: Int
    )

    /**
     * 检测当前文件使用的缩进风格。
     * 优先读取 CodeStyleSettings，其次从 prefix 中推断。
     */
    private fun detectFileIndentStyle(
        indentOptions: CommonCodeStyleSettings.IndentOptions?,
        prefix: String
    ): FileIndentInfo {
        // 优先使用 IDE CodeStyle 设置
        if (indentOptions != null) {
            return FileIndentInfo(
                useTab = indentOptions.USE_TAB_CHARACTER,
                indentSize = indentOptions.INDENT_SIZE
            )
        }

        // 退而求其次：从 prefix 中采样推断
        val lines = prefix.split('\n')
        var tabCount = 0
        var spaceCount = 0
        var spaceSizes = mutableListOf<Int>()

        for (line in lines) {
            val indent = extractIndent(line)
            if (indent.isEmpty()) continue
            if (indent[0] == '\t') {
                tabCount++
            } else {
                spaceCount++
                spaceSizes.add(indent.length)
            }
        }

        // 推断缩进大小：取空格缩进宽度中出现最多的
        val inferredSize = if (spaceSizes.isNotEmpty()) {
            spaceSizes.groupBy { it }.maxByOrNull { it.value.size }?.key ?: 4
        } else {
            4
        }

        return FileIndentInfo(
            useTab = tabCount > spaceCount,
            indentSize = inferredSize
        )
    }

    /**
     * 多行补全缩进处理：
     * 1. 首行继承光标所在行的缩进级别
     * 2. 后续行以首行为基准做偏移校正——如果首行补全内容比光标前代码多出 N 个缩进层级，
     *    则后续行统一减去 N 个层级
     */
    private fun processMultiLineIndent(
        text: String,
        cursorLineIndent: String,
        fileIndentInfo: FileIndentInfo
    ): String {
        val lines = text.split('\n').toMutableList()
        if (lines.isEmpty()) return text

        // 计算光标行缩进的"层级数"
        val cursorIndentLevel = computeIndentLevel(cursorLineIndent, fileIndentInfo)

        // 首行缩进继承：补全首行使用光标行的缩进级别
        val firstLineContent = lines[0]
        val firstLineContentStripped = firstLineContent.trimStart()
        lines[0] = cursorLineIndent + firstLineContentStripped

        // 如果只有一行，直接返回
        if (lines.size <= 1) return lines.joinToString("\n")

        // 后续行偏移校正：计算首行补全内容相对于光标行的缩进偏移量
        val firstLineOrigIndent = extractIndent(firstLineContent)
        val firstLineOrigLevel = computeIndentLevel(firstLineOrigIndent, fileIndentInfo)
        val levelOffset = firstLineOrigLevel - cursorIndentLevel

        if (levelOffset > 0) {
            // 首行缩进比光标行多 N 级 → 后续行统一减去 N 级
            for (i in 1 until lines.size) {
                val line = lines[i]
                val lineIndent = extractIndent(line)
                val lineLevel = computeIndentLevel(lineIndent, fileIndentInfo)
                val newLevel = (lineLevel - levelOffset).coerceAtLeast(0)
                lines[i] = buildIndent(newLevel, fileIndentInfo) + line.trimStart()
            }
        }

        return lines.joinToString("\n")
    }

    /**
     * 计算缩进字符串对应的"层级数"。
     * tab 缩进：每个 tab 算一级
     * 空格缩进：按 indentSize 分组算级
     */
    private fun computeIndentLevel(indent: String, fileIndentInfo: FileIndentInfo): Int {
        if (indent.isEmpty()) return 0
        return if (fileIndentInfo.useTab) {
            indent.count { it == '\t' }
        } else {
            // 空格缩进：按 indentSize 计算层级，容忍少量偏差
            (indent.length + fileIndentInfo.indentSize / 2) / fileIndentInfo.indentSize
        }
    }

    /**
     * 根据缩进信息构建指定层级数的缩进字符串。
     */
    private fun buildIndent(level: Int, fileIndentInfo: FileIndentInfo): String {
        if (level <= 0) return ""
        return if (fileIndentInfo.useTab) {
            "\t".repeat(level)
        } else {
            " ".repeat(level * fileIndentInfo.indentSize)
        }
    }

    /**
     * 检查补全文本的缩进风格是否与文件一致。
     * 不一致的情况：文件用 4 空格但补全用 2 空格；文件用 tab 但补全用空格等。
     */
    private fun checkIndentConsistency(
        text: String,
        fileIndentInfo: FileIndentInfo
    ): Boolean {
        val lines = text.split('\n')
        // 检查每行（非空行）的缩进风格
        for (line in lines) {
            val indent = extractIndent(line)
            if (indent.isEmpty()) continue

            if (fileIndentInfo.useTab) {
                // 文件使用 tab → 补全中有空格缩进则为不一致
                if (indent[0] == ' ') return false
            } else {
                // 文件使用空格 → 补全中有 tab 缩进则为不一致
                if (indent[0] == '\t') return false
                // 检查空格缩进宽度是否为 indentSize 的整数倍
                if (indent.length % fileIndentInfo.indentSize != 0) return false
            }
        }
        return true
    }

    /**
     * 尝试用正则修正缩进不一致的候选。
     * 将补全中的缩进替换为文件标准缩进风格。
     * 返回修正后的文本，如果无法修正则返回 null。
     */
    private fun tryCorrectIndent(
        text: String,
        fileIndentInfo: FileIndentInfo
    ): String? {
        return try {
            val standardIndent =
                if (fileIndentInfo.useTab) "\t" else " ".repeat(fileIndentInfo.indentSize)
            val lines = text.split('\n')
            val corrected = lines.map { line ->
                val indent = extractIndent(line)
                if (indent.isEmpty()) {
                    line
                } else {
                    // 计算该行的缩进层级
                    val level = if (indent[0] == '\t') {
                        indent.length
                    } else {
                        (indent.length + fileIndentInfo.indentSize / 2) / fileIndentInfo.indentSize
                    }
                    standardIndent.repeat(level) + line.trimStart()
                }
            }
            corrected.joinToString("\n")
        } catch (_: Exception) {
            null
        }
    }
}
