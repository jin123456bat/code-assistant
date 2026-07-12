# Agent Message Incremental Rendering Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate repeated Swing component reconstruction during Agent streaming and reasoning updates, while giving every top-level message row one consistent 8px gap.

**Architecture:** `AgentBubbleHandle` owns a stable Markdown block reconciler and separates content updates from width layout. A stable thinking handle updates reasoning in place. `ChatPage` owns top-level row ordering and spacing through one helper instead of callback-specific spacers.

**Tech Stack:** Kotlin, Swing, IntelliJ Platform, JUnit/Kotlin test, Gradle.

## Global Constraints

- Timestamp remains outside the rounded Agent and user cards.
- Preserve existing Markdown block appearance and code block behavior.
- Ordinary token updates must not call `removeAll()` on the Agent body.
- Width changes must not recreate Markdown block components.
- Preserve unrelated uncommitted workspace changes.

---

### Task 1: Stable Agent Markdown blocks

**Files:**
- Modify: `src/main/kotlin/com/aiassistant/ui/chat/ChatBubbleRenderer.kt`
- Modify: `src/main/kotlin/com/aiassistant/ui/page/ChatPage.kt`
- Test: `src/test/kotlin/com/aiassistant/ui/chat/ChatBubbleRendererTest.kt`
- Test: `src/test/kotlin/com/aiassistant/ui/page/ChatPageTest.kt`

**Interfaces:**
- Consumes: streaming text deltas and the existing `MarkdownBlock` parser model.
- Produces: `AgentBubbleHandle.appendStreaming(delta)`, stable block views, and width-only `constrainWidth(maxWidth)`.

- [ ] **Step 1: Write failing component-identity tests**

Assert that paragraph components survive multiple streaming appends, committed prefix blocks survive tail changes, width updates preserve every block component, and finish preserves unchanged blocks.

- [ ] **Step 2: Run tests and verify RED**

Run: `./gradlew test --tests com.aiassistant.ui.chat.ChatBubbleRendererTest --tests com.aiassistant.ui.page.ChatPageTest --rerun-tasks`

Expected: failures showing paragraph/body components are replaced and width updates trigger reconstruction.

- [ ] **Step 3: Implement stable block reconciliation**

Introduce internal block views that expose stable `component`, mutable `block`, `update(newBlock)`, and `applyWidth(width)`. Reconcile blocks by stable prefix and block type; replace only the changed suffix. Move cursor and timestamp into stable hosts outside the reconciled body.

- [ ] **Step 4: Separate streaming and width updates**

Change ChatPage to append deltas directly. Apply width once when the bubble is created and later only from viewport resize handling. Add an unchanged-width early return.

- [ ] **Step 5: Run target tests and verify GREEN**

Run: `./gradlew test --tests com.aiassistant.ui.chat.ChatBubbleRendererTest --tests com.aiassistant.ui.page.ChatPageTest --rerun-tasks`

Expected: all target tests pass.

### Task 2: Stable reasoning block

**Files:**
- Modify: `src/main/kotlin/com/aiassistant/ui/chat/ChatBubbleRenderer.kt`
- Modify: `src/main/kotlin/com/aiassistant/ui/page/ChatPage.kt`
- Test: `src/test/kotlin/com/aiassistant/ui/chat/ChatBubbleRendererTest.kt`
- Test: `src/test/kotlin/com/aiassistant/ui/page/ChatPageTest.kt`

**Interfaces:**
- Produces: a thinking handle whose component, body and expanded state survive repeated reasoning updates.

- [ ] **Step 1: Write failing reasoning identity tests**

Update reasoning twice and assert the outer component and text area are the same objects while content and duration change.

- [ ] **Step 2: Run tests and verify RED**

Run the two target test classes. Expected: current remove/render/add implementation replaces the thinking component.

- [ ] **Step 3: Implement in-place reasoning updates**

Create the thinking component once, append reasoning to its text area, update the duration label, retain expanded state, and revalidate without changing its parent position.

- [ ] **Step 4: Run tests and verify GREEN**

Run the two target test classes. Expected: all reasoning tests pass.

### Task 3: Single owner for message-row spacing

**Files:**
- Modify: `src/main/kotlin/com/aiassistant/ui/page/ChatPage.kt`
- Test: `src/test/kotlin/com/aiassistant/ui/page/ChatPageTest.kt`

**Interfaces:**
- Produces: one row insertion/removal path that owns exactly one 8px spacer between adjacent top-level rows.

- [ ] **Step 1: Write failing row-spacing tests**

Build user → thinking → Agent sequences and assert the top-level component order alternates between rows and one 8px filler, without callback-owned duplicate spacers.

- [ ] **Step 2: Run test and verify RED**

Run: `./gradlew test --tests com.aiassistant.ui.page.ChatPageTest --rerun-tasks`

Expected: failure because message, tool and reasoning callbacks currently insert spacers independently.

- [ ] **Step 3: Centralize row spacing**

Add a single row append/remove helper in ChatPage, remove `reasoningSpacer` and direct `Box.createVerticalStrut(8)` calls from event callbacks, and keep the timestamp inside its owning row but outside the rounded card.

- [ ] **Step 4: Run full verification**

Run:

```bash
./gradlew test --rerun-tasks
./gradlew buildPlugin
git diff --check
```

Expected: every command exits with status 0.
