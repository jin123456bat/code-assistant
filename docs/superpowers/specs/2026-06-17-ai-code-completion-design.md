# AI 代码补全功能设计文档

- **日期**: 2026-06-17
- **版本**: 2.0（v1.0 已实现并通过对抗审查修复 13 个 bug）
- **状态**: 迭代中

---

## 1. 需求概述

为 Code Assistant 插件新增 AI 驱动的行内代码补全功能，类似 GitHub Copilot，以幽灵文本（ghost text）形式在编辑器中显示补全建议。

### 1.1 核心需求

| 维度 | 选择 |
|------|------|
| 呈现形式 | 行内幽灵文本（Inline Ghost Text） |
| 触发方式 | 自动触发（防抖）+ 手动快捷键 |
| 与 IDEA 关系 | AI 优先，无建议时回退 IDEA 自带补全 |
| API | DeepSeek FIM API（`/beta/completions`），模型名复用现有设置 |
| 上下文 | 智能上下文（动态收集依赖文件，基于 PSI） |
| 语言支持 | 所有语言通用（纯文本 fallback）+ PHP/JS/HTML/CSS PSI 增强 |
| 补全粒度 | Token → 整行 → 多行，Tab 逐词接受 |
| max_tokens | 默认 1024，范围 1-1024，根据设置值自动适配输入上下文预算 |
| 多候选 | 候选数可配（默认 10，范围 1-10），↑↓ 键切换（快捷键可配） |
| 缓存 | 60s TTL，前 200 + 后 200 字符 hash 做 key，LRU 最大 20 条 |
| 统计 | 本地统计接受率/延迟，设置页展示，重启清零 |
| Endpoint | 内部常量 `https://api.deepseek.com/beta/completions`，不可配置 |

---

## 2. 架构总览

```
plugin.xml 注册
  └── InlineCompletionProvider (IntelliJ 扩展点)
        │
        ├── isEnabled() → CompletionSettings.completionEnabled
        │
        ├── getInlineCompletion(request)
        │     │
        │     ├── 1. TokenBudgetManager
        │     │     └── 根据 userMaxTokens 计算 prefix/suffix/smartContext 预算
        │     │
        │     ├── 2. CompletionContextCollector
        │     │     ├── 核心层（纯文本）: prefix + suffix + 语言检测
        │     │     └── PSI 增强层: 按语言选择策略
        │     │           ├── PhpPsiStrategy
        │     │           ├── JsPsiStrategy
        │     │           ├── HtmlPsiStrategy
        │     │           ├── CssPsiStrategy
        │     │           └── FallbackStrategy（纯文本）
        │     │
        │     ├── 3. DeepSeekFimClient
        │     │     ├── Endpoint: 内部常量 https://api.deepseek.com/beta/completions
        │     │     ├── Model: AppSettingsService.getModel()（复用设置）
        │     │     ├── API Key: AppSettingsService.getApiKey()
        │     │     ├── n: 可配（CompletionSettings.completionNumCandidates，默认 10）
        │     │     ├── Timeout: 连接 2s + 读取 3s，总超时 5s
        │     │     └── 非流式，HTTP POST
        │     │
        │     ├── 4. CompletionCache
        │     │     ├── Key: hash(prefix[-200:] + suffix[:200])
        │     │     ├── TTL: 60s, LRU 最大 20 条
        │     │     ├── 文件变更 → 该文件缓存全部清除
        │     │     └── 手动快捷键 → 跳过缓存
        │     │
        │     └── 5. 后处理 → InlineCompletionResponse
        │           ├── suffix 重叠去重
        │           ├── prefix 开头去重
        │           ├── 完整性/有效性过滤
        │           └── 返回 n 个候选幽灵文本（含元数据用于多候选切换）
        │
        ├── CompletionDebounceManager
        │     ├── 字符/回车/关键字/空格/小粘贴 → 300ms 防抖后触发
        │     ├── 手动快捷键 → 立即触发
        │     └── 新输入 → 取消进行中请求
        │
        └── CompletionStats
              ├── 显示次数 / 接受次数 / 接受率 / 平均延迟
              └── 存内存，重启清零，设置页展示
```

### 2.1 新增文件清单

| 文件 | 路径 | 职责 | 估算行数 |
|------|------|------|---------|
| CompletionProvider | `src/main/kotlin/com/aiassistant/completion/CompletionProvider.kt` | InlineCompletionProvider 实现 | ~200 |
| CompletionContextCollector | `.../completion/CompletionContextCollector.kt` | 上下文采集（核心层 + PSI 增强层） | ~300 |
| DeepSeekFimClient | `.../completion/DeepSeekFimClient.kt` | DeepSeek FIM API HTTP 客户端 | ~150 |
| CompletionDebounceManager | `.../completion/CompletionDebounceManager.kt` | 防抖 + 请求生命周期管理 | ~80 |
| TokenBudgetManager | `.../completion/TokenBudgetManager.kt` | 输入上下文预算分配 | ~60 |
| PsiCompletionStrategy | `.../completion/PsiCompletionStrategy.kt` | PSI 策略接口及各语言实现 | ~200 |
| CompletionCache | `.../completion/CompletionCache.kt` | 补全结果缓存（LRU + TTL） | ~80 |
| CompletionStats | `.../completion/CompletionStats.kt` | 本地统计数据收集 | ~60 |

### 2.2 修改文件

| 文件 | 改动 |
|------|------|
| `plugin.xml` | 注册 `completion.contributor` 或 `inlineCompletionProvider` 扩展点 |
| `AppSettingsService.kt` | 添加补全设置字段（`completionEnabled`, `completionMaxTokens`, `completionDebounceMs`, `completionNumCandidates`, `completionManualShortcut`, `completionPrevCandidateKey`, `completionNextCandidateKey`） |
| `SettingsConfigurable.kt` | 添加代码补全设置区域 + 统计卡片 |

---

## 3. 模块设计

### 3.1 TokenBudgetManager

**职责**：根据用户的 `maxTokens` 设置，动态分配 prefix/suffix/smartContext 的输入预算。

**算法**：
```
总上限 = 16K tokens (DeepSeek FIM 上下文窗口)
可用输入 = 16K - userMaxTokens
字符估算 = tokens × 4 (近似，按英文平均 4 chars/token)

分配比例:
  prefix:     50%
  suffix:     25%
  smartContext: 25%
```

**约束**：
- 不超过分配预算
- 不超过文件实际长度（不会凭空填满）
- userMaxTokens 越大 → 输入预算越小（动态适配）

### 3.2 CompletionContextCollector

**职责**：从编辑器采集补全所需的上下文信息。

**数据模型**：
```kotlin
data class CompletionContext(
    val prefix: String,          // 光标前代码
    val suffix: String,          // 光标后代码
    val language: String,        // 本地语言标识（不传 API），用于 PSI 策略选择
    val fileName: String,        // 文件名
    val smartContext: String?,   // PSI 增强上下文（可为 null）
)
```

#### 3.2.1 核心层（纯文本，所有语言）

- **prefix**：光标前文本，上限由 TokenBudgetManager 分配
- **suffix**：光标后文本，上限由 TokenBudgetManager 分配
- **language**：通过文件扩展名映射
  ```
  .php → php
  .js/.ts/.jsx/.tsx → javascript (typescript 统一到 javascript)
  .html/.htm → html
  .css/.scss/.less → css
  其他 → 文件扩展名原样
  ```

#### 3.2.2 PSI 增强层（可选，按语言分策略）

```kotlin
fun selectStrategy(language: String): PsiCompletionStrategy = when (language) {
    "php" -> PhpPsiStrategy()
    "javascript", "typescript" -> JsPsiStrategy()
    "html", "xml" -> HtmlPsiStrategy()
    "css", "scss", "less" -> CssPsiStrategy()
    else -> FallbackStrategy()  // 纯文本，不做 PSI 增强
}
```

各策略负责收集：
- 光标所在函数/类/方法边界（取声明头 + 参数）
- import 行解析 → resolve 到依赖文件 → 读公开方法签名
- 同级文件风格参考

### 3.3 DeepSeekFimClient

**职责**：封装 DeepSeek FIM API 调用。

**Endpoint**：`https://api.deepseek.com/beta/completions`

**Request Payload**：
```json
{
    "model": "<AppSettingsService.getModel()>",
    "prompt": "<prefix + smartContext 拼接>",
    "suffix": "<光标后代码>",
    "max_tokens": "<CompletionSettings.completionMaxTokens>",
    "n": "<CompletionSettings.completionNumCandidates>",
    "temperature": 0.0,
    "stop": ["\n\n\n"],
    "stream": false
}
```

**超时与重试**：
| 配置 | 值 | 说明 |
|------|------|------|
| 连接超时 | 2s | OkHttp connectTimeout |
| 读取超时 | 3s | OkHttp readTimeout |
| 重试 | 最多 1 次 | 仅网络错误，非 4xx 状态码 |
| 总超时 | 5s | 超时则取消，不阻塞用户 |

**API Key 来源**：`AppSettingsService.getApiKey()`（与 Agent 复用）

**请求取消**：用户继续输入时调用 `OkHttp Call.cancel()` 取消进行中请求

**响应解析**：
```json
{
    "id": "...",
    "object": "text_completion",
    "choices": [
        {
            "text": "<候选 1>",
            "index": 0,
            "finish_reason": "stop"
        },
        {
            "text": "<候选 2>",
            "index": 1,
            "finish_reason": "stop"
        }
    ],
    "usage": {
        "prompt_tokens": ...,
        "completion_tokens": ...
    }
}
```

### 3.4 后处理（Post-Processing）

**职责**：对模型返回的所有候选做清理和验证。

**处理流程**（每个候选独立执行）：
```
rawText (choices[i].text)
  ├── 1. suffix 重叠裁剪（最长公共前缀匹配）
  ├── 2. prefix 开头重叠裁剪（去开头的重复）
  ├── 3. 有效性过滤:
  │      ├── 纯空白/空行 → 标记无效
  │      ├── 去重后 < 3 字符 → 标记无效
  │      ├── 完全等于 suffix 片段 → 标记无效
  │      ├── 与前面候选完全重复 → 标记无效（去重）
  │      ├── finish_reason == "content_filter" → 标记无效
  │      └── finish_reason == "length" → 截断到最后一个完整行
  └── 4. 收集所有有效候选 → 返回 InlineCompletionResponse
```

### 3.5 多候选切换

**请求**：`n` 由用户设置 `completionNumCandidates`（默认 10，范围 1-10），API 返回多个候选。

**默认显示**：候选 0 在最前面。

**切换**（快捷键可配置）：

| 默认按键 | 行为 |
|---------|------|
| `↓` | 切换到下一个候选 |
| `↑` | 切换到上一个候选 |
| `Tab` | 接受当前显示的候选 |
| `Esc` | 取消所有补全 |

**优先级**：当幽灵文本显示时，切换候选键优先于正常光标移动。无幽灵文本时，恢复为正常行为。

**循环**：到末尾再按 `↓` 回到候选 0；在候选 0 按 `↑` 跳到末尾。

**候选元数据**：
```kotlin
data class CompletionCandidate(
    val text: String,
    val index: Int,          // choices[i].index
    val finishReason: String
)
```

### 3.6 CompletionCache

**职责**：避免同一位置重复请求。

```
Cache Key: hash(prefix[-200:] + suffix[:200])
  ↓
命中（且未过期）→ 直接返回缓存结果，跳过 API 请求
未命中 → 请求 API → 后处理 → 有效候选存入缓存
```

| 参数 | 值 | 说明 |
|------|------|------|
| TTL | 60s | 超时自动清除 |
| 最大条目 | 20 | LRU 淘汰 |
| Key 粒度 | 前 200 + 后 200 字符 hash | 宽松匹配，避免位置微小变化导致 miss |
| 失效 | 文件内容变更（DocumentListener） | 该文件缓存全部清除 |
| 跳过 | 手动快捷键触发 | 强制请求，不使用缓存 |

### 3.7 CompletionStats

**职责**：本地统计，存内存，不上报。

| 指标 | 说明 |
|------|------|
| `totalShown` | 补全幽灵文本出现次数 |
| `totalAccepted` | 用户按 Tab 接受次数 |
| 接受率 | `totalAccepted / totalShown * 100%` |
| `totalLatencyMs` | 请求发起到显示的时间累计 |
| 平均延迟 | `totalLatencyMs / totalShown` |

**收集时机**：
- `onShown()` — 幽灵文本渲染时 +1
- `onAccepted()` — 用户 Tab 接受时 +1
- `onCancelled()` — 用户 Esc 取消时，不计入 accepted

**展示**：设置页面底部卡片，含「重置统计」按钮。

**生命周期**：存内存，重启 IDE 后清零。

### 3.8 CompletionDebounceManager

**职责**：管理补全请求的触发时机和生命周期。

**防抖延迟**：默认 300ms，范围 100-2000ms（用户可配置）。

**触发规则**：

| 触发补全 | 示例 |
|---------|------|
| 字符输入（字母、数字、符号） | `f`, `$`, `(` |
| 回车 | 换行后写新代码 |
| 粘贴 ≤ 50 字符 | 小段粘贴 |
| 手动快捷键 | 立即触发，跳过防抖 |
| 关键字后（PSI 感知） | 输入 `function`、`class`、`if`、`return` 等 |
| 空格后（PSI 感知） | 排除缩进空格、注释/字符串内空格 |

| 忽略（不触发） | 原因 |
|---------------|------|
| 光标移动（↑↓←→、鼠标点击） | 没改代码 |
| 纯删除（Backspace/Delete） | 等用户确认后再写 |
| 粘贴 > 50 字符 | 大段粘贴后通常还需调整 |
| 撤销/重做 | 等用户停顿 |
| 连续空格/缩进 | 无意义触发 |
| 注释/字符串内的空格和关键字 | PSI 检测排除 |

**生命周期**：
```
用户输入 "f" → 开始 300ms timer
用户输入 "o" → 重置 timer
用户输入 "o" → 重置 timer
timer 到期   → 发起请求
请求中，用户输入 "(" → cancel 当前请求，重新开始 timer
timer 到期   → 新请求
```

---

## 4. 设置项

### 4.1 用户可见设置

| 设置项 | 字段名 | 默认值 | 范围 | UI 提示 |
|--------|--------|--------|------|---------|
| 补全开关 | `completionEnabled` | `true` | — | 关闭后恢复 IDEA 原生补全 |
| 最大补全长度 | `completionMaxTokens` | `1024` | 1 - 1024 | 「值越大延迟越高，建议不超过 1024」 |
| 防抖延迟 | `completionDebounceMs` | `300` | 100 - 2000 (ms) | 「输入停止后等待多久触发补全」 |
| 候选数量 | `completionNumCandidates` | `10` | 1 - 10 | 「每次请求返回的候选数，越大延迟可能越高」 |
| 手动触发快捷键 | `completionManualShortcut` | Mac: `Cmd+P` / Win: `Alt+P` | 用户可自定义 | 冲突时提示并拒绝设置 |
| 向上切换候选 | `completionPrevCandidateKey` | `↑` | 用户可自定义 | |
| 向下切换候选 | `completionNextCandidateKey` | `↓` | 用户可自定义 | |

**模型**：复用 `AppSettingsService.getModel()`，不单独设置。

**API Key**：复用 `AppSettingsService.getApiKey()`。

**FIM Endpoint**：内部常量 `https://api.deepseek.com/beta/completions`，不在设置页显示。

### 4.1.1 快捷键冲突处理

设置快捷键时检测与 IDE 已有快捷键的冲突：

```
用户输入快捷键 → 遍历当前 Keymap 已注册的快捷键
  ├── 无冲突 → 保存
  └── 有冲突 → 弹框提示冲突信息（被哪个 Action 占用），拒绝设置
```

冲突提示示例：
```
⚠️ 快捷键冲突
"Cmd+P" 已被 "Print" 占用。
请使用其他快捷键。
[确定]
```

所有三个可配快捷键（手动触发、向上切换、向下切换）均做冲突检测。

### 4.2 内部常量（不可配置）

| 常量 | 值 | 说明 |
|------|------|------|
| `FIM_ENDPOINT` | `https://api.deepseek.com/beta/completions` | FIM API 地址 |
| `CACHE_TTL_MS` | 60_000 | 缓存过期时间 |
| `CACHE_MAX_SIZE` | 20 | 最大缓存条目 |
| `MAX_RETRIES` | 1 | 网络错误最大重试 |
| `CONNECT_TIMEOUT_MS` | 2_000 | 连接超时 |
| `READ_TIMEOUT_MS` | 3_000 | 读取超时 |

### 4.3 统计展示

设置页面底部卡片：

```
┌─ 补全统计 ────────────────────────────────┐
│  本次会话:                                  │
│  显示: 128   接受: 89   接受率: 69.5%       │
│  平均延迟: 320ms                            │
│                                             │
│  [重置统计]                                 │
└─────────────────────────────────────────────┘
```

统计存内存，重启 IDE 后清零。

---

## 5. plugin.xml 扩展点

```xml
<extensions defaultExtensionNs="com.intellij">
    <!-- 代码补全 -->
    <completion.contributor
        id="AiAssistantCompletion"
        implementationClass="com.aiassistant.completion.CompletionProvider"
        language="any"/>
</extensions>
```

或者如果使用内联补全 API：
```xml
<inlineCompletionProvider
    id="AiAssistantInlineCompletion"
    implementation="com.aiassistant.completion.CompletionProvider"/>
```

具体扩展点名称以 IntelliJ Platform 2023.3 版本的实际 API 为准。

---

## 6. 外部依赖与限制

### 6.1 外部依赖
- 无需新增 Gradle 依赖，复用现有 OkHttp（Anthropic Java SDK 内置）

### 6.2 IDE 版本要求
- IntelliJ Platform 2023.3+（InlineCompletionProvider API）

### 6.3 模型依赖
- DeepSeek V4 Pro 支持 `/beta/completions` FIM 端点
- 多候选依赖 `n` 参数（DeepSeek FIM API 支持）

---

## 7. v2.0 改进（对比 Copilot 复盘后新增）

### 7.1 改进 1：增强上下文收集

**现状**：仅取同目录 1 个兄弟文件（按字母序首个），文件路径/语言等元信息未传入 prompt。

**改进**：
- **邻近文件选择**：取同目录下最多 5 个同扩展名文件，用 Jaccard 相似度（基于 import/use 行）排序，选最相关的前 N 个
- **打开标签页信号**：利用 `FileEditorManager.getOpenFiles()` 获取最近打开的文件，优先选取已打开的标签页文件（权重 ×2）
- **文件路径元信息**：在 prompt 中以注释形式嵌入文件路径，帮助模型理解上下文
  ```
  // File: src/Service/UserService.php
  // Project: my-app
  <?php
  namespace App\Service;
  ...
  ```

### 7.2 改进 2：候选导航 Action 注册

**现状**：`AppSettingsService` 中已配置 `PrevCandidateKey`（UP）/ `NextCandidateKey`（DOWN），但 plugin.xml 中未注册对应 Action，用户配置的快捷键不生效。

**改进**：
- 在 plugin.xml 中注册 `AiAssistant.NextCandidate` 和 `AiAssistant.PrevCandidate` Action
- 默认快捷键：`↓`（下一个）、`↑`（上一个）
- 通过 IntelliJ `InlineCompletionHandler` API 切换候选
- 幽灵文本显示时，快捷键优先于光标移动；无幽灵文本时恢复为正常光标移动

### 7.3 改进 3：网络层优化

**现状**：`HttpURLConnection` 每次新建连接（无连接池），连接超时 2s/读取 3s（偏激进），仅 1 次重试。

**改进**：
- 从 `HttpURLConnection` 迁移到 OkHttp（复用 Anthropic Java SDK 内置的 OkHttp 依赖）
- 启用连接池（默认 5 个空闲连接，保活 5 分钟）和 HTTP/2 多路复用
- 超时调整：连接 5s、读取 10s（对齐 Copilot 的宽松策略）
- 指数退避重试：2 次重试，间隔 200ms → 400ms → 800ms
- 使用 OkHttp `Dispatcher` 管理请求优先级

### 7.4 改进 4：遥测持久化与统计 Dashboard

**现状**：`CompletionStats` 统计存内存，重启 IDE 清零，设置页无 Dashboard。

**改进**：
- **持久化**：每日/每周统计写入项目 `.claude/completion-stats.json`
- **按语言维度**：分语言统计接受率（PHP/JS/HTML/CSS 各自独立）
- **拒绝原因分类**：记录用户取消时的前缀片段（脱敏后的 hash），分析高频拒绝模式
- **设置页 Dashboard**：
  ```
  ┌─ 补全统计 ──────────────────────────────────┐
  │  本次会话:                                    │
  │  显示: 128   接受: 89   接受率: 69.5%        │
  │  平均延迟: 320ms                              │
  │                                               │
  │  按语言:                                      │
  │  PHP:     53/80  (66.3%)  平均 290ms         │
  │  JS:      25/34  (73.5%)  平均 340ms         │
  │  HTML:    8/10   (80.0%)  平均 260ms         │
  │  CSS:     3/4    (75.0%)  平均 310ms         │
  │                                               │
  │  近 7 日:  显示: 1847  接受: 1254 (67.9%)   │
  │                                               │
  │  [重置统计]                                   │
  └───────────────────────────────────────────────┘
  ```

### 7.5 改进 5：Prompt 结构化

**现状**：prompt 是 `smartContext + "\n" + prefix` 的纯文本拼接。

**改进**：添加文件路径/语言等元信息标记，对齐 Copilot 风格：
```
// File: src/Service/UserService.php
// Language: php
// Project: my-app
<?php
namespace App\Service;
...
[光标前代码]
<fim_suffix>
[光标后代码]
```

FIM API 中 `prompt` 和 `suffix` 分别传输，`suffix` 不需要 `<fim_suffix>` 标记（API 字段本身就是分隔）。所以实际 prompt 结构为：

```
// File: {fileName}
// Language: {language}

{smartContext}

{prefix}
```

---

## 8. 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| 1.0 | 2026-06-17 | 初版设计，实现核心功能 |
| 1.1 | 2026-06-18 | 对抗审查，修复 13 个 bug |
| 2.0 | 2026-06-18 | 对比 Copilot 复盘，新增 5 项改进 |
