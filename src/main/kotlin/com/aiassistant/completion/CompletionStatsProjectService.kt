package com.aiassistant.completion

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class CompletionStatsProjectService(private val project: Project) : Disposable {

    override fun dispose() {
        project.basePath?.let { CompletionStats.persist(it) }
        CompletionStats.dispose()
    }
}
