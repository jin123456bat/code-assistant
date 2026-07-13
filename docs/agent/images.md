# 图片支持

>
关联文档：[Agent Loop](./loop.md)、[System Prompt 规范](../specs/system-prompt.md)、[Chat UI](../ui/chat.md)

Agent 支持用户在文本输入框中粘贴图片，图片经编码后作为独立 content block 发送给 LLM。Read
工具也可读取项目中的图片文件。

---

## 一、图片粘贴

### 触发方式

用户在 Chat 输入框聚焦时，`Ctrl+V` / `Cmd+V` 粘贴剪贴板中的图片。

### 处理流程

```
读取一次剪贴板 Transferable 快照
  → 优先解析 javaFileListFlavor（本地图片文件，可多选并保留文件名）
  → 否则解析 imageFlavor（截图、浏览器复制图片，支持通用 java.awt.Image）
  → 否则解析 image/* MIME flavor（InputStream / ByteArray / ByteBuffer）
  → BufferedImage（统一像素数据）
  → 缩放：长边 > 2048px 时等比缩放到 2048px
  → 本地文件尽量保留 JPEG/PNG/GIF/WebP 编码；截图/浏览器图片及不支持写出的格式回退为 PNG
  → Base64 编码
  → 封装为 ImageRef(base64Data, mimeType=实际发送格式)
  → 追加到 ChatInputArea.imageRefs，发送时显式传给 ChatViewModel
  → TagsRow 显示与文件引用一致的单行紧凑芯片（图标 + 文件名 + 移除按钮）
```

### 约束

| 限制项    | 值                  | 说明                    |
|--------|--------------------|-----------------------|
| 单张大小上限 | 5MB（原始像素数据）        | 超过拒绝粘贴，toast 提示       |
| 单次粘贴张数 | ≤ 20 张              | 超过 20 张时拒绝粘贴，提示移除部分图片        |
| 缩放策略   | 长边 max 2048px，等比缩放 | 保持宽高比，不拉伸             |
| 编码格式   | JPEG/PNG/GIF/WebP（Base64） | 本地文件尽量保留原格式；截图、浏览器图片和 BMP 统一使用 PNG |
| 内存占用   | 缩放后通常 ≤ 2MB/张      | 2048px 长边 PNG 约 1-3MB |

### 压缩实现细节

```kotlin
// 1. 从剪贴板快照读取图片；浏览器返回的 ToolkitImage 会先转换为 BufferedImage
val images = ClipboardImageReader.read(clipboard.getContents(null))
val img = images.first().image

// 2. 缩放：长边超过 2048px 时等比缩放
val maxDim = 2048
val scaled = if (img.width > maxDim || img.height > maxDim) {
    val ratio = maxDim.toDouble() / maxOf(img.width, img.height)
    BufferedImage(
        (img.width * ratio).toInt(),
        (img.height * ratio).toInt(),
        BufferedImage.TYPE_INT_ARGB  // 保留 Alpha 通道，避免透明区域变黑
    ).apply {
        graphics.drawImage(
            img.getScaledInstance(width, height, Image.SCALE_SMOOTH),
            0, 0, null
        )
    }
} else img

// 3. 按已检测到的受支持格式编码；没有对应 writer 时回退为 PNG，再转 Base64
val baos = ByteArrayOutputStream()
if (!ImageIO.write(scaled, formatName, baos) || baos.size() == 0) {
    baos.reset()
    ImageIO.write(scaled, "png", baos)
}
val base64Data = Base64.getEncoder().encodeToString(baos.toByteArray())
```

**关键参数说明：**

| 参数   | 值                    | 理由                                             |
|------|----------------------|------------------------------------------------|
| 缩放算法 | `Image.SCALE_SMOOTH` | 平滑缩放，视觉质量优先                                    |
| 色彩空间 | `TYPE_INT_ARGB`      | 保留透明通道，避免透明区域在缩放时变黑 |
| 编码格式 | 原格式优先，PNG 回退        | 本地图片尽量保留文件格式；系统像素图和缺少 writer 的格式使用 PNG |
| 长边上限 | 2048px               | 平衡 LLM 视觉能力与传输体积。2048px 足够看清代码截图和 UI 细节        |

### 支持的输入格式

输入会按本地图片文件、标准图片 flavor、图片 MIME flavor 的顺序解析，支持系统剪贴板能提供像素数据的常见来源：

| 来源     | 格式                    | 说明                     |
|--------|-----------------------|------------------------|
| 截图工具   | PNG                   | macOS 截图、Win+Shift+S 等 |
| 复制图片文件 | JPEG/PNG/GIF/WebP（BMP 自动转 PNG） | 从文件管理器复制，可一次粘贴多张并保留原文件名 |
| 浏览器复制  | PNG/JPEG              | 支持通用 `Image` 和 `image/*` 字节流；不下载“复制图片地址”的远程 URL |
| 其他应用   | 任何 AWT/ImageIO 可解码的格式    | 统一转换为 `BufferedImage` |

> **注意：** 发送格式仅限 Anthropic 图片块支持的 JPEG、PNG、GIF、WebP；BMP 和缺少 ImageIO writer 的格式转为 PNG。GIF 经缩放或重编码时仅保留第一帧。

---

## 二、发送到 LLM

### ContentBlock 组装

图片不嵌入文本——作为独立的 `image` content block 与 `text` block 并列：

```
buildContext(text, attachments, images) → List<ContentBlock>:
  [
    ContentBlock.text(textWithFiles),    // 文本 + @file 引用
    ContentBlock.image(image1),          // 图片 1
    ContentBlock.image(image2),          // 图片 2
    ...
  ]
```

**顺序规则：** 文本 block 始终在前，图片 blocks 在后。多个图片按粘贴顺序排列。

### ImageSource 结构

```
ImageSource:
├── base64Data: String    // Base64 编码的图片数据（不含 data: URI 前缀）
├── mediaType: String     // MIME 类型：image/png、image/jpeg、image/gif 或 image/webp
└── data: String          // 完整 data URI，格式 "data:{mediaType};base64,{base64Data}"
```

### API 请求格式

图片作为 user message 的 `content` 数组中的 `image` 类型 block：

```json
{
  "role": "user",
  "content": [
    {
      "type": "text",
      "text": "[Image: screenshot.png]\n用户原始消息文本"
    },
    {
      "type": "image",
      "source": {
        "type": "base64",
        "media_type": "image/png",
        "data": "iVBORw0KGgo..."
      }
    }
  ]
}
```

文本 block 中自动附加 `[Image: screenshot.png]` 前缀（文件名从粘贴时间戳生成），帮助 LLM 在纯文本流中感知图片的存在。

---

## 三、Read 工具读图片

`Read` 工具在读取图片文件时，构建 `ImageRef` 对象并通过 `session.pendingImages` 侧通道传递给 `AgentLoop`，
由 `AgentLoop` 在组装 API 请求时统一转换为 image content block。

| 特性       | 说明                                              |
|----------|-------------------------------------------------|
| **支持格式** | PNG、JPEG、GIF、WebP                               |
| **检测方式** | 文件扩展名（`.png`、`.jpg`、`.jpeg`、`.gif`、`.webp`）     |
| **返回格式** | ContentBlock 数组，含 `image` block（非 `text` block） |
| **大小限制** | 与粘贴图片相同：原始 ≤ 5MB                                |
| **多图片**  | Read 不返回多张图片——一个文件一个 image block                |
| **实现**   | ToolExecutor 将 ImageRef 存入 session.pendingImages，AgentLoop 取出后调用 `ImageRef.toBetaImageBlockParam()` 构建 API 参数 |

**与文本文件的区别：** Read 文本文件时返回 `ContentBlock.text(fileContent)`，Read 图片文件时返回
`ContentBlock.image(ImageSource)`。LLM 通过 block 类型区分。

**设计原则：** 图片数据走对象引用（`ImageRef`），不走字符串协议。所有图片 → API ImageBlock 的转换
统一通过 `ImageRef.toBetaImageBlockParam()` 完成，消除分散的 MIME 映射和魔术字符串解析。

---

## 四、图片在会话中的生命周期

| 阶段          | 行为                                                                                                         |
|-------------|------------------------------------------------------------------------------------------------------------|
| **粘贴**      | 编码为 Base64 → 存入 `ChatInputArea.imageRefs` → UI 显示单行图片芯片 → 发送时传给 `ChatViewModel.sendMessage()` |
| **预览**      | 首次点击图片芯片时按需把 Base64 解码到系统临时目录 → IDEA Image Editor 打开；预览路径不进入 `ImageRef` 或 API 请求，移除/发送/销毁时立即清理 |
| **发送**      | `buildContext()` 组装为 image block → 追加到 API 请求                                                              |
| **持久化**     | Base64 数据**不**写入 Session JSON（过大）。`session.messages` 中仅保留 `[Image: screenshot.png]` 占位文本                   |
| **恢复**      | 从 Session JSON 恢复时，图片 block 丢失，LLM 只能看到占位文本。用户从历史会话恢复后再次发送消息时，图片占位文本 `[Image: screenshot.png]` 被丢弃，不传给 LLM |
| **Compact** | 与普通消息一同压缩为摘要，图片内容不参与摘要生成                                                                                   |

> **为什么不持久化图片：** Base64 编码的 2048px PNG 约 1-3MB，频繁粘贴会迅速膨胀 Session
> JSON。对齐主流实现做法——图片仅当前 turn 有效，重启后 LLM 需要通过 Read 工具重新读取图片文件（如果图片来自项目文件）。

---

## 五、ImageRef 数据结构

```
ImageRef:
├── id: String              // 唯一标识（UUID）
├── fileName: String        // 文件名，粘贴图片生成时间戳名如 "paste_20260628_143000.png"
├── base64Data: String      // Base64 编码数据
├── mimeType: String        // MIME 类型，"image/png" / "image/jpeg" / "image/gif" / "image/webp"
├── thumbnail: BufferedImage? // 可选缩略图；当前输入框紧凑芯片不展示
├── width: Int              // 缩放后宽度（px）
├── height: Int             // 缩放后高度（px）
└── sizeBytes: Long         // Base64 编码前字节数
```
