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

Bash 工具包含 `dangerous` 必填参数（bool 类型），由 LLM 判断当前命令是否危险。`dangerous=true`
时始终弹窗二次确认，无视白名单。`dangerous=false` 时走正常审批流程：首次调用需审批，用户点击"允许此会话"
后同会话内后续调用信任放行。

### 审批流程

审批以**对话内嵌 ToolCallCard 形式**呈现，不弹出独立 Dialog。Bash
工具的审批流程与普通工具一致，详见 [tools.md §六 审批机制](../agent/tools.md#六审批机制)。

`dangerous=true` 时，ToolCallCard 仅展示"允许一次"/"拒绝"按钮（无"允许此会话"），始终二次确认。
`dangerous=false` 时，首次调用展示全部按钮。`dangerous=true` 的命令即使已在白名单中也必须弹出确认。

## 二、输出上限

Bash 输出上限及截断策略详见 [tools.md §四 工具返回截断策略](../agent/tools.md#四工具返回截断策略)。

## 三、执行环境

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

## 四、超时与资源限制

| 限制项  | 值                           | 说明                                                                |
|------|-----------------------------|-------------------------------------------------------------------|
| 超时   | 由 LLM 在 `timeout` 参数中指定（必填） | 0 = 不限                                                            |
| 返回内容 | 200 行 / 4,000 字符（取较小者）      | 中段截断：保留头部 30 行 + 尾部 30 行，中间标注 `... (省略 N 行)`。Bash 不支持翻页（重新执行有副作用） |
| 超时行为 | `process.destroyForcibly()` | 强制终止                                                              |

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

## 五、危险命令检测

危险命令检测由 LLM 通过 Bash 工具的参数 `dangerous`（bool，必填）自行判断。LLM 认为命令具有高风险时设置
`dangerous=true`，典型危险命令包括：

- `rm -rf /` —— 递归删除根目录
- `git push --force` —— 强制推送覆盖远程
- `sudo` —— 提权操作
- `chmod 777` —— 开放全部权限
- 其他破坏性文件操作、生产环境数据修改等

`dangerous=true` 时 ToolCallCard 始终弹窗二次确认，无视白名单，不提供"允许此会话"选项。
`dangerous=false` 时走正常审批流程（首次授权后同会话信任）。

## 六、已知安全边界

| 边界          | 当前状态                                        | 风险等级             |
|-------------|---------------------------------------------|------------------|
| 环境变量继承      | 继承 IDE 进程全部环境变量，包括 `PATH`、`HOME`、可能的密钥类环境变量 | 🟡 中             |
| 文件系统访问      | 无任何限制，可读写 `workDir` 参数指定的任意路径               | 🟡 中             |
| 网络访问        | 无限制                                         | 🟢 低（IDE 已有网络权限） |
| 子进程         | 命令可启动子进程，停止按钮仅杀直接子进程                        | 🟡 中             |
| sudo        | 不阻止，由用户审批时自行判断                              | 🔴 高             |
| `timeout=0` | LLM 可指定不限超时，命令可能永久挂起                        | 🟡 中             |

## 七、对比 Claude Code

| 特性     | Claude Code | Code Assistant                 |
|--------|-------------|--------------------------------|
| 审批粒度   | 首次授权后同会话信任  | 首次授权后同会话信任，dangerous=true 始终确认 |
| 危险命令检测 | ✅ 内置检测      | ✅ LLM 通过 `dangerous` 参数判断      |
| 工作目录沙箱 | ❌           | ❌                              |
| 环境变量隔离 | ❌           | ❌                              |
| 审批超时   | 有           | 无（对话内嵌按钮，用户必响应）                |
| 命令白名单  | ❌           | 首次授权后同会话信任                     |
