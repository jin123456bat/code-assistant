# Code Assistant

[![English](https://img.shields.io/badge/English-README-blue)](README_EN.md)

**开源 AI 编程 Agent 插件，适用于 IntelliJ IDEA 及所有 JetBrains IDE。**

基于 [DeepSeek V4](https://platform.deepseek.com) 大模型，通过 Agent 循环自主调用工具完成复杂编码任务。完全免费开源，自带 API Key 即可使用。

---

## 目录

- [安装](#安装)
- [获取 API Key](#获取-api-key)
- [配置插件](#配置插件)
- [使用指南](#使用指南)
  - [基础对话](#基础对话)
  - [文件引用](#文件引用)
  - [工具执行与审批](#工具执行与审批)
  - [斜杠命令](#斜杠命令)
  - [Skills（技能）](#skills技能)
  - [Rules（规则）](#rules规则)
  - [MCP 外部工具](#mcp-外部工具)
  - [会话管理](#会话管理)
  - [Token 用量查看](#token-用量查看)
- [内置工具列表](#内置工具列表)
- [兼容性](#兼容性)
- [常见问题](#常见问题)
- [隐私说明](#隐私说明)

---

## 安装

### 从 JetBrains Marketplace 安装（推荐）

打开 IDE → `Settings / Preferences` → `Plugins` → 搜索 **Code Assistant** → 点击安装。

### 本地构建安装

```bash
git clone https://github.com/jin123456bat/code-assistant.git
cd code-assistant
./gradlew buildPlugin
```

构建产物在 `build/distributions/` 目录，得到 `.zip` 文件后：
`Settings → Plugins → ⚙️ → Install Plugin from Disk...` 选择该 zip 文件。

---

## 获取 API Key

1. 访问 [platform.deepseek.com](https://platform.deepseek.com) 注册账号
2. 进入「API Keys」页面，点击「创建 API Key」
3. 复制生成的 Key（格式为 `sk-xxxxxxxx`）

> **费用说明**：DeepSeek 采用按量计费。`deepseek-v4-pro` 定价为 ¥2/百万 input tokens + ¥8/百万 output tokens。日常编码对话每次约消耗几千到几万 token，单次成本几分钱。

---

## 配置插件

打开 `Settings → Tools → Code Assistant`：

| 设置项 | 说明 | 建议值 |
|--------|------|--------|
| **API Key** | DeepSeek 密钥 | 粘贴你的 `sk-xxxx` |
| **Model** | 使用的模型 | `deepseek-v4-pro`（复杂编码）/ `deepseek-v4-flash`（快速任务） |
| **API Endpoint** | API 地址（只读） | 默认 `https://api.deepseek.com/anthropic/v1/messages` |
| **自动 Compact 比例** | 对话长度达到上下文窗口多少时自动压缩 | 默认 90% |
| **代码补全** | AI 代码补全功能 | 默认开启 |

> **注意**：API Key 使用系统密钥链加密存储，不会明文写入配置文件。

---

## 使用指南

### 基础对话

安装并配置 API Key 后，IDE 右侧会出现 `Code Assistant` 工具窗口，点击打开即可开始对话。

```
👤 帮我创建一个用户登录的 REST API
🤖 我会帮你创建以下文件：
   1. UserController.java — 登录接口
   2. LoginRequest.java — 请求 DTO
   3. AuthService.java — 认证逻辑
   ...
```

Agent 会自动分析需求、规划步骤、调用工具（读写文件、搜索代码等），并在每次写操作前请求你确认。

### 文件引用

在输入框中输入 `@` 会自动弹出文件选择器，可以快速引用项目中的文件：

- **引用单个文件**：输入 `@` + 文件名关键字，选中后文件内容会作为上下文发给 AI
- **引用选中代码**：在编辑器中选中代码后右键 → `Send to Chat`，选中的代码会出现在输入框引用中

### 工具执行与审批

Agent 拥有 17 个内置工具，在执行危险操作前需要你确认：

- **只读工具**（自动执行）：搜索代码、读取文件、查看 Git 状态等
- **需确认工具**（弹窗审批）：写入文件、执行终端命令等

你可以在设置中将信任的工具加入白名单，白名单中的工具不再弹窗确认。

### 斜杠命令

在输入框中输入 `/` 开头的命令：

| 命令 | 说明 | 示例 |
|------|------|------|
| `/new` | 清空对话，开始新会话 | `/new` |
| `/plan` | 进入规划模式，AI 只读探索后提交方案 | `/plan 添加用户认证系统` |
| `/goal` | 设置目标，AI 持续工作直到完成 | `/goal 完成所有单元测试` |
| `/init` | 为当前项目生成 CLAUDE.md 文档 | `/init` |
| `/review` | 审查当前分支的代码改动 | `/review` |
| `/test` | 运行测试并分析失败原因 | `/test` |
| `/stop` | 立即停止 Agent 正在执行的任务 | `/stop` |
| `/compact` | 压缩对话历史，释放 token | `/compact` |
| `/context` | 查看 token 用量统计面板 | `/context` |
| `/resume` | 恢复之前保存的对话 | `/resume` |
| `/export` | 导出当前对话为 Markdown | `/export` |
| `/clear` | 清空输入框 | `/clear` |

### Skills（技能）

Skills 是自定义的 AI 行为扩展，通过 Markdown 文件定义：

1. 在项目根目录（或全局 `~/.claude/`）创建 `.claude/skills/` 目录
2. 在其中创建 `your-skill/SKILL.md` 文件，包含 YAML 前置元数据和指令

示例 `SKILL.md`：

```markdown
---
name: code-review
description: 对当前改动进行代码审查
allowed-tools: read_file, git_diff, search_code
---

## 审查清单

1. 检查空指针
2. 检查 SQL 注入
3. 检查异常处理
...
```

使用时直接在对话中提及 skill 名称，或输入 `/code-review`。

> **安全限制**：即使 skill 声明了 `allowed-tools`，写入类工具（`write_file`、`execute_command`）仍然会弹出审批弹窗。

### Rules（规则）

Rules 可以在特定条件下自动注入 prompt 指令：

1. 在 `.claude/rules/` 目录下创建 `.md` 文件
2. 可选 YAML frontmatter 中的 `paths` 字段指定规则生效的文件范围

```markdown
---
paths: ["src/**/*.java"]
---
所有 Java 文件必须遵循 Google Java Style。
```

### MCP 外部工具

Code Assistant 支持 [Model Context Protocol](https://modelcontextprotocol.io)，可以连接外部工具服务器来扩展 AI 能力。

在项目根目录或 `~/.claude.json` 中配置 MCP 服务器，例如连接文件系统服务器：

```json
{
  "mcpServers": {
    "filesystem": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/path/to/allowed/dir"]
    }
  }
}
```

配置后重新打开对话，AI 即可使用 MCP 服务器提供的工具。

### 会话管理

- **自动保存**：每次对话自动保存到 `.code-assistant/sessions/` 目录
- **恢复对话**：使用 `/resume` 命令查看并恢复历史对话
- **导出对话**：使用 `/export` 导出为 Markdown 文件，方便分享和存档

### Token 用量查看

- **气泡悬停**：将鼠标悬停在任何 AI 回复气泡上，会显示该条消息的 input/output token 消耗
- **用量面板**：输入 `/context` 查看按天/周统计的 token 用量 Dashboard

---

## 内置工具列表

| 工具 | 用途 | 示例 |
|------|------|------|
| `search_code` | 项目内文本/正则搜索 | 搜索函数调用、查找字符串 |
| `read_file` | 读取文件内容（支持行范围） | 查看文件某一段落 |
| `list_directory` | 树状列出目录结构 | 了解项目结构 |
| `write_file` | 创建或覆写文件 | 创建新类文件 |
| `edit_file` | 精确替换文件中的文本 | 修改函数实现 |
| `execute_command` | 执行 Shell 命令 | 运行构建、测试 |
| `git_diff` | 查看未暂存的改动 | 检查当前修改 |
| `git_log` | 查看提交历史 | 了解最近改动 |
| `git_status` | 查看工作区状态 | 检查变更文件 |
| `web_search` | 网络搜索 | 查找 API 文档 |
| `web_fetch` | 抓取网页内容 | 阅读在线文档 |
| `code_intelligence` | PSI 代码智能分析 | 跳转定义、查找引用 |
| `task` | 创建子 Agent 执行独立任务 | 并行处理多个文件 |
| `workflow` | 编排多个子 Agent（并行/串行） | 批量重构 |
| `ask_user` | 向用户提问（支持单选/多选） | 确认方案选择 |
| `notebook_edit` | 编辑 Jupyter Notebook | 修改 .ipynb 文件 |
| `mcp_get_prompt` | 获取 MCP 服务器模板 | 获取外部工具提示 |

> 只读工具自动执行；写入/执行类工具需要弹窗确认（可加入白名单）。

---

## 兼容性

兼容所有基于 IntelliJ Platform 的 IDE（2023.3 及以上版本）：

IntelliJ IDEA（Community / Ultimate）、PhpStorm、WebStorm、PyCharm、GoLand、RubyMine、CLion、Rider、DataGrip、Android Studio 等。

---

## 常见问题

### Q: 提示"网络连接失败"或"无法解析 api.deepseek.com"

1. 检查网络是否能访问 `https://api.deepseek.com`（在浏览器中打开确认）
2. 如果使用代理/VPN，在 IDE 中配置：`Settings → Appearance & Behavior → System Settings → HTTP Proxy`
3. 部分公司网络可能屏蔽了 DeepSeek API，请切换网络环境后重试

### Q: 模型回复不完整或突然中断

1. 可能触发了输出 token 限制（默认 32768）。等待 Agent 自动续写，或输入「继续」提示
2. 网络不稳定也可能导致流式中断，重试即可

### Q: API Key 保存在哪里？安全吗？

API Key 使用系统密钥链（macOS Keychain / Windows Credential Manager）加密存储，不写入任何配置文件。插件无遥测、无日志上传。

### Q: 如何减少 token 消耗？

1. 使用 `/compact` 命令压缩对话历史
2. 不需要时用 `/new` 开启新对话，避免无关上下文占用 token
3. 使用 `deepseek-v4-flash` 模型进行简单任务，成本更低

### Q: 工具白名单是什么？

在设置中可将信任的工具加入白名单。白名单中的工具执行时不弹窗确认。建议将常用只读工具加入白名单提升效率，但保留 `execute_command` 需要确认以保证安全。

### Q: 对话保存在哪里？

对话自动保存在项目的 `.code-assistant/sessions/` 目录。建议将 `.code-assistant/` 加入 `.gitignore`。

---

## 隐私说明

- **API Key**：通过 IntelliJ PasswordSafe（系统密钥链）加密存储
- **代码数据**：仅在发送消息时传输对话内容和相关代码上下文到 DeepSeek API
- **无数据收集**：不收集用户数据、不发送遥测、不使用分析 SDK
- **本地运行**：所有 Skill、Rule、Session 数据均存储在本地

---

## 许可证

[Apache-2.0](LICENSE)
