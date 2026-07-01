package com.aiassistant.agent

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription

// ponytail: Tool 数据类，Anthropic SDK 自动从类注解生成 JSON Schema。每个工具都包含 timeout（秒，0=不限）
// 工具名对齐文档 tools.md §一：Read / Write / Edit / Bash / Glob / Grep / readLints / Agent /
//   WebSearch / WebFetch / AskUserQuestion / Skill / Symbol + 5 个 Plan 管理工具

@JsonClassDescription("读取项目内指定文件的内容。单次最多返回 500 行，超出时尾部标注截断提示，需用 startLine 参数分页读取。支持读取图片文件（PNG/JPEG/GIF/WebP）。")
class Read {
    @JsonPropertyDescription("项目内相对路径，如 src/main/kotlin/UserService.kt")
    var filePath: String = ""

    @JsonPropertyDescription("起始行号，1-based，可选")
    var startLine: Int? = null

    @JsonPropertyDescription("结束行号，1-based，可选")
    var endLine: Int? = null

    @JsonPropertyDescription("超时秒数，必填。读取小文件设 5-10s，大文件设 15-30s，0=不限")
    var timeout: Int = 0
}

@JsonClassDescription("覆盖写入整个文件。用于创建新文件或大范围修改。内容最多 3000 行，超出将被拒绝。")
class Write {
    @JsonPropertyDescription("项目内相对路径")
    var filePath: String = ""

    @JsonPropertyDescription("完整的新文件内容")
    var content: String = ""

    @JsonPropertyDescription("超时秒数，必填。小文件设 5-10s，大文件设 15-30s，0=不限")
    var timeout: Int = 0
}

@JsonClassDescription("精确替换文件中的部分内容。oldString 必须在文件中唯一且精确匹配。newString 最多 3000 行，超出将被拒绝。")
class Edit {
    @JsonPropertyDescription("项目内相对路径")
    var filePath: String = ""

    @JsonPropertyDescription("要被替换的旧内容片段，必须精确匹配文件中的唯一片段")
    var oldString: String = ""

    @JsonPropertyDescription("替换后的新内容")
    var newString: String = ""

    @JsonPropertyDescription("超时秒数，必填。简单替换设 5-10s，大文件设 15-30s，0=不限")
    var timeout: Int = 0
}

@JsonClassDescription("执行 Shell 命令。工作目录限定为项目根目录。最多返回 200 行/4000 字符输出（取先达到者），超出后中段截断。timeout 必填，由 LLM 根据命令类型设定（编译 300s，简单命令 30s，0=不限）。")
class Bash {
    @JsonPropertyDescription("要执行的 Shell 命令")
    var command: String = ""

    @JsonPropertyDescription("工作目录，可选，默认为项目根目录")
    var workDir: String? = null

    @JsonPropertyDescription("超时秒数，必填。快速命令设 10-30s，构建/测试设 120-300s，长时间任务设 600s，0=不限")
    var timeout: Int = 0

    @JsonPropertyDescription("是否为危险命令（如 rm -rf /、git push --force、sudo、chmod 777），bool 类型，必填。dangerous=true 时始终弹窗二次确认，无视白名单")
    var dangerous: Boolean = false
}

@JsonClassDescription("列出项目目录结构。最多返回 50 个条目（文件+目录）。如果超出此限制，结果会被截断并在返回值中标注。请用 dirPath/maxDepth 缩小范围，或用 offset 参数翻页获取更多条目。")
class Glob {
    @JsonPropertyDescription("目录相对路径，可选，默认项目根目录")
    var dirPath: String? = null

    @JsonPropertyDescription("最大递归深度，默认 2 层")
    var maxDepth: Int? = null

    @JsonPropertyDescription("翻页起始偏移，默认 0。超出 50 条目时用 offset=50 翻页获取后续条目")
    var offset: Int? = null

    @JsonPropertyDescription("超时秒数，必填。建议 5-10s，大项目设 15-30s，0=不限")
    var timeout: Int = 0
}

@JsonClassDescription("在项目中搜索文本内容。支持正则表达式，不区分大小写。非法正则自动回退为字面子串匹配。最多返回 50 条匹配，超出时尾部标注截断提示。跳过 build/、.git/、.idea/、node_modules/ 目录。")
class Grep {
    @JsonPropertyDescription("搜索关键词")
    var query: String = ""

    @JsonPropertyDescription("文件名过滤模式，可选，如 *.kt、*.java")
    var filePattern: String? = null

    @JsonPropertyDescription("超时秒数，必填。建议 5-15s，大项目设 20-30s，0=不限")
    var timeout: Int = 0
}

@JsonClassDescription("读取指定文件的 IDE 诊断信息（错误和警告）。按严重程度降序排列，最多返回 50 条诊断。")
class ReadLints {
    @JsonPropertyDescription("项目内相对路径")
    var filePath: String = ""

    @JsonPropertyDescription("超时秒数，必填。建议 5-10s，大文件设 15-20s，0=不限")
    var timeout: Int = 0
}

@JsonClassDescription("执行指定 Skill。LLM 根据用户需求自主判断触发时机，将 SKILL.md 内容作为消息注入 conversation。")
class Skill {
    @JsonPropertyDescription("Skill 名称或命令，如 code-review 或 /review")
    var skill: String = ""

    @JsonPropertyDescription("可选参数字符串，传递给 Skill")
    var args: String? = null

    @JsonPropertyDescription("超时秒数，必填。建议 5-10s，0=不限")
    var timeout: Int = 0
}

@JsonClassDescription("启动子代理处理子任务。子代理完成后返回结果摘要（最多 2000 tokens）。子代理使用独立的工具白名单，不可嵌套启动孙代理。")
class Agent {
    @JsonPropertyDescription("子代理的任务描述，必填")
    var prompt: String = ""

    @JsonPropertyDescription("超时秒数，必填。简单任务设 30-60s，复杂任务设 120-300s，0=不限")
    var timeout: Int = 0

    @JsonPropertyDescription("是否在后台异步执行，必填。true=异步（父代理立即返回），false=同步（父代理阻塞等待子完成）")
    var run_in_background: Boolean = false
}

// ── 扩展工具：WebSearch / WebFetch / AskUserQuestion / Symbol ──

@JsonClassDescription("搜索网页，返回标题和 URL 列表。≤ 10 条/页，超出时用 offset 参数翻页。不支持缓存。")
class WebSearch {
    @JsonPropertyDescription("搜索关键词")
    var query: String = ""

    @JsonPropertyDescription("限定域名，可选")
    var allowedDomains: List<String>? = null

    @JsonPropertyDescription("排除域名，可选")
    var blockedDomains: List<String>? = null

    @JsonPropertyDescription("翻页起始位置，默认 0")
    var offset: Int? = null

    @JsonPropertyDescription("超时秒数，必填。建议 10-30s，0=不限")
    var timeout: Int = 0
}

@JsonClassDescription("抓取 URL 内容并按提示提取信息。HTTP 自动升级为 HTTPS。不支持需认证的页面。不支持缓存。")
class WebFetch {
    @JsonPropertyDescription("要抓取的 URL")
    var url: String = ""

    @JsonPropertyDescription("描述要提取的内容")
    var prompt: String = ""

    @JsonPropertyDescription("超时秒数，必填。建议 10-30s，0=不限")
    var timeout: Int = 0
}

@JsonClassDescription("向用户发起问题以澄清需求。一次 1-4 个问题，每题 2-4 个选项。支持多选。")
class AskUserQuestion {
    @JsonPropertyDescription("问题列表，1-4 个")
    var questions: List<QuestionItem> = emptyList()

    @JsonPropertyDescription("超时秒数，必填。建议 120-300s（等待用户手动操作），0=不限")
    var timeout: Int = 0
}

/** 单个问题项，用于 AskUserQuestion.questions */
class QuestionItem {
    @JsonPropertyDescription("完整问题文本，以问号结尾")
    var question: String = ""

    @JsonPropertyDescription("短标签，如 Auth method, Library, Approach（≤12 字符）")
    var header: String = ""

    @JsonPropertyDescription("可选项列表，2-4 个。支持多选时允许多选")
    var options: List<OptionItem> = emptyList()

    @JsonPropertyDescription("是否允许多选，默认 false")
    var multiSelect: Boolean = false
}

/** 问题的可选项，用于 QuestionItem.options */
class OptionItem {
    @JsonPropertyDescription("选项标签，1-5 词")
    var label: String = ""

    @JsonPropertyDescription("选项含义说明")
    var description: String = ""
}

@JsonClassDescription("基于 IDE PSI 的语义导航（8 种操作）。goToDefinition 跳转定义、goToImplementation 查实现、findReferences 查引用（≤50）、hover 类型提示、documentSymbol 文件结构（≤100）、workspaceSymbol 全局搜索（≤20，需 query）、incomingCalls/outgoingCalls 调用链（≤50）。")
class Symbol {
    @JsonPropertyDescription("操作类型: goToDefinition, goToImplementation, findReferences, hover, documentSymbol, workspaceSymbol, incomingCalls, outgoingCalls")
    var operation: String = ""

    @JsonPropertyDescription("项目内相对路径（workspaceSymbol 不需要）")
    var filePath: String = ""

    @JsonPropertyDescription("行号 1-based（workspaceSymbol 不需要）")
    var line: Int = 0

    @JsonPropertyDescription("字符位置 1-based（workspaceSymbol 不需要）")
    var character: Int = 0

    @JsonPropertyDescription("符号名查询（仅 workspaceSymbol 需要）")
    var query: String? = null

    @JsonPropertyDescription("超时秒数，必填。建议 5-15s，0=不限")
    var timeout: Int = 0
}

// ── Plan 管理工具 ──

@JsonClassDescription("创建/更新执行计划。当任务涉及 3 个以上文件或预计需要 5 轮以上完成时，在执行关键修改前先创建执行计划。最多 20 个计划项。")
class CreatePlan {
    @JsonPropertyDescription("任务描述，一句话概括总体目标")
    var task: String = ""

    @JsonPropertyDescription("计划项列表，每项含 description(描述)、tool(建议工具名)、files(涉及文件路径列表)")
    var plans: List<PlanItemInput> = emptyList()

    @JsonPropertyDescription("超时秒数，必填。建议 10-20s，0=不限")
    var timeout: Int = 0
}

/** 单个计划项，用于 CreatePlan.plans */
class PlanItemInput {
    @JsonPropertyDescription("步骤描述")
    var description: String = ""

    @JsonPropertyDescription("建议工具名，如 Read/Write/Edit/Bash/AskUserQuestion")
    var tool: String = ""

    @JsonPropertyDescription("涉及文件路径列表，如 [\"src/main/UserService.kt\"]")
    var files: List<String> = emptyList()
}

@JsonClassDescription("查看当前计划的所有计划项及状态")
class ListPlans {
    @JsonPropertyDescription("超时秒数，必填。建议 5s，0=不限")
    var timeout: Int = 0
}

@JsonClassDescription("删除指定计划项，仅 PAUSED 状态可删除")
class RemovePlan {
    @JsonPropertyDescription("要删除的计划项 ID")
    var planId: String = ""

    @JsonPropertyDescription("超时秒数，必填。建议 5s，0=不限")
    var timeout: Int = 0
}

@JsonClassDescription("重排剩余 PAUSED 计划项的执行顺序，传入新的 planId 序列")
class ReorderPlans {
    @JsonPropertyDescription("新的 planId 顺序列表，按需要的执行顺序排列")
    var planIds: List<String> = emptyList()

    @JsonPropertyDescription("超时秒数，必填。建议 5s，0=不限")
    var timeout: Int = 0
}

@JsonClassDescription("将指定计划项标记为 COMPLETED")
class MarkPlanDone {
    @JsonPropertyDescription("要标记为完成的计划项 ID")
    var planId: String = ""

    @JsonPropertyDescription("超时秒数，必填。建议 5s，0=不限")
    var timeout: Int = 0
}
