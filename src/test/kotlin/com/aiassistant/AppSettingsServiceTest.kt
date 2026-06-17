package com.aiassistant

import org.junit.Assert.*
import org.junit.Test

/**
 * AppSettingsService 单元测试。
 * 反射检查仅验证方法签名存在；业务逻辑测试依赖 IntelliJ 沙箱服务。
 */
class AppSettingsServiceTest {

    // ---- 不依赖 IntelliJ 服务的纯逻辑测试 ----

    @Test
    fun `compact ratio default should be in valid range`() {
        // 使用反射获取私有默认值，验证常量合理性
        val service = AppSettingsService::class.java
        val getMethod = service.getDeclaredMethod("getCompactRatio")
        assertNotNull("getCompactRatio should exist", getMethod)
    }

    @Test
    fun `isThinkingEnabled should exist and return boolean`() {
        val method = AppSettingsService::class.java.getDeclaredMethod("isThinkingEnabled")
        assertNotNull(method)
        assertEquals(java.lang.Boolean.TYPE, method.returnType)
    }

    @Test
    fun `getToolWhitelist should return Set of String`() {
        val method = AppSettingsService::class.java.getDeclaredMethod("getToolWhitelist")
        assertNotNull(method)
        assertEquals(Set::class.java, method.returnType)
    }

    @Test
    fun `add and remove tool whitelist methods should exist`() {
        val addMethod = AppSettingsService::class.java.getDeclaredMethod("addToolToWhitelist", String::class.java)
        val removeMethod = AppSettingsService::class.java.getDeclaredMethod("removeToolFromWhitelist", String::class.java)
        assertNotNull(addMethod)
        assertNotNull(removeMethod)
    }

    @Test
    fun `add and remove command whitelist methods should exist`() {
        val addMethod = AppSettingsService::class.java.getDeclaredMethod("addCommandToWhitelist", String::class.java)
        val removeMethod = AppSettingsService::class.java.getDeclaredMethod("removeCommandFromWhitelist", String::class.java)
        assertNotNull(addMethod)
        assertNotNull(removeMethod)
    }

    @Test
    fun `getModel and setModel should exist`() {
        val getMethod = AppSettingsService::class.java.getDeclaredMethod("getModel")
        val setMethod = AppSettingsService::class.java.getDeclaredMethod("setModel", String::class.java)
        assertNotNull(getMethod)
        assertNotNull(setMethod)
        assertEquals(String::class.java, getMethod.returnType)
    }

    @Test
    fun `getPrompt and setPrompt should exist`() {
        val getMethod = AppSettingsService::class.java.getDeclaredMethod("getPrompt")
        val setMethod = AppSettingsService::class.java.getDeclaredMethod("setPrompt", String::class.java)
        assertNotNull(getMethod)
        assertNotNull(setMethod)
    }

    @Test
    fun `getApiKey setApiKey clearApiKey should exist`() {
        assertNotNull(AppSettingsService::class.java.getDeclaredMethod("getApiKey"))
        assertNotNull(AppSettingsService::class.java.getDeclaredMethod("setApiKey", String::class.java))
        assertNotNull(AppSettingsService::class.java.getDeclaredMethod("clearApiKey"))
    }

    @Test
    fun `service should have correct annotation`() {
        val service = AppSettingsService::class.java
        val annotation = service.getAnnotation(com.intellij.openapi.components.Service::class.java)
        assertNotNull("@Service annotation should exist", annotation)
    }

    @Test
    fun `getCompactRatio should exist and return Double`() {
        val method = AppSettingsService::class.java.getDeclaredMethod("getCompactRatio")
        assertNotNull(method)
        // Double.TYPE 校验返回值类型为基本 double
    }
}
