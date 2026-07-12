# Built-in Tool Name Contract Design

## Problem

Anthropic Java SDK 2.43.0 derives a class-based tool name from the parameter class name and converts it to `snake_case`. The plugin registers and dispatches built-in tools with explicit product names such as `Bash`, `readLints`, and `createPlan`. As a result, the model calls `bash`, while approval and execution only recognize `Bash`.

`ToolRegistry.dispose()` also clears a process-wide object from a project-scoped `ChatToolWindow.dispose()`. The object initializer does not run again, so closing one window can remove built-in tools for other projects.

## Design

- Declare every registered built-in tool's public name explicitly with `@JsonTypeName`, using the existing `ToolRegistry` name as the source of truth.
- Derive legacy SDK `snake_case` aliases in `ToolRegistry` and canonicalize them before UI display, approval, session state updates, special handling, and execution.
- Verify names through the actual Anthropic SDK `MessageCreateParams.Builder.addTool(Class)` path, not only through registry lookups.
- Keep approval and execution branches on the documented canonical names; compatibility aliases never bypass approval.
- Remove project-scoped clearing of the process-wide registry. Dynamic MCP entries continue to be managed by their owning MCP lifecycle rather than by `ChatToolWindow`.

## Safety and Compatibility

- `Bash` reaches the existing dangerous-command checks before execution.
- MCP tools are unaffected because they already use explicit `BetaTool` names.
- Lowercase names from an older SDK or provider are normalized before approval, so `bash` receives the same dangerous-command checks as `Bash`.

## Verification

- A regression test must first demonstrate that SDK-generated names differ from registry names.
- After the fix, every registered built-in class must generate exactly its registered name.
- Registry lifecycle tests must prove built-ins remain available when project UI disposal occurs.
- Run targeted agent tests, the full test suite, plugin build, and `git diff --check`.
