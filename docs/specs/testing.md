# 测试策略

> 本文档定义 Code Assistant 插件的测试分层、工具选择和覆盖目标。

## 一、测试分层

```
┌──────────────────────────────────────────┐
│ E2E 测试（手动 + IDE Sandbox）             │  ← 完整插件行为，真实 IDE 环境
├──────────────────────────────────────────┤
│ 集成测试（JUnit + IntelliJ LightFixture）  │  ← 多模块协作，模拟项目/VFS
├──────────────────────────────────────────┤
│ 单元测试（JUnit 5 + MockK）                │  ← 纯逻辑，无 IDE 依赖
└──────────────────────────────────────────┘
```

## 二、单元测试

### 框架与工具

| 项       | 选择                               | 说明                     |
|---------|----------------------------------|------------------------|
| 测试框架    | JUnit 5                          | IntelliJ Platform 默认集成 |
| Mock 框架 | MockK                            | Kotlin 原生 mock，支持协程    |
| 断言库     | JUnit 5 Assertions + kotlin.test | 标准断言                   |
| 运行方式    | `./gradlew test`                 | Gradle test task       |

### 必须覆盖的纯逻辑

| 模块                      | 测试目标                              | 优先级 |
|-------------------------|-----------------------------------|-----|
| ToolRegistry            | 工具注册/查找/名称冲突优先级（内置 > MCP）         | 🔴  |
| CompletionPostProcessor | 去重、重叠裁剪、content_filter 丢弃、空白过滤    | 🔴  |
| CompletionCache         | LRU 驱逐、TTL 过期、SHA-256 Key 一致性     | 🔴  |
| SimpleDiff              | Myers LCS 算法正确性（已知输入/预期输出）        | 🔴  |
| TokenEstimator          | 中英文 token 估算精度（±20% 误差范围内）        | 🟠  |
| SessionStore            | JSON 序列化/反序列化、损坏文件容错、FileLock 竞态  | 🟠  |
| AppSettingsService      | 配置读写、PasswordSafe 集成、默认值          | 🟡  |
| SkillManager            | SKILL.md 解析、frontmatter 提取、工具交叉验证 | 🟡  |
| ToolInput               | JsonNode 参数提取、null safety         | 🟡  |

### 不需要单元测试的

- 纯 IntelliJ API 封装（如 PSI 操作、VFS 读写）——这些由集成测试覆盖
- 纯数据类（data class）——无逻辑
- UI 渲染逻辑——手动验证

## 三、集成测试

### 框架

使用 IntelliJ `LightCodeInsightFixtureTestCase`（轻量级，无 UI，可访问 PSI/VFS）：

```kotlin
class ReadToolTest : LightCodeInsightFixtureTestCase() {
    fun testReadExistingFile() {
        myFixture.configureByText("test.kt", "fun main() = println(\"hello\")")
        val result = ReadTool.execute(myFixture.file.virtualFile.path, null, null, 10)
        assertTrue(result.success)
        assertTrue(result.content.contains("fun main()"))
    }
}
```

### 覆盖场景

| 测试场景                                          | 涉及模块                             |
|-----------------------------------------------|----------------------------------|
| Read → Edit 完整流程（正常 + stamp 冲突）               | ToolExecutor + modificationStamp |
| Bash 执行（正常退出 + 超时 + 非零退出码）                    | BashTool + ProcessHandler        |
| Agent Loop 模拟（mock API 响应 → tool call → 结果回传） | AgentLoop + ToolRegistry         |
| Session 持久化（写入 → 关闭 → 重新加载）                   | SessionStore + SessionManager    |
| MCP Server 生命周期（启动 → tools/list → 崩溃恢复）       | McpManager                       |
| @file 引用解析（合法路径 + 不存在的文件 + glob 匹配）           | ChatInputArea                    |

## 四、E2E 测试

### IDE Sandbox 测试

通过 `./gradlew runIde` 在 sandbox IDE 中手动验证：

| 验证项                                  | 频率    |
|--------------------------------------|-------|
| 完整对话流程（发消息 → 工具调用 → 审批 → 继续）         | 每次大版本 |
| 代码补全接受/拒绝/切换                         | 每次大版本 |
| Git Message 生成（正常 + 空 diff + 大 diff） | 每次大版本 |
| Session 恢复（IDE 重启后对话恢复）              | 每次大版本 |
| MCP Server 连接/使用/断开                  | 每次大版本 |
| 多 Agent 协作（父 spawn 子 → 并行执行 → 结果汇总）  | 每次大版本 |
| 快捷键全表验证                              | 每次大版本 |

## 五、测试命名规范

```
<被测类/功能>Test.kt        // 如 ReadToolTest.kt
<被测类><场景>Test.kt       // 如 SessionStoreCorruptionTest.kt
```

方法命名：

```kotlin
fun `Read 工具读取存在的文件返回内容`() { }
fun `Grep 非法正则回退字面子串`() { }
fun `缓存 TTL 过期后返回 null`() { }
```

## 六、覆盖率目标

| 层级                                                   | 目标        | 说明           |
|------------------------------------------------------|-----------|--------------|
| 核心逻辑（ToolRegistry/PostProcessor/Cache/SimpleDiff）    | ≥ 80% 行覆盖 | 纯逻辑，容易测试     |
| 数据模型（TokenEstimator/SessionStore/AppSettingsService） | ≥ 60% 行覆盖 | 部分依赖 IDE API |
| UI 层                                                 | 不做硬性要求    | 手动验证为主       |
| 整体                                                   | ≥ 50% 行覆盖 | 综合性指标        |

## 七、CI 集成

```
PR 推送 → GitHub Actions
  ├── ./gradlew test（单元 + 集成）
  ├── 覆盖率报告（JaCoCo）
  └── 覆盖率下降 >5% → PR 评论警告
```
