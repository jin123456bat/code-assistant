package com.aiassistant.review

class CommentFormatter {
    fun toGitHub(findings: List<Finding>): String {
        return buildString {
            findings.forEach { f ->
                appendLine("> **${f.severity}** (${f.category}, confidence: ${f.confidence}/10)")
                appendLine("> **${f.title}**")
                appendLine("> ${f.description}")
                if (f.suggestion.isNotBlank()) {
                    appendLine("> ```suggestion")
                    appendLine("> ${f.suggestion}")
                    appendLine("> ```")
                }
                appendLine()
            }
        }
    }
}
