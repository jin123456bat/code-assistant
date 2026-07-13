package com.aiassistant

import com.aiassistant.completion.FimCandidateShortcutBinding
import com.aiassistant.completion.buildInlineCompletionSuggestion
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.ui.ComponentUtil
import java.awt.event.KeyEvent
import java.lang.reflect.Proxy
import javax.swing.JTextArea
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PluginXmlTest {

    @Test
    fun `registers tools settings configurable`() {
        val pluginXml = java.io.File("src/main/resources/META-INF/plugin.xml").readText()

        assertContains(pluginXml, """<applicationConfigurable""")
        assertContains(pluginXml, """instance="com.aiassistant.SettingsConfigurable"""")
    }

    @Test
    fun `does not register FIM arrow keys globally`() {
        val pluginXml = java.io.File("src/main/resources/META-INF/plugin.xml").readText()

        assertFalse(
            pluginXml.contains("""first-keystroke="DOWN"""),
            "FIM 下一个候选不能占用全局 DOWN 键",
        )
        assertFalse(
            pluginXml.contains("""first-keystroke="UP"""),
            "FIM 上一个候选不能占用全局 UP 键",
        )
        assertFalse(pluginXml.contains("FimCandidateShortcutRegistrar"))
    }

    @Test
    fun `registers and unregisters FIM shortcuts on editor component`() {
        val component = JTextArea()
        val editor = createEditorProxy(component)
        val sessionDisposable = Disposer.newDisposable()

        FimCandidateShortcutBinding.registerActions(editor, sessionDisposable)

        val actions = ComponentUtil.getClientProperty(component, AnAction.ACTIONS_KEY)
        assertNotNull(actions)
        assertEquals(2, actions.size)
        val keyCodes = actions
            .flatMap { it.shortcutSet.shortcuts.asList() }
            .filterIsInstance<KeyboardShortcut>()
            .map { it.firstKeyStroke.keyCode }
            .toSet()
        assertEquals(setOf(KeyEvent.VK_UP, KeyEvent.VK_DOWN), keyCodes)

        Disposer.dispose(sessionDisposable)

        assertTrue(ComponentUtil.getClientProperty(component, AnAction.ACTIONS_KEY).orEmpty().isEmpty())
    }

    @Test
    fun `builds each FIM candidate as an independent variant`() = runBlocking {
        val suggestion = buildInlineCompletionSuggestion(listOf("first", "second"))

        val variants = suggestion.getVariants()

        assertEquals(2, variants.size)
    }

    private fun createEditorProxy(component: JTextArea): Editor {
        val userData = mutableMapOf<Key<*>, Any?>()
        return Proxy.newProxyInstance(
            Editor::class.java.classLoader,
            arrayOf(Editor::class.java),
        ) { proxy, method, arguments ->
            when (method.name) {
                "getContentComponent" -> component
                "getUserData" -> userData[arguments!![0] as Key<*>]
                "putUserData" -> {
                    val key = arguments!![0] as Key<*>
                    val value = arguments[1]
                    if (value == null) userData.remove(key) else userData[key] = value
                    null
                }
                "equals" -> proxy === arguments!![0]
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "EditorProxy"
                else -> error("Unexpected Editor method: ${method.name}")
            }
        } as Editor
    }
}
