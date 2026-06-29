# Settings 持久化规范

> **原始来源：** `tech-spec.md`（已拆分，内容归入 `specs/` 各文件）

Agent 设置已迁移到 IDE SettingsConfigurable（Settings > Tools > Code Assistant），与代码补全、Commit
生成统一管理。面板内 Settings 页面简化为关于页 + 快捷键参考。存储复用 `PasswordSafe`（API Key）和
`PropertiesComponent`（其余配置）。

---

## AppSettingsService（SettingsConfigurable 读写）

```
AppSettingsService（SettingsConfigurable 读写）
├── apiKey: String                               // PasswordSafe 存储
├── model: String = "deepseek-v4-pro"            // 固定
├── completionEnabled: Boolean = true             // 代码补全开关
├── commitPrompt: String                          // Commit 消息模板（{diff} 占位）
├── maxTurns: Int                      // 最大轮次，默认 20。每轮 = 一次用户消息触发的 API 调用。0 时内部转为 Int.MAX_VALUE（不限轮次）
└── maxConcurrentAgents: Int (default 3)          // 多 Agent 并发上限
```

## 设置项详解

| 设置项                   | 类型      | 默认值               | 存储方式                  | 说明                                                              |
|-----------------------|---------|-------------------|-----------------------|-----------------------------------------------------------------|
| `apiKey`              | String  | —                 | `PasswordSafe`        | DeepSeek API Key，安全存储，UI 显示掩码                                   |
| `model`               | String  | `deepseek-v4-pro` | `PropertiesComponent` | 当前固定为 DeepSeek V4 Pro，不可修改                                      |
| `completionEnabled`   | Boolean | `true`            | `PropertiesComponent` | 代码补全总开关                                                         |
| `commitPrompt`        | String  | —                 | `PropertiesComponent` | Commit 消息生成模板，`{diff}` 为 diff 内容占位符                             |
| `maxTurns`            | Int     | `20`              | `PropertiesComponent` | Agent 每轮最大 API 调用次数。每轮 = 一次用户消息触发的 API 调用（续写不计数）。设为 0 时内部转为不限轮次 |
| `maxConcurrentAgents` | Int     | `3`               | `PropertiesComponent` | 多 Agent 模式下的并发上限                                                |

## 存储方式

| 存储方式                  | 适用场景    | 说明                                                                  |
|-----------------------|---------|---------------------------------------------------------------------|
| `PasswordSafe`        | API Key | IntelliJ 内置安全存储，自动加密，跨项目共享。不可用时直接抛 `IllegalStateException`，不做降级明文存储 |
| `PropertiesComponent` | 其余所有配置  | IntelliJ 项目级/应用级 properties 存储，支持默认值回退                              |

## Settings 页面（UI）

Settings 页面实际为关于页 + 快捷键参考 + IDE 设置入口卡片，不包含 Agent 配置编辑控件。Agent 配置通过
`Settings > Tools > Code Assistant` 的 IDE SettingsConfigurable 面板管理。

---

## 配置项索引（全项目）

以下汇总所有可配置项及其所在位置，避免分散查找：

| 配置项             | 默认值                 | 范围/可选值             | 存储方式                  | 说明文档                                                    |
|-----------------|---------------------|--------------------|-----------------------|---------------------------------------------------------|
| API Key         | —                   | —                  | `PasswordSafe`        | 本文 §设置项详解                                               |
| Model           | `deepseek-v4-pro`   | 固定                 | `PropertiesComponent` | 本文 §设置项详解                                               |
| 代码补全开关          | `true`              | on/off             | `PropertiesComponent` | [completion.md](../../docs/completion.md#八配置项)          |
| 补全 max_tokens   | `256`               | 1-1024             | `PropertiesComponent` | [completion.md](../../docs/completion.md#八配置项)          |
| Commit Prompt   | `""`（使用默认）          | 自定义模板              | `PropertiesComponent` | [git-message.md](../../docs/git-message.md#八配置项)        |
| Agent 最大轮次      | `20`                | 0=不限               | `PropertiesComponent` | [agent.md](../../docs/agent.md#配置项)                     |
| 多 Agent 并发上限    | `3`                 | 0=不限               | `PropertiesComponent` | [multi-agent.md](../../docs/agent/multi-agent.md#二关键约束) |
| 补全缓存 TTL        | `60s`               | 固定                 | 内存（不可配）               | [completion.md](../../docs/completion.md#五缓存策略)         |
| 上下文窗口           | `1M tokens`         | 固定（DeepSeek V4 上限） | 硬编码                   | [context.md](../../docs/agent/context.md)               |
| Auto-Compact 阈值 | `70%` (700K tokens) | 固定                 | 硬编码                   | [auto-compact.md](auto-compact.md)                      |
| Shell 超时        | LLM 每次 tool call 传入 | 0=不限               | 非 Settings            | [tools.md](../../docs/agent/tools.md#五shell-安全)         |

> **不在 Settings 中的固定值：** Shell 超时由 LLM 每次传入（非用户配置）、缓存 TTL 60s、上下文窗口
> 1M tokens、Compact 阈值 70%——这些为硬编码常量，不暴露为可配置项。
