# Settings 持久化规范

> **原始来源：** `docs/tech-spec.md`（已拆分，内容归入 `docs/specs/` 各文件）

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
├── maxAgentTurns: Int                      // 最大轮次，默认 20。每轮 = 一次用户消息触发的 API 调用。0 时内部转为 Int.MAX_VALUE（不限轮次）
└── maxConcurrentAgents: Int (default 3)          // 多 Agent 并发上限
```

## 设置项详解

| 设置项                   | 类型      | 默认值               | 存储方式                  | 说明                                                              |
|-----------------------|---------|-------------------|-----------------------|-----------------------------------------------------------------|
| `apiKey`              | String  | —                 | `PasswordSafe`        | DeepSeek API Key，安全存储，UI 显示掩码                                   |
| `model`               | String  | `deepseek-v4-pro` | `PropertiesComponent` | 当前固定为 DeepSeek V4 Pro，不可修改                                      |
| `completionEnabled`   | Boolean | `true`            | `PropertiesComponent` | 代码补全总开关                                                         |
| `commitPrompt`        | String  | —                 | `PropertiesComponent` | Commit 消息生成模板，`{diff}` 为 diff 内容占位符                             |
| `maxAgentTurns`       | Int     | `20`              | `PropertiesComponent` | Agent 每轮最大 API 调用次数。每轮 = 一次用户消息触发的 API 调用（续写不计数）。设为 0 时内部转为不限轮次 |
| `maxConcurrentAgents` | Int     | `3`               | `PropertiesComponent` | 多 Agent 模式下的并发上限                                                |

## 存储方式

| 存储方式                  | 适用场景    | 说明                                     |
|-----------------------|---------|----------------------------------------|
| `PasswordSafe`        | API Key | IntelliJ 内置安全存储，自动加密，跨项目共享             |
| `PropertiesComponent` | 其余所有配置  | IntelliJ 项目级/应用级 properties 存储，支持默认值回退 |

## Settings 页面（UI）

Settings 页面实际为关于页 + 快捷键参考 + IDE 设置入口卡片，不包含 Agent 配置编辑控件。Agent 配置通过
`Settings > Tools > Code Assistant` 的 IDE SettingsConfigurable 面板管理。
