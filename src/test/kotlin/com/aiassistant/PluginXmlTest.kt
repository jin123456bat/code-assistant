package com.aiassistant

import kotlin.test.Test
import kotlin.test.assertContains

class PluginXmlTest {

    @Test
    fun `registers tools settings configurable`() {
        val pluginXml = java.io.File("src/main/resources/META-INF/plugin.xml").readText()

        assertContains(pluginXml, """<applicationConfigurable""")
        assertContains(pluginXml, """instance="com.aiassistant.SettingsConfigurable"""")
    }
}
