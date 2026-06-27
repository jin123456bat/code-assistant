# Skill 系统

> 关联文档：[[tools]], [[../specs/system-prompt]]

兼容 Claude Code / Codex SKILL.md 格式。Skill 持久化数据位于项目根目录，IDEA 插件启动时自动加载。

---

## 一、概述

Skill 系统将领域知识以 `SKILL.md` 文件形式注入 Agent 对话。兼容 **Claude Code** 和 **Codex** 的 Skill
格式，用户可直接复用社区 Skills。

Skill 通过两种方式触发：

- **LLM 自动触发**：LLM 调用内置 `Skill` 工具，参数为 skill 名称
- **用户手动调用**：用户在输入框中输入 `/command`（如 `/review`）

---

## 二、扫描目录

按 **Code-Assistant > Claude > Codex** 优先级扫描，同名 Skill 先扫到的覆盖后扫到的。同一平台内项目级优先于用户级：

- `.code-assistant/skills/` — 主目录，优先级最高。安装 Skill 统一写入此目录
- `.claude/skills/` — 兼容 Claude Code 项目级（只读）
- `~/.claude/skills/` — 兼容 Claude Code 用户级（只读）
- `.codex/skills/` — 兼容 Codex 项目级（只读）
- `~/.codex/skills/` — 兼容 Codex 用户级（只读）

---

## 三、SKILL.md 格式

YAML frontmatter + Markdown 正文：

```yaml
---
name: code-review
description: 审查代码质量
command: review
tools:
  - Read
  - Bash
---
# 正文（Markdown）
```

- `name`：Skill 唯一标识
- `description`：简短描述，注入 System Prompt 的 Skill 列表中
- `command`：用户手动调用时的 `/` 命令名（如 `review` → `/review`）
- `tools`：声明的依赖工具列表，用于与 `ToolRegistry` 交叉验证
- 正文：Markdown 格式，注入 conversation 供 LLM 遵循

---

## 四、注册 & 验证

- `SkillManager` 启动时扫描目录，解析注册。Skill 文件更新后需执行 `/reload-skill` 重新加载
- 工具声明与 `ToolRegistry` 交叉验证——不存在则发出警告标记（启动时一次性检查，结果体现在 Skill 列表中，
  `hasMissingTools=true` 的 Skill 不可调用）
- System Prompt 末尾注入 Skill 列表（名称 + 描述），不包含完整正文
- LLM 通过内置 `Skill` 工具触发 Skill（对齐 Claude Code），不做关键词匹配
- 用户也可手动 `/command` 调用

---

## 五、调用方式

### LLM 自动触发

LLM 根据 System Prompt 末尾注入的 Skill 列表（`name` + 截断 `description`）**自主判断**触发时机，调用
`Skill` 工具（参数：skill 名称）→ `SkillManager` 加载 SKILL.md → 正文作为消息注入
conversation → LLM 下一轮按指令执行。不存在单独的"触发词"字段——LLM 通过语义匹配 `name` 和
`description` 决定何时调用。后续不再重复注入（避免 context 膨胀），compact 时被调用过的 skill
重新注入。

### 用户手动调用

输入 `/command`（如 `/review`）→ `AgentLoop` 解析 `/` 指令 → `SkillManager.getByCommand(command)` →
正文作为消息注入 conversation。绕过 LLM 决策，直接加载。

---

## 六、Skills 页面

Skills 页面提供 Skill 列表管理界面：

```
┌──────────────────────────────────────────────────┐
│ [🏠] [💬] [📁] [📊] [🔌] [🎯*] [⚙]        │
├──────────────────────────────────────────────────┤
│  Skill 目录: .code-assistant/skills/              │
│  [📂 打开目录]  [➕ 新建 Skill]                   │
├──────────────────────────────────────────────────┤
│                                                  │
│  ┌──────────────────────────────────────────────┐│
│  │ [✅] code-review                      [详情] ││ ← 启用/禁用开关
│  │      审查代码质量，查找 bug 和安全问题        ││
│  │      命令: /review                         ││
│  │      所需工具: Read, Bash            ││
│  │                                               ││
│  │ [✅] refactor                         [详情] ││
│  │      重构代码结构，改进可读性                 ││
│  │      命令: /refactor                       ││
│  │      所需工具: Read, Edit, Write  ││
│  │                                               ││
│  │ [❌] docker-helper              ⚠ 工具缺失   ││ ← 禁用+警告
│  │      Docker 容器管理和编排                   ││
│  │      ⚠ 声明了不存在的工具: docker-compose    ││
│  └──────────────────────────────────────────────┘│
└──────────────────────────────────────────────────┘
```

**添加 Skill 流程：** 点击"➕ 新建"→ 内联表单填写名称/描述/命令/依赖工具/正文(Markdown) → "创建" →
`SkillManager` 创建 `SKILL.md` 文件到 `.code-assistant/skills/<name>/` 目录 → 工具声明与
`ToolRegistry` 交叉验证 → 卡片出现在列表中，switch 默认启用。

---

## 七、SkillManager 接口

```
SkillManager
├── loadSkills(basePath: Path): List<Skill>    // 扫描 .code-assistant/skills/、.claude/skills/、~/.claude/skills/、.codex/skills/、~/.codex/skills/。同名 Skill 按 Code-Assistant > Claude > Codex 优先级，同平台项目级优先于用户级，先扫到的覆盖后扫到的。安装 Skill 统一写入 .code-assistant/skills/
├── getEnabledSkills(): List<Skill>
├── enableSkill(name: String)
├── disableSkill(name: String)
├── getByCommand(command: String): Skill?               // 按 /command 查找（如 /review）
├── getSystemPromptExtension(): String               // Skill 列表（名称 + 截断描述），注入 System Prompt 末尾，不包含正文
└── getTriggeredContent(skills: List<Skill>): String // LLM 调用 Skill 工具时获取 SKILL.md 正文，作为消息注入 conversation

Skill:
├── name: String
├── description: String
├── command: String                   // YAML `command` 字段（如 "review"）
├── requiredTools: List<String>       // YAML `tools` 字段
├── content: String                   // SKILL.md 正文
├── enabled: Boolean
└── hasMissingTools: Boolean          // 声明的工具是否全部存在于 ToolRegistry
```
