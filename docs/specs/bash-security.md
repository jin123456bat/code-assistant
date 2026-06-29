# Bash 安全模型

> 本文档描述 Agent 模式下 Shell 命令执行的安全边界和审批流程。

## 一、审批策略

### 需要审批的工具

```kotlin
object ToolApprovalPolicy {
    private val approvalRequiredTools = setOf("Write", "Edit", "Bash", "Task")

    fun requiresApproval(toolName: String): Boolean = toolName in approvalRequiredTools
}
```

所有 **Bash** 调用都需要用户审批，**无例外**。当前不区分"安全命令"和"危险命令"——都是统一的审批弹窗。

### 审批流程

```
LLM 发起 Bash tool call
  │
  ▼
ToolExecutor.execute(toolUse)
  ├── ToolApprovalPolicy.requiresApproval("Bash") → true
  ├── session.requireApproval()
  ├── onToolStateChanged(AWAITING_APPROVAL)
  │
  ├── EDT: showApprovalDialog(message)
  │     └── Messages.showYesNoDialog(项目, 消息, "确认工具执行", "允许", "拒绝", WARNING_ICON)
  │
  ├── 用户选择 "允许" → session.approvalGranted() → 继续执行
  └── 用户选择 "拒绝" → session.approvalRejected() → 返回 "用户拒绝执行工具: Bash"
```

### 审批弹窗消息格式

```
工具: Bash
命令: {command}
目录: {workDir}

允许 Code Assistant 执行这个操作吗？
```

## 二、执行环境

### Shell 类型

```kotlin
val process = Runtime.getRuntime().exec(
    arrayOf("/bin/bash", "-c", command),  // 固定使用 bash
    null,                                   // 继承父进程环境变量
    File(workDir)                           // 工作目录
)
```

| 属性        | 值                  |
|-----------|--------------------|
| Shell     | `/bin/bash -c`     |
| 环境变量      | 继承 IDE 进程（**不隔离**） |
| 默认工作目录    | `project.basePath` |
| 用户可指定工作目录 | `workDir` 参数       |

### 进程管理

- 每个 Bash 调用启动独立进程
- 进程注册到 `session.runningProcesses`（MutableSet）
- 用户点击停止按钮时，遍历 `runningProcesses` 逐个 `destroyForcibly()`

## 三、超时与资源限制

| 限制项  | 值                             | 说明          |
|------|-------------------------------|-------------|
| 超时   | 由 LLM 在 `timeout` 参数中指定（必填）   | 0 = 不限      |
| 输出缓冲 | 10,000 字符（stdout + stderr 合并） | 超出截断        |
| 返回内容 | 4,000 字符                      | 返回给 LLM 的内容 |
| 超时行为 | `process.destroyForcibly()`   | 强制终止        |

### 超时实现

```kotlin
val (stdout, stderr) = if (timeoutSec > 0) {
    try {
        val out = stdoutFuture.get(timeoutSec.toLong(), TimeUnit.SECONDS)
        val err = stderrFuture.get(1, TimeUnit.SECONDS)
        process.waitFor(1, TimeUnit.SECONDS)
        if (process.isAlive) {
            process.destroyForcibly()
            return "超时: 命令执行超过 ${timeoutSec}s，已强制终止"
        }
        Pair(out, err)
    } catch (e: TimeoutException) {
        process.destroyForcibly()
        return "超时: 命令执行超过 ${timeoutSec}s，已强制终止"
    }
} else {
    // timeout=0: 无限等待
    val out = stdoutFuture.get()  // 无超时
    val err = stderrFuture.get()
    process.waitFor()
    Pair(out, err)
}
```

## 四、危险命令检测（当前：无）

**当前版本不区分安全/危险命令**。所有 Bash 调用统一走审批弹窗。

> **设计考量：** LLM 自身不知道用户环境的具体情况（如生产数据库连接、敏感文件），因此交由用户判断是最安全的策略。后续可考虑：
> - 命令白名单模式（如 `ls`、`pwd`、`cat` 自动放行）
> - 正则黑名单模式（如 `rm -rf /`、`sudo` 始终二次确认）
> - 按工作目录区分（项目目录内 vs 项目目录外）

## 五、已知安全边界

| 边界          | 当前状态                                        | 风险等级             |
|-------------|---------------------------------------------|------------------|
| 环境变量继承      | 继承 IDE 进程全部环境变量，包括 `PATH`、`HOME`、可能的密钥类环境变量 | 🟡 中             |
| 文件系统访问      | 无任何限制，可读写 `workDir` 参数指定的任意路径               | 🟡 中             |
| 网络访问        | 无限制                                         | 🟢 低（IDE 已有网络权限） |
| 子进程         | 命令可启动子进程，停止按钮仅杀直接子进程                        | 🟡 中             |
| sudo        | 不阻止，由用户审批时自行判断                              | 🔴 高             |
| `timeout=0` | LLM 可指定不限超时，命令可能永久挂起                        | 🟡 中             |

## 六、对比 Claude Code

| 特性     | Claude Code | Code Assistant |
|--------|-------------|----------------|
| 审批粒度   | 首次授权后同会话信任  | 每次 Bash 调用都审批  |
| 危险命令检测 | ✅ 内置检测      | ❌ 无            |
| 工作目录沙箱 | ❌           | ❌              |
| 环境变量隔离 | ❌           | ❌              |
| 审批超时   | 有           | 无（模态弹窗，用户必响应）  |
| 命令白名单  | ❌           | ❌ 待考虑          |
