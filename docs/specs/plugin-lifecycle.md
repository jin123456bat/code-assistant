# 插件启动与初始化流程

> 本文档描述 Code Assistant 插件从 IDE 启动到用户可用的完整初始化流程。

## 一、插件注册（plugin.xml）

```xml
<idea-plugin>
    <id>cn.wivoce.code-assistant</id>
    <name>Code Assistant</name>
    <vendor>wivoce.cn</vendor>

    <depends>com.intellij.modules.platform</depends>
    <depends>com.intellij.modules.vcs</depends>
    <depends>org.intellij.plugins.markdown</depends>
</idea-plugin>
```

### 依赖说明

| 依赖                              | 用途                                          |
|---------------------------------|---------------------------------------------|
| `com.intellij.modules.platform` | IntelliJ Platform 基础 API                    |
| `com.intellij.modules.vcs`      | VCS 集成（GenerateCommitAction）                |
| `org.intellij.plugins.markdown` | Markdown bundled plugin（已声明但实际未使用其 PSI API） |

## 二、Extensions 注册

```xml
<extensions defaultExtensionNs="com.intellij">
    <!-- 设置页面 -->
    <applicationConfigurable
        id="com.aiassistant.settings"
        instance="com.aiassistant.SettingsConfigurable"
        parentId="tools"
        displayName="Code Assistant"/>

    <!-- 工具窗口 -->
    <toolWindow
        id="Code Assistant"
        anchor="right"
        icon="icon.svg"
        factoryClass="com.aiassistant.ui.ChatToolWindowFactory"/>

    <!-- 内联补全 -->
    <inline.completion.provider
        id="ai-assistant"
        implementationClass="com.aiassistant.completion.AiCompletionProvider"/>
</extensions>
```

## 三、Actions 注册

| Action                         | 快捷键 (Mac)      | 快捷键 (Win/Linux) | 注册位置                     |
|--------------------------------|----------------|-----------------|--------------------------|
| `AiAssistant.OpenChat`         | `Meta+Shift+K` | `Ctrl+Shift+K`  | 全局                       |
| `AiAssistant.ManualCompletion` | `Meta+P`       | `Alt+P`         | 编辑器                      |
| `AiAssistant.NextCandidate`    | `Down`         | `Down`          | 编辑器（有候选时）                |
| `AiAssistant.PrevCandidate`    | `Up`           | `Up`            | 编辑器（有候选时）                |
| `AiAssistant.GenerateCommit`   | —              | —               | `Vcs.MessageActionGroup` |

## 四、Services 初始化顺序

> 所有 Service 通过 `@Service` 注解自动注册（非 plugin.xml 声明），初始化时机由 IntelliJ Platform 管理。

### 4.1 应用级 Service（全局单例）

```
IDE 启动
  │
  ▼
AppSettingsService.getInstance()  ← @Service(Service.Level.APP), 首次访问时实例化
  ├── init 块: 无（配置项懒加载）
  ├── getApiKey(): 双重检查锁定 + PasswordSafe 异步加载
  │     ├── 首次: @Volatile apiKey = null → PasswordSafe.getPassword()
  │     └── 后续: 直接返回 @Volatile apiKey
  └── 其他 getter: PropertiesComponent 同步读取
```

### 4.2 项目级初始化（打开项目时）

```
用户打开项目
  │
  ▼
ChatToolWindowFactory.createToolWindowContent()
  ├── 创建 ChatToolWindow（主容器）
  ├── 创建 TabBar（7 个标签页）
  ├── 创建 CardLayout 容器
  ├── 首屏懒加载: 仅创建 WelcomePage + ChatPage
  ├── 注册 SelectionListener（编辑器选中监听）
  └── 注册 MessageBus 订阅者
  │
  ▼
首次切换到某页面时: 懒加载创建对应 Page
  ├── SessionsPage: 加载 Session Index → 显示会话列表
  ├── TokenUsagePage: 加载统计 JSON → 渲染图表
  ├── McpPage: 加载 mcp-config.json → 显示 Server 列表
  ├── SkillsPage: 扫描 .code-assistant/skills/ → 显示 Skill 列表
  └── SettingsPage: 静态关于页面
```

### 4.3 Agent 首次对话时

```
用户发送第一条消息
  │
  ▼
AgentLoop.run(task)
  ├── ToolRegistry: 已在静态 init 块注册 8 个内置工具
  ├── SkillManager(project): 扫描 .code-assistant/skills/*/SKILL.md
  ├── McpManager(project): init 块加载 mcp-config.json
  ├── buildSystemPrompt(): 动态组装
  │     ├── 项目名/路径/当前文件
  │     ├── Git 分支
  │     ├── 编码规范文件检测（CLAUDE.md/CODEX.md/AGENTS.md/.cursorrules）
  │     ├── ToolRegistry.buildSystemPromptTools()
  │     └── SkillManager.getSystemPromptExtension()
  ├── getClient(): 创建 AnthropicOkHttpClient（按需，API Key 变更时重建）
  └── 开始 while 循环
```

## 五、关键初始化时序

```
IDE 启动
├── AppSettingsService 实例化（首次访问时）
├── ToolRegistry.registerBuiltins()（static init）
│
用户打开项目
├── ChatToolWindowFactory.createToolWindowContent()
├── McpManager(project).loadServers()
├── SkillManager(project): 扫描 skills 目录
│
用户首次对话
├── AgentLoop(project, session)
├── getClient() → AnthropicOkHttpClient 创建
├── buildSystemPrompt() → 完整 prompt 组装
│
项目关闭
├── SessionStore.save()
├── McpManager.dispose() → 清理所有 MCP 进程
├── CompletionStats 持久化
└── AppSettingsService: 无需清理（PropertiesComponent 自动持久化）
```

## 六、懒加载策略

| 组件                     | 创建时机          | 理由                 |
|------------------------|---------------|--------------------|
| WelcomePage + ChatPage | 工具窗口首次打开      | 首屏必须可见             |
| SessionsPage           | 用户切换到该 Tab    | 需要读磁盘加载 JSON       |
| TokenUsagePage         | 用户切换到该 Tab    | 需要读磁盘加载 JSON       |
| McpPage                | 用户切换到该 Tab    | 需要读磁盘加载配置          |
| SkillsPage             | 用户切换到该 Tab    | 需要扫描文件系统           |
| SettingsPage           | 用户切换到该 Tab    | 静态内容               |
| ChatBubbleRenderer     | 首次收到 Agent 消息 | 按需渲染               |
| AnthropicOkHttpClient  | 首次 API 调用     | 按需创建，API Key 变更时重建 |

## 七、配置持久化时机

| 操作                    | 持久化时机                                     |
|-----------------------|-------------------------------------------|
| API Key               | `PasswordSafe.setPassword()` 立即写入         |
| Model / Prompt / 补全开关 | `PropertiesComponent.setValue()` 立即写入     |
| Session               | 每轮对话结束后 `SessionStore.save()`             |
| Session Index         | Session 创建/删除时 `SessionStore.saveIndex()` |
| MCP Config            | Server 增删改时 `McpManager.persist()`        |
| Completion Stats      | 项目关闭时写入 JSON                              |

## 八、环境检测

System Prompt 中注入的环境信息获取方式：

```kotlin
val projectName = project.name                                    // IntelliJ API
val basePath = project.basePath ?: "unknown"                      // IntelliJ API
val currentFile = FileEditorManager.getInstance(project)
    .selectedTextEditor?.virtualFile?.name ?: "无"                // IntelliJ API
val dateStr = LocalDate.now().toString()                          // JDK
val osName = System.getProperty("os.name")                        // JDK
val branch = File(basePath, ".git/HEAD").readText()               // 文件读取
    .trim().removePrefix("ref: refs/heads/")
```
