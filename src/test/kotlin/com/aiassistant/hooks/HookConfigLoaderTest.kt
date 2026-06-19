package com.aiassistant.hooks

import org.junit.Test
import org.junit.Assert.*

class HookConfigLoaderTest {

    @Test
    fun `parse YAML extracts event names`() {
        val yaml = """
hooks:
  PreToolUse:
    - matcher: "write_file"
      hooks:
        - type: command
          command: "echo test"
""".trim()
        val config = HookConfigLoader.parseHookYaml(yaml)
        assertTrue("should contain PreToolUse", config.hooks.containsKey("PreToolUse"))
    }

    @Test
    fun `parse YAML extracts multiple events`() {
        val yaml = """
hooks:
  PreToolUse:
    - matcher: "write_file"
      hooks:
        - type: command
          command: "echo test"
  SessionStart:
    - hooks:
        - type: http
          url: "https://example.com"
""".trim()
        val config = HookConfigLoader.parseHookYaml(yaml)
        assertTrue(config.hooks.containsKey("PreToolUse"))
        assertTrue(config.hooks.containsKey("SessionStart"))
    }

    @Test
    fun `merge project overrides global`() {
        val global = HookConfig(
            mapOf(
                "PreToolUse" to listOf(
                    HookMatcherEntry("write_file", listOf(HookEntry("command", command = "global.sh")))
                )
            )
        )
        val project = HookConfig(
            mapOf(
                "PreToolUse" to listOf(
                    HookMatcherEntry("write_file", listOf(HookEntry("command", command = "project.sh")))
                )
            )
        )
        val merged = HookConfigLoader.merge(global, project)
        assertEquals(1, merged.hooks["PreToolUse"]!!.size)
        assertEquals("project.sh", merged.hooks["PreToolUse"]!![0].hooks[0].command)
    }

    @Test
    fun `merge adds new event from project`() {
        val global = HookConfig(emptyMap())
        val project = HookConfig(
            mapOf(
                "SessionStart" to listOf(
                    HookMatcherEntry(null, listOf(HookEntry("http", url = "https://example.com")))
                )
            )
        )
        val merged = HookConfigLoader.merge(global, project)
        assertEquals(1, merged.hooks.size)
        assertEquals("SessionStart", merged.hooks.keys.first())
    }

    @Test
    fun `parse YAML returns empty for non-hooks text`() {
        val config = HookConfigLoader.parseHookYaml("just some text")
        assertTrue(config.hooks.isEmpty())
    }
}
