# TODO — 功能待办清单

对照 Claude Code 功能清单，列出本插件尚未实现的功能。按优先级排序。

## P0 — 核心能力（2 个）

- [ ] **子代理系统（Sub-Agent）**：支持创建子代理执行独立任务，前后台运行，自定义工具集/模型/权限
- [ ] **任务跟踪（Task System）**：TaskCreate / TaskList / TaskGet / TaskUpdate，Agent 自动分解任务并跟踪进度

## P1 — 交互增强（4 个）

- [ ] **上下文压缩（Context Compaction）**：长对话自动压缩历史，保留关键信息，释放 token 预算
- [ ] **更多斜杠命令**：/review（代码审查）、/test（运行测试）、/diff（查看变更）、/security-review（安全审查）等
- [ ] **会话管理**：会话命名/恢复/分支/导出，支持多会话并行
- [ ] **Memory 系统**：跨会话自动记忆，用户/项目/本地三层存储

## P2 — 代码智能增强（3 个）

- [ ] **快速诊断反馈**：每次 Write/Edit 后自动注入 IDE inspection 结果到 Agent 上下文（类似 Claude Code 的 LSP 自动诊断）
- [ ] **find_implementations / call_hierarchy**：接口实现查找和调用层级（需 Java 模块支持，或通过可选的 `depends` 声明）
- [ ] **代码审查（/review）**：集成 IDE diff 视图，多维度审查（正确性/安全/性能/可读性）

## P3 — 自动化（4 个）

- [ ] **Hooks 系统**：事件驱动自动化（PreToolUse / PostToolUse / SessionStart / SessionEnd 等 30+ 事件）
- [ ] **Loop 模式**：定时/自适应循环执行提示词
- [ ] **后台代理**：Agent 后台运行，用户可继续编辑代码
- [ ] **Workflow 编排**：多子代理并行/流水线编排

## P4 — 安全与生态（4 个）

- [ ] **沙箱（Sandbox）**：文件系统/网络隔离执行环境
- [ ] **文件变更前后 Hook**：FileChanged / WorktreeCreate 等
- [ ] **插件市场集成**：支持从 marketplace 安装 Skills/Agents/MCP
- [ ] **通知推送**：桌面/手机推送长期运行任务结果

## P5 — 体验细节（5 个）

- [ ] **Diff 交互查看器**：左右箭头切换文件，上下浏览差异行
- [ ] **Vim 模式**：输入框支持 Vim 编辑键位
- [ ] **键盘快捷键**：可自定义的快捷键系统
- [ ] **非交互模式**：右键/快捷键一键执行 Agent 任务
- [ ] **上下文可视化**：/context 查看当前 token 使用分布

---

共 22 项，其中 P0 2 项、P1 4 项、P2 3 项、P3 4 项、P4 4 项、P5 5 项。
