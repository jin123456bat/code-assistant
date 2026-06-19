# Code Review & Slash Commands — 设计文档

> 日期: 2025-06-20 | 状态: 已确认

## 概述

实现完整代码审查系统，覆盖 P1（斜杠命令 `/review`/`/test`/`/diff`/`/security-review`）和 P2（代码审查 Review 功能）。支持聊天入口 + IDE 集成 + 右键菜单三种交互方式，对齐 Claude Code `/review`（`--fix` 自动修复 + `--comment` PR 评论）。

## 架构

```
review/ (新增包)
├── ReviewEngine.kt              — 审查引擎入口
├── DiffCollector.kt             — diff 来源收集+解析
├── ReviewAnalyzer.kt            — 四维度分析协调
├── FixApplier.kt                — --fix 自动修复
├── CommentFormatter.kt          — --comment PR 评论格式
├── detectors/
│   ├── BugDetector.kt           — 正确性（空指针/竞态/边界/逻辑）
│   ├── SimplifyDetector.kt      — 简化（DRY/魔法数字/过长方法）
│   ├── PerfDetector.kt          — 性能（N+1/内存泄漏/不必要分配）
│   └── SecurityDetector.kt      — 安全（注入/密钥/不安全 API）

security/ (新增包)
├── SecurityReviewEngine.kt      — 安全审查引擎
├── InjectionScanner.kt          — SQL/命令/模板/prompt 注入
├── SecretDetector.kt            — API Key/Token/密码泄漏
├── PermissionAnalyzer.kt        — 路径遍历/权限缺陷
└── DependencyChecker.kt         — 依赖 CVE 检查

commands/ (新增包)
└── ReviewCommands.kt            — /review /diff /security-review 命令

ui/ (修改 + 新增)
├── ReviewResultPanel.kt         — 审查结果面板（可逐条操作）
├── ReviewAnnotationGutter.kt    — IDE diff 视图行标注
└── ReviewContextMenu.kt         — 右键菜单

actions/ (新增)
└── ReviewSelectedCodeAction.kt  — 选中代码审查 Action
```

## 数据模型

### Finding

```kotlin
data class Finding(
    val severity: Severity,       // CRITICAL / WARNING / INFO
    val category: Category,       // BUG / SIMPLIFY / PERF / SECURITY
    val file: String,             // 文件路径
    val line: Int,                // 行号
    val title: String,            // 标题
    val description: String,      // 详细说明
    val suggestion: String,       // 建议修复代码
    val confidence: Int           // 1-10
)

enum class Severity { CRITICAL, WARNING, INFO }
enum class Category { BUG, SIMPLIFY, PERF, SECURITY }
```

### FileChange

```kotlin
data class FileChange(
    val path: String,
    val status: String,           // added/modified/deleted/renamed
    val hunks: List<Hunk>,
    val isBinary: Boolean
)

data class Hunk(
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val lines: List<String>
)
```

## 流程设计

### /review 核心流程

```
输入: git diff (分支/选中代码/文件)
    ↓
DiffCollector.parse(diff)
    → List<FileChange>
    ↓ (过滤: 跳过二进制/空文件/>10000行截断)
    ↓
ReviewAnalyzer.analyze(fileChanges)
    → 每个 hunk 逐行 LLM 分析
    → 四维度检测器规则集并行运行
    ↓ (合并+去重+置信度排序)
    ↓
ReviewResult { findings, score }
    ↓
输出: Markdown 报告（按严重度分组，含行号+代码片段+修复建议）
```

### /review --fix

```
/review 流程 → findings
    ↓
FixApplier.apply(findings.filter { severity==CRITICAL || confidence>=8 })
    → 逐条通过 Edit 工具应用 suggestion
    → 报告修复结果 [已修复N条, 需手动处理M条]
```

### /review --comment

```
/review 流程 → findings
    ↓
CommentFormatter.format(findings)
    → GitHub/GitLab PR review comment 格式
    → 输出可直接粘贴的评论
```

### /diff 流程

```
git diff --stat → 变更摘要
    ↓
git diff → 每个文件的 hunk 预览（前10行）
    ↓
不调用 LLM，纯本地执行
```

### /test 流程

```
输入: /test 命令
    ↓
执行: ./gradlew test (通过 ProcessBuilder)
    ↓
解析输出:
    ├── BUILD SUCCESSFUL → 摘要：N tests passed, 0 failed
    └── BUILD FAILED   → 提取失败测试名 + 错误消息 + 堆栈
    ↓
结果展示:
    ├── 全部通过 → 聊天 Bubble "✅ 全部 N 个测试通过"
    └── 有失败   → 列出失败测试名 + 错误消息 + 堆栈（仅报告，不修复）
```

### /fix 流程

```
输入: /fix 命令（或 /test 失败后用户手动调用）
    ↓
收集上下文:
    ├── 上次 /test 的失败输出（缓存在 ViewModel 中）
    ├── 相关源文件（根据失败测试的堆栈定位）
    └── 当前 git diff（如果有变更）
    ↓
发 LLM: "以下测试失败，分析根因并修复代码：<test>: <error>"
    ↓
LLM 通过 Edit 工具直接修复源文件
    ↓
修复后自动重新运行 /test 验证
```

### /security-review 流程

```
输入: diff 或目标文件
    ↓
SecurityReviewEngine.analyze(diff)
    ├── InjectionScanner    — 正则模式匹配 + LLM 上下文分析
    ├── SecretDetector      — 模式匹配 (token/apikey/password=)
    ├── PermissionAnalyzer  — canonicalPath/chmod/路径遍历
    └── DependencyChecker   — build.gradle.kts 版本 CVE 查询
    ↓
输出: 安全报告（按维度分组，CVSS 评分）
```

## 斜杠命令

### /review

```
/review                  → 审查当前分支变更（git diff main...HEAD）
/review --fix            → 审查 + 自动修复
/review --comment        → 审查 + 输出 PR 评论格式
/review --file Foo.kt    → 审查指定文件
```

### /diff

```
/diff                    → 展示分支变更摘要（统计+文件列表+hunk预览）
/diff --stat             → 仅统计
/diff <file>             → 指定文件 diff
```

### /test

```
/test                    → 运行 ./gradlew test，解析结果并展示（仅报告，不修复）
/test --file FooTest.kt  → 运行指定测试类
/test --method testName  → 运行指定测试方法
```

### /fix

```
/fix                    → 分析上次 /test 的失败输出，调 LLM 定位根因并修复代码
/fix --retry            → 修复后自动重新运行 /test 验证
```

### /security-review

```
/security-review         → 安全五维度审查当前分支变更
/security-review --file  → 审查指定文件
```

## IDE 集成

### ReviewResultPanel

- ToolWindow 内嵌面板（JBPanel），位于聊天区下方或侧边
- 展示审查 Findings 列表（可折叠/展开）
- 每条 Finding: 严重度图标 + 标题 + 文件:行号（点击跳转）+ "修复"/"忽略"/"展开" 按钮
- 顶部：评分徽章 + 修复进度条（N/M 已处理）

### ReviewAnnotationGutter

- IntelliJ EditorGutterIconProvider 扩展
- 在编辑器 gutter 中标注 🔴🟡🔵 图标
- 悬停显示 Finding 详情
- 点击跳转到审查结果面板对应条目

### ReviewContextMenu

- EditorPopupMenu 扩展
- "审查选中代码" — 将选中文本作为 diff 输入
- "安全审查当前文件" — 直接审查打开的文件
- "审查分支变更" — 等同于 `/review`

## 边界条件 & 故障处理

| 场景 | 处理 |
|------|------|
| diff 为空 | 返回"无变更可审查" |
| diff >10000 行 | 截断为前 10000 行 + 提示 |
| 包含二进制文件 | DiffCollector 检测 `Binary files differ`，自动跳过 |
| 不在 git 仓库 | 降级到"审查选中代码"模式 |
| LLM 返回格式错误 | JSON schema 约束 + 回退到纯文本报告 |
| --fix 应用失败 | 跳过 + 标注"需手动修复" |
| 右键无选中代码 | 降级为审查整个文件 |
| 依赖 CVE 查询失败 | 跳过维度 + 提示网络不可用 |

## 测试

| 层级 | 对象 | 内容 |
|------|------|------|
| 单元 | DiffCollector | 解析 git diff 输出（空/单文件/多文件/二进制/rename） |
| 单元 | ReviewAnalyzer | 内置检测规则集的正确性 |
| 单元 | SecurityDetector | 密钥模式匹配 + 注入向量检测正/负样本 |
| 单元 | FixApplier | suggestion → Edit 工具调用映射 |
| 单元 | CommentFormatter | PR 评论格式生成 |
| 集成 | ReviewEngine | 端到端：给定 diff → 输出 Findings |
| 集成 | SecurityReviewEngine | 给定已知漏洞代码 → 报告所有维度 |
| 集成 | /diff 命令 | git diff 统计 + 文件列表格式 |

## 新增文件

| 文件 | 职责 |
|------|------|
| `review/ReviewEngine.kt` | 审查引擎入口 |
| `review/DiffCollector.kt` | diff 收集+解析 |
| `review/ReviewAnalyzer.kt` | 四维度分析协调 |
| `review/detectors/BugDetector.kt` | 正确性检测 |
| `review/detectors/SimplifyDetector.kt` | 简化检测 |
| `review/detectors/PerfDetector.kt` | 性能检测 |
| `review/detectors/SecurityDetector.kt` | 安全检测 |
| `review/FixApplier.kt` | --fix 自动修复 |
| `review/CommentFormatter.kt` | --comment PR 格式 |
| `security/SecurityReviewEngine.kt` | 安全审查引擎 |
| `security/InjectionScanner.kt` | 注入向量扫描 |
| `security/SecretDetector.kt` | 密钥检测 |
| `security/PermissionAnalyzer.kt` | 权限分析 |
| `security/DependencyChecker.kt` | 依赖漏洞 |
| `commands/ReviewCommands.kt` | 斜杠命令实现 (/review /diff /test /security-review /fix) |
| `commands/TestRunner.kt` | /test + /fix 实现：gradlew test 执行 + 输出解析 + 失败缓存 + LLM 修复 |
| `ui/ReviewResultPanel.kt` | 审查结果面板 |
| `ui/ReviewAnnotationGutter.kt` | IDE diff gutter 标注 |
| `ui/ReviewContextMenu.kt` | 右键菜单 |
| `actions/ReviewSelectedCodeAction.kt` | 选中代码审查 |
