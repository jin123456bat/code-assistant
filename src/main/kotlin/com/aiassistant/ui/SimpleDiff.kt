package com.aiassistant.ui

/** diff 行的类型：新增、删除、上下文（未变） */
enum class DiffKind { ADD, DEL, CTX }

/** 单行 diff 记录 */
data class DiffLine(val kind: DiffKind, val text: String)

/**
 * 纯函数行级 diff 工具。
 *
 * 使用 Myers 最长公共子序列（LCS）算法，时间复杂度 O(N*M)，
 * 对几百行以内的文件编辑确认场景足够。
 */
object SimpleDiff {

    /**
     * 对两段文本做行级 diff，返回带 DiffKind 标注的行列表。
     *
     * - CTX：两边都有、内容相同的行
     * - DEL：仅旧版本有（被删除）
     * - ADD：仅新版本有（被添加）
     *
     * DEL 在对应位置的 ADD 之前出现，符合标准 diff 习惯。
     */
    fun diff(oldText: String, newText: String): List<DiffLine> {
        // 空文本特殊处理：split("") 会产生一个空字符串元素，需要过滤
        val oldLines = if (oldText.isEmpty()) emptyList() else oldText.split("\n")
        val newLines = if (newText.isEmpty()) emptyList() else newText.split("\n")

        if (oldLines.isEmpty() && newLines.isEmpty()) return emptyList()

        // 计算 LCS 长度矩阵（DP）
        val lcs = buildLcsTable(oldLines, newLines)

        // 回溯 LCS 矩阵生成 diff 序列
        return buildDiffLines(oldLines, newLines, lcs)
    }

    /**
     * 使用动态规划计算 LCS 长度表。
     * lcs[i][j] = oldLines[0..i-1] 与 newLines[0..j-1] 的最长公共子序列长度。
     */
    private fun buildLcsTable(oldLines: List<String>, newLines: List<String>): Array<IntArray> {
        val m = oldLines.size
        val n = newLines.size
        val dp = Array(m + 1) { IntArray(n + 1) }

        for (i in 1..m) {
            for (j in 1..n) {
                dp[i][j] = if (oldLines[i - 1] == newLines[j - 1]) {
                    dp[i - 1][j - 1] + 1
                } else {
                    maxOf(dp[i - 1][j], dp[i][j - 1])
                }
            }
        }
        return dp
    }

    /**
     * 从 LCS 表回溯，生成带标注的 diff 行列表。
     * DEL 先于对应的 ADD，保持与 unified diff 相同的语义。
     */
    private fun buildDiffLines(
        oldLines: List<String>,
        newLines: List<String>,
        lcs: Array<IntArray>
    ): List<DiffLine> {
        val result = mutableListOf<DiffLine>()
        var i = oldLines.size
        var j = newLines.size

        // 回溯路径（逆序），最后反转
        val reversed = mutableListOf<DiffLine>()

        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && oldLines[i - 1] == newLines[j - 1] -> {
                    // 两边相同：上下文行
                    reversed.add(DiffLine(DiffKind.CTX, oldLines[i - 1]))
                    i--; j--
                }
                j > 0 && (i == 0 || lcs[i][j - 1] >= lcs[i - 1][j]) -> {
                    // 新版本多出的行：ADD（逆序，ADD 在 DEL 后面 → 反转后在前面？
                    // 逆序回溯时先遇到 ADD，反转后 ADD 会在 DEL 之后；
                    // 但我们希望 DEL 在 ADD 之前，所以先收集 ADD，再按正序排列。
                    // 实际做法：在逆序阶段先不管顺序，最后反转即可；
                    // 逆序时 j 减少，意味着正序中 newLines[j-1] 是"被添加的行"）
                    reversed.add(DiffLine(DiffKind.ADD, newLines[j - 1]))
                    j--
                }
                else -> {
                    // 旧版本独有的行：DEL
                    reversed.add(DiffLine(DiffKind.DEL, oldLines[i - 1]))
                    i--
                }
            }
        }

        // 反转得到正序，但此时 DEL/ADD 相对顺序可能需要修正：
        // 标准 diff 要求同一"块"中 DEL 先于 ADD。
        // 反转后的序列中，对于相邻的 ADD/DEL 混排块，需要将 DEL 提前。
        result.addAll(reversed.reversed())
        return result
    }

    /**
     * 对 diff 序列做稳定化处理：
     * 在连续的 ADD/DEL 混排块中，将所有 DEL 行移到 ADD 行之前。
     * 这符合 `diff -u` 的约定（先展示删除，再展示新增）。
     */
    private fun stabilizeDiff(lines: List<DiffLine>): List<DiffLine> {
        val result = mutableListOf<DiffLine>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.kind == DiffKind.CTX) {
                result.add(line)
                i++
            } else {
                // 收集连续的 ADD/DEL 块
                val dels = mutableListOf<DiffLine>()
                val adds = mutableListOf<DiffLine>()
                while (i < lines.size && lines[i].kind != DiffKind.CTX) {
                    when (lines[i].kind) {
                        DiffKind.DEL -> dels.add(lines[i])
                        DiffKind.ADD -> adds.add(lines[i])
                        DiffKind.CTX -> { /* 不会走到这里 */ }
                    }
                    i++
                }
                // DEL 先，ADD 后
                result.addAll(dels)
                result.addAll(adds)
            }
        }
        return result
    }
}
