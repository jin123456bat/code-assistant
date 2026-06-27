# Plan Mode

> 关联文档：[Agent Loop](./loop.md)、[工具系统](./tools.md)

## 一、职责边界

Plan Mode 将复杂任务拆解为有序步骤列表，创建后自动连续执行。LLM 通过 5 个计划管理工具自主管理执行流程。

- **PlanCard**：纯 UI 组件，展示步骤列表及进度。每个 PENDING 步骤行末有 [✕] 单步删除按钮（**用户唯一干预入口
  **）。头部可点击折叠/展开——默认折叠（仅显示标题+摘要+进度），用户点击展开查看完整步骤列表。EXECUTING
  状态下自动展开且不可折叠。全部步骤 DONE 或被删除后 PlanCard 消失。
- **PlanExecutor**：负责自动执行。创建计划后按步骤顺序串行执行，每步完成后自动进入下一步。LLM 通过
  `deleteStep`/`reorderSteps`/`markStepDone` 工具自主控制执行节奏，无需用户干预。

## 二、交互模式

默认**自动连续执行**，用户可随时暂停进入手动控制：

```
默认自动连续执行:

Step 1 DONE → 自动执行 Step 2 → Step 2 DONE → 自动执行 Step 3 → ...
  LLM 可在任意时刻通过工具调整计划：
    - 删除步骤 → deleteStep（仅 PENDING 状态可删）
    - 调整顺序 → reorderSteps
    - 标记完成 → markStepDone
    - 重新规划 → createPlan
  LLM 判断任务完成或无法继续时，自行终止循环

Step 出错了？
  → LLM 自行判断：重试 / 调用 deleteStep 删除 / 调用 reorderSteps 调整顺序
  → 继续自动执行剩余步骤
```

### 用户干预方式

- 仅可点单步 [✕] 删除 PENDING 步骤（PlanCard 行末按钮），LLM 收到通知后跳过该步
- 全局停止按钮(⏹) 终止当前 Agent 执行，Plan 保持当前状态。用户再次发送消息时，LLM 根据 Plan 状态自行决定是否继续
- 除此之外的所有执行控制（暂停/继续/重排/标记完成）均由 LLM 通过工具自主决策，用户不直接操作

### 常见中断场景

**场景 1：用户提供额外信息**

```
用户: "Step 3 还要修改 AuthInterceptor.kt"
→ PlanExecutor: 在 Step 3 的文件列表中追加 AuthInterceptor.kt
→ 用户再次发送消息 → 执行更新后的 Step 3
```

**场景 2：用户提前终止**

```
用户: 说 "剩下的不需要了" → LLM 调用 deleteStep 删除所有剩余步骤
→ 剩余步骤标记 DELETED → 计划结束
```

**场景 3：步骤执行出错需要人工介入**

```
用户: "用 @AuthService.kt:80-100 的内容重新读一遍"
→ Agent 读新文件 → 继续执行
```

### 关键行为

- **自动执行**：创建后自动开始，每步完成后自动进入下一步，不等待用户
- **暂停**：用户点全局停止按钮(⏹) → 当前 step 完成后停止
- **继续**：用户再次发送消息 → LLM 根据 Plan 状态自行决定是否继续执行（可先回应消息再继续，也可直接执行下一步）
- **终止**：用户通过 LLM 调用 deleteStep 工具删除所有剩余步骤 → Plan 标记 DELETED
- **单步删除**：PENDING 步骤行末有 [✕] 按钮，用户可删除不需要的步骤
- **计划持久化**：暂停的计划跨会话保留。关闭 IDE 再打开 → 从 Sessions 页面恢复
- **计划中可自由聊天**：暂停时用户可发送任意消息，消息追加到同一 Session。LLM
  自行判断消息意图——纯咨询则先回答再继续执行，要求继续则执行下一步。Plan 状态保持 EXECUTING

## 三、Plan 生命周期

```
创建 → 执行 → 完成/删除

1. 创建：用户 /plan 或 LLM 调用 createPlan 工具，状态 PENDING
2. 执行：创建后自动开始，状态变为 EXECUTING，逐步执行直到全部完成
3. 单步删除：用户通过 PlanCard [✕] 或 LLM 通过 deleteStep 工具删除任意 PENDING 步，自动跳过该步继续执行
4. 完成：全部步骤 DONE 或全部被删除 → Plan 状态变为 DONE 或 DELETED → PlanCard 消失
5. 持久化：跨会话保留，关闭 IDE 再打开可恢复
```

## 四、Plan & Step 状态

### Plan 状态

| 状态          | 含义                                  |
|-------------|-------------------------------------|
| `PENDING`   | 计划已创建，尚未开始执行                        |
| `EXECUTING` | 正在执行中（含暂停状态），LLM 通过工具自主控制执行流程       |
| `DONE`      | 全部步骤执行完成                            |
| `DELETED`   | 全部步骤被删除（LLM 调用 deleteStep 删除所有剩余步骤） |

### Step 状态

| 状态          | 含义          |
|-------------|-------------|
| `PENDING`   | 等待执行        |
| `EXECUTING` | 正在执行        |
| `DONE`      | 执行完成        |
| `DELETED`   | 被用户或 LLM 删除 |

## 五、计划管理工具

LLM 通过 5 个工具自主管理任务列表：

| 工具             | 参数                      | 用途                                   |
|----------------|-------------------------|--------------------------------------|
| `createPlan`   | `task` + `steps[]`      | 创建/更新执行计划，自动开始执行，steps ≤ 20 步        |
| `listSteps`    | 无                       | 查看当前计划的所有步骤及状态                       |
| `deleteStep`   | `stepId: String`        | 删除指定步骤（仅 PENDING 状态可删）               |
| `reorderSteps` | `stepIds: List<String>` | 重排剩余 PENDING 步骤的执行顺序（传入新的 stepId 序列） |
| `markStepDone` | `stepId: String`        | 将指定步骤标记为 DONE                        |

LLM 在执行过程中可随时调用这些工具调整计划：

- 发现某个步骤不再需要 → `deleteStep`
- 执行中意识到顺序不合理 → `reorderSteps`
- 步骤已通过其他方式完成 → `markStepDone`
- 任务范围扩大需要重新规划 → `createPlan`
- 不确定当前进度 → `listSteps`

### Step 工具自由度

Step 的 `tool` 字段是**建议**而非**约束**。LLM 可以调用预期之外的工具，但应在 step result 中说明原因。

## 六、createPlan 工具

LLM 在执行过程中**随时主动**创建正式执行计划：

| 字段   | 说明                                            |
|------|-----------------------------------------------|
| 工具名  | `createPlan`                                  |
| 参数   | `task`（任务描述）+ `steps[]`（步骤列表，每步含描述、预期工具、涉及文件） |
| 触发时机 | 任务涉及 3 个以上文件，或 LLM 读了 5 个文件后发现还有更多要改          |
| 效果   | 创建 PlanCard 并持久化，自动开始执行                       |

**与 `/plan` 命令的关系：**

|       | `/plan` 命令  | `createPlan` 工具  |
|-------|-------------|------------------|
| 触发方   | 用户手动输入      | LLM 根据实际情况主动创建   |
| 创建时机  | 任务开始前       | 执行中途，LLM 已充分了解项目 |
| 计划准确性 | LLM 凭用户描述猜测 | LLM 已读代码，步骤更精准   |

**工具描述提示词（供 LLM 理解何时使用）：**
> 当任务涉及 3 个以上文件或预计需要 5 轮以上完成时，在执行关键修改前先调用 createPlan 创建执行计划。执行中可随时用
> listSteps/deleteStep/reorderSteps 管理步骤。

## 七、边界处理

| 场景                   | 行为                                                                                     |
|----------------------|----------------------------------------------------------------------------------------|
| Step 引用的文件已删除        | LLM 通过工具结果获知文件不存在，自行决定：调用 `deleteStep` 跳过该步 / 重新 `Read` 定位正确文件 / 调用 `createPlan` 重规划   |
| Step 引用的文件被外部修改      | 执行前检查 `modificationStamp` ≠ plan 记录值 → 提示用户"文件自计划生成后已变更"，显示 diff 概要 → 用户决定（用新版本/保留原计划） |
| 用户修改了 Plan 后续步骤      | 修改持久化到 Session JSON 的 `plan.modifiedSteps` 字段，恢复时展示修改后的计划                              |
| LLM 需要调整剩余步骤         | 调用 `reorderSteps` / `deleteStep` 自主管理                                                  |
| 用户删除 PENDING 步骤      | 通过 PlanCard [✕] 按钮 → PlanExecutor.deleteStep()，LLM 收到通知后跳过该步                           |
| LLM 终止计划             | LLM 调用 deleteStep 删除所有剩余步骤 → Plan 标记 DELETED                                           |
| 多个会话各有暂停计划           | 允许。每个会话独立存储。Sessions 页面标注"⏸ 计划暂停中"                                                     |
| Plan 暂停期间切换到 Chat 输入 | 新消息追加到同一个 session。LLM 上下文包含暂停的计划摘要 + 新消息。Agent 可以响应聊天但不自动恢复计划执行                        |

**Plan 与会话的关系：**

- 一个 Session 最多一个活跃 Plan
- Plan DONE 或 DELETED 后，Session 可继续当作普通 Chat 会话
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
    "currentStepIndex": 1,
    "steps": [
      {
        "id": "step-1",
        "description": "读取文件",
        "tool": "Read",
        "files": ["UserService.kt:40-60"],
        "status": "DONE",
        "result": "成功读取 156 行",
        "retryCount": 0
      },
      {
        "id": "step-2",
        "description": "修改方法签名",
        "tool": "Edit",
        "files": ["UserService.kt"],
        "status": "PENDING",
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
├── createPlanFromTool(task: String, steps: List<PlanStepInput>): Plan  // LLM 通过 createPlan 工具主动创建
├── executeNextStep(): StepResult          // 自动执行下一步，完成后自动继续
├── deletePlan()                           // 终止 → 所有剩余步骤标记 DELETED，Plan 标记 DELETED
├── deleteStep(stepId: String)             // 用户（PlanCard [✕]）或 LLM（deleteStep 工具）删除单步（仅 PENDING）
├── reorderSteps(stepIds: List<String>)    // LLM 重排剩余 PENDING 步骤顺序
├── listSteps(): List<PlanStep>            // LLM 查看当前计划状态
└── currentPlan: Plan?                     // 当前计划

Plan:
├── id: String
├── status: PENDING | EXECUTING | DONE | DELETED
├── summary: String                        // 一句话描述
├── steps: List<PlanStep>
├── currentStepIndex: Int                  // 当前执行到第几步 (0-based)
├── createdAt: Instant
└── updatedAt: Instant

PlanStep:
├── id: String
├── description: String                    // 步骤描述
├── tool: String                           // 建议工具名。LLM 应优先使用但不强制
├── files: List<String>                    // 涉及文件（含行号 "UserService.kt:40-60"）
├── status: PENDING | EXECUTING | DONE | DELETED
├── result: String?                        // 执行结果
└── retryCount: Int

StepResult:
├── stepId: String
├── status: DONE | DELETED
├── output: String                         // LLM 输出
└── toolCalls: List<ToolCallRecord>        // 该步骤使用的工具调用记录
```

### Plan 解析流程（4 层回退）

```
1. 提取 ```json``` → Gson → Plan
2. 搜索裸 { ... } → Gson → Plan
3. 正则 "Step N:" / "步骤 N:" → 文本拆分 → Plan
4. 原始文本 → Plan(summary=原始文本, steps=[Step(description=原始文本)])
```

## 十、PlanCard 接口

```
PlanCard
├── 构造: PlanCard(plan: Plan)
├── setStepState(stepId: String, state: StepState)   // 更新单步状态
├── setCurrentStepIndex(index: Int)                  // 高亮当前步骤
├── isExpanded: Boolean = false                      // 折叠/展开，默认折叠
├── toggleExpanded()                                 // 切换折叠/展开
├── onStepDeleted: ((stepId: String) -> Unit)?       // 用户删除单步回调
└── 渲染: JPanel (计划摘要 + 步骤列表，单步行末 [✕] 仅 PENDING 和 ERROR 状态可见)
```

**折叠/展开规则：**

- 默认折叠（仅显示标题+摘要+进度）
- EXECUTING 状态下自动展开且忽略折叠操作（强制展开）
- 折叠态显示标题行+任务摘要+当前执行中步骤（EXECUTING）；无执行中步骤时仅显示标题+摘要+进度
- 全部步骤 DONE/DELETED 后 PlanCard 消失

PlanCard 仅负责 UI 展示步骤列表和 PENDING 步骤行末的单步删除入口（用户唯一干预入口）。不提供暂停/继续/删除计划等全局按钮——计划执行流程由
LLM 通过工具自主管理。
