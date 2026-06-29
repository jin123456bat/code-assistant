# 上下文管理

> 关联文档：[Agent Loop](./loop.md)

## 一、超长任务三层防线

| 层级    | 机制                        | 触发时机                              | 作用                       |
|-------|---------------------------|-----------------------------------|--------------------------|
| 1. 预防 | LLM 复杂度判断 + createPlan 工具 | 任务开始前 / 执行中途（复杂度自判，createPlan 已有） | 让 LLM 主动拆解任务，从源头避免失控     |
| 2. 预警 | 轮次预警                      | turn 达到 maxTurns 的 60%            | 提醒 LLM 评估剩余工作量，建议拆分      |
| 3. 兜底 | Auto-Compact              | 上下文超过 700K tokens                 | 压缩旧消息为摘要，确保 LLM 不丢失关键上下文 |

三层环环相扣：**预防**最优（不产生问题），**预警**次之（问题刚出现就提醒），**兜底**保底（问题已发生但自动修复）。

## 二、Auto-Compact

对齐 Claude Code：当会话消息历史接近模型上下文上限时，自动将旧消息压缩为摘要，避免消息被粗暴截断导致
LLM 丢失关键上下文。

### 触发时机

每次 `AgentLoop.run()` 构建 `params` 后、发送 API 请求前，估算当前 `messages` + system prompt + tools
的总 token 数。当估算值超过模型上下文窗口的 **70%** 时触发压缩。

- 模型上下文窗口写死为 **1,000,000 tokens**（DeepSeek V4 的 1M 上下文上限）
- 选择 70% 而非更接近 1.0：token 估算有 ±20% 误差，最坏情况下 700K / 0.8 = 875K 仍在 1M 窗口内留有安全余量；若设
  80%，最坏情况实际已达 1M，存在溢出风险

### 压缩策略（保留窗口 + 摘要）

1. 保留最近 N 条消息原文。`N = max(保留最近 2 轮的消息数, ceil(messages.size / 3))`。"保留最近 2 轮"
   是硬下限——即使 `messages.size` 很大，最近 2 轮（至少 4 条：user + assistant × 2）也绝不压缩
2. 早期消息 → 独立 API 调用生成摘要（不带 tools，`max_tokens=1024`），不阻塞主流程
3. 摘要插入消息列表头部，替换被压缩消息
4. 从 `session.messages` 中移除被压缩的旧消息原文（保留摘要消息在头部）
5. 后续 API 请求的 `messages` 参数变为 `[摘要消息, ...近期原文, 新用户消息]`
6. 多次压缩时，之前的摘要参与新一轮压缩（幂等）

**摘要生成 Prompt 模板：**

```
请将以下对话压缩为简洁摘要。保留：
- 用户的核心任务和目标
- 已完成的计划项和关键决策
- 当前进行中的工作和上下文
- 重要的文件路径、错误信息、技术约束

省略：思考过程、工具调用详情（具体参数/返回值）、中间探索性操作。

对话内容：
{需压缩的消息内容}
```

**与 Plan 的交互：** Plan 暂停时仍可压缩。压缩 prompt 中额外注入 plan 摘要（`plan.summary` +
`plans[*].description` + `plans[*].status`），确保 LLM 在压缩后的上下文中仍然知道计划的存在和进展。

### compact 后上下文重建（对齐 Claude Code）

compact 后从头重建上下文时：

| 内容                       | 行为                                                 |
|--------------------------|----------------------------------------------------|
| System Prompt            | 从 `SystemPromptBuilder` 重新构建（不变）                   |
| Tools 定义                 | 从 `ToolRegistry` 重新生成（不变）                          |
| Skill 正文（`/command` 触发时） | **从磁盘重新注入**（确保关键约束不因摘要质量丢失）                        |
| `@file` 文件内容             | **不重新注入**（LLM 应通过 `Read` 工具重新读取目标文件，不应依赖旧消息中的过期快照） |
| `session.messages`       | 旧消息被摘要替代，近期消息保留原文                                  |

### 压缩范围

`compactIfNeeded()` 基于**实际总 token** 判断是否触发压缩：

```
实际总 token = System Prompt + session.messages + Tools 定义
触发条件: 实际总 token > modelContextLimit × 0.7 (= 700K)
```

触发后**只压缩 `session.messages`**（将旧消息替换为摘要），System Prompt 和 Tools 定义保持不变。

| 组成部分               | 是否被 compact 压缩 | 是否每次重建                                          | 说明                                                 |
|--------------------|----------------|-------------------------------------------------|----------------------------------------------------|
| `session.messages` | 会（超阈值时）        | 否，持久化保留                                         | 唯一被压缩的部分。`@file` 注入的文件内容、MCP 工具结果均作为普通消息参与压缩，不单独保留 |
| System Prompt      | 不会             | 是，每次 `SystemPromptBuilder.build()`              | 含基础角色 + 工具使用原则 + 防幻觉规则 + 复杂度判断等                    |
| Tools 定义           | 不会             | 是，每次从 `ToolRegistry.generateToolDescriptions()` | 每个工具的 JSON Schema + 上限声明                           |
| Skill 注入正文         | 会参与压缩          | compact 后从磁盘重新注入（对齐 Claude Code）                | 确保关键约束不因摘要质量丢失。正文 ≤ 2000 tokens 以减少重新注入开销          |

### 子 Agent 压缩行为

子 Agent 拥有独立 Session，**复用父 Agent 的 Auto-Compact 机制**（同一阈值、同一压缩策略）。二者关系：

| 阶段                | 行为                                                                           |
|-------------------|------------------------------------------------------------------------------|
| 子 Agent 运行中       | 独立触发 compact，与父 Agent 互不干扰                                                   |
| 子 Agent 完成后       | 结果截断为 ≤ 2000 tokens 摘要返回父 Agent（见 [multi-agent.md §二](multi-agent.md#二关键约束)） |
| 父 Agent compact 时 | 子 Agent 结果摘要作为普通消息参与压缩，不递归进入子 Session                                        |

## 三、max_tokens 自动续写

当 LLM 在输出中途达到 `max_tokens` 限制时，Agent 自动追加一条 `role: "user", content: "继续"` 的 API
消息，让 LLM
从中断处继续输出。

```
while (turn < maxTurns && !cancelled):
  ...
  收到 MessageStop(stop_reason):
    ├─ "end_turn"    → 正常结束，退出 while
    ├─ "max_tokens"  → 自动追加 user message "继续" → continue while（不增加 turn 计数）
    └─ "stop_sequence" → 正常结束，消息尾部标注
```

**约束：**

| 规则       | 说明                                                                    |
|----------|-----------------------------------------------------------------------|
| 不持久化     | "继续"消息不持久化到 `session.messages`（避免污染会话历史），仅在当前 `params.messages` 中临时追加 |
| 不增加 turn | 自动续写不增加 `turn` 计数，不计入 `maxTurns` 限制                                   |
| 上限 5 次   | 最多连续续写 5 次，防止 LLM 陷入无限输出循环。`end_turn` 后 `continueStreak` 计数器重置        |
| 生命周期     | 仅在当前会话生命周期内有效。重启 IDE 后不自动续写，被截断消息保持原样                                 |

## 四、/clear 和 /new

`/clear` 和 `/new` 行为完全一致——重置当前会话。对齐 Claude Code 的 `/clear` 语义。

**行为：**

- 清空 `session.messages = []`
- 清空 `session.compactSummary = null`，`session.compactCount = 0`
- 清空 `session.plan = null`（如有活跃 Plan 一并丢弃）
- 清空 `session.totalTokens` 归零
- `session.id` 保持不变（不创建新文件，复用当前 session）
- 不保存旧消息（等价于"当前会话重新开始"）

**为什么复用 session 而不是新建：** 用户通常只是想让上下文干净一点，频繁新建 session 文件会堆积。

## 五、Token 估算

项目使用统一的 Token
估算方法，完整公式、精度说明和适用场景见 [token-estimation.md](../specs/token-estimation.md)。
