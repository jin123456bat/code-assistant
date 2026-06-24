# Code Assistant - 技术方案文档

> 最后更新: 2026-06-24 | master 分支实际代码

## 1. 架构设计

### 1.1 分层架构

```
┌──────────────────────────────────────────────┐
│                IntelliJ Platform             │
│  InlineCompletionProvider · AnAction         │
│  PasswordSafe · PropertiesComponent          │
├──────────────────────────────────────────────┤
│              补全模块 (completion/)            │
│  AiCompletionProvider · DeepSeekFimClient    │
│  CompletionPostProcessor · ContextCollector  │
│  CompletionCache · CompletionStats           │
│  PsiCompletionStrategy · ContextEnhancer     │
│  TokenBudgetManager · ManualCompletionAction │
├──────────────────────────────────────────────┤
│            Git Message 模块                   │
│  GenerateCommitAction (VCS 集成)             │
├──────────────────────────────────────────────┤
│              Settings 层                      │
│  AppSettingsService · SettingsConfigurable   │
├──────────────────────────────────────────────┤
│              Utilities                        │
│  SimpleDiff · AppLogger · AiAssistantBundle  │
└──────────────────────────────────────────────┘
```

### 1.2 数据流

**代码补全（FIM）：**
```
编辑器光标移动
  │ IntelliJ InlineCompletionProvider 自动触发
  ▼
AiCompletionProvider.getSuggestion() [协程]
  │
  ├─► CompletionContextCollector.collect()
  │     ├─ prefix (光标前文本) + suffix (光标后文本)
  │     ├─ 上下文 < 8K → ContextEnhancer 兄弟文件增强
  │     └─ 上下文 > 16K → TokenBudgetManager 裁剪 (prefix 2/3, suffix 1/3)
  │
  ├─► CompletionCache.get(prefix, suffix) → 命中则跳过 API
  │
  ├─► DeepSeekFimClient.complete(prompt, suffix)
  │     │ OkHttp + 连接超时 2s + 读取超时 3s + callTimeout 3s
  │     │ 指数退避重试: 200ms → 400ms（最多 2 次）
  │     └─► DeepSeek /beta/completions (FIM API)
  │
  ├─► CompletionPostProcessor.process()
  │     │ 裁剪 prefix/suffix 重叠、去重、过滤空结果
  │     └─► List<String> 候选
  │
  └─► InlineCompletionGrayTextElement 灰显渲染
```

**Git Commit Message：**
```
用户点击 GenerateCommitAction
  │ IntelliJ AnAction, 注册在 VCS Commit 对话框
  ▼
VCS API 读取 staged/unstaged diff
  │ SimpleDiff 格式化
  ▼
LLM API (DeepSeek Chat, 非流式)
  │ prompt: Conventional Commits 规范
  ▼
填充 commit message 文本框
```

## 2. 核心模块

### 2.1 补全模块 (`completion/`，9 文件)

| 组件 | 职责 |
|------|------|
| `AiCompletionProvider` | 对接 IntelliJ InlineCompletionProvider API，管理请求生命周期 |
| `DeepSeekFimClient` | OkHttp 封装，连接超时 2s/读取 3s/callTimeout 3s，指数退避重试 |
| `CompletionPostProcessor` | 后处理：裁剪 prefix/suffix 重叠、去重、过滤 |
| `CompletionContextCollector` | 双层上下文：纯文本 prefix+suffix + PSI 语言策略增强 |
| `CompletionCache` | prefix+suffix 缓存去重，手动触发跳过缓存 |
| `CompletionStats` | 补全接受率/延迟统计，持久化到 `.claude/completion-stats.json` |
| `PsiCompletionStrategy` | 语言特定 PSI 上下文提取（PHP/JS/TS/Python 等） |
| `ContextEnhancer` | Jaccard 相似度兄弟文件推荐（仅上下文<8K 时触发） |
| `TokenBudgetManager` | 16K 字符上限裁剪（prefix 2/3, suffix 1/3） |

### 2.2 Git Message 模块

| `GenerateCommitAction` | VCS diff → LLM → 填充 commit 对话框。支持中英文 prompt |

### 2.3 Settings 层

| `AppSettingsService` | API Key（PasswordSafe 加密）、模型选择（deepseek-v4-pro/flash）、补全开关 |
| `SettingsConfigurable` | IntelliJ Settings 面板集成 |

### 2.4 Utilities

| `SimpleDiff` | LCS diff 算法 |
| `AppLogger` | IDE 日志（`plugins/ai-assistant/` 前缀） |
| `AiAssistantBundle` | i18n 国际化 |

## 3. 通信协议

### 3.1 FIM API

- **端点**: `https://api.deepseek.com/beta/completions`
- **协议**: HTTP POST (JSON)，非流式
- **超时**: 连接 2s / 读取 3s / 整体 callTimeout 3s
- **重试**: 指数退避 200ms→400ms，最多 2 次，4xx 不重试
- **取消**: `activeCall.cancel()` 支持取消进行中请求

### 3.2 Chat API（GenerateCommitAction）

- **端点**: `https://api.deepseek.com/v1/chat/completions`
- **协议**: HTTP POST (JSON)，非流式
- **Prompt**: Conventional Commits 规范（中/英文）

## 4. 性能要求

| 功能 | 延迟预算 | 策略 |
|------|---------|------|
| 代码补全 | 请求→渲染 < 500ms（不含 IntelliJ 平台去抖） | OkHttp callTimeout 3s + 2 次重试上限 |
| Git Message | 无硬性要求 | 后台请求，不阻塞 UI |

## 5. 错误处理

| 场景 | 处理 |
|------|------|
| API Key 无效 | Settings 面板红色提示 |
| 网络超时 | 补全静默失败；Git Message 弹窗提示 |
| 4xx 错误 | 不重试，AppLogger 记录 |
| 5xx/IO 错误 | 指数退避重试 2 次 |
| LLM 返回空/乱码 | CompletionPostProcessor 过滤，不显示 |

## 6. 安全

- API Key: IntelliJ `PasswordSafe` 加密存储
- 代码上下文: 仅当前文件 + 光标附近，不发送整个项目
- 日志: 仅本地，不含 API Key 和代码内容
- 无远程遥测或行为追踪

## 7. 线程安全

- `AiCompletionProvider.getSuggestion()`：suspend 协程，IntelliJ 管理线程
- `DeepSeekFimClient.activeCall`：`@Volatile` 保证取消可见性
- `CompletionStats`：`synchronized` 保护统计数据写入
- `AppSettingsService.cachedApiKey`：`@Volatile` + `apiKeyLoaded` 双重检查

## 8. 配置

| 配置项 | 存储 | 默认值 |
|--------|------|--------|
| API Key | PasswordSafe | — |
| 模型 | PropertiesComponent | `deepseek-v4-pro` |
| 补全开关 | PropertiesComponent | 开启 |
| 最大 tokens | PropertiesComponent | 256 |
| Token 显示 | PropertiesComponent | 关闭 |

## 9. 测试

- **单元测试**: `SimpleDiffTest.kt`（LCS 算法验证）
- **集成测试**: Sandbox IDE + Gradle `runIde` 手动验证
- **CI**: GitHub Actions 构建 + 测试 + 插件打包

## 10. 项目统计

| 维度 | 数值 |
|------|------|
| 源文件 | 17 个 Kotlin 文件 |
| 代码行数 | ~2,500 行 |
| 测试文件 | 1 个（SimpleDiffTest） |
| 包数量 | 3 个（根/completion/actions） |
| 核心功能 | 代码补全 + Git Message |
