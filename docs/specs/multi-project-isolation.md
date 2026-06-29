# 多项目隔离

> 本文档描述同时打开多个项目窗口时，AgentLoop 及各模块的隔离策略。

## 一、架构保证

Code Assistant 的关键组件按 IntelliJ Platform 的 Service 层级隔离：

| 组件                   | Service 层级                 | 隔离方式                                                      |
|----------------------|----------------------------|-----------------------------------------------------------|
| `AppSettingsService` | 应用级（`@Service(Level.APP)`） | **共享**——API Key、Model、补全开关等配置跨项目共用                        |
| `AgentLoop`          | 手动创建（每次对话新建）               | **项目级**——绑定单个 `Project` 实例                                |
| `AgentSession`       | 手动创建                       | **项目级**——通过 `SessionStore` 与项目目录绑定                        |
| `SessionStore`       | 依赖 `project.basePath`      | **项目级**——JSON 文件写入 `{project}/.code-assistant/sessions/`  |
| `McpManager`         | 构造器注入 `Project`            | **项目级**——配置写入 `{project}/.code-assistant/mcp-config.json` |
| `SkillManager`       | 构造器注入 `Project`            | **项目级**——扫描 `{project}/.code-assistant/skills/`           |
| `ChatToolWindow`     | `ToolWindowFactory` 创建     | **项目级**——每个项目窗口独立的 ToolWindow 实例                          |

## 二、不同场景下的行为

### 场景 A：多个不同项目各开一个窗口

```
IDE 窗口 1: Project A → AgentLoop_A → SessionStore(projectA/.code-assistant/)
IDE 窗口 2: Project B → AgentLoop_B → SessionStore(projectB/.code-assistant/)
```

**完全隔离，互不影响。** 每个 AgentLoop 绑定各自的 `Project`，Session/MCP/Skills 数据位于各自项目目录下。

### 场景 B：同一个项目打开多个窗口

```
IDE 窗口 1: Project A (窗口 1) → AgentLoop_1 → SessionStore(projectA/.code-assistant/)
IDE 窗口 2: Project A (窗口 2) → AgentLoop_2 → SessionStore(projectA/.code-assistant/)  ⚠️
```

**⚠️ 存在冲突风险。** 两个窗口共享同一套 Session JSON 文件和 MCP 配置：

| 风险                   | 说明                                             |
|----------------------|------------------------------------------------|
| Session 竞态写入         | 两个 AgentLoop 同时 `SessionStore.save()` → 后者覆盖前者 |
| modificationStamp 冲突 | 窗口 1 的 Read 记录的 stamp，在窗口 2 的 Edit 中校验时已过时     |
| MCP Server 进程冲突      | 两个 `McpManager` 可能启动同一 MCP Server 两次（端口/资源冲突）  |
| 文件写入竞争               | 两个 Agent 同时 Write/Edit 同一文件 → 后写覆盖先写           |

### 场景 B 的处理策略

**不做自动阻止，仅警告。** 检测到同一项目已有一个 Agent 窗口打开时，在后续窗口中显示 toast 提示：

> ⚠️ 项目 "{projectName}" 已在另一个窗口打开。同时操作同一项目可能导致数据冲突，请自行承担风险。

## 三、共享资源

### 应用级 Service（跨项目共享）

`AppSettingsService` 是唯一的应用级共享组件：

```kotlin
@Service(Service.Level.APP)
class AppSettingsService {
    // API Key → 所有项目共用
    // Model → 所有项目共用
    // Completion 开关 → 所有项目共用
    // Commit Prompt → 所有项目共用
}
```

修改 Settings 页面中的任何配置项，**立即对所有项目窗口生效**。

**注意：**

- 用户在窗口 A 修改了 API Key，窗口 B 需要刷新或重启才能生效。
- 不支持不同项目使用不同 API Key（仅支持单 Key）。

### API Key 客户端

`AnthropicOkHttpClient` 按 API Key 缓存——相同 Key 复用同一客户端实例。多项目共用同一 Key 时共享 HTTP
连接池，无并发限制。

## 四、设计决策

| 决策            | 理由                                                              |
|---------------|-----------------------------------------------------------------|
| 不阻止同项目多窗口     | IntelliJ Platform 原生支持多窗口（`Window > New Window`），阻止与 IDE 设计哲学冲突 |
| 仅警告不强制        | Session 级数据损失可接受（对话历史），代码级冲突由 modificationStamp 检测兜底            |
| 应用级共享 API Key | 用户通常只有一个 Key，减少重复配置                                             |
| 不做分布式锁        | 单机 IDE 场景，无必要引入文件锁复杂度                                           |
