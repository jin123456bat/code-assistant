# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## 🌐 语言声明

**本项目所有输出必须使用简体中文**，包括但不限于：代码注释、文档、Git commit message、PR 描述、Issue
回复、对话回复。禁止输出英文内容。

## 项目概述

面向 IntelliJ IDEA 及所有 JetBrains IDE 的免费 AI 编程助手插件（IntelliJ Platform Plugin, type `IC`
）。通过 DeepSeek API 提供代码补全和 Git commit message 自动生成。用户自带 DeepSeek API Key。

## 常用命令

```bash
./gradlew buildPlugin      # 构建插件 zip（产物在 build/distributions/）
./gradlew runIde           # 启动 sandbox IntelliJ IDEA（autoReloadPlugins=true：改代码后重新编译即热加载，无需重启）
./gradlew test             # 运行全部 JUnit 测试
```

环境：JVM 17、Kotlin 1.9.22、IntelliJ Platform 2023.3（IntelliJ IDEA Community）、Gradle IntelliJ Plugin
1.17.4。

## 架构（big picture）

```
CompletionProvider → DeepSeekFimClient (HTTP)
    → CompletionPostProcessor (trim/validate)
    → InlineCompletionProvider (suspend/Flow)
```

### 补全层（`completion/` 包，9 文件）

| 组件                           | 职责                                  |
|------------------------------|-------------------------------------|
| `CompletionProvider`         | 对接 DeepSeek FIM API，管理上下文、缓存、请求生命周期 |
| `DeepSeekFimClient`          | OkHttp 封装，带超时、重试、取消                 |
| `CompletionPostProcessor`    | 后处理：裁剪重叠前缀/后缀，去重、过滤                 |
| `CompletionContextCollector` | 双层上下文采集：纯文本 + PSI 增强                |
| `CompletionCache`            | 缓存去重                                |
| `CompletionStats`            | 延迟统计 + 持久化                          |

### Settings 层

| 组件                     | 职责                                     |
|------------------------|----------------------------------------|
| `AppSettingsService`   | API Key 存储（IntelliJ PasswordSafe）、模型选择 |
| `SettingsConfigurable` | 补全开关、最大 tokens 配置                      |

### Git Message 模块

| 组件                     | 职责                                |
|------------------------|-----------------------------------|
| `GenerateCommitAction` | VCS diff → LLM → commit dialog 填充 |

### Utilities

| 组件                  | 职责          |
|---------------------|-------------|
| `SimpleDiff`        | LCS diff 算法 |
| `AppLogger`         | IDE 日志      |
| `AiAssistantBundle` | i18n 国际化    |

## Skill routing

When the user's request matches an available skill, invoke it via the Skill tool. When in doubt,
invoke the skill.

Key routing rules:

- Product ideas/brainstorming → invoke /office-hours
- Strategy/scope → invoke /plan-ceo-review
- Architecture → invoke /plan-eng-review
- Design system/plan review → invoke /design-consultation or /plan-design-review
- Full review pipeline → invoke /autoplan
- Bugs/errors → invoke /investigate
- QA/testing site behavior → invoke /qa or /qa-only
- Code review/diff check → invoke /review
- Visual polish → invoke /design-review
- Ship/deploy/PR → invoke /ship or /land-and-deploy
- Save progress → invoke /context-save
- Resume context → invoke /context-restore
- Author a backlog-ready spec/issue → invoke /spec