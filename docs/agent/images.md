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
剪贴板检测到 imageFlavor
  → BufferedImage（原始像素数据）
  → 缩放：长边 > 2048px 时等比缩放到 2048px
  → PNG 编码（无损压缩）
  → Base64 编码
  → 封装为 ImageRef(base64Data, mimeType="image/png")
  → 追加到 ChatViewModel.images[]
  → TagsRow 显示图片缩略图 tag（可点击 [✕] 移除）
```

### 约束

| 限制项    | 值                  | 说明                    |
|--------|--------------------|-----------------------|
| 单张大小上限 | 5MB（原始像素数据）        | 超过拒绝粘贴，toast 提示       |
| 单次粘贴张数 | ≤ 5 张              | 超过 5 张时仅取前 5 张        |
| 缩放策略   | 长边 max 2048px，等比缩放 | 保持宽高比，不拉伸             |
| 编码格式   | PNG（Base64）        | 无损编码，确保 LLM 看到的图片质量   |
| 内存占用   | 缩放后通常 ≤ 2MB/张      | 2048px 长边 PNG 约 1-3MB |

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
├── mediaType: String     // MIME 类型，粘贴图片统一为 "image/png"
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

`Read` 工具在读取图片文件时，返回图片内容而非文本：

| 特性       | 说明                                              |
|----------|-------------------------------------------------|
| **支持格式** | PNG、JPEG、GIF、WebP                               |
| **检测方式** | 文件扩展名（`.png`、`.jpg`、`.jpeg`、`.gif`、`.webp`）     |
| **返回格式** | ContentBlock 数组，含 `image` block（非 `text` block） |
| **大小限制** | 与粘贴图片相同：原始 ≤ 5MB                                |
| **多图片**  | Read 不返回多张图片——一个文件一个 image block                |

**与文本文件的区别：** Read 文本文件时返回 `ContentBlock.text(fileContent)`，Read 图片文件时返回
`ContentBlock.image(ImageSource)`。LLM 通过 block 类型区分。

---

## 四、图片在会话中的生命周期

| 阶段          | 行为                                                                                       |
|-------------|------------------------------------------------------------------------------------------|
| **粘贴**      | 编码为 Base64 → 存入 `ChatViewModel.images[]` → UI 显示缩略图 tag                                  |
| **发送**      | `buildContext()` 组装为 image block → 追加到 API 请求                                            |
| **持久化**     | Base64 数据**不**写入 Session JSON（过大）。`session.messages` 中仅保留 `[Image: screenshot.png]` 占位文本 |
| **恢复**      | 从 Session JSON 恢复时，图片 block 丢失，LLM 只能看到占位文本                                              |
| **Compact** | 与普通消息一同压缩为摘要，图片内容不参与摘要生成                                                                 |

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
├── thumbnail: BufferedImage // 缩略图（64×64，TagsRow 展示用）
├── width: Int              // 缩放后宽度（px）
├── height: Int             // 缩放后高度（px）
└── sizeBytes: Long         // Base64 编码前字节数
```
