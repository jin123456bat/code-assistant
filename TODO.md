# TODO — 功能待办清单

master 分支轻量路线：代码补全 + Git Message + Settings

---

## P0 — 核心体验

- [ ] **补全延迟优化** ✅ 已完成（2026-06-24）
  连接超时 5s→2s、读取 10s→3s、callTimeout 3s、maxTokens 1024→256

- [ ] **补全质量迭代**
  收集 20 个 PHP 补全场景，人工评估接受率，针对性优化 prompt 和上下文策略

- [ ] **Git Message 触发优化**
  当前仅在 VCS Commit 对话框内可用，改为右键菜单也可触发

## P1 — 快速增值

- [ ] **流式对话面板（轻量版）**
  IDE 内置 Markdown 渲染对话框，不做 Agent 循环——只做 Q&A

- [ ] **补全统计面板**
  Settings 面板中展示接受率、延迟分布，让用户看到价值

- [ ] **黑暗模式自动跟随**
  跟随 IDE 主题自动切换，无需手动配置

## P2 — 扩展

- [ ] **多 LLM 支持**
  OpenAI/Claude API 切换，模型选择下拉扩展

- [ ] **补全触发方式可选**
  保持列表式还是改为 Copilot 灰显式，待 UX 测试

- [ ] **Marketplace 发布**
  JetBrains Marketplace 审核 + 发布

## P3 — 考虑中

- [ ] **开源**
  GitHub 公开仓库，Apache 2.0 协议

- [ ] **插件改名**
  "Code Assistant"太通用，需要更有辨识度的名称
