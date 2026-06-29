# 代码自动补全（FIM Completion）

基于 DeepSeek FIM（Fill-in-the-Middle）API 的智能代码补全功能。当用户在编辑器中输入代码时，插件自动采集上下文并请求
AI 生成补全候选。

## 一、核心流程

```
用户输入（DocumentChange 事件）
  │
  ▼
InlineCompletionProvider.isEnabled()
  ├── 检查 AppSettingsService.isCompletionEnabled()
  └── 禁用时直接跳过
  │
  ▼
getSuggestion() [suspend 协程]
  │
  ├── 1. CompletionContextCollector.collect()
  │     ├── ReadAction 内读取 Editor Document
  │     │     ├── prefix = 光标前全部文本
  │     │     └── suffix = 光标后全部文本
  │     ├── PsiCompletionStrategy.collectContext()（当前仅 PHP）
  │     │     ├── 反射加载 PHP PSI → 函数签名 + use 语句
  │     │     └── 体积小、信息密度高，始终采集
  │     ├── ContextEnhancer.findBestSiblingFiles()
  │     │     ├── 从已打开文件中找同目录、同扩展名的兄弟文件
  │     │     ├── Jaccard 相似度排序（基于 import 行）
  │     │     ├── Top-5 兄弟文件内容作为 smartContext
  │     │     └── 仅当 prefix+suffix < 8K 时触发
  │     ├── 预算层合并（PSI 优先，smartContext 填充剩余空间）
  │     │     ├── 总上限 16,384 字符
  │     │     ├── prefix 占 2/3, suffix 占 1/3
  │     │     └── 增强上下文 = PSI（前）+ smartContext（后）
  │
  ├── 2. 缓存检查 CompletionCache
  │     ├── 匹配: SHA-256(prefix[-200:] + "|" + suffix[:200])
  │     ├── TTL: 60 秒
  │     └── 手动触发时跳过缓存
  │
  ├── 3. 构建 FIM Prompt（缓存未命中时）
  │     ├── // File: <fileName>
  │     ├── // Language: <language>
  │     ├── <smartContext>（PSI 语义 + 兄弟文件风格，预算层合并）
  │     └── <prefix>
  │
  ├── 4. DeepSeekFimClient.complete(prompt, suffix)
  │     ├── POST https://api.deepseek.com/beta/completions
  │     ├── 非流式 HTTP POST
  │     ├── 指数退避重试: 200ms → 400ms（最多 2 次）
  │     └── 4xx 不重试
  │
  ├── 5. CompletionPostProcessor.process()
  │     ├── 丢弃 content_filter 候选
  │     ├── 裁剪 suffix 开头重叠
  │     ├── 裁剪 prefix 结尾重叠
  │     ├── finish_reason="length" → 截断到最后一个完整行
  │     └── 去重 + 过滤空白候选
  │
  ├── 6. 写入缓存（非手动触发 && 候选不空）
  │
  ├── 7. CompletionStats.recordShown() 记录统计
  │
  └── 8. 构建 InlineCompletionSuggestion → 返回给 IDE 显示
```

## 二、关键类

| 类名                           | 文件                                         | 职责                                                       |
|------------------------------|--------------------------------------------|----------------------------------------------------------|
| `AiCompletionProvider`       | `completion/AiCompletionProvider.kt`       | 实现 `InlineCompletionProvider`，插件主入口                      |
| `CompletionContextCollector` | `completion/CompletionContextCollector.kt` | 上下文采集：prefix/suffix + PSI 语义 + 兄弟文件风格，预算层合并              |
| `CompletionContext`          | `completion/CompletionContextCollector.kt` | 数据类：携带 prefix、suffix、language、fileName、smartContext（合并后） |
| `DeepSeekFimClient`          | `completion/DeepSeekFimClient.kt`          | FIM API 客户端：POST `/beta/completions`，OkHttp + 重试         |
| `FimRequest`                 | `completion/DeepSeekFimClient.kt`          | API 请求体：model、prompt、suffix、maxTokens、temperature        |
| `FimResponse`                | `completion/DeepSeekFimClient.kt`          | API 响应体：choices、usage                                    |
| `FimChoice`                  | `completion/DeepSeekFimClient.kt`          | 单个候选：text、index、finishReason                             |
| `CompletionCache`            | `completion/CompletionCache.kt`            | LRU 缓存：SHA-256 Key，TTL 60s，最大 20 条                       |
| `CompletionPostProcessor`    | `completion/CompletionPostProcessor.kt`    | 后处理器：丢弃 content_filter、裁剪重叠、截断 incomplete、去重、过滤空白        |
| `CompletionStats`            | `completion/CompletionStats.kt`            | 单例统计：显示/接受次数、延迟、按语言细分、JSON 持久化                           |
| `ContextEnhancer`            | `completion/ContextEnhancer.kt`            | 兄弟文件增强：Jaccard 相似度，仅从已打开标签页选取                            |
| `PsiCompletionStrategy`      | `completion/PsiCompletionStrategy.kt`      | PHP PSI 上下文：函数签名 + use 语句（反射，可选依赖）                       |
| `CharBudgetManager`          | `completion/CharBudgetManager.kt`          | 字符预算常量：MAX_CHARS=16384, PREFIX_RATIO=2/3                 |
| `ManualCompletionAction`     | `completion/ManualCompletionAction.kt`     | 手动触发补全：`Cmd+P`(Mac) / `Alt+P`(Win)                       |
| `NextCandidateAction`        | `completion/NextCandidateAction.kt`        | 下一个候选：`↓` 键切换                                            |
| `PrevCandidateAction`        | `completion/PrevCandidateAction.kt`        | 上一个候选：`↑` 键切换                                            |

## 三、FIM API 调用

### 端点

```
POST https://api.deepseek.com/beta/completions
Authorization: Bearer <API Key>
Content-Type: application/json
```

### 请求体

```json
{
  "model": "deepseek-v4-pro",
  "prompt": "// File: UserService.kt\n// Language: Kotlin\n\n<smartContext>\npackage com.example\n\nclass UserService {\n    fun findById(",
  "suffix": "): User? {\n        return null\n    }\n}",
  "max_tokens": 256,
  "temperature": 0.0
}
```

### 参数说明

| 参数            | 类型     | 说明                                                |
|---------------|--------|---------------------------------------------------|
| `model`       | string | 模型名称，从 `AppSettingsService.getModel()` 读取         |
| `prompt`      | string | 光标前的代码 + 上下文增强（文件名、语言、兄弟文件/PSI 信息）                |
| `suffix`      | string | 光标后的代码，FIM 的关键输入                                  |
| `max_tokens`  | int    | 默认 256，范围 1-1024，通过 `getCompletionMaxTokens()` 配置 |
| `temperature` | float  | 固定 0.0，确保确定性输出                                    |

### 网络层

- **HTTP 库**: OkHttp 单例
- **连接池**: 5 个空闲连接，保持 5 分钟
- **超时**: 连接超时 2s，读取超时 3s
- **重试**: 指数退避 200ms → 400ms，最多 2 次。4xx 不重试，网络错误重试
- **HTTP/2**: 启用

### 响应格式

```json
{
  "choices": [
    {
      "text": "id: Long",
      "index": 0,
      "finish_reason": "stop"
    }
  ],
  "usage": {
    "prompt_tokens": 150,
    "completion_tokens": 3,
    "total_tokens": 153
  }
}
```

`finish_reason` 取值：

- `"stop"` — 正常完成
- `"length"` — 达到 max_tokens 上限，后处理器会截断到最后一个完整行
- `"content_filter"` — 内容过滤，候选被丢弃

## 四、上下文增强

### 4.1 兄弟文件增强（全局，任意语言）

`ContextEnhancer` 从已打开的编辑器标签页中查找与当前文件相关的兄弟文件：

1. 筛选条件：同目录 + 同扩展名
2. 提取兄弟文件的 import 行集合
3. 与当前文件的 import 行集合计算 Jaccard 相似度
4. 取 Top-5 相似度最高的兄弟文件
5. 将其内容注入 prompt 作为 `smartContext`

**触发条件**：仅当 prefix + suffix < 8,192 字符时触发，避免在上下文已经足够充分的情况下，额外注入兄弟文件内容导致
prompt 过长、增加 API 延迟。

### 4.2 PHP PSI 增强（仅 PHP 文件）

通过反射加载 PHP PSI 类（`com.jetbrains.php.lang.psi.elements.Function`、`PhpUse`），采集：

1. 光标所在函数/方法的签名（前 500 字符）
2. 文件的 use 语句（最多 10 条）

PHP PSI 为可选依赖——如果 PHP 插件未安装，反射失败时静默跳过，不影响其他语言。

## 五、缓存策略

| 属性     | 值                                           |
|--------|---------------------------------------------|
| 缓存 Key | `SHA-256(prefix[-200:] + "                  |" + suffix[:200])` |
| TTL    | 60 秒                                        |
| 容量     | 20 条（LRU 驱逐）                                |
| 线程安全   | `@Synchronized` 方法级锁                        |
| 跳缓存    | 手动触发（`InlineCompletionEvent.DirectCall`）时跳过 |

同一光标位置的重复输入在 60 秒内直接从缓存返回，避免重复 API 调用。

## 六、后处理（CompletionPostProcessor）

对 API 返回的候选进行逐层处理：

1. **丢弃 content_filter 候选** — `finish_reason="content_filter"` 直接丢弃
2. **裁剪 suffix 开头重叠** — 补全文本末尾与 suffix 开头重复部分删除
3. **裁剪 prefix 结尾重叠** — 补全文本开头与 prefix 末尾重复部分删除
4. **截断 incomplete 候选** — `finish_reason="length"` → 截断到最后一个完整行
5. **过滤空白候选** — 去除空字符串、纯空白、与 suffix 完全相同的候选
6. **去重** — 多个候选的 text 相同时去重

## 七、统计系统

### 内存统计

通过 `CompletionStats` 单例管理，使用 `ConcurrentHashMap` + `AtomicInteger`/`AtomicLong` 确保线程安全：

| 指标             | 说明                            |
|----------------|-------------------------------|
| 显示次数（shown）    | IDE 展示了补全候选的次数                |
| 接受次数（accepted） | 用户 Tab 接受了补全的次数               |
| 平均延迟（ms）       | API 调用的平均耗时                   |
| 按语言细分          | 每种编程语言独立的 shown / accepted 计数 |

### 接受率

```
接受率 = accepted / shown × 100%
```

### 持久化

项目关闭时写入 `<project>/.code-assistant/completion-stats.json`，按天覆盖：

```json
{
  "date": "2026-06-26",
  "totalShown": 150,
  "totalAccepted": 45,
  "averageLatencyMs": 320,
  "byLanguage": {
    "kotlin": {
      "shown": 80,
      "accepted": 25,
      "avgLatencyMs": 310
    },
    "java": {
      "shown": 50,
      "accepted": 15,
      "avgLatencyMs": 340
    },
    "php": {
      "shown": 20,
      "accepted": 5,
      "avgLatencyMs": 350
    }
  }
}
```

## 八、配置项

所有配置统一在 **IDE Settings > Tools > Code Assistant** 中管理（`AppSettingsService` +
`SettingsConfigurable`）：

| 配置项           | 存储方式                  | 默认值               | 说明                     |
|---------------|-----------------------|-------------------|------------------------|
| 启用 AI 代码补全    | `PropertiesComponent` | `true`            | 开关补全功能                 |
| API Key       | `PasswordSafe`        | —                 | DeepSeek API Key       |
| Model         | `PropertiesComponent` | `deepseek-v4-pro` | 可选 `deepseek-v4-flash` |
| 补全 max_tokens | `PropertiesComponent` | 256（范围 1-1024）    | 补全最大 token 数           |

## 九、多行补全缩进处理

多行补全候选插入时，需要保证缩进与当前代码一致：

1. **首行缩进继承**：补全文本的首行继承光标所在行的缩进级别（空格/tab 数量）
2. **后续行缩进调整**：后续每行的缩进以首行为基准做偏移校正——如果首行补全内容比光标前代码多出 N
   个缩进层级，则后续行统一减去 N 个层级
3. **Tab/空格检测**：读取 IDE 的 `CodeStyleSettings` 判断当前文件使用 tab 还是空格缩进
4. **不一致候选降级**：缩进格式与当前文件风格不一致的候选（如 2 空格 vs 4 空格），后处理器会尝试用正则修正，修正失败则降权排在最后

## 十、补全拒绝与负反馈

用户拒绝补全候选（继续输入而非按 Tab 接受）时触发负反馈：

- **缓存失效**：拒绝的候选立即从 `CompletionCache` 中移除，同一光标位置不会再次出现相同补全
- **前缀负反馈**：SHA-256(prefix[-200:] + "|" + suffix[:200]) 加入 `rejectedKeys`（内存 LRU，最大 200
  条），后续
  5 分钟内同一位置返回空或上一个候选的不同结果
- **统计记录**：`CompletionStats.recordRejected()` 记录拒绝，与 `recordAccepted()` 共同计算接受率
- **不跨会话持久化**：拒绝记录仅内存，IDE 重启后清空

> **实现说明：** IntelliJ Platform 的 `InlineCompletionInsertHandler` 只提供 `afterInsertion`
> （接受时触发），不提供 rejection 回调。当前通过监听编辑器 `Document` 变更事件 +
> 记录最近一次补全位置来实现拒绝检测：如果用户在补全显示后的 N 次按键内既未接受补全且改变了文档，视为拒绝。

## 十一、用户交互

### 自动触发

用户正常输入代码时自动触发。触发时机完全由 IntelliJ 平台控制（包括 debounce 延迟、事件类型过滤），插件仅通过
`isEnabled()` 控制开关。触发频率和时机不可由插件自定义。

### 手动触发

快捷键 `Cmd+P`（macOS）/ `Alt+P`（Windows/Linux）手动触发补全。手动触发会跳过缓存，强制请求新候选。

### 候选切换

| 快捷键 | 操作          |
|-----|-------------|
| `↓` | 下一个候选（有候选时） |
| `↑` | 上一个候选（有候选时） |

当有补全候选显示时，`↓`/`↑` 切换候选；无候选时正常移动光标。

### 接受补全

按 `Tab` 接受当前显示的补全候选，IDE 会将候选文本插入光标位置。接受后触发
`CompletionStats.recordAccepted()` 记录统计。

## 十二、已知限制

- 仅支持 DeepSeek FIM API（`/beta/completions`），不支持其他提供商
- 字符预算上限 16,384（含 prefix + suffix + smartContext），超大文件会丢失上下文
- PHP PSI 增强为唯一语言特定增强，其他语言仅依赖兄弟文件
- 缓存 Key 仅基于 prefix 后 200 字符 + suffix 前 200 字符，不感知文件路径
- 缓存 TTL 固定 60 秒，不支持配置
- 延迟目标 <500ms（含重试），网络不稳定时可能超时
