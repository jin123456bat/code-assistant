# Skills 页面卡片高度修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 Skills 页面多行卡片被压缩后发生的文本重叠和裁切。

**Architecture:** 保留现有 `JPanel(BorderLayout)` 卡片和 `BoxLayout.Y_AXIS` 列表，只把卡片最大高度的计算移到所有子组件组装完成之后。测试通过真实临时 Skill 文件构造页面，并从组件树取得 Skill 卡片验证尺寸约束。

**Tech Stack:** Kotlin 2.0、Java Swing、IntelliJ Platform 2024.3、kotlin-test、Gradle

## Global Constraints

- 仅调整 Skill 卡片最大高度的计算时机。
- 不改变标题栏、滚动容器、卡片内容、颜色、间距和交互。
- 不处理 HTML 转义或超长文本换行。
- 必须先观察回归测试在当前实现上因 `maximumSize.height < preferredSize.height` 失败。

---

### Task 1: 修复 Skill 卡片高度约束

**Files:**
- Create: `src/test/kotlin/com/aiassistant/ui/page/SkillsPageTest.kt`
- Modify: `src/main/kotlin/com/aiassistant/ui/page/SkillsPage.kt:152-234`

**Interfaces:**
- Consumes: `SkillsPage(Project)` 和 `.code-assistant/skills/<name>/SKILL.md`。
- Produces: 组装完成后满足 `maximumSize.height == preferredSize.height` 的 Skill 卡片。

- [x] **Step 1: 写入失败回归测试**

```kotlin
package com.aiassistant.ui.page

import com.intellij.openapi.project.Project
import java.awt.Container
import java.lang.reflect.Proxy
import javax.swing.JLabel
import javax.swing.JPanel
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class SkillsPageTest {

    @Test
    fun `skill card maximum height contains all metadata rows`() {
        val root = createTempDirectory()
        val skillDir = root.resolve(".code-assistant/skills/review").createDirectories()
        skillDir.resolve("SKILL.md").writeText(
            """
            ---
            name: review
            description: Review code for correctness and maintainability
            command: review
            tools:
              - read
            triggers:
              - review
              - 审查
            ---
            Review the current changes.
            """.trimIndent()
        )
        val page = SkillsPage(projectAt(root.toString()))
        val skillCard = panelsIn(page).first { panel ->
            panel.components.any { child ->
                child is JLabel && child.text.contains("<b>review</b>")
            }
        }

        assertEquals(
            skillCard.preferredSize.height,
            skillCard.maximumSize.height,
            "Skill card maximum height must match its final preferred height"
        )
    }

    private fun panelsIn(container: Container): List<JPanel> =
        container.components.flatMap { child ->
            when (child) {
                is JPanel -> listOf(child) + panelsIn(child)
                is Container -> panelsIn(child)
                else -> emptyList()
            }
        }

    private fun projectAt(basePath: String): Project =
        Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getBasePath" -> basePath
                "getName" -> "TestProject"
                "isDisposed" -> false
                "toString" -> "TestProject($basePath)"
                else -> null
            }
        } as Project
}
```

- [x] **Step 2: 运行测试并确认因现有高度冻结而失败**

Run: `./gradlew test --tests com.aiassistant.ui.page.SkillsPageTest --rerun-tasks`

Expected: FAIL，错误信息包含 `Skill card maximum height 17 must contain preferred height`。

- [x] **Step 3: 实施最小修复**

从 `renderCard()` 创建空卡片时删除：

```kotlin
maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
```

在 `card.add(detailBtn, BorderLayout.EAST)` 之后、`return card` 之前加入：

```kotlin
card.maximumSize = Dimension(Int.MAX_VALUE, card.preferredSize.height)
```

- [x] **Step 4: 运行回归测试并确认通过**

Run: `./gradlew test --tests com.aiassistant.ui.page.SkillsPageTest --rerun-tasks`

Expected: PASS。

- [x] **Step 5: 运行完整自动验证**

Run: `./gradlew test --rerun-tasks`

Expected: BUILD SUCCESSFUL，0 个失败测试。

Run: `./gradlew buildPlugin`

Expected: BUILD SUCCESSFUL。

Run: `git diff --check`

Expected: 无输出并返回 0。

- [x] **Step 6: 验证 IDE 页面**

Run: `./gradlew runIde`

Expected: 打开 Skills 页面后，含描述、触发词和所需工具的卡片按内容自然增高，文本没有重叠或裁切，滚动条可正常浏览列表。

- [x] **Step 7: 提交修复**

```bash
git add src/main/kotlin/com/aiassistant/ui/page/SkillsPage.kt src/test/kotlin/com/aiassistant/ui/page/SkillsPageTest.kt docs/superpowers/plans/2026-07-11-skills-page-card-height.md
git commit -m "fix: 修复 Skills 页面卡片高度错乱"
```
