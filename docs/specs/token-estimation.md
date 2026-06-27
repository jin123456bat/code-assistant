# Token 估算规范

> **原始来源：** `docs/tech-spec.md`（已拆分，内容归入 `docs/specs/` 各文件）

整个项目使用统一的 Token 估算方法，所有依赖 token 计数的场景均调用此方法。

---

## 估算公式

**公式（伪代码）：**

```
fun estimateTokens(text: String): Int {
    if (text.isEmpty()) return 0
    val bytes = text.encodeToByteArray().size
    val asciiOnly = text.count { it.code <= 127 }
    val nonAscii = text.length - asciiOnly
    // 英文/代码 ~4 字节/token，中文 ~0.67 token/字符（即 1.5 字符/token）
    return max(bytes / 4, asciiOnly / 4 + (nonAscii * 3) / 2)
}
```

## 精度说明

启发式估算，误差 ±20%。API 返回的 `usage` 字段为精确值，优先使用。估算仅用于"写入前"的场景（compact
阈值判定、输入框预览），持久化时使用 API 返回的精确值。

## 适用场景及上限

| 场景                        | 上限                     | 方法                         |
|---------------------------|------------------------|----------------------------|
| Auto-Compact 触发判定         | 1M x 0.7 = 700K tokens | `estimateTokens()` 估算      |
| 输入框实时 token 预览            | 无上限（仅展示）               | `estimateTokens()` 估算      |
| `session.totalTokens` 持久化 | 精确值                    | API `usage` 字段，fallback 估算 |
| 子 Agent 结果摘要截断            | 2000 tokens            | `estimateTokens()` 估算截断点   |
| 工具返回值截断                   | 见工具截断表（按行）             | 不依赖 token 估算               |

## 紧凑阈值选择说明

`compactThreshold = 0.7`（即 1M x 0.7 = 700K tokens）：token 估算误差 ±20%，最坏低估时 0.7/0.8 = 87.5%
仍在 1M 窗口内；若设 0.8 则最坏已达 100%。

