package com.aiassistant.security

import com.aiassistant.review.Finding
import com.aiassistant.review.Severity

class SecurityReviewEngine(private val projectBasePath: String?) {
    private val injectionScanner = InjectionScanner()
    private val secretDetector = SecretDetector()
    private val permissionAnalyzer = PermissionAnalyzer()
    private val dependencyChecker = DependencyChecker(projectBasePath)

    data class SecurityReport(
        val findings: List<Finding>,
        val dimensionsCovered: List<String>,
        val score: Int
    )

    fun analyze(fileContents: Map<String, String>): SecurityReport {
        val allFindings = mutableListOf<Finding>()
        val dimensions = mutableListOf<String>()

        for ((path, content) in fileContents) {
            val inj = injectionScanner.scan(content, path)
            if (inj.isNotEmpty()) { allFindings.addAll(inj); if ("injection" !in dimensions) dimensions.add("injection") }
            val sec = secretDetector.scan(content, path)
            if (sec.isNotEmpty()) { allFindings.addAll(sec); if ("secrets" !in dimensions) dimensions.add("secrets") }
            val perm = permissionAnalyzer.scan(content, path)
            if (perm.isNotEmpty()) { allFindings.addAll(perm); if ("permissions" !in dimensions) dimensions.add("permissions") }
        }

        val depFindings = dependencyChecker.check()
        if (depFindings.isNotEmpty()) { allFindings.addAll(depFindings); dimensions.add("dependencies") }

        val score = if (allFindings.isEmpty()) 100 else (100 - allFindings.count { it.severity == Severity.CRITICAL } * 20).coerceAtLeast(0)
        return SecurityReport(allFindings.sortedByDescending { it.severity.ordinal }, dimensions, score)
    }
}
