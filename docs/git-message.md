# Git Commit Message 自动生成

基于 DeepSeek Chat API 的 Git commit message 自动生成功能。在 IntelliJ IDEA 的 Commit 对话框中，点击按钮即可基于
`git diff` 生成符合 Conventional Commits 规范的提交信息。

## 一、核心流程

```
用户在 Commit 对话框中点击 "Generate Commit Message" 按钮
  │
  ▼
GenerateCommitAction.actionPerformed()
  │
  ├── 防抖: 1.5 秒内不重复触发
  ├── 检查 API Key → 未配置时弹出警告
  │
  ├── getCheckedChanges(controlComponent, project)
  │     ├── 反射遍历组件树 → 找到 CheckinProjectPanel
  │     ├── 调用 getSelectedChanges() → 获取用户勾选的文件
  │     └── 降级 → ChangeListManager.defaultChangeList.changes
  │
  ├── findEditorField(controlComponent) → 递归找 EditorTextField
  ├── isGenerating = true（锁定按钮）
  │
  └── Task.Backgroundable（后台任务）
        │
        ├── buildDiff(project, selectedChanges)
        │     ├── 首选: git diff --staged -- <勾选文件>
        │     ├── 降级: git diff -- <勾选文件>（如果 staged 无内容）
        │     ├── 附: git diff --stat 摘要 + git log --oneline -5 风格参考
        │     └── 最终降级: SimpleDiff（Myers LCS 算法）+ ContentRevision
        │
        ├── buildPrompt(diffText)
        │     ├── 读取用户自定义 prompt（{diff} 占位符）
        │     ├── 未自定义 → 根据系统语言选择中/英文默认 prompt
        │     └── 拆分: systemPrompt（指令）+ userPrompt（diff 数据）
        │
        ├── indicator.checkCanceled()  // 清空前检查取消，防止编辑器内容被清空后任务取消导致数据丢失
        ├── invokeAndWait { editor.document.setText("") }  // 清空
        │
        └── callDeepSeek(apiKey, systemPrompt, userPrompt, onDelta)
              ├── POST https://api.deepseek.com/v1/chat/completions
              ├── 流式 (stream=true, SSE 解析)
              ├── 连接超时 15s, 读取超时 60s
              ├── onDelta → invokeLater → insertString() 实时写入
              └── 完成后 invokeAndWait → setText() 兜底写入
```

## 二、关键类

| 类名                      | 文件                                | 职责                                                    |
|-------------------------|-----------------------------------|-------------------------------------------------------|
| `GenerateCommitAction`  | `actions/GenerateCommitAction.kt` | 主 Action 类，继承 `AnAction`，注册在 `Vcs.MessageActionGroup` |
| `SimpleDiff`            | `ui/chat/SimpleDiff.kt`           | 行级 diff 工具，Myers LCS 算法，输出 unified diff 格式            |
| `DiffLine` / `DiffKind` | `ui/chat/SimpleDiff.kt`           | diff 行数据类和枚举（ADD/DEL/CTX）                             |

## 三、Diff 构建策略

### 优先级

```
1. git diff --staged -- <用户勾选的文件>
   └── 如果 staged 区域无内容
2. git diff -- <用户勾选的文件>
   └── 如果 git 命令不可用
3. SimpleDiff（Myers LCS 算法） + ContentRevision
```

### 增强信息

除了 diff 内容本身，还附加以下信息帮助 LLM 生成更准确的 message：

1. **`git diff --stat`** — 变更文件摘要
2. **`git log --oneline -5`** — 最近 5 条 commit 日志（作为项目风格参考）

### 限制

| 限制项             | 值                  |
|-----------------|--------------------|
| 最多处理文件数（路径列表）   | 50 个               |
| 最多 diff 文件数（内容） | 30 个               |
| 总大小上限           | 50,000 字符          |
| 二进制文件           | 不处理（检测 null 字符后跳过） |

**路径列表截断说明**：当用户勾选文件数超过 50 个时，路径列表按用户勾选文件的原始顺序取前 50 个。该截断发生在
git diff 命令执行前，仅影响传递给 `git diff` 的路径参数数量，不影响 diff 内容的后续截断策略（详见 §九
超大 Diff）。

## 四、Prompt 模板

### 英文默认 Prompt（系统语言非中文时）

```
Generate a concise git commit message following Conventional Commits
(feat:/fix:/refactor:/chore:/docs:/test:).
Output ONLY the commit message, no explanations.
The diff is provided in the user message.
```

### 中文默认 Prompt（系统语言以 "zh" 开头时）

```
根据以下 git diff 生成简洁的中文 git commit message，
遵循 Conventional Commits 规范（feat:/fix:/refactor:/chore:/docs:/test:）。
只输出 commit message，不要解释。Diff 在用户消息中提供。
```

### 自定义 Prompt

用户可在 **Settings > Tools > Code Assistant** 中自定义 prompt 模板，使用 `{diff}` 作为 diff
文本的占位符。例如：

```
根据以下 diff 生成中文 commit message，格式：<type>: <简要描述>
- type: feat/fix/refactor/chore/docs/test
- 描述不超过 50 字
- 如有破坏性变更，用 BREAKING CHANGE: 标注

{diff}
```

### 消息角色分离

API 调用时，prompt 被拆分为两个角色：

- **`system`** — 指令部分（`{diff}` 之前和之后的文本拼接，中间的 `{diff}` 占位符被替换为 diff 内容后放入
  `user`）
- **`user`** — 数据部分（diff 内容本身）

这样分离能让 LLM 更好地区分"指令"和"数据"，减少 prompt injection 风险。

## 五、API 调用

### 端点

```
POST https://api.deepseek.com/v1/chat/completions
Authorization: Bearer <API Key>
Content-Type: application/json
```

### 请求体

```json
{
  "model": "deepseek-v4-pro",
  "messages": [
    {
      "role": "system",
      "content": "根据以下 git diff 生成简洁的中文 git commit message..."
    },
    {
      "role": "user",
      "content": "diff --git a/UserService.kt b/UserService.kt\n..."
    }
  ],
  "stream": true
}
```

### 流式处理

- **协议**: SSE（Server-Sent Events），`data: {...}` 格式
- **写入策略**:
    1. 每个 delta chunk → `invokeLater` + `runWriteAction` → `insertString()` 追加到编辑器
    2. 流完成后 → `invokeAndWait` + `setText()` 兜底覆盖（确保最终内容完整）
- **超时**: 连接 15s，读取 60s

### 错误处理

- **API Key 未配置**: 弹出警告对话框
- **网络错误**: 在 UI 线程显示错误
- **生成中重复点击**: 1.5s 防抖 + `isGenerating` 禁用按钮

## 六、用户交互

### 按钮位置

按钮通过 `plugin.xml` 注册在 `Vcs.MessageActionGroup` 中，自动出现在 IntelliJ IDEA Commit
对话框的消息输入框旁边：

```xml

<action id="AiAssistant.GenerateCommit" class="com.aiassistant.actions.GenerateCommitAction"
        text="%action.generate.commit">
    <!-- 实际文本值来自 messages_*.properties 中的 action.generate.commit 键 -->
    <add-to-group group-id="Vcs.MessageActionGroup" anchor="last" />
</action>
```

### 按钮状态

| 状态  | 行为                                                               |
|-----|------------------------------------------------------------------|
| 可见  | 有 commit 对话框时始终显示                                                |
| 启用  | `getCheckedChanges().isNotEmpty() && !isGenerating`（已勾选文件且未在生成中） |
| 禁用  | 未勾选任何文件时灰色不可点击                                                   |
| 生成中 | 按钮文字变为 "正在生成提交信息..."，禁用                                          |

### 用户勾选文件

获取用户在 Commit 对话框中勾选的文件：

1. 反射遍历组件树 → 找到 `CheckinProjectPanel` 类（`vcs-impl` 模块，仅运行时访问）
2. 调用 `getSelectedChanges()` 方法获取勾选文件列表
3. 如反射失败（新版 Commit UI） → 尝试 `ChangesBrowser` / `CommitChangeListPanel`
4. 所有反射路径失败 → 降级到 `ChangeListManager.defaultChangeList.changes`

### 流式写入

生成过程中，commit message 会实时流式写入编辑器，用户可以看到逐字生成的过程。生成完成后用户可以手动修改。

## 七、SimpleDiff 降级方案

当 `git` 命令不可用时（如非 Git VCS、git 未安装），使用纯 Kotlin 实现的 `SimpleDiff`：

- **算法**: Myers LCS（最长公共子序列）
- **输出格式**: Unified diff（与 `git diff` 格式一致）
- **差异化**: `SimpleDiff` 仅做行级比较，不包含 `--stat` 摘要和 `git log` 风格参考

```kotlin
// SimpleDiff 核心接口
data class DiffLine(val kind: DiffKind, val content: String)
enum class DiffKind { ADD, DEL, CTX }

fun diff(oldLines: List<String>, newLines: List<String>): List<DiffLine>
fun toUnifiedDiff(oldName: String, newName: String, diff: List<DiffLine>): String
```

## 八、配置项

| 配置项           | 存储方式                  | 默认值               | 说明                         |
|---------------|-----------------------|-------------------|----------------------------|
| Commit Prompt | `PropertiesComponent` | `""`（空=使用默认）      | 自定义 prompt 模板，`{diff}` 占位符 |
| API Key       | `PasswordSafe`        | —                 | 与 Agent、补全共用               |
| Model         | `PropertiesComponent` | `deepseek-v4-pro` | 与 Agent、补全共用               |

## 九、边界情况处理

### 空 diff（无变更）

当所有变更文件被取消勾选，或 diff 内容为空时：

- 按钮仍可见但点击后弹出提示：`"没有检测到代码变更，请勾选需要提交的文件"`
- 不发起 API 调用
- `isGenerating` 保持 `false`

### Merge Commit

检测到 merge commit 场景（`.git/MERGE_HEAD` 存在或 `git diff --staged` 包含 merge 特征）时：

- 使用专门的 merge prompt 模板：`"生成一个简洁的 merge commit message，描述合并的内容"`
- 不强行要求 Conventional Commits 格式
- diff 内容仍然正常采集（包含冲突解决后的变更）

### 超大 Diff（超出 Token 限制）

当 diff 总字符数超过 50,000 上限时：

1. 优先保留 `git diff --stat` 摘要（变更文件列表 + 增删行数统计）
2. 选取前 30 个文件的 diff 内容（按变更行数降序）
3. 每个文件的 diff 截断到前 500 行
4. 尾部标注：`... (共 {totalFiles} 个文件变更，仅展示前 30 个文件的 diff)`
5. 如果截断后仍超过 50,000 字符 → 仅发送 `--stat` 摘要，不发送逐文件 diff

### 无 Staged Changes 且无 Unstaged Changes

（新仓库、刚 commit 完）时：

- `getCheckedChanges()` 返回空列表
- 按钮隐藏（`changes.isNotEmpty()` = false）
- 不发起任何操作

## 十、已知限制

- 仅支持 Git VCS，其他版本控制系统（SVN、Mercurial）不支持
- 仅支持 DeepSeek Chat API（`/v1/chat/completions`），不支持其他 LLM 提供商
- 新版 IntelliJ IDEA Commit UI 的勾选文件获取依赖反射，可能存在兼容性风险
- 无手动停止生成按钮（生成过程中无法中断）
- 无历史记录（每次生成的 message 不保存历史）
