# API 错误码处理策略

> 本文档描述 Code Assistant 调用 DeepSeek API 时的错误分类与处理策略，涵盖 Agent、Completion、Git
> Message 三个模块。

## 一、HTTP 客户端配置

### Agent 模式

```kotlin
AnthropicOkHttpClient.builder()
    .baseUrl("https://api.deepseek.com/anthropic")
    .apiKey(apiKey)
    .build()
```

使用 Anthropic Java SDK 内置的 OkHttp 客户端，SDK 自动处理连接池、重试、超时。

### Completion 模式

```kotlin
// OkHttp 单例配置
- 连接池: 5 个空闲连接，保持 5 分钟
- 连接超时: 2s
- 读取超时: 3s
- HTTP/2: 启用
```

### Git Message 模式

```
- POST https://api.deepseek.com/v1/chat/completions
- 连接超时: 15s
- 读取超时: 60s
- 流式: SSE (stream=true)
```

## 二、Agent 模式错误处理

### 2.1 异常分类和处理策略

```kotlin
try {
    client.beta().messages().createStreaming(builder.build()).use { stream ->
        stream.stream().forEach { event -> /* 正常处理 */ }
    }
} catch (e: Exception) {
    when {
        // 429 Rate Limit — 自动重试（PAUSED 状态等待后恢复）
        e is RateLimitException -> {
            if (retryCount < 2) {
                retryCount++
                val waitSec = parseWaitSeconds(e.message) ?: 30L
                session.pause()
                Thread.sleep(waitSec * 1000)
                session.resume()
                continue  // 重试当前 turn
            } else {
                session.pause()
                return Result.Error("429 Rate Limit 重试耗尽")
            }
        }

        // 5xx 服务器错误 — 退避重试（1s → 3s → 9s，最多 3 次）
        e is InternalServerException -> {
            val backoffDelays = longArrayOf(1_000L, 3_000L, 9_000L)
            if (serverErrorRetryCount < 3) {
                Thread.sleep(backoffDelays[serverErrorRetryCount])
                serverErrorRetryCount++
                continue  // 重试当前 turn
            } else {
                session.markError("5xx — 退避重试耗尽")
                return Result.Error("服务器错误: ${e.message}")
            }
        }

        // 网络超时 — 退避重试（2s → 5s → 10s，最多 3 次）
        e is SocketTimeoutException -> {
            val backoffDelays = longArrayOf(2_000L, 5_000L, 10_000L)
            if (timeoutRetryCount < 3) {
                Thread.sleep(backoffDelays[timeoutRetryCount])
                timeoutRetryCount++
                continue  // 重试当前 turn
            } else {
                output.append("\n[连接中断]")
                session.markError("连接中断: ${e.message}")
                return Result.Error("连接中断: ... 已接收文本: ${output.take(500)}")
            }
        }

        // 400 context too long — 强制 compact 后重发
        e is BadRequestException -> {
            if (isContextTooLong(e.message)) {
                if (compactIfNeeded()) {
                    continue  // compact 成功后重试
                } else {
                    return Result.Error("上下文过大，请使用 /clear 或 /new")
                }
            } else {
                return Result.Error("请求参数错误: ${e.message}")
            }
        }

        // IO 流中断 — 保留已接收文本，直接返回错误
        e is IOException -> {
            output.append("\n[连接中断]")
            session.markError("连接中断: ${e.message}")
            return Result.Error("连接中断: ... 已接收文本: ${output.take(500)}")
        }

        // 其他异常
        else -> {
            session.markError(e.message ?: "Unknown error")
            return Result.Error(e.message ?: "Unknown error")
        }
    }
}
```

### 2.2 错误处理决策表

| HTTP 状态码 | 异常类型                                    | 处理策略                              | 重试次数               | 用户感知                                   |
|----------|-----------------------------------------|-----------------------------------|--------------------|----------------------------------------|
| 429      | `RateLimitException`                    | 解析 Retry-After → PAUSED → 等待 → 重试 | 最多 2 次             | 进入 PAUSED 自动静默重试，2 次后 PAUSED + 建议降并发   |
| 5xx      | `InternalServerException`               | 退避重试：1s → 3s → 9s                 | 最多 3 次             | 自动静默重试，3 次后 ERROR                      |
| 超时       | `SocketTimeoutException`                | 退避重试：2s → 5s → 10s                | 最多 3 次             | 3 次均失败 → ERROR，已渲染内容 + `[连接中断]`        |
| 400      | `BadRequestException`（context too long） | 不重试，强制 compact 后重发                | 0（compact 后重试 1 次） | compact 成功则自动重发；仍失败 → 提示 /clear 或 /new |
| 400（其他）  | `BadRequestException`                   | 不重试，直接返回错误                        | 0                  | 错误消息显示在气泡中                             |
| IO 异常    | `IOException`                           | 保留已接收文本 + `[连接中断]` 标注 → 直接返回错误    | 0                  | 已渲染内容保留，含 `[连接中断]` 标注                  |

### 2.3 Rate Limit 等待时间解析

```kotlin
val waitSec = try {
    e.message?.let { msg ->
        Regex("(\\d+)\\s*(?:seconds|秒)").find(msg)?.groupValues?.get(1)?.toLongOrNull()
    } ?: 10L  // 解析失败默认等 10 秒
} catch (_: Exception) {
    10L
}
```

> 正则匹配英文 `"429 Too Many Requests. Please retry after 30 seconds"` 和中文 `"请 30 秒后重试"`。

### 2.4 流式中断处理

```
用户主动停止（⏹按钮）:
  → cancelled = true
  → client.close()（取消 HTTP 请求）
  → destroyForcibly() 遍历 session.runningProcesses
  → 已累积的 BetaMessageAccumulator 内容持久化
  → streamingBuf 未 flush 内容丢弃
  → 已解析但未执行的 tool call → CANCELLED，不持久化
  → 状态恢复 IDLE

被动断连（SocketTimeoutException）:
  → 已渲染内容保留 + 持久化
  → 尾部标注 [连接中断]
  → 用户可点击 [重试] 发送相同 params 重新开始当前 turn
```

## 三、Completion 模式错误处理

### 3.1 重试策略

```
POST https://api.deepseek.com/beta/completions
├── 成功 → 后处理 → 返回候选
├── 4xx → 不重试，静默跳过（无候选）
├── 5xx / 网络错误 → 指数退避重试
│     ├── 第 1 次: 等待 200ms
│     ├── 第 2 次: 等待 400ms
│     └── 2 次后仍失败 → 静默跳过
```

### 3.2 FIM 响应后处理

| `finish_reason`    | 处理              |
|--------------------|-----------------|
| `"stop"`           | 正常，保留候选         |
| `"length"`         | 截断到最后一个完整行，保留候选 |
| `"content_filter"` | 丢弃候选，不显示        |

## 四、Git Message 模式错误处理

### 4.1 错误场景

| 场景          | 处理                              |
|-------------|---------------------------------|
| API Key 未配置 | 弹出 `Messages.showWarningDialog` |
| 网络错误        | 在 UI 线程显示错误消息                   |
| 生成中重复点击     | 1.5s 防抖 + `isGenerating` 标志禁用按钮 |

### 4.2 无重试机制

Git Message 生成不实现自动重试。失败后用户需手动重新点击按钮。

## 五、API Key 验证

### 验证流程

```kotlin
fun validate(key: String): String {
    return try {
        val client = AnthropicOkHttpClient.builder()
            .baseUrl("https://api.deepseek.com/anthropic")
            .apiKey(key)
            .build()
        val params = MessageCreateParams.builder()
            .model("deepseek-v4-pro")
            .maxTokens(1)
            .addUserMessage("hi")
            .build()
        client.beta().messages().create(params)
        "valid"
    } catch (e: Exception) {
        when {
            e.message?.contains("401") == true || e.message?.contains("Unauthorized") == true
                → "invalid"
            else → "unknown"
        }
    }
}
```

### 验证结果

| 返回值         | 含义              | UI 表现              |
|-------------|-----------------|--------------------|
| `"valid"`   | API Key 有效      | 绿色勾                |
| `"invalid"` | API Key 无效（401） | 红色叉 + "API Key 无效" |
| `"unknown"` | 网络不可达或其他错误      | 黄色问号 + "无法验证"      |

> **注意：** 验证仅通过异常消息字符串匹配状态码，不检查 HTTP 响应体。可能存在误判（如代理返回 401 但 Key
> 有效）。

## 六、日志记录

```kotlin
object AppLogger {
    fun requestStarted(url: String, tokenCount: Int)     // INFO: 请求开始（URL 脱敏仅保留 host）
    fun requestCompleted(durationMs: Long, tokenCount: Int)  // INFO: 请求完成
    fun requestFailed(httpCode: Int, message: String?)   // WARN: 请求失败（消息截断 200 字符）
    fun apiKeyInvalid()                                   // WARN: 401
    fun rateLimited()                                     // WARN: 429
    fun retryAttempt(attempt: Int, maxRetries: Int)      // INFO: 重试
    fun retryFailed()                                     // WARN: 重试耗尽
    fun streamCancelled()                                 // INFO: 用户取消
}
```

### 安全措施

- URL 脱敏：`sanitizeUrl()` 仅保留 host，去除路径细节
- 消息截断：`sanitizeMessage()` 截断超过 200 字符的错误消息
- **绝不记录 API Key 或代码内容**

## 七、各模块错误策略对比

| 维度         | Agent                                               | Completion    | Git Message |
|------------|-----------------------------------------------------|---------------|-------------|
| HTTP 库     | Anthropic SDK (OkHttp)                              | OkHttp 单例     | OkHttp      |
| 重试         | ✅ 429 + 5xx + 超时 退避重试                               | ✅ 指数退避 2 次    | ❌ 不重试       |
| 重试等待       | 429: Retry-After(默认30s) / 5xx: 1-3-9s / 超时: 2-5-10s | 200ms → 400ms | —           |
| 流中断恢复      | ✅ 保留已接收 + 可重试                                       | —             | ❌ 丢失        |
| 400 过大     | ✅ 强制 compact 后重发                                    | ❌             | ❌           |
| 错误提示       | 气泡显示错误消息                                            | 静默（无候选）       | 对话框         |
| 日志         | ✅ 分级记录                                              | ❌ 无           | ❌ 无         |
| API Key 验证 | 启动时可选验证                                             | ❌             | ❌           |

## 八、待改进

| 问题               | 建议                               |
|------------------|----------------------------------|
| Key 验证仅字符串匹配 401 | 应检查 HTTP 状态码而非异常消息               |
| Completion 错误静默  | 可考虑非侵入式提示（如状态栏）                  |
| Git Message 无重试  | 可加 1 次自动重试                       |
| 无全局 429 协调       | Agent 和 Completion 同时触发时可能互相加重限流 |

## 九、全局异常处理

**各组件不自行处理异常，所有异常由全局异常处理器统一处理。**

### 处理器注册

```kotlin
// EDT 线程
SwingUtilities.invokeLater {
    Thread.setDefaultUncaughtExceptionHandler(GlobalExceptionHandler)
}

// 后台线程池
ExecutorService.execute {
    Thread.currentThread().uncaughtExceptionHandler = GlobalExceptionHandler
}
```

### 处理策略

| 线程                                    | 异常处理                                                                        |
|---------------------------------------|-----------------------------------------------------------------------------|
| EDT                                   | `LoggingUncaughtExceptionHandler` → 记录日志 + toast 提示"插件内部错误，请查看日志" + 不中断 IDE |
| PooledThread（Agent Loop / 工具执行）       | 记录日志 + `session.markError()` → 错误气泡展示给用户 + Agent 状态 → ERROR                 |
| Swing Timer / ProcessHandler listener | 记录日志 + 静默恢复（根据上下文决定是否 toast）                                                |
| Completion 协程                         | `CoroutineExceptionHandler` → 记录日志 + 静默（无候选，不打扰用户）                          |

### 降级行为

| 异常类型                   | 降级行为                          |
|------------------------|-------------------------------|
| 未捕获 `RuntimeException` | 记录完整堆栈 + toast "插件内部错误"       |
| `OutOfMemoryError`     | 记录日志 + 不做额外操作（让 IDE 自行处理）     |
| `Error`（非 OOM）         | 记录日志 + 不捕获（让 IDE 处理，避免隐藏严重问题） |

### 组件规范

- **禁止组件自行 try-catch 吞掉异常**：各组件不写 `catch (e: Exception) { log }` 空处理
- **需要特定降级时向上抛**：抛出自定义异常，由全局处理器按类型分发
- **日志脱敏**：`AppLogger` 统一提供，自动脱敏 API Key 和代码内容

## 十、Agent 与 Completion 并发

**Agent 和 Completion 同时使用 API Key 时无并发限制。**

两个模块各自独立发起 HTTP 请求，不设互斥锁或令牌桶：

| 维度       | 说明                                                                |
|----------|-------------------------------------------------------------------|
| HTTP 客户端 | Agent 使用 Anthropic SDK（OkHttp），Completion 使用独立 OkHttp 单例——两个连接池隔离 |
| API Key  | 共享同一 Key，各自从 `AppSettingsService.getApiKey()` 读取                  |
| 限流协调     | 无——两个模块的 429 重试独立运行，不感知彼此                                         |
| 线程       | Agent 在后台线程池，Completion 在协程——无共享锁                                 |
| 风险       | 两个模块同时高频调用时可能加速触发 429 限流，但各自有独立重试兜底                               |
