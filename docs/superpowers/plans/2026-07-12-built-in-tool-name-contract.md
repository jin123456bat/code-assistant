# Built-in Tool Name Contract Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every built-in tool use one stable name across SDK schema generation, approval, session state, and execution, while preserving the global registry lifecycle.

**Architecture:** Existing `ToolRegistry` names remain canonical. Tool parameter classes explicitly expose those names through Jackson `@JsonTypeName`, which the Anthropic SDK already supports; legacy SDK names are normalized before approval and execution. Project UI disposal no longer clears the process-wide registry.

**Tech Stack:** Kotlin, Anthropic Java SDK 2.43.0, Jackson annotations, JUnit/Kotlin test, Gradle IntelliJ Plugin.

## Global Constraints

- Do not rename documented tool names.
- Do not add an execution-only lowercase alias that bypasses approval checks.
- Do not change MCP explicit-name behavior.
- Preserve unrelated uncommitted workspace changes.

---

### Task 1: Stable SDK-generated built-in names

**Files:**
- Modify: `src/main/kotlin/com/aiassistant/agent/ToolModels.kt`
- Test: `src/test/kotlin/com/aiassistant/agent/ToolRegistryTest.kt`

**Interfaces:**
- Consumes: `ToolRegistry.listRegistered(): List<RegisteredTool>` and `MessageCreateParams.Builder.addTool(Class<*>)`.
- Produces: each registered built-in class generates a `BetaTool` whose name equals `RegisteredTool.name`, and `ToolRegistry.canonicalName(String)` maps legacy SDK names to that same value.

- [x] **Step 1: Write the failing SDK-name regression test**

Build one `MessageCreateParams` per registered built-in class, add the class through `addTool`, and assert the generated `BetaTool.name()` equals the registry name.

- [x] **Step 2: Run the targeted test and verify RED**

Run: `./gradlew test --tests com.aiassistant.agent.ToolRegistryTest --rerun-tasks`

Expected: failure showing `bash` instead of `Bash` and equivalent mismatches for other built-ins.

- [x] **Step 3: Add explicit public names**

Import `com.fasterxml.jackson.annotation.JsonTypeName` and annotate every registered built-in class with its exact registry name, including `@JsonTypeName("Bash")`, `@JsonTypeName("readLints")`, and `@JsonTypeName("createPlan")`. Add registry-derived legacy aliases and canonicalize names before AgentLoop display/special handling and ToolExecutor approval/execution.

- [x] **Step 4: Run the targeted test and verify GREEN**

Run: `./gradlew test --tests com.aiassistant.agent.ToolRegistryTest --rerun-tasks`

Expected: all `ToolRegistryTest` tests pass.

### Task 2: Preserve the global registry lifecycle

**Files:**
- Modify: `src/main/kotlin/com/aiassistant/agent/ToolRegistry.kt`
- Modify: `src/main/kotlin/com/aiassistant/ui/ChatToolWindow.kt`
- Test: `src/test/kotlin/com/aiassistant/agent/ToolRegistryTest.kt`

**Interfaces:**
- Consumes: process-wide Kotlin `object ToolRegistry`.
- Produces: project UI disposal cannot clear built-in registrations.

- [x] **Step 1: Write the lifecycle regression test**

Add a reflection-level assertion that `ToolRegistry` exposes no public `dispose` operation, preventing project-scoped owners from clearing the process-wide registry.

- [x] **Step 2: Run the targeted test and verify RED**

Run: `./gradlew test --tests com.aiassistant.agent.ToolRegistryTest --rerun-tasks`

Expected: failure because `ToolRegistry.dispose()` currently exists.

- [x] **Step 3: Remove the invalid ownership path**

Remove `ToolRegistry.dispose()` and the call/import from `ChatToolWindow.dispose()`. Leave other plugin-unload cleanup unchanged.

- [x] **Step 4: Run targeted and full verification**

Run:

```bash
./gradlew test --tests com.aiassistant.agent.ToolRegistryTest --tests com.aiassistant.agent.ToolApprovalPolicyTest --rerun-tasks
./gradlew test --rerun-tasks
./gradlew buildPlugin
git diff --check
```

Expected: every command exits with status 0.
