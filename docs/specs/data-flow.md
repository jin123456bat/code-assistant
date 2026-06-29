# 数据流时序

> **原始来源：** `tech-spec.md`（已拆分，内容归入 `specs/` 各文件）

本文档描述用户发送消息和 Plan 执行两条核心数据流的完整时序。

---

## 5.1 用户发送消息

```
ChatInputArea.enterPressed()
  → ChatViewModel.sendMessage(text, attachments, images)
    → buildContext(text, attachments, images)  // 组装 @file + 选中代码 + 图片到 userContent
    → AgentSession.addMessage(USER, userContent)
    → AgentSession.setState(PROCESSING)
    → AgentLoop.run(userContent)
        → while (turn < maxTurns && !cancelled):
            → if (stopReason != "max_tokens") turn++  // 仅用户消息触发的正常 API 调用计数，续写不增加 turn
            → 构建 params：
                system = SystemPromptBuilder.build(toolRegistry, skillManager)
                messages = session.messages       // 每轮从 session.messages 重建
                tools = toolRegistry.toToolDefinitions()
            → compactIfNeeded(system, messages, tools, modelContextLimit)  // 估算实际总 token（system + messages + tools），超 70% 阈值则压缩 messages
                → 如触发压缩：独立 API 调用（不带 tools, max_tokens=1024）生成摘要
                → session.messages = [摘要消息, ...近期原文(≥2轮)]
                → 重建 params.messages = session.messages
                → SessionStore.save()
            → client.messages().createStreaming(params)
              .forEach { chunk →
                when (chunk):
                  ContentBlockDelta.text →
                    emit(TextDelta(text))       // → ViewModel → UI
                  ContentBlockStart.toolUse →
                    emit(ToolCallStarted(name))  // → ToolCallCard(PENDING)
                  MessageStop →
                    when (stopReason):
                      "end_turn"       → emit(TurnCompleted(usage)) → 退出 while
                      "max_tokens"     → continueStreak++
                                         if (continueStreak > maxAutoContinue):
                                           emit Error + 退出 while
                                         else:
                                           params.messages += UserMessage("继续") → continue while
                                         （续写不增加 turn：while 顶部跳过 turn++。"继续"不持久化，
                                         仅当前会话生命周期内有效，重启 IDE 后不自动续写）
                      "stop_sequence"  → emit(TurnCompleted(usage)) → 退出 while（尾部标注）
              }
            → for each toolUse:
                if (requiresApproval):
                  emit(AgentState.AWAITING_APPROVAL)
                  latch = CountDownLatch(1)
                  invokeLater { showDialog() → latch.countDown() }
                  latch.await()  // 无超时，等待用户手动处理
                if (approved):
                  ToolCallCard.setState(EXECUTING)
                  result = tool.execute(params, session, onOutput)
                  ToolCallCard.setState(DONE/ERROR)
                  emit(ToolCallCompleted(name, result))
                  params = params.add(toolCallResult)

    → 流式中断处理：
        用户点停止按钮(⏹)（三件事同时做）:
          → cancelled=true （退出 Agent while 循环）
          → client.close() （取消当前 HTTP 请求）
          → destroyForcibly()：遍历当前 AgentSession 的 runningProcesses 销毁子进程
          → 已累积的 BetaMessageAccumulator 内容持久化到 session.messages
          → 当前 streamingBuf 中未 flush 的内容丢弃
          → 已解析但未执行的 tool call → CANCELLED，不持久化
          → Agent 状态 → IDLE
        Escape 键：仅关闭 Popup，不影响 Agent 执行。
        被动断连（SocketTimeoutException）:
          → 已渲染内容保留 + 持久化（含尾部 [连接中断] 标注）
          → 用户点击 [重试] → 发送相同 params 重新开始当前 turn
    → AgentSession.setState(IDLE)
    → SessionStore.save(session)
    → messageBus.publish(TokenUsageUpdated)
```

### 续写机制说明

- `maxAutoContinue = 5`：单轮内连续续写链最大长度，防止死循环
- 续写不增加 `turn` 计数（while 顶部 `if (stopReason != "max_tokens") turn++` 跳过）
- `continueStreak` 在 `end_turn` 后归零
- `"继续"` 消息不持久化，仅当前会话生命周期内有效，重启 IDE 后不自动续写

---

## 5.2 Plan 执行

```
ChatViewModel.sendMessage("/plan 重构 UserService", ...)
  → PlanExecutor.generatePlan(task)
    → AgentLoop.run("plan-request:" + task)  // 生成计划
    → parsePlan(llmOutput)  // 4 层解析
    → PlanCard 渲染
    → 自动开始执行

PlanExecutor.executeNext() 自动循环:
  → 获取 nextPlan (currentPlanIndex 对应的 PAUSED 计划项)
  → AgentLoop.run(plan.summary)
  → 更新 plan.status + plan.result
  → currentPlanIndex++
  → 自动继续执行下一个
  → 全部 COMPLETED → PlanCard 消失
  → LLM 可在任意计划项完成后通过工具调整计划（removePlan/reorderPlans/markPlanDone/createPlan）

LLM 通过工具管理计划:
  listPlans()    → 查看计划项状态
  removePlan(id) → 删除 PAUSED 计划项，执行时自动跳过
  reorderPlans([id...]) → 重排 PAUSED 计划项顺序
  markPlanDone(id) → 将计划项标记为 COMPLETED
  createPlan(task, plans) → 重新创建/更新计划
```

### Plan 解析流程（4 层回退）

1. **JSON 代码块**：提取 ` ```json ... ``` ` → Gson 反序列化 → `Plan`
    - 示例输入：
      `` ```json {"task": "重构", "plans": [{"description": "读取文件", "tool": "Read", "files": ["UserService.kt"]}]} ``` ``
2. **裸 JSON**：搜索 `{ ... }` → Gson 反序列化 → `Plan`（跳过代码块标记不匹配的情况）
    - 示例输入：`{"task": "重构 UserService", "plans": [...]}`（无 ``` 包裹）
3. **正则文本**：正则 `/Plan \d+:/` → 按 `Plan N:` 拆分文本 → `Plan`
    - 示例输入：`Plan 1: 读取 UserService.kt，用 Read 工具\nPlan 2: 修改方法签名，用 Edit 工具`
4. **原始文本**：前三层均失败时兜底 → `Plan(summary=原始文本, plans=[PlanItem(description=原始文本)])`
    - 将整个 LLM 输出作为一个计划项，让 PlanExecutor 逐条尝试

> 任一层解析成功后立即返回，不再尝试后续层。第 4 层为无条件兜底（原始文本 → Plan），永不失败。
>
> **注意：** 4 层解析仅用于 `/plan` 命令场景（LLM 输出文本 → 解析为结构化 Plan）。`createPlan` 工具走
`createPlanFromTool()` 直接拿结构化参数，不经过解析。

### Plan 生命周期

- 创建计划后自动开始，逐步执行直到全部 COMPLETED 或 LLM 调用 `removePlan` 删除所有计划项终止（CANCELLED）
- 入口：用户 `/plan` 命令或 LLM 通过 `createPlan` 工具主动创建。两者触发 `generatePlan()` 流程一致
