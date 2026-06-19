package com.aiassistant.ui

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager

/**
 * 右键菜单 Action 回调桥接——ChatToolWindow 注册 handler，右键菜单通过此桥接触发审查。
 * 改为 per-project 存储，多项目互不覆盖，支持 dispose 清理。
 */
object ReviewActionBridge {
    private val handlers = java.util.concurrent.ConcurrentHashMap<String, Handler>()

    data class Handler(
        val onReviewSelectedCode: ((String, String) -> Unit)?,
        val onSecurityReviewFile: ((String) -> Unit)?,
        val onFixSelectedCode: ((String, String) -> Unit)?,
        val onExplainSelectedCode: ((String, String) -> Unit)?,
        val onOptimizeSelectedCode: ((String, String) -> Unit)?,
        val onGenerateComment: ((String, String) -> Unit)?,
        val onGenerateTest: ((String, String) -> Unit)?
    )

    fun register(projectBasePath: String?,
                 onReviewSelectedCode: ((String, String) -> Unit)?,
                 onSecurityReviewFile: ((String) -> Unit)?,
                 onFixSelectedCode: ((String, String) -> Unit)? = null,
                 onExplainSelectedCode: ((String, String) -> Unit)? = null,
                 onOptimizeSelectedCode: ((String, String) -> Unit)? = null,
                 onGenerateComment: ((String, String) -> Unit)? = null,
                 onGenerateTest: ((String, String) -> Unit)? = null
    ) {
        val key = projectBasePath ?: return
        handlers[key] = Handler(onReviewSelectedCode, onSecurityReviewFile, onFixSelectedCode, onExplainSelectedCode, onOptimizeSelectedCode, onGenerateComment, onGenerateTest)
    }

    fun unregister(projectBasePath: String?) {
        val key = projectBasePath ?: return
        handlers.remove(key)
    }

    private fun getHandler(projectBasePath: String?): Handler? {
        val key = projectBasePath ?: return null
        return handlers[key]
    }

    fun getOnReviewSelectedCode(projectBasePath: String?): ((String, String) -> Unit)? {
        return getHandler(projectBasePath)?.onReviewSelectedCode
    }

    fun getOnSecurityReviewFile(projectBasePath: String?): ((String) -> Unit)? {
        return getHandler(projectBasePath)?.onSecurityReviewFile
    }

    fun getOnFixSelectedCode(projectBasePath: String?): ((String, String) -> Unit)? {
        return getHandler(projectBasePath)?.onFixSelectedCode
    }

    fun getOnExplainSelectedCode(projectBasePath: String?): ((String, String) -> Unit)? {
        return getHandler(projectBasePath)?.onExplainSelectedCode
    }

    fun getOnOptimizeSelectedCode(projectBasePath: String?): ((String, String) -> Unit)? {
        return getHandler(projectBasePath)?.onOptimizeSelectedCode
    }

    fun getOnGenerateComment(projectBasePath: String?): ((String, String) -> Unit)? {
        return getHandler(projectBasePath)?.onGenerateComment
    }

    fun getOnGenerateTest(projectBasePath: String?): ((String, String) -> Unit)? {
        return getHandler(projectBasePath)?.onGenerateTest
    }

    /** 兼容旧接口 */
    @Volatile
    @Deprecated("用 register/unregister 替代")
    var onReviewSelectedCode: ((String, String) -> Unit)? = null

    @Volatile
    @Deprecated("用 register/unregister 替代")
    var onSecurityReviewFile: ((String) -> Unit)? = null
}

class ReviewSelectedCodeAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        val file = e.getData(CommonDataKeys.PSI_FILE)?.virtualFile ?: return
        val code = e.getData(CommonDataKeys.EDITOR)?.selectionModel?.selectedText ?: ""
        val handler = ReviewActionBridge.getOnReviewSelectedCode(project.basePath)
        if (handler != null) {
            handler(file.path, code)
        } else {
            // 回退：发送到聊天窗口
            val msg = if (code.isNotBlank()) "请审查以下选中代码（${file.name}）：\n```\n${code.take(3000)}\n```"
            else "请审查文件 ${file.name}"
            com.aiassistant.ChatToolWindow.sendMessageToChat(project, msg)
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.getData(CommonDataKeys.PROJECT) != null
    }
}

class SecurityReviewFileAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        val file = e.getData(CommonDataKeys.PSI_FILE)?.virtualFile ?: return
        val handler = ReviewActionBridge.getOnSecurityReviewFile(project.basePath)
        if (handler != null) {
            handler(file.path)
        } else {
            com.aiassistant.ChatToolWindow.sendMessageToChat(project, "请对 ${file.name} 进行安全审查，检查注入向量、密钥泄漏、权限缺陷、不安全 API 和依赖漏洞。")
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.getData(CommonDataKeys.PSI_FILE) != null
    }
}

class FixSelectedCodeAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        val file = e.getData(CommonDataKeys.PSI_FILE)?.virtualFile ?: return
        val code = e.getData(CommonDataKeys.EDITOR)?.selectionModel?.selectedText ?: ""

        val handler = ReviewActionBridge.getOnFixSelectedCode(project.basePath)
        if (handler != null) {
            handler(file.path, code)
        } else {
            val msg = if (code.isNotBlank())
                "请修复以下代码（${file.name}）：\n```\n${code.take(3000)}\n```"
            else "请检查并修复 ${file.name} 中的问题"
            com.aiassistant.ChatToolWindow.sendMessageToChat(project, msg)
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.getData(CommonDataKeys.PROJECT) != null
    }
}

class ExplainSelectedCodeAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        val file = e.getData(CommonDataKeys.PSI_FILE)?.virtualFile ?: return
        val code = e.getData(CommonDataKeys.EDITOR)?.selectionModel?.selectedText ?: ""

        val handler = ReviewActionBridge.getOnExplainSelectedCode(project.basePath)
        if (handler != null) {
            handler(file.path, code)
        } else {
            val msg = if (code.isNotBlank())
                "请解释以下代码（${file.name}）：\n```\n${code.take(3000)}\n```"
            else "请解释 ${file.name} 的功能和设计思路"
            com.aiassistant.ChatToolWindow.sendMessageToChat(project, msg)
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.getData(CommonDataKeys.PROJECT) != null
    }
}

class OptimizeSelectedCodeAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        val file = e.getData(CommonDataKeys.PSI_FILE)?.virtualFile ?: return
        val code = e.getData(CommonDataKeys.EDITOR)?.selectionModel?.selectedText ?: ""

        val handler = ReviewActionBridge.getOnOptimizeSelectedCode(project.basePath)
        if (handler != null) {
            handler(file.path, code)
        } else {
            val msg = if (code.isNotBlank())
                "请优化以下代码，提升可读性和性能（${file.name}）：\n```\n${code.take(3000)}\n```"
            else "请分析 ${file.name} 并给出优化建议"
            com.aiassistant.ChatToolWindow.sendMessageToChat(project, msg)
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.getData(CommonDataKeys.PROJECT) != null
    }
}

class GenerateCommentAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        val file = e.getData(CommonDataKeys.PSI_FILE)?.virtualFile ?: return
        val code = e.getData(CommonDataKeys.EDITOR)?.selectionModel?.selectedText ?: ""

        val handler = ReviewActionBridge.getOnGenerateComment(project.basePath)
        if (handler != null) {
            handler(file.path, code)
        } else {
            val msg = if (code.isNotBlank())
                "请为以下代码生成中文注释（${file.name}）：\n```\n${code.take(3000)}\n```"
            else "请为 ${file.name} 生成完整的类/方法注释"
            com.aiassistant.ChatToolWindow.sendMessageToChat(project, msg)
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.getData(CommonDataKeys.PROJECT) != null
    }
}

class GenerateTestAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        val file = e.getData(CommonDataKeys.PSI_FILE)?.virtualFile ?: return
        val code = e.getData(CommonDataKeys.EDITOR)?.selectionModel?.selectedText ?: ""

        val handler = ReviewActionBridge.getOnGenerateTest(project.basePath)
        if (handler != null) {
            handler(file.path, code)
        } else {
            val msg = if (code.isNotBlank())
                "请为以下代码生成单元测试（${file.name}）：\n```\n${code.take(3000)}\n```"
            else "请为 ${file.name} 生成完整的单元测试"
            com.aiassistant.ChatToolWindow.sendMessageToChat(project, msg)
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.getData(CommonDataKeys.PROJECT) != null
    }
}
