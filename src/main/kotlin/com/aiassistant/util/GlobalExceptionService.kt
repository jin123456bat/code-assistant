package com.aiassistant.util

import com.aiassistant.AppLogger
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

/**
 * 项目级 Service，管理 GlobalExceptionHandler 的生命周期：
 * - project open 时注册全局异常处理器
 * - project close 时注销并清理
 *
 * 对齐文档 docs/specs/api-error-handling.md §九「全局异常处理」。
 */
@Service(Service.Level.PROJECT)
class GlobalExceptionService(private val project: Project) : Disposable {

    init {
        GlobalExceptionHandler.register()
        AppLogger.info("GlobalExceptionService 已初始化 (project=${project.name})")
    }

    override fun dispose() {
        GlobalExceptionHandler.unregister()
        AppLogger.info("GlobalExceptionService 已销毁 (project=${project.name})")
    }
}
