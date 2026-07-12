# Agent 气泡单一所有者 Implementation Plan

**Goal:** 让流式、最终和历史 Agent 消息复用同一种稳定气泡组件，消除反复重建和跨模块尺寸所有权。

**Architecture:** `ChatBubbleRenderer.AgentBubbleHandle` 持有稳定顶层组件并提供 `updateStreaming()`、`finish()`、`constrainWidth()` 和 `dispose()`。`ChatPage` 保存 handle 而不是临时 `JComponent`，流式更新与最终完成均调用 handle；宽度算法只向 Renderer 传入最大宽度。

**Tech Stack:** Kotlin、Java Swing、IntelliJ Platform、kotlin-test、Gradle

## Global Constraints

- 保留用户现有的非气泡工作区修改。
- 恢复 Agent 卡片的统一圆角边框、左侧 3px 蓝条和 12px 内边距；时间戳明确保持在气泡外。
- 不新增依赖，不修改用户气泡、工具卡片和思考块视觉规范。
- 先观察新增测试失败，再修改生产代码。

### Task 1: 锁定稳定组件契约

**Files:**
- Modify: `src/test/kotlin/com/aiassistant/ui/chat/ChatBubbleRendererTest.kt`
- Modify: `src/test/kotlin/com/aiassistant/ui/page/ChatPageTest.kt`

- [x] 新增测试：同一 handle 的两次流式更新保持相同 `component`。
- [x] 新增测试：`finish()` 后仍是同一个组件，光标消失且时间戳位于圆角卡片外部。
- [x] 新增测试：`constrainWidth()` 由 Renderer 处理，不依赖 `ChatPage` 猜测第一层子组件。
- [x] 运行目标测试并确认当前实现因缺少 handle API 或仍重建组件而失败。

### Task 2: 实现稳定 AgentBubbleHandle

**Files:**
- Modify: `src/main/kotlin/com/aiassistant/ui/chat/ChatBubbleRenderer.kt`

- [x] 增加 `AgentBubbleHandle`，公开稳定 `component` 并实现 `updateStreaming(markdownText)`、`finish(message)`、`constrainWidth(maxWidth)`、`dispose()`。
- [x] 抽取 Markdown body 填充逻辑，使更新只替换卡片内部内容，不替换顶层气泡。
- [x] 恢复单一卡片边框结构：`RoundedBorder` + 左侧蓝条 + 内边距；时间戳保持在卡片外部。
- [x] `render(AGENT_TEXT)` 与 `renderStreaming()` 复用同一 handle 实现。
- [x] 将气泡内部宽度选择和 capped-height 刷新收口到 `ChatBubbleRenderer.constrainWidth()`。

### Task 3: ChatPage 原地完成流式气泡

**Files:**
- Modify: `src/main/kotlin/com/aiassistant/ui/page/ChatPage.kt`

- [x] 将 `streamingBubble: JComponent?` 改为 `streamingBubble: AgentBubbleHandle?`。
- [x] 首个 token 创建并添加一次组件；后续 token 只调用 `updateStreaming()`。
- [x] 最终 `AGENT_TEXT` 到达时调用 `finish()` 并复用现有组件，不再删除和动画创建新气泡。
- [x] 清空、取消、工具调用切换时调用 handle 的 `dispose()` 并移除稳定组件。
- [x] `updateBubbleMaxWidths()` 改为调用 Renderer 宽度 API，不再遍历内部层级寻找 target。

### Task 4: 验证与审查

- [x] 运行 Renderer 与 ChatPage 目标测试。
- [ ] 运行 `./gradlew test --rerun-tasks`。
- [ ] 运行 `./gradlew buildPlugin` 和 `git diff --check`。
- [ ] 测试 IDE 中验证流式、完成和历史消息视觉一致。
- [ ] 派独立子代理复核组件所有权、生命周期和工作区边界。
