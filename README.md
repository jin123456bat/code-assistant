# Code Assistant

[![English](https://img.shields.io/badge/English-README-blue)](README_EN.md)

**开源 AI 编程 Agent 插件，适用于 IntelliJ IDEA 及所有 JetBrains IDE。**

基于 [DeepSeek V4](https://platform.deepseek.com) 大模型，通过 Agent 循环自主调用工具完成复杂编码任务。完全免费开源，自带 API Key 即可使用。

## 快速开始

1. 从 [JetBrains Marketplace](https://plugins.jetbrains.com) 安装插件（或 `./gradlew buildPlugin` 本地构建）
2. 注册 [DeepSeek API Key](https://platform.deepseek.com)
3. `Settings → Tools → Code Assistant` 填入 API Key
4. 打开右侧 `Code Assistant` 工具窗，开始对话

## 核心能力

- **Agent 自主循环**：AI 自动规划并执行多步骤任务，最大 100 轮推理，连续失败 3 次自动中止
- **14 个内置工具**：代码搜索、文件读写、命令执行、Git 操作、网页抓取、PSI 代码智能等
- **MCP 协议**：连接外部 Model Context Protocol 服务器扩展工具集
- **Skills 引擎**：从 `.claude/skills/**/SKILL.md` 加载自定义技能
- **执行计划**：LLM 自主决定是否创建计划，可视化步骤追踪
- **安全审批**：写入/命令执行需用户确认，支持白名单机制
- **流式聊天**：Markdown 实时渲染 + 代码语法高亮
- **PSI 代码智能**：基于 IntelliJ PSI 引擎的跳转定义、查找引用、类型信息、符号搜索
- **文件引用**：输入 `@` 快速选择文件、选中代码自动添加到引用
- **图片粘贴**：支持从剪贴板粘贴图片到对话

## 内置工具

| 工具 | 说明 | 权限 |
|------|------|------|
| `search_code` | 项目中搜索文本/正则 | 只读 |
| `read_file` | 读取文件内容（支持行范围） | 只读 |
| `list_directory` | 列出目录（树状输出） | 只读 |
| `git_diff` | Git 差异对比 | 只读 |
| `git_log` | Git 提交历史 | 只读 |
| `git_status` | Git 工作区状态 | 只读 |
| `web_search` | 网络搜索 | 只读 |
| `web_fetch` | 网页内容获取（支持重连/编码检测） | 只读 |
| `code_intelligence` | PSI 代码智能（定义/引用/类型/符号） | 只读 |
| `task` | 创建子 Agent 执行独立任务 | 只读 |
| `ask_user` | 向用户提问（单选/多选） | 只读 |
| `write_file` | 写入/覆写文件（含越界防护） | 需确认 |
| `execute_command` | 执行 Shell 命令（危险命令拦截） | 需确认 |
| `notebook_edit` | 编辑 Jupyter Notebook | 需确认 |

## 斜杠命令

| 命令 | 说明 |
|------|------|
| `/new` | 清空对话，开始新会话 |
| `/plan` | 创建执行计划 |
| `/init` | 初始化项目文档（CLAUDE.md） |
| `/review` | 审查当前分支代码改动 |
| `/test` | 运行测试并分析结果 |
| `/stop` | 停止 AI 生成 |
| `/clear` | 清空输入框 |

## 兼容性

兼容所有基于 IntelliJ Platform 的 IDE：
IntelliJ IDEA、PhpStorm、WebStorm、PyCharm、GoLand、RubyMine、CLion、Rider、DataGrip 等。

## 开发

```bash
# 构建插件
./gradlew buildPlugin

# 启动沙盒 IDE（支持热重载）
./gradlew runIde

# 运行测试
./gradlew test
```

## 技术栈

Kotlin · Swing · IntelliJ Platform 2023.3 · DeepSeek Anthropic 兼容 Messages API · SSE 流式传输

## 文档

- [项目文档](PROJECT.md) — 架构与开发指南
- [设计规范](DESIGN.md) — UI 设计 Token 与交互规范
- [功能待办](TODO.md) — 对照 Claude Code 的缺失功能清单

## 隐私说明

- API Key 通过 IntelliJ PasswordSafe 加密存储
- 无遥测、无追踪、无数据收集
- 代码上下文仅在你发送消息时传输至 DeepSeek API

## 许可证

[Apache-2.0](LICENSE)
