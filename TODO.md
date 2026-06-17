# TODO — 功能待办清单

对照 Claude Code 功能清单，按优先级排列。

---

## P0 — 核心能力

- [ ] **Task 任务跟踪**
  `TaskCreate` / `TaskList` / `TaskGet` / `TaskUpdate`，Agent 自动分解任务并跟踪进度

## P1 — 交互增强

- [ ] **更多斜杠命令**
  `/review` 代码审查、`/test` 运行测试、`/diff` 查看变更、`/security-review` 安全审查

- [ ] **会话管理**
  会话命名、恢复、分支、导出，多会话并行

- [ ] **Memory 记忆系统**
  跨会话自动记忆，用户/项目/本地三层存储，`/memory` 命令管理

## P2 — 代码智能增强

- [ ] **快速诊断反馈**
  每次 `Write`/`Edit` 后自动注入 IDE Inspection 结果到 Agent 上下文（类似 Claude Code LSP 自动诊断）

- [ ] **调用层级 Call Hierarchy**
  `incomingCalls` / `outgoingCalls` 操作（需 `CallHierarchyProvider` EP，per-language 实现）

- [ ] **代码审查 Review**
  集成 IDE diff 视图，多维度审查（正确性/安全/性能/可读性），支持 `--fix` 自动修复

## P3 — 自动化

- [ ] **Hooks 事件系统**
  30+ 事件（`PreToolUse` / `PostToolUse` / `SessionStart` / `SessionEnd` 等），Command/HTTP/MCP/Prompt 多种 hook 类型

- [ ] **Loop 循环模式**
  `/loop [interval] [prompt]` 定时或自适应间隔循环执行

- [ ] **后台代理**
  Agent 后台运行不阻塞编辑，`/background` 启动，`/tasks` 查看进度

- [ ] **Workflow 工作流编排**
  多子代理并行/流水线编排，`Workflow` 工具 + `/workflows` 查看进度

## P4 — 安全与生态

- [ ] **Sandbox 沙箱**
  文件系统/网络/OS 级隔离执行环境

- [ ] **文件变更 Hook**
  `FileChanged` / `WorktreeCreate` / `WorktreeRemove` 事件

- [ ] **插件市场集成**
  支持从 Marketplace 安装 Skills / Agents / MCP Servers

- [ ] **Push 通知**
  桌面/手机推送长期运行任务完成通知

## P5 — 体验细节

- [ ] **交互式 Diff 查看器**
  左右箭头切换文件，上下浏览差异行

- [ ] **Vim 模式**
  输入框支持 Vim 编辑键位

- [ ] **快捷键系统**
  可自定义的键盘快捷键（200+ 可绑定动作）

- [ ] **非交互/Headless 模式**
  右键菜单一键执行 Agent 任务，不打开工具窗

- [ ] **上下文可视化**
  `/context` 查看当前 token 使用分布，压缩建议

---

共 22 项：P0 × 2、P1 × 4、P2 × 3、P3 × 4、P4 × 4、P5 × 5