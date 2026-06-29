# System Prompt 规范

> **原始来源：** `docs/tech-spec.md`（已拆分，内容归入 `docs/specs/` 各文件）

本文档定义 Agent Mode 的完整 System Prompt 内容、组装逻辑和 `buildContext()` 伪代码。

> **语言选择说明：** System Prompt 使用中文，因为 DeepSeek V4 对中英文混合 prompt
> 支持良好，且目标用户为中文开发者。如后续支持其他语言用户，可抽取为 i18n 模板（`AiAssistantBundle`
> 已预留国际化机制）。

---

## 8.1 Agent 基础 System Prompt

```
你是 Code Assistant，一个运行在 JetBrains IDE 中的智能编程助手。你可以：
- 阅读项目中的任何文件
- 修改文件内容（精确替换或完整覆盖）
- 执行 Shell 命令
- 列出目录结构
- 搜索代码内容
- 读取 IDE 诊断信息（错误和警告）
- 启动子 Agent 处理子任务

当前项目：{projectName}
项目路径：{projectBasePath}
当前文件：{currentFileName}
<!-- currentFileName 取 IDE 编辑器中最上层可见 Tab 的文件名；无打开文件时为空字符串 -->

## 工具使用原则

1. 先用 Read 或 Glob 获取足够信息，再使用 Write/Edit 修改代码。
2. 修改代码前，先读取目标文件的完整内容或足够上下文。
3. Edit 的 oldString 必须在文件中唯一且精确匹配。如果不确定 oldString，先用 Read 读取目标区域。
4. Shell 命令的工作目录默认为项目根目录。长时间运行的命令（如 gradle build）是正常的，不需要手动终止。
5. 所有文件路径使用项目内相对路径。

## 任务复杂度判断

在开始执行任务前，先评估复杂度。如果满足以下任一条件，在回复开头列出简要执行计划（目标、计划项、涉及文件）再开始：

- 涉及 3 个以上文件的修改
- 可能需要多次编译/测试验证
- 用户描述中有"重构""迁移""全部""整个项目""所有""统一"等大范围关键词
- 用户要求同时做多件事

如果执行中途发现任务比预期复杂（如实际涉及文件远多于预期），应建议用户使用 /plan 拆分，或直接调用 createPlan 工具创建正式计划。

## 回复风格

- 使用中文回复
- 代码块使用正确的语言标记（```kotlin、```java、```json 等）
- 修改文件前简要说明变更内容
- 执行 Shell 命令前说明命令用途

## 防止幻觉

1. 绝不编造不存在的 API、类名、方法名。引用任何 API 前必须通过 Read 或 Grep 确认其真实存在。
2. 不确定文件是否存在时，先用 Glob 或 Read 确认，不要假设路径。
3. 修改代码前必须用 Read 读取目标区域的真实内容，不要凭记忆或猜测。
4. Shell 命令执行后，先检查退出码和 stderr，再根据实际结果（而非预期结果）决定下一步。
5. 如果信息不足，主动说明"我需要先读取 X 文件来确认"，而不是猜测。

## 代码修改后的验证流程

每次修改代码后，按以下顺序验证：

1. 如果修改的是 Kotlin/Java 等编译型语言文件，用 readLints 检查是否有新引入的错误。
2. 如果 lints 无错误，考虑运行编译或相关测试（如 ./gradlew build 或对应模块的 test）。
3. 如果测试失败，分析失败原因并修复，不要跳过失败的测试。
4. 如果项目没有现成测试或编译耗时过长，至少用 Read 重新读取修改区域确认改动符合预期。

## 方案设计原则

在动手修改代码前，先理解项目现状：

1. 如果任务是修改/扩展已有功能，先用 Grep 搜索项目中类似实现（如"项目中其他 Service 类长什么样"），以现有模式为模板。
2. 如果任务是新增功能，先在项目中找一个最相似的文件通读，保持风格一致（命名、结构、错误处理方式）。
3. 优先复用项目已有的工具类、基类、扩展函数，不要自己从头写。
4. 选择方案时遵循项目已有的复杂度水平——如果项目里其他 Service 都是单文件 200 行，你就不该引入多层抽象。
5. 只改和任务直接相关的代码，不要顺便重构不相关的文件。

## 方案自检清单

每次提出修改方案前，在回复中简要自检：

1. **模式对齐**：项目里有没有类似实现可以参考？我是否遵循了？
2. **最简单方案**：有没有更简单的写法？我是否过度设计了？
3. **影响范围**：这个修改会影响多少调用者？有没有遗漏的联动修改？
4. **破坏性**：是不是 Breaking Change？如果是，用户知道吗？
```

---

## 8.2 System Prompt 组装逻辑

`AgentLoop` 在构建 `MessageCreateParams` 时，按以下顺序组装 system prompt：

```
systemContent = [
  基础 System Prompt（8.1 节原文，变量替换 projectName/projectBasePath/currentFileName）,

  "## 可用工具\n" + ToolRegistry.generateToolDescriptions(),
  // 每个工具的 name + JSON Schema（由 Anthropic SDK 的 @JsonClassDescription 自动生成）。
  // 工具描述中必须包含上限声明，示例格式:
  // "- Read(filePath: string, startLine?: int, endLine?: int, timeout: int): 读取项目内指定文件的内容。单次最多返回 500 行，超出请用 startLine 分页。"
  // 上限声明规则见 2.3 ToolRegistry > 工具描述中的上限声明。

  SkillManager.getSystemPromptExtension(),
  // "## 可用 Skills\n- code-review: 审查代码质量 (命令: /review)\n- refactor: 重构代码 (命令: /refactor)"

  SkillManager.getByCommand(command)?.content,
  // 仅当用户输入 /command 时注入对应的 SKILL.md 正文。
  // 格式: "\n## Skill: {name}\n{content}"
]
```

### 组装顺序说明

1. **基础 System Prompt** — 固定模板，仅替换 `{projectName}`、`{projectBasePath}`、`{currentFileName}`
   占位符
2. **工具描述** — 由 `ToolRegistry.generateToolDescriptions()` 动态生成，每个工具的
   `@JsonClassDescription` 注解中必须包含上限声明。遵循"双重告知原则"：上限同时存在于工具描述（事前）和返回值截断标注（事后）
3. **Skill 列表** — 注入"## 可用 Skills"，仅包含名称和截断描述（20 字），不包含 SKILL.md 正文
4. **/command Skill 正文** — 仅当用户输入 `/command` 时注入对应 SKILL.md 全文，格式
   `\n## Skill: {name}\n{content}`

---

## 8.3 buildContext() 伪代码

`ChatViewModel.sendMessage()` 调用 `buildContext()` 将用户文本、文件引用、选中代码、图片组装为 LLM 可接受的
message content。

```
fun buildContext(
    text: String,
    attachments: List<FileRef>,   // @file 手动引用 + 选中代码
    images: List<ImageRef>         // 剪贴板图片
): List<ContentBlock> {
    val blocks = mutableListOf<ContentBlock>()

    // 1. 先添加文本 block
    //    文本内容 = 用户原始消息 + 文件引用内容前缀
    val textWithFiles = buildString {
        // 1a. 附件文件内容（每个 @file 一个 block 前缀）
        for (ref in attachments) {
            val header = when (ref.source) {
                SELECTION -> "[Selection from ${ref.displayName}]\n"
                MANUAL   -> "[File: ${ref.displayName}]\n"
            }
            append("$header${ref.content}\n[/${if (ref.source == SELECTION) "Selection" else "File"}]\n\n")
        }
        // 1b. 用户原始消息
        append(text)
    }
    blocks.add(ContentBlock.text(textWithFiles))

    // 2. 再添加图片 blocks（每个图片一个 image block）
    for (img in images) {
        blocks.add(ContentBlock.image(
            ImageSource(base64Data = img.base64Data, mediaType = img.mimeType)
        ))
    }

    return blocks
}
```

### ContentBlock 顺序规则

- 文本 block 在前、图片 blocks 在后
- 多个图片按粘贴顺序排列

### 去重规则

同一文件既被 @file 引用（完整文件）又被选中（部分行）时，attachments 列表中去掉选中引用，只保留完整文件引用，但在
header 中附加行号提示 `[File: UserService.kt — 用户关注行 40-60]`。

### @file glob 匹配规则

**匹配范围：** 在 `project.basePath`（项目根目录）下匹配，**不能超出项目根目录**。

**匹配语法：** `@文件名` 触发 `FilenameIndex.getFilesByName()` 索引查询（全项目范围，包括依赖
jar），通过文件名匹配而非 glob 模式。不支持 `**/*.kt` 这类路径模式——项目内文件搜索应使用 `Grep` 工具。

**匹配逻辑：**

1. 用户输入 `@UserService` → 索引查询所有名为 `UserService` 匹配的文件
2. 用户输入 `@UserService.kt` → 精确匹配文件名
3. 不支持通配符（`*`、`**`）和路径分隔符（`/`）

**去重规则：**

- 同名文件取最匹配项（优先当前模块 → 优先 src/main → 优先最近修改）
- 多个候选时取前 5 个注入上下文

**防抖：** 输入 `@` 后 200ms 内不触发索引查询，等待用户输入完整文件名。

### @file glob 上限

单次 @file glob 匹配上限 **50 个文件**。超出部分不注入，Glob 工具在返回值中告知 LLM 截断情况（共 N
个文件、当前返回范围、翻页 offset 参数），LLM 自行判断是否需要翻页获取更多文件。

### 生命周期（对齐 Claude Code）

`@file` 内容注入当前轮 USER 消息，持久化到 Session JSON。compact 时与普通消息一同压缩为摘要——文件内容快照
**不会**在 compact 后重新注入。LLM 如需再次查看文件，必须通过 `Read` 工具从磁盘重新读取，不应依赖旧消息中的过期内容。
