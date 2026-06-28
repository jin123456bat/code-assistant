# Plan Mode

> 关联文档：[Agent Loop](./loop.md)、[工具系统](./tools.md)

## 一、职责边界

Plan Mode 将复杂任务拆解为有序计划列表，创建后自动连续执行。LLM 通过 5 个计划管理工具自主管理执行流程。

- **PlanCard**：纯 UI 组件，展示计划列表及进度。每个 PAUSED 计划项行末有 [✕] 删除按钮（**用户唯一干预入口
  **）。头部可点击折叠/展开——默认折叠（仅显示标题+摘要+进度），用户点击展开查看完整计划列表。EXECUTING
  状态下自动展开且不可折叠。全部计划项 COMPLETED 或被取消后 PlanCard 消失。
- **PlanExecutor**：负责自动执行。创建计划后按顺序串行执行，每个计划项完成后自动进入下一个。LLM 通过
  `removePlan`/`reorderPlans`/`markPlanDone` 工具自主控制执行节奏，无需用户干预。

## 二、交互模式

默认**自动连续执行**，用户可随时暂停进入手动控制：

```
默认自动连续执行:

Plan 1 COMPLETED → 自动执行 Plan 2 → Plan 2 COMPLETED → 自动执行 Plan 3 → ...
  LLM 可在任意时刻通过工具调整计划：
    - 删除 → removePlan（仅 PAUSED 状态可删）
    - 调整顺序 → reorderPlans
    - 标记完成 → markPlanDone
    - 重新规划 → createPlan
  LLM 判断任务完成或无法继续时，自行终止循环

Plan 出错了？
  → LLM 自行判断：重试 / 调用 removePlan 删除 / 调用 reorderPlans 调整顺序
  → 继续自动执行剩余计划项
```

### 用户干预方式

- 仅可点 [✕] 删除 PAUSED 计划项（PlanCard 行末按钮），LLM 收到通知后跳过该项
- 全局停止按钮(⏹) 终止当前 Agent 执行，Plan 保持当前状态。用户再次发送消息时，LLM 根据 Plan 状态自行决定是否继续
- 除此之外的所有执行控制（暂停/继续/重排/标记完成）均由 LLM 通过工具自主决策，用户不直接操作

### 常见中断场景

**场景 1：用户提供额外信息**

```
用户: "Plan 3 还要修改 AuthInterceptor.kt"
→ PlanExecutor: 在 Plan 3 的文件列表中追加 AuthInterceptor.kt
→ 用户再次发送消息 → 执行更新后的 Plan 3
```

**场景 2：用户提前终止**

```
用户: 说 "剩下的不需要了" → LLM 调用 removePlan 删除所有剩余计划项
→ 剩余计划项标记 CANCELLED → 计划结束
```

**场景 3：执行出错需要人工介入**

```
用户: "用 @AuthService.kt:80-100 的内容重新读一遍"
→ Agent 读新文件 → 继续执行
```

### 关键行为

- **自动执行**：创建后自动开始，每个计划项完成后自动进入下一个，不等待用户
- **暂停**：用户点全局停止按钮(⏹) → 终止当前 Agent 执行，Plan 保持当前状态
- **继续**：用户再次发送消息 → LLM 根据 Plan 状态自行决定是否继续执行（可先回应消息再继续，也可直接执行下一个）
- **终止**：用户通过 LLM 调用 removePlan 工具删除所有剩余计划项 → Plan 标记 CANCELLED
- **单项删除**：PAUSED 计划项行末有 [✕] 按钮，用户可删除不需要的计划项
- **计划持久化**：暂停的计划跨会话保留。关闭 IDE 再打开 → 从 Sessions 页面恢复。重启时若 Plan 为
  EXECUTING 则重置为 PAUSED，Agent 保持 IDLE，等待用户发送下一条消息
- **计划中可自由聊天**：暂停时用户可发送任意消息，消息追加到同一 Session。LLM
  自行判断消息意图——纯咨询则先回答再继续执行，要求继续则执行下一个计划项。Plan 状态保持 PAUSED

## 三、Plan 生命周期

```
创建 → 执行 → 完成/取消

1. 创建：用户 /plan 或 LLM 调用 createPlan 工具，状态 PAUSED
2. 执行：创建后自动开始，状态变为 EXECUTING，逐步执行直到全部完成
3. 单项删除：用户通过 PlanCard [✕] 或 LLM 通过 removePlan 工具删除任意 PAUSED 计划项，自动跳过该项继续执行
4. 完成：全部计划项 COMPLETED 或全部被取消 → Plan 状态变为 COMPLETED 或 CANCELLED → PlanCard 消失
5. 持久化：跨会话保留，关闭 IDE 再打开可恢复
```

## 四、Plan 状态

Plan 及其子计划项共用同一套状态：

| 状态          | 含义                               |
|-------------|----------------------------------|
| `PAUSED`    | 已创建，尚未开始执行；或用户暂停，等待继续            |
| `EXECUTING` | 正在执行中，LLM 通过工具自主控制执行流程           |
| `COMPLETED` | 执行完成                             |
| `CANCELLED` | 被取消（LLM 调用 removePlan 删除所有剩余计划项） |

## 五、计划管理工具

LLM 通过 5 个工具自主管理任务列表：

| 工具             | 参数                      | 用途                                   |
|----------------|-------------------------|--------------------------------------|
| `createPlan`   | `task` + `plans[]`      | 创建/更新执行计划，自动开始执行，≤ 20 项              |
| `listPlans`    | 无                       | 查看当前计划的所有计划项及状态                      |
| `removePlan`   | `planId: String`        | 删除指定计划项（仅 PAUSED 状态可删）               |
| `reorderPlans` | `planIds: List<String>` | 重排剩余 PAUSED 计划项的执行顺序（传入新的 planId 序列） |
| `markPlanDone` | `planId: String`        | 将指定计划项标记为 COMPLETED                  |

LLM 在执行过程中可随时调用这些工具调整计划：

- 发现某个计划项不再需要 → `removePlan`
- 执行中意识到顺序不合理 → `reorderPlans`
- 计划项已通过其他方式完成 → `markPlanDone`
- 任务范围扩大需要重新规划 → `createPlan`
- 不确定当前进度 → `listPlans`

### 工具自由度

`tool` 字段是**建议**而非**约束**。LLM 可以调用预期之外的工具，但应在结果中说明原因。

## 六、createPlan 工具

LLM 在执行过程中**随时主动**创建正式执行计划：

| 字段   | 说明                                             |
|------|------------------------------------------------|
| 工具名  | `createPlan`                                   |
| 参数   | `task`（任务描述）+ `plans[]`（计划项列表，每项含描述、预期工具、涉及文件） |
| 触发时机 | 任务涉及 3 个以上文件，或 LLM 读了 5 个文件后发现还有更多要改           |
| 效果   | 创建 PlanCard 并持久化，自动开始执行                        |

**与 `/plan` 命令的关系：**

|       | `/plan` 命令  | `createPlan` 工具  |
|-------|-------------|------------------|
| 触发方   | 用户手动输入      | LLM 根据实际情况主动创建   |
| 创建时机  | 任务开始前       | 执行中途，LLM 已充分了解项目 |
| 计划准确性 | LLM 凭用户描述猜测 | LLM 已读代码，计划更精准   |

**工具描述提示词（供 LLM 理解何时使用）：**
> 当任务涉及 3 个以上文件或预计需要 5 轮以上完成时，在执行关键修改前先调用 createPlan 创建执行计划。执行中可随时用
> listPlans/removePlan/reorderPlans 管理。

## 七、边界处理

| 场景                   | 行为                                                                                                 |
|----------------------|----------------------------------------------------------------------------------------------------|
| 用户修改了 Plan 后续项       | 修改直接更新 Session JSON 的 `plan.plans` 数组，恢复时展示修改后的计划                                                  |
| LLM 需要调整剩余项          | 调用 `reorderPlans` / `removePlan` 自主管理                                                              |
| 用户删除 PAUSED 项        | 通过 PlanCard [✕] 按钮 → PlanExecutor.removePlan()，LLM 收到通知后跳过该项                                       |
| LLM 终止计划             | LLM 调用 removePlan 删除所有剩余项 → Plan 标记 CANCELLED                                                      |
| 多个会话各有暂停计划           | 允许。每个会话独立存储。Sessions 页面标注"⏸ 计划暂停中"                                                                 |
| Plan 暂停期间切换到 Chat 输入 | 新消息追加到同一个 session。LLM 上下文包含暂停的计划摘要 + 新消息。LLM 自行判断消息意图——纯咨询则先回答再继续执行，要求继续则执行下一个计划项。Plan 状态保持 PAUSED |

**Plan 与会话的关系：**

- 一个 Session 最多一个活跃 Plan。LLM 调用 `createPlan` 重新规划时，新 Plan 自动成为活跃 Plan，旧 Plan
  的 EXECUTING 项重置为 PAUSED。旧 Plan 项的清理（标记 CANCELLED 等）需 LLM 主动调用 `removePlan`/
  `markPlanDone` 或用户手动删除
- Plan COMPLETED 或 CANCELLED 后，Session 可继续当作普通 Chat 会话
- Plan 暂停时，用户可在同一 Session 中继续聊天，消息追加到同一 message 列表
- Plan 状态独立于 AgentSession 状态——Plan 暂停但 Agent 可以 IDLE/聊天

## 八、Plan JSON Schema

存于 Session JSON 的 `plan` 字段：

```json
{
  "plan": {
    "id": "plan-1",
    "status": "EXECUTING",
    "summary": "将 UserService.findById 改为 suspend",
    "currentPlanIndex": 1,
    "plans": [
      {
        "id": "plan-1",
        "description": "读取文件",
        "tool": "Read",
        "files": ["UserService.kt:40-60"],
        "status": "COMPLETED",
        "result": "成功读取 156 行",
        "retryCount": 0
      },
      {
        "id": "plan-2",
        "description": "修改方法签名",
        "tool": "Edit",
        "files": ["UserService.kt"],
        "status": "PAUSED",
        "retryCount": 0
      }
    ],
    "createdAt": "2026-06-24T14:30:00Z",
    "updatedAt": "2026-06-24T14:45:00Z"
  }
}
```

## 九、PlanExecutor 接口

```
PlanExecutor
├── 构造: PlanExecutor(session: AgentSession, agentLoop: AgentLoop)
├── generatePlan(task: String): Plan       // /plan 或 createPlan → LLM 输出 → 4 层解析 → Plan
├── createPlanFromTool(task: String, plans: List<Plan>): Plan  // LLM 通过 createPlan 工具主动创建
├── executeNext(): PlanResult              // 自动执行下一个计划项，完成后自动继续
├── deletePlan()                           // 终止 → 所有剩余项标记 CANCELLED，Plan 标记 CANCELLED
├── removePlan(planId: String)             // 用户（PlanCard [✕]）或 LLM（removePlan 工具）删除单项（仅 PAUSED）
├── reorderPlans(planIds: List<String>)    // LLM 重排剩余 PAUSED 项的顺序
├── listPlans(): List<Plan>                // LLM 查看当前计划状态
└── currentPlan: Plan?                     // 当前计划

Plan:
├── id: String
├── parentId: String?                      // 父 Plan ID，顶级为 null
├── status: PAUSED | EXECUTING | COMPLETED | CANCELLED
├── summary: String                        // 一句话描述
├── plans: List<Plan>                      // 子计划项列表
├── currentPlanIndex: Int                  // 当前执行到第几项 (0-based)
├── createdAt: Instant
└── updatedAt: Instant

PlanResult:
├── planId: String
├── status: COMPLETED | CANCELLED
├── output: String                         // LLM 输出
└── toolCalls: List<ToolCallRecord>        // 该计划项使用的工具调用记录
```

### Plan 解析流程（4 层回退）

```
1. 提取 ```json``` → Gson → Plan
2. 搜索裸 { ... } → Gson → Plan
3. 正则 "Plan N:" → 文本拆分 → Plan
4. 原始文本 → Plan(summary=原始文本, plans=[Plan(description=原始文本)])
```

## 十、PlanCard 接口

```
PlanCard
├── 构造: PlanCard(plan: Plan)
├── setPlanState(planId: String, state: PlanStatus)   // 更新单项状态
├── setCurrentPlanIndex(index: Int)                  // 高亮当前项
├── isExpanded: Boolean = false                      // 折叠/展开，默认折叠
├── toggleExpanded()                                 // 切换折叠/展开
├── onPlanDeleted: ((planId: String) -> Unit)?       // 用户删除单项回调
└── 渲染: JPanel (计划摘要 + 计划项列表，行末 [✕] 仅 PAUSED 状态可见)
```

**折叠/展开规则：**

- 默认折叠（仅显示标题+摘要+进度）
- EXECUTING 状态下自动展开且忽略折叠操作（强制展开）
- 折叠态显示标题行+任务摘要+当前执行中项（EXECUTING）；无执行中项时仅显示标题+摘要+进度
- 全部计划项 COMPLETED/CANCELLED 后 PlanCard 消失

PlanCard 仅负责 UI 展示计划项列表和 PAUSED 项的行末删除入口（用户唯一干预入口）。不提供暂停/继续/删除计划等全局按钮——计划执行流程由
LLM 通过工具自主管理。
