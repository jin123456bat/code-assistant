package com.aiassistant.commands

import java.io.File

class TestRunner(private val projectBasePath: String?) {

    @Volatile var lastTestOutput: String? = null
    @Volatile var lastTestFailed: Boolean = false

    data class TestResult(val success: Boolean, val summary: String, val failures: List<TestFailure>)
    data class TestFailure(val testName: String, val errorMessage: String, val stackTrace: String)

    fun run(testFilter: String? = null): TestResult {
        val base = projectBasePath ?: return TestResult(false, "项目路径不可用", emptyList())

        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val gradleCmd = if (isWindows) "gradlew.bat" else "./gradlew"
        val args = mutableListOf(gradleCmd, "test")
        if (testFilter != null) {
            args.add("--tests")
            args.add(testFilter)
        }

        return try {
            val process = ProcessBuilder(args)
                .directory(File(base))
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(10, java.util.concurrent.TimeUnit.MINUTES)
            if (!finished) {
                process.destroyForcibly()
                process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = if (finished) process.exitValue() else -1

            val success = exitCode == 0
            lastTestOutput = output
            lastTestFailed = !success

            if (success) {
                val testCount = Regex("""(\d+) tests completed""").find(output)?.groupValues?.get(1) ?: "?"
                TestResult(true, "✅ 全部 $testCount 个测试通过", emptyList())
            } else {
                val failures = parseFailures(output)
                TestResult(false, "❌ ${failures.size} 个测试失败", failures)
            }
        } catch (e: Exception) {
            TestResult(false, "❌ 测试执行异常: ${e.message}", emptyList())
        }
    }

    private fun parseFailures(output: String): List<TestFailure> {
        val failures = mutableListOf<TestFailure>()
        val testNameRegex = Regex("""(\S+)\s+FAILED""")

        val lines = output.lines()
        var i = 0
        while (i < lines.size) {
            val match = testNameRegex.find(lines[i])
            if (match != null) {
                val name = match.groupValues[1]
                val errorLines = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].startsWith("Gradle Test ") && !lines[i].startsWith("BUILD")) {
                    errorLines.add(lines[i])
                    i++
                }
                failures.add(TestFailure(name, errorLines.firstOrNull() ?: "", errorLines.joinToString("\n")))
            } else {
                i++
            }
        }
        return failures
    }

    fun buildFixPrompt(): String? {
        val output = lastTestOutput ?: return null
        return buildString {
            appendLine("以下测试失败，请分析根因并直接修复源代码（使用 edit_file 工具）：")
            appendLine()
            appendLine("```")
            appendLine(output.take(5000))
            appendLine("```")
            appendLine()
            appendLine("修复原则：")
            appendLine("1. 只修改源代码，不修改测试（除非测试本身有 bug）")
            appendLine("2. 每次修改后思考代码变更是否合理")
            appendLine("3. 修复完后自行判断是否还需要进一步修改")
        }
    }
}
