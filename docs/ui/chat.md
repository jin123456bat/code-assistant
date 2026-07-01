# 聊天面板

> 关联文档：[[pages]], [[design-system]], [[components]], [[../agent/loop]], [[../agent/tools]]

## 一、面板布局

```
┌──────────────────────────────────────────────────────┐
│ [🏠] [💬] [📁] [📊] [🔌] [🎯] [⚙] │ ← 顶部 TabBar
├──────────────────────────────────────────────────────┤
│  🤖 Code Assistant                          [🗑] [✕]│ ← 标题行
├──────────────────────────────────────────────────────┤
│  消息列表（JScrollPane）                              │
│  ├ 用户气泡 (右对齐, 蓝底)                           │
│  ├ Agent 文本 (左对齐, Markdown 渲染)                │
│  ├ 工具调用卡片 (折叠, 8 状态)                       │
│  ├ 错误气泡 (红底 + 重试)                           │
│  └ 系统消息 (居中, 灰色)                            │
├──────────────────────────────────────────────────────┤
│ 📄 UserService.kt:40-60 [✕] 📄 Config.java [✕]        │ ← Tags 行
│ 输入你的问题，@ 选择文件或 Ctrl+V 粘贴图片...             │ ← 文本区域
│ [+]                                          [→]       │ ← 底部栏（发送按钮）
└──────────────────────────────────────────────────────┘
```

### 颜色

亮/暗双主题通过 `AppColors` 令牌统一管理：

| 元素         | 亮色主题          | 暗色主题          |
|------------|---------------|---------------|
| 用户气泡       | `#E8F0FE`     | `#1E3A5F`     |
| Agent 气泡   | `#FFFFFF`     | `#1F2937`     |
| 错误气泡       | `#FEE2E2`     | `#7F1D1D`     |
| 系统消息       | `#9CA3AF`     | `#6B7280`     |
| 代码块背景      | `#F6F8FA`     | `#1E1E2E`     |
| Agent 左侧蓝线 | `#3B82F6` 3px | `#60A5FA` 3px |

## 二、消息气泡

### 用户气泡

```
bg=#E8F0FE 圆角=12px
右对齐，maxWidth=容器宽度×70%（默认 300-500px）
padding=12px
文字=Body (14px)
底部: 时间戳 Caption fg=#9CA3AF 右对齐
```

### Agent 气泡（Markdown 流式渲染）

```
bg=#FFFFFF 圆角=12px
左对齐，maxWidth=容器宽度×85%（默认 300-500px）
border-left=3px #3B82F6
padding=12px
文字=Body (14px)
底部: 时间戳 + token 消耗 Caption
```

**流式渲染策略：** 流式输出时文本先追加到字符串缓冲，完整 Block 闭合后通过 `parseMarkdown()` 解析，
渲染为对应的 Swing 组件（段落→JLabel，代码块→JTextArea）。未闭合 Markdown 块（如未配对的 ```）
缓存等待，闭合后再渲染。`MessageAccumulator` 自动累积完整 Message 用于持久化。

**代码块渲染：** 不真正 inline 嵌入。Markdown 文本拆分为"文本段 + 代码块 + 文本段"序列，每段独立组件，包裹在
BoxLayout.Y_AXIS 的 BubblePanel 中。代码块使用 `JTextArea`（只读模式，JetBrains Mono 等宽字体），
包裹在 `JScrollPane` 中。无语法高亮，无语言前缀映射。

```
┌─────────────────────────────────────────┐
│ 好的，先读取 UserService.kt...          │ ← 普通段落
│                                         │
│ ┌─────────────────────────────────┐     │
│ │ fun findById(id: Long): User?  │     │ ← 代码块 (JTextArea)
│ │     return repo.findById(id)   │     │   等宽字体, 只读, block-level
│ └─────────────────────────────────┘     │
│                                         │
│ 接下来：1. 加 suspend  2. 改调用...     │ ← 有序列表
│                                         │
│ 14:33  |  tokens: 1.2K+0.4K            │ ← 底部统计
└─────────────────────────────────────────┘
```

### 流式气泡（生成中）

- 与 Agent 气泡样式相同
- 末尾闪烁光标 ▍ (`#3B82F6`, 500ms blink)
- 每个 token 到达后追加到字符串缓冲，完整 Block 闭合后通过 `parseMarkdown()` 解析为 JLabel/JTextArea
  组件
- 未闭合代码块 → 缓存等待

### 错误气泡

```
bg=#FEE2E2 圆角=12px
border-left=3px #EF4444
图标: AllIcons.RunConfigurations.TestFailed
[重试] 按钮
[复制错误信息] 按钮
```

### 系统消息

```
居中，fg=#9CA3AF Caption (11px)
无背景，仅文字
示例: "── 14:30 ──"
```

## 三、思考过程

DeepSeek V4 在流式响应中会先输出 `reasoning_content`（思考过程），再输出正式回复。思考过程以折叠块形式展示，默认收起：

```
┌─────────────────────────────────────────┐
│ ▶ 💭 思考过程                    1.2s   │ ← 点击展开/折叠
├─────────────────────────────────────────┤
│ 需要先读取 UserService.kt 了解当前实现， │ ← 展开后显示
│ 然后修改方法签名和调用处。考虑到协程...  │   斜体, 浅橙背景 #FFF8F0
└─────────────────────────────────────────┘
```

- 无 tool call 时：思考过程在前，回复文本在后
- 有 tool call 时：思考过程在前，tool call 在后（思考决定调用哪个工具）
- 默认折叠，用户可点击展开查看完整推理
- 文字颜色：亮色 `#92400E`，暗色 `#FBBF24`；背景：亮色 `#FFF8F0`，暗色 `#422006`
- 思考内容**不持久化**到 Session JSON（节省存储，仅回复文本+tool calls 持久化）

## 四、工具调用卡片（8 状态）

```
展开态:
┌──────────────────────────────────────────┐
│ ▾ 🔧 Bash                         ⏳   │ ← 头部(箭头+工具名+状态), 可点击折叠
│ ┌──────────────────────────────────────┐ │
│ │ command: ./gradlew build            │ │ ← 参数
│ └──────────────────────────────────────┘ │
│ ┌──────────────────────────────────────┐ │
│ │ BUILD SUCCESSFUL in 12s            │ │ ← 结果 (monospaced, 灰底, max-h=240px)
│ └──────────────────────────────────────┘ │
│ 耗时: 12.3s                              │
└──────────────────────────────────────────┘

折叠态:
┌──────────────────────────────────────────┐
│ ▶ 🔧 Bash                         ⏳   │ ← 仅显示头部
└──────────────────────────────────────────┘
```

### 8 个状态

| 状态                   | 图标         | 颜色       | 说明              |
|----------------------|------------|----------|-----------------|
| ⏳ PENDING            | Plan_0     | Gray 500 | 等待执行            |
| 🔒 AWAITING_APPROVAL | Warning    | 黄色       | 等待用户审批，始终展开不可折叠 |
| 🔄 EXECUTING         | Plan_4 旋转  | 蓝色       | 执行中，不可折叠，显示进度条  |
| ✅ DONE               | TestPassed | 绿色       | 执行成功            |
| ❌ ERROR              | TestFailed | 红色       | 执行失败，含 [重试] 按钮  |
| ⏰ TIMEOUT            | Warning    | 橙色       | 超时              |
| 🚫 REJECTED          | Suspend    | 灰色       | 用户拒绝            |
| ⛔ CANCELLED          | Suspend    | 灰色       | 用户终止            |

### 折叠/展开规则

- 所有状态默认折叠，用户点击头部展开
- 箭头 ▾/▶ 切换
- AWAITING_APPROVAL 始终展开不可折叠
- EXECUTING 不可折叠
- 结果区域 max-height=240px，超出滚动显示

### Diff 可视化

`Edit` 执行成功后，ToolCallCard 内联展示可视化 Diff（`SimpleDiff` 生成，ADD 绿色/DEL 红色/CTX
灰色），替换纯文本的前后对比。

## 五、Plan 卡片

PlanCard 渲染在 Chat 消息流顶部，展示当前执行计划。详细规范见 [Plan Mode](../agent/plan.md)。

## 六、多 Agent 调度卡片（MultiAgentBlock）

```
┌──────────────────────────────────────────┐
│ ▶ 🤖 多 Agent 调度中           2/3 运行中│ ← 头部（可点击折叠）
├──────────────────────────────────────────┤
│ 🔵 子 Agent A: 重构 UserService   ✅ 完成│ ← 点击展开该子 Agent 详情
│ ├─ 流式输出: 已读取文件，开始修改...       │ ← 展开后的子 Agent 流式文本
│ ├─ 🔧 Read       ✅ DONE       ▶│ ← 嵌套 ToolCallCard（可折叠）
│ └─ 耗时: 2.3s  详情: sub-session #42    │
├──────────────────────────────────────────┤
│ 🔵 子 Agent B: 更新 UserController🔄 执行 │
│    流式生成中...▍                         │
├──────────────────────────────────────────┤
│ ⏸ 子 Agent C: 运行测试          ⏸ 排队  │ ← 未开始，不可展开
├──────────────────────────────────────────┤
│ 并发上限: 3 | 文件锁: UserService.kt (B) │ ← 底部状态行
└──────────────────────────────────────────┘
```

- 头部可点击折叠/展开整个卡片（箭头 ▶/▾ 切换），默认折叠
- 点击已完成/执行中的子 Agent 行 → 展开该子 Agent 的详情（流式输出 + 嵌套 ToolCallCard + 耗时）
- 排队的子 Agent 不可展开
- 卡片背景: `#F0F7FF`（亮）/ `#0F1D2F`（暗），border=`#BFDBFE`
- 完成色: `#22C55E`，执行中色: `#3B82F6`，排队色: `#6B7280`

## 七、输入区域

```
┌─────────────────────────────────────────┐
│ 📄 UserService.kt:40-60 [✕]              │ ← Tags 行（输入框上方，FlowLayout，文件+图片混合排列）
│ 📄 Config.java [✕]  🖼 screenshot.png    │
├─────────────────────────────────────────┤
│                                         │
│ 输入你的问题，@ 选择文件或 Ctrl+V 粘贴图片  │ ← 文本区域（无边框，自适应 2-10 行）
│                                         │
├─────────────────────────────────────────┤
│ [+]                                  [→]   │ ← 底部栏（发送按钮）
└─────────────────────────────────────────┘

交互:
  ├─ 点击 [+] 或输入 @ → 弹出文件选择 Popup（按子目录分组，支持子目录文件）
  ├─ 选中文件 → 自动添加 tag 到 Tags 行，tag 可点击 ✕ 移除
  ├─ 输入 / → 弹出指令选择 Popup
  └─ Enter → 发送消息
```

### @file 引用格式

- `@file` 注入格式：`[File: UserService.kt (156 lines)]\n<content>\n[/File]`
- 选中代码注入格式：`[Selection from UserService.kt:40-60]\n<selected code>\n[/Selection]`
- 两者同时存在时：@file 内容在前，选中在后。同一文件既被 @file 引用又被选中 → 去重：@file
  注入完整文件，选中内容不重复注入，但保留选中行号标记
- 选中引用仅一个（新选中替换旧），@file 引用可多个
- 输入框 tag 显示：手动 @file tag 可多个 `[📎 UserService.kt ✕]`，选中 tag 仅一个
  `[📎 UserService.kt:40-60]`（不用 ✕，取消选中自动消失）
- @file glob 匹配上限 50 个文件，超出部分不注入

### IDE 代码选中即时引用

用户在编辑器中选择代码 → 自动更新输入框中的引用 tag（无需右键菜单）：

- 选中后立即生效——tag 自动替换为 `📄 UserService.kt:40-60 [✕]`
- **仅允许一个**选中引用——新选中会替换旧的
- 取消选中 → tag 自动移除
- 选中内容作为上下文注入，附带文件名 + 精确行号

### 剪贴板图片粘贴

```
剪贴板检测到图片 → Clipboard.getSystemClipboard().getData(DataFlavor.imageFlavor)
  → BufferedImage → 缩放限制（长边 max 2048px，保持比例）
  → PNG 编码 → Base64 → data:image/png;base64,...
  → 注入到 LLM 消息的 content 数组中（image block 类型）
  → 输入区域显示缩略图 tag [🖼 filename ✕]，可点击删除
```

- 支持格式：PNG、JPEG、GIF、WebP、BMP
- 单张上限 5MB，单次粘贴最多 5 张
- UI 展示：48×48 缩略图 tag，带文件名和大小
- 图片不注入文本上下文——作为独立的 `image` content block 与文本 `text` block 并列在 API 请求的 user
  message `content` 数组中

## 八、/ 指令 & @ 文件引用 Popup

输入 `/` 或 `@` 或点击 [+] 时从输入区域底部弹出选择列表，支持键盘导航和实时过滤：

```
输入 `/`:
┌──────────────────────────────┐
│ 内置指令                      │
│ /plan    生成执行计划         │ ← ↑↓ 移动高亮
│ /clear   清除会话上下文       │ ← Enter 确认
│ Skills                       │ ← Esc 关闭
│ /review  审查代码质量         │ ← 继续输入实时过滤
│ /refactor 重构代码            │
│ /test     生成单元测试         │
└──────────────────────────────┘

输入 `@` / 点击 [+]:
┌──────────────────────────────┐
│ 📁 src/main/kotlin/service/  │ ← 按子目录分组
│   UserService.kt   service/  │
│   AuthService.kt   service/  │
│ 📁 src/main/kotlin/controller/│
│   UserController   controller/│
│ 📁 src/main/java/config/     │
│   Config.java      config/   │
│ 📁 项目根目录                 │
│   build.gradle.kts ./        │
└──────────────────────────────┘
   最大 8 行可见 + 滚动条
```

**键盘导航：** ↑↓ 移动高亮（循环），Enter 确认选择，Esc 关闭，继续输入实时过滤匹配项。

`/` 指令列表内容：内置指令（`/plan`, `/clear`）+ 已启用 Skills 的 command（如 `/review`, `/refactor`）。

## 九、选项组件（OptionsBlock）

当 Agent 需要用户做选择时（而非简单的审批），以选项列表形式呈现：

```
┌─────────────────────────────────────────┐
│ 选择下一步操作：                         │ ← 提问标题
├─────────────────────────────────────────┤
│ ● A  直接用 Edit 修改               │ ← 选中态：蓝左边框 + 蓝圆圈
│       修改 UserService.kt 和相关调用处    │
├─────────────────────────────────────────┤
│ ○ B  生成 Plan 确认后再执行              │ ← 默认态
│       列出详细步骤，逐步执行，可随时调整   │
├─────────────────────────────────────────┤
│ ○ C  只改 UserService.kt                │
│       其他调用处不动，快速验证方案        │
├─────────────────────────────────────────┤
│ [确认选择]          已选: B             │ ← 操作栏
└─────────────────────────────────────────┘
```

选项状态：

- Default: fg=#374151, bg=transparent, 左边框透明
- Hover: bg=#F3F4F6（亮）/ #1F2937（暗）
- Selected: bg=#EFF6FF（亮）/ #1E3A5F（暗），border-left: 3px solid #3B82F6，圆圈实心蓝
- Disabled: fg=#9CA3AF, cursor=default

## 十、Toast & 横幅

```
Toast（右下角弹出，3s 自动消失）:
┌────────────────────────────────┐
│ ✅ API Key 验证成功            │ ← bg=#22C55E, fg=#FFFFFF
│                                │   圆角 8px, inset-md
│ ❌ 网络不可用，Key 暂未验证     │ ← bg=#EF4444
│                                │   从底部滑入 (300ms ease-out)
│ ⚠ 项目正在建立索引，搜索结果   │ ← bg=#F59E0B, fg=#FFFFFF
│   可能不完整                   │
└────────────────────────────────┘

横幅（页面顶部，持续显示直到条件消除）:
┌──────────────────────────────────────────────────────────┐
│ 🔄 正在验证 API Key...                                   │ ← Chat 页顶部
│    bg=#EFF6FF, fg=#3B82F6, 无关闭按钮                    │
├──────────────────────────────────────────────────────────┤
│ ⚠ API Key 无效，请检查 Settings                          │ ← Chat 页顶部
│    bg=#FEE2E2, fg=#EF4444, [✕ 关闭] [⚙ Settings]       │
└──────────────────────────────────────────────────────────┘
```

## 十一、审批内嵌卡片

审批不弹出独立 Dialog，而是通过 ToolCallCard 的 `AWAITING_APPROVAL` 状态在对话流中内嵌呈现。

```
ToolCallCard（AWAITING_APPROVAL 状态）:
┌──────────────────────────────────────────┐
│ 🔒 Bash                           ⚠️ 需要确认 │ ← 头部（黄色），始终展开不可折叠
├──────────────────────────────────────────┤
│ ┌──────────────────────────────────────┐ │
│ │ command: rm -rf ./build              │ │ ← 参数
│ └──────────────────────────────────────┘ │
│                                          │
│ [允许一次]  [允许此会话]  [拒绝]           │ ← 内嵌按钮行
└──────────────────────────────────────────┘
```

| 特性        | 说明                                                                |
|-----------|-------------------------------------------------------------------|
| **交互位置**  | 嵌入 ChatPage 消息流中，ToolCallCard 自动展开                                |
| **按钮**    | 首次审批：[允许一次] / [允许此会话] / [拒绝]；危险命令：无"允许此会话"                        |
| **阻塞机制**  | AWAITING_APPROVAL 状态卡片不可折叠，Agent Loop 在后台线程等待用户点击（CountDownLatch） |
| **无关闭概念** | 审批内嵌在对话中，用户只能选择批准或拒绝，不存在"关闭窗口绕过"的路径                               |
| **拒绝后**   | ToolCallCard → REJECTED（灰色），发送拒绝 tool_result 给 LLM                |

### 审批触发规则

详见 [工具系统 §六](../agent/tools.md#六审批机制)。

---

## 十二、数据模型

```
ChatMessage:
├── id: String
├── type: USER_TEXT | AGENT_TEXT | TOOL_CALL | ERROR | SYSTEM
├── content: String                        // Markdown 源码
├── toolCall: ToolCallUIData?              // TOOL_CALL 类型的额外数据
├── timestamp: Instant
└── tokenDelta: TokenDelta?                // AGENT_TEXT 的单条消息级 token 增量

ToolCallUIData:
├── toolName: String
├── parameters: Map<String, Any>
├── state: PENDING | AWAITING_APPROVAL | EXECUTING | DONE | ERROR | TIMEOUT | REJECTED | CANCELLED
├── result: String?
├── durationMs: Long?
└── planId: String?
```

## 十三、技术接口

> ChatViewModel、ChatBubbleRenderer、ChatInputArea 等技术契约详见 [Components 文档](components.md)。
