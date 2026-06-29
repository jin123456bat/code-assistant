package com.aiassistant.agent

import com.anthropic.core.JsonObject
import com.anthropic.core.JsonValue

internal object ToolInput {

    fun string(input: Any?, key: String): String? {
        val value = value(input, key) ?: return null
        return when (value) {
            is String -> value
            is JsonValue -> jsonString(value)
            else -> value.toString().removeSurrounding("\"")
        }
    }

    fun int(input: Any?, key: String): Int? {
        val value = value(input, key) ?: return null
        return when (value) {
            is Int -> value
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            is JsonValue -> jsonInt(value)
            else -> value.toString().removeSurrounding("\"").toIntOrNull()
        }
    }

    fun bool(input: Any?, key: String): Boolean? {
        val value = value(input, key) ?: return null
        return when (value) {
            is Boolean -> value
            is String -> value.toBooleanStrictOrNull()
            is JsonValue -> runCatching { value.convert(Boolean::class.java) }.getOrNull()
            else -> value.toString().removeSurrounding("\"").toBooleanStrictOrNull()
        }
    }

    fun map(input: Any?): Map<String, Any?> {
        return when (input) {
            is JsonObject -> input.values.mapValues { (_, value) -> jsonAny(value) }
            is JsonValue -> runCatching {
                @Suppress("UNCHECKED_CAST")
                input.convert(Map::class.java) as Map<String, Any?>
            }.getOrDefault(emptyMap())

            is Map<*, *> -> input.entries.associate { (key, value) -> key.toString() to value }
            else -> emptyMap()
        }
    }

    private fun value(input: Any?, key: String): Any? {
        return when (input) {
            is JsonObject -> input.values[key]
            is JsonValue -> map(input)[key]
            is Map<*, *> -> input[key]
            else -> null
        }
    }

    private fun jsonString(value: JsonValue): String? {
        return runCatching { value.convert(String::class.java) }
            .getOrNull()
            ?: jsonAny(value)?.toString()
    }

    private fun jsonInt(value: JsonValue): Int? {
        return runCatching { value.convert(Int::class.java) }.getOrNull()
            ?: runCatching { value.convert(java.lang.Integer::class.java)?.toInt() }.getOrNull()
            ?: runCatching { value.convert(String::class.java)?.toIntOrNull() }.getOrNull()
    }

    private fun jsonAny(value: JsonValue): Any? {
        return runCatching { value.convert(Any::class.java) }.getOrNull()
            ?: value.toString().removeSurrounding("\"")
    }
}
