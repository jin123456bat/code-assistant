# Code Assistant

[![中文](https://img.shields.io/badge/中文-README-red)](README.md)

**Open-source AI coding agent plugin for IntelliJ IDEA and all JetBrains IDEs.**

Powered by [DeepSeek V4](https://platform.deepseek.com) models. Agent loop autonomously invokes tools to search code, read/write files, execute commands, perform Git operations, and more. Completely free and open-source. Bring your own API key.

## Quick Start

1. Install from [JetBrains Marketplace](https://plugins.jetbrains.com) or build locally with `./gradlew buildPlugin`
2. Register for a [DeepSeek API Key](https://platform.deepseek.com)
3. Go to `Settings → Tools → Code Assistant` and paste your API Key
4. Open the `Code Assistant` tool window on the right and start chatting

## Core Capabilities

- **Autonomous Agent Loop**: AI plans and executes multi-step tasks, up to 100 turns, auto-aborts after 3 consecutive failures
- **18 Built-in Tools**: Edit, Workflow orchestration, code search, file I/O, command execution, Git, web fetch, PSI, etc.
- **MCP Protocol**: Connect external Model Context Protocol servers to extend tool capabilities
- **Skills Engine**: Custom skills with YAML frontmatter (`allowed-tools`, `invoke-for`), aligned with Claude Code
- **Rules System**: `.claude/rules/*.md` with path-conditional injection into system prompt
- **Goal-Driven Mode**: `/goal` command lets Agent work continuously until goal is reached
- **Execution Plans**: LLM autonomously creates step-by-step plans with visual progress tracking
- **Session Persistence**: Auto-save conversations, `/resume` to restore, `/export` to Markdown
- **Token Tracking**: Per-message token usage on hover, 📊 Dashboard with daily/weekly stats
- **Security Approval**: Write/execute require confirmation, skill-level `allowed-tools` auto-approval
- **Streaming Chat**: Real-time Markdown rendering with syntax highlighting
- **PSI Code Intelligence**: Go-to-definition, find references, type info, and symbol search via IntelliJ PSI engine
- **File References**: Type `@` to quickly select files; selected code auto-added to references
- **Image Paste**: Paste images from clipboard into conversations

## Built-in Tools

| Tool | Description | Permission |
|------|-------------|------------|
| `search_code` | Search text/regex in project files | Read-only |
| `read_file` | Read file content with line range support | Read-only |
| `list_directory` | List directory as tree structure | Read-only |
| `git_diff` | Show working tree changes | Read-only |
| `git_log` | Show commit history | Read-only |
| `git_status` | Show working tree status | Read-only |
| `web_search` | Search the web | Read-only |
| `web_fetch` | Fetch web page content (retry + encoding detection) | Read-only |
| `code_intelligence` | PSI code navigation (def/ref/type/symbols) | Read-only |
| `task` | Spawn sub-agent for independent tasks | Read-only |
| `ask_user` | Ask user a question (single/multi choice) | Read-only |
| `write_file` | Create or overwrite files (path traversal protection) | Confirmation |
| `execute_command` | Execute shell commands (length limit + danger pattern detection) | Confirmation |
| `notebook_edit` | Edit Jupyter Notebook cells | Confirmation |
| `edit_file` | Precise string replacement (unique match required) | Confirmation |
| `workflow` | Parallel/sequential multi sub-agent orchestration | Read-only |
| `mcp_get_prompt` | Get MCP server prompt templates | Read-only |

## Slash Commands

| Command | Description |
|---------|-------------|
| `/new` | Clear conversation and start fresh |
| `/plan` | Create an execution plan |
| `/goal` | Set a goal — Agent works continuously until achieved |
| `/init` | Initialize project documentation (CLAUDE.md) |
| `/review` | Review current branch changes |
| `/test` | Run tests and analyze results |
| `/stop` | Stop AI generation |
| `/compact` | Compress conversation to free up tokens |
| `/context` | Open Token usage Dashboard |
| `/resume` | Restore a saved session |
| `/export` | Export conversation to Markdown file |
| `/clear` | Clear input area |

## Compatibility

Compatible with all IntelliJ Platform-based IDEs:
IntelliJ IDEA, PhpStorm, WebStorm, PyCharm, GoLand, RubyMine, CLion, Rider, DataGrip, and more.

## Development

```bash
# Build plugin
./gradlew buildPlugin

# Run sandbox IDE (with hot reload)
./gradlew runIde

# Run tests
./gradlew test
```

## Tech Stack

Kotlin · Swing · IntelliJ Platform 2023.3 · DeepSeek Anthropic-compatible Messages API · SSE streaming

## Documentation

- [Project Docs](PROJECT.md) — Architecture & development guide
- [Design System](DESIGN.md) — UI design tokens & interaction specs
- [TODO List](TODO.md) — Missing features compared to Claude Code

## Privacy

- API Key stored encrypted via IntelliJ PasswordSafe
- No telemetry, no tracking, no data collection
- Code context is only sent to DeepSeek API when you send a message

## License

[Apache-2.0](LICENSE)
