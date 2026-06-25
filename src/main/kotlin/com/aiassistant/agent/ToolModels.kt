package com.aiassistant.agent

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription

// ponytail: 8 Tool 类，Anthropic SDK 自动从类注解生成 JSON Schema

@JsonClassDescription("读取项目内指定文件的内容")
class ReadFile {
    @JsonPropertyDescription("项目内相对路径，如 src/main/kotlin/UserService.kt")
    var filePath: String = ""

    @JsonPropertyDescription("起始行号，1-based，可选")
    var startLine: Int? = null

    @JsonPropertyDescription("结束行号，1-based，可选")
    var endLine: Int? = null
}

@JsonClassDescription("覆盖写入整个文件。用于创建新文件或大范围修改")
class WriteFile {
    @JsonPropertyDescription("项目内相对路径")
    var filePath: String = ""

    @JsonPropertyDescription("完整的新文件内容")
    var content: String = ""
}

@JsonClassDescription("精确替换文件中的部分内容。oldString 必须在文件中唯一且精确匹配")
class EditFile {
    @JsonPropertyDescription("项目内相对路径")
    var filePath: String = ""

    @JsonPropertyDescription("要被替换的旧内容片段，必须精确匹配文件中的唯一片段")
    var oldString: String = ""

    @JsonPropertyDescription("替换后的新内容")
    var newString: String = ""
}

@JsonClassDescription("执行 Shell 命令。工作目录默认为项目根目录，无超时限制")
class RunShell {
    @JsonPropertyDescription("要执行的 Shell 命令")
    var command: String = ""

    @JsonPropertyDescription("工作目录，可选，默认为项目根目录")
    var workDir: String? = null
}

@JsonClassDescription("列出项目目录结构")
class ListFiles {
    @JsonPropertyDescription("目录相对路径，可选，默认项目根目录")
    var dirPath: String? = null

    @JsonPropertyDescription("最大递归深度，默认 2 层")
    var maxDepth: Int? = null
}

@JsonClassDescription("在项目中搜索文本内容。使用单词边界匹配")
class SearchContent {
    @JsonPropertyDescription("搜索关键词")
    var query: String = ""
}

@JsonClassDescription("读取指定文件的 IDE 诊断信息（错误和警告）")
class ReadLints {
    @JsonPropertyDescription("项目内相对路径")
    var filePath: String = ""
}

@JsonClassDescription("启动子代理处理子任务，子代理完成后返回结果摘要")
class SpawnAgent {
    @JsonPropertyDescription("子代理的任务描述")
    var task: String = ""
}
