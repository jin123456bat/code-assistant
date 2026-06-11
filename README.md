# PhpStorm AI 编程助手

免费、开源的 JetBrains PhpStorm AI 编程助手插件。
由 DeepSeek V4 模型驱动。自带 API Key 即可使用。

## 功能特性（V2 — Agent 模式）

- **Agent 模式** — AI 自主规划并执行多步骤任务：搜索代码、读写文件、运行命令、查看 git
- **流式聊天面板** — IDE 原生可停靠的工具窗口，支持实时 AI 响应和 Markdown 渲染
- **工具系统** — 8 个内置工具：`search_code`、`read_file`、`write_file`、`list_directory`、`execute_command`、`git_diff`、`git_log`、`git_status`
- **MCP 支持** — 连接 MCP（模型上下文协议）服务器以扩展能力
- **Skills 引擎** — 从 `.claude/skills/**/SKILL.md`（项目级和全局）加载自定义技能
- **Plan 模式** — 自动检测复杂任务，生成分步计划并跟踪进度
- **模型路由** — 根据任务复杂度自动选择 `deepseek-v4-flash`（快速）或 `deepseek-v4-pro`（深度推理）
- **DeepSeek Anthropic API** — 使用 DeepSeek 的 Anthropic 兼容 Messages API（`/anthropic/v1/messages`），支持 SSE 流式传输，`deepseek-v4-flash` / `deepseek-v4-pro` 模型
- **安全密钥存储** — API 密钥通过 IntelliJ CredentialStore（PasswordSafe）加密存储
- **错误恢复** — 5xx 自动重试、速率限制倒计时、清晰的错误提示、命令执行沙箱
- **文件引用** — 一键文件选择器、3 秒防抖自动检测选中代码、支持粘贴图片

## 内置工具

| 工具 | 描述 | 安全级别 |
|------|-------------|--------|
| `search_code` | 在项目文件中搜索文本/正则 | 只读 |
| `read_file` | 读取文件内容（带行号） | 只读 |
| `list_directory` | 列出目录树结构 | 只读 |
| `git_diff` | 查看工作区变更 | 只读 |
| `git_log` | 查看最近提交历史 | 只读 |
| `git_status` | 查看工作区状态 | 只读 |
| `write_file` | 创建或覆盖文件 | 需确认 |
| `execute_command` | 运行 Shell 命令 | 白名单 + 确认 |

## MCP 配置

### 项目级配置（`.code-assistant/mcp.json`）

```json
{
  "mcpServers": {
    "filesystem": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/path/to/allowed/dir"]
    },
    "sqlite": {
      "command": "uvx",
      "args": ["mcp-server-sqlite", "--db-path", "database.db"]
    }
  }
}
```

### 全局配置

Settings → Tools → Code Assistant → MCP Servers

## 安装

### 从 JetBrains Marketplace 安装
1. 打开 PhpStorm → Settings → Plugins → Marketplace
2. 搜索 "AI Coding Assistant"
3. 安装并重启 IDE

### 手动安装（GitHub Releases）
1. 从 [GitHub Releases](https://github.com/ai-assistant/phpstorm-plugin/releases) 下载最新的 `.zip` 文件
2. Settings → Plugins → 齿轮图标 → Install Plugin from Disk
3. 选择下载的 `.zip` 文件

## 快速开始

1. 在 [platform.deepseek.com](https://platform.deepseek.com) 注册并获取 API Key
2. 打开 Settings → Tools → AI Coding Assistant
3. 粘贴你的 DeepSeek API Key 并点击 Apply
4. 点击 Tools → Open AI Chat（或使用底部工具窗口）

## 环境要求

- PhpStorm 2023.3+
- DeepSeek API Key（提供免费额度）

## 开发

```bash
# 构建插件
./gradlew buildPlugin

# 在 Sandbox IDE 中运行
./gradlew runIde

# 运行测试
./gradlew test
```

## 许可证

MIT License

## 隐私说明

- API 密钥通过 IntelliJ CredentialStore 加密存储
- 无遥测、无追踪、无数据收集
- 代码上下文仅在你发送消息时发送至 DeepSeek API
