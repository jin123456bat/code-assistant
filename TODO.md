# TODO — 功能待办清单

对照 Claude Code 功能清单，按优先级排列。

---

## P0 — 核心能力

- [x] **Task 任务跟踪** ✅ 已完成
  `TaskCreate` / `TaskList` / `TaskGet` / `TaskUpdate`，Agent 自动分解任务并跟踪进度，PlanBar UI 展示进度

## P1 — 交互增强

- [x] **更多斜杠命令** ✅ 已完成
  `/review`(审查+--fix+--comment)、`/test`(执行+解析)、`/diff`(变更摘要)、`/security-review`(安全五维度)、`/fix`(修复测试)

- [x] **会话管理** ✅ 已完成
  自动保存 + `/resume` 恢复 + `/export` 导出 Markdown

- [x] **Token 精细化追踪** ✅ 已完成
  气泡悬停显示 token 消耗，📊 Dashboard 天/周统计，设置中可关闭

- [x] **Memory 记忆系统** ✅ 已完成
  跨会话自动记忆(MemoryStore+Engine+Relevance+AutoExtract)，`/memory` 命令管理，对齐 Claude Code 文件格式

## P2 — 代码智能增强

- [x] **快速诊断反馈** ✅ 已完成
  每次 `Write`/`Edit` 后自动注入 IDE Inspection 结果（DaemonCodeAnalyzer→Agent 上下文 `## 代码诊断`）

- [x] **调用层级 Call Hierarchy** ✅ 已完成
  `code_intelligence` 新增 `incoming_calls`(ReferencesSearch) / `outgoing_calls`(PsiRecursiveElementVisitor)，不需额外 EP

- [x] **代码审查 Review** ✅ 已完成
  四维度审查引擎(正确性/简化/效率/安全)+ReviewResultPanel IDE集成+右键菜单+`--fix`+`--comment`

## P3 — 自动化

- [x] **Hooks 事件系统** ✅ 已完成
  13 事件(PreToolUse/PostToolUse/SessionStart/End/Stop…) + Command/HTTP/MCP/Prompt 4 种 hook 类型，对齐 Claude Code

- [x] **Loop 循环模式** ⛔ wontfix
  CLI 场景特有（终端定时轮询），IDE 插件用户无此场景

- [x] **后台代理** ⛔ wontfix
  Agent 已在后台 Thread 运行，子 Agent（task/workflow 工具）满足并行需求。IDE 环境下独立多会话场景不存在。

- [x] **Workflow 工作流编排** ✅ 已完成
  `workflow` 工具：并行/串行多子 Agent + 自动结果合并

## P4 — 安全与生态

- [x] **Sandbox 沙箱** ⛔ wontfix
  审批卡已提供有效防护，容器/VM 级沙箱实现极重

- [x] **文件变更 Hook** ✅ 已完成
  Hooks 系统已支持 `FileChanged`/`WorktreeCreate`/`WorktreeRemove` 事件

- [x] **插件市场集成** ⛔ wontfix
  Skills/Agents/MCP 已可通过文件系统和配置管理，Marketplace UI 非必需

- [x] **Push 通知** ⛔ wontfix
  需推送服务+移动端基础设施，IDE 插件场景不存在

## P5 — 体验细节

- [x] **交互式 Diff 查看器** ⛔ wontfix
  IntelliJ 自带 diff 已满足需求，重复造轮子

- [x] **Vim 模式** ⛔ wontfix
  IntelliJ 已有 IdeaVim 插件，输入框 Vim 键位场景不存在

- [x] **快捷键系统** ⛔ wontfix
  IntelliJ Keymap EP + Action 注册即可绑定，无需额外系统

- [x] **非交互/Headless 模式** ✅ 已完成
  右键菜单 7 动作(审查/安全/修复/解释/优化/注释/测试)，不打开工具窗直接送 Agent

- [x] **上下文可视化** ✅ 已完成
  `/context` 查看 Token 用量 Dashboard，自动 compact

---

共 22 项：已完成 13、wontfix 2、待做 7

| P0 | P1 | P2 | P3 | P4 | P5 |
|----|----|----|----|----|----|
| 2/2 ✅ | 4/4 ✅ | 3/3 ✅ | 2/2 ✅ + 2 wontfix | 0/4 | 0/5 |