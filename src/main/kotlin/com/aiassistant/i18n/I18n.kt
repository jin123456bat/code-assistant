package com.aiassistant.i18n

import java.util.*

object I18n {

    private val locale: Locale by lazy { Locale.getDefault() }

    private val bundle: ResourceBundle by lazy {
        try {
            ResourceBundle.getBundle("i18n/messages", locale)
        } catch (_: MissingResourceException) {
            ResourceBundle.getBundle("i18n/messages", Locale.SIMPLIFIED_CHINESE)
        }
    }

    fun get(key: String, vararg args: Any): String {
        val template = try {
            bundle.getString(key)
        } catch (_: MissingResourceException) {
            return try {
                ResourceBundle.getBundle("i18n/messages", Locale.SIMPLIFIED_CHINESE).getString(key)
            } catch (_: MissingResourceException) {
                key
            }
        }
        return if (args.isEmpty()) template else String.format(template, *args)
    }

    /** 判断当前语言是否为中文 */
    fun isChinese(): Boolean = locale.language == "zh"

    /** 获取当前语言标识：zh_CN 或 en */
    fun languageSuffix(): String = if (isChinese()) "zh_CN" else "en"
}
