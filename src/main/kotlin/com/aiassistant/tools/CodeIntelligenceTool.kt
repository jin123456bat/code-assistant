package com.aiassistant.tools

import com.aiassistant.agent.AgentTool
import com.aiassistant.agent.ToolParameter
import com.aiassistant.agent.ToolResult
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiClass
import com.intellij.psi.presentation.java.SymbolPresentationUtil
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.search.searches.ReferencesSearch

/**
 * PSI 代码智能工具 — 通过 IntelliJ PSI API 提供结构化代码导航。
 */
class CodeIntelligenceTool : AgentTool {

    override val name = "code_intelligence"
    override val description = buildString {
        append("通过 IntelliJ PSI 引擎提供结构化代码导航。支持以下操作：\n")
        append("- go_to_definition: 跳转到符号定义位置\n")
        append("- find_references: 查找符号的所有引用点\n")
        append("- find_implementations: 查找接口/抽象类的所有实现类（需 Java/Kotlin 支持）\n")
        append("- hover: 返回符号的类型信息和文档注释\n")
        append("- document_symbols: 列出文件的所有顶层符号\n")
        append("- workspace_symbol: 按文件名或符号名在全局范围内搜索")
    }
    override val parameters = listOf(
        ToolParameter(
            "operation", "string",
            "要执行的代码智能操作",
            required = true,
            enum = listOf(
                "go_to_definition", "find_references", "find_implementations",
                "hover", "document_symbols", "workspace_symbol"
            )
        ),
        ToolParameter("file_path", "string", "文件相对项目根路径（workspace_symbol 操作除外）"),
        ToolParameter("line", "integer", "光标行号，1-based（document_symbols 和 workspace_symbol 操作除外）"),
        ToolParameter("character", "integer", "光标字符偏移，1-based（document_symbols 和 workspace_symbol 操作除外）"),
        ToolParameter("query", "string", "workspace_symbol 操作的符号或文件名搜索词"),
        ToolParameter("max_results", "integer", "最大返回结果数，默认 20")
    )

    override fun execute(params: Map<String, String>, project: Project): ToolResult {
        val operation = params["operation"] ?: return ToolResult.err("缺少 operation 参数")
        val basePath = project.basePath ?: return ToolResult.err("项目路径不可用")
        val maxResults = params["max_results"]?.toIntOrNull() ?: 20

        // PSI/Index 操作必须在 read action 中执行（AgentLoop 运行在后台线程）
        return try {
            ApplicationManager.getApplication().runReadAction<ToolResult> {
                when (operation) {
                    "document_symbols" -> documentSymbols(params, project, basePath)
                    "workspace_symbol" -> workspaceSymbols(params, project, basePath, maxResults)
                    else -> {
                        val filePath = params["file_path"] ?: return@runReadAction ToolResult.err("缺少 file_path 参数")
                        val line = params["line"]?.toIntOrNull() ?: return@runReadAction ToolResult.err("缺少 line 参数")
                        val char = params["character"]?.toIntOrNull() ?: return@runReadAction ToolResult.err("缺少 character 参数")
                        val element = resolveElement(project, basePath, filePath, line, char)
                            ?: return@runReadAction ToolResult.err("无法定位 $filePath:$line:$char 处的元素")

                        when (operation) {
                            "go_to_definition" -> goToDefinition(element, project, basePath)
                            "find_references" -> findReferences(element, project, basePath, maxResults)
                            "find_implementations" -> findImplementations(element, project, basePath, maxResults)
                            "hover" -> hover(element, project, basePath)
                            else -> ToolResult.err("未知操作: $operation")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            ToolResult.err("$operation 执行失败: ${e.message}")
        }
    }

    // ---- 核心辅助 ----

    /** 根据文件路径 + 行列号定位 PSI 元素 */
    private fun resolveElement(
        project: Project, basePath: String, filePath: String, line: Int, character: Int
    ): PsiElement? {
        val separator = java.io.File.separator
        val fullPath = if (filePath.startsWith(basePath)) filePath else "$basePath$separator$filePath"
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(fullPath) ?: return null
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile) ?: return null
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return null
        if (document.textLength == 0) return null
        val offset = (document.getLineStartOffset((line - 1).coerceAtLeast(0)) + (character - 1).coerceAtLeast(0))
            .coerceIn(0, document.textLength - 1)
        return psiFile.findElementAt(offset) ?: psiFile
    }

    private fun formatLocation(element: PsiElement, basePath: String): String {
        val vFile = element.containingFile?.virtualFile ?: return "(未知)"
        val relativePath = vFile.path.removePrefix("$basePath${java.io.File.separator}")
        val document = FileDocumentManager.getInstance().getDocument(vFile) ?: return relativePath
        val offset = element.textOffset.coerceIn(0, document.textLength - 1)
        val elLine = document.getLineNumber(offset) + 1
        val elCol = offset - document.getLineStartOffset(elLine - 1) + 1
        return "$relativePath:$elLine:$elCol"
    }

    private fun getLineContent(element: PsiElement): String {
        val vFile = element.containingFile?.virtualFile ?: return ""
        val document = FileDocumentManager.getInstance().getDocument(vFile) ?: return ""
        val offset = element.textOffset.coerceIn(0, document.textLength - 1)
        val lineNum = document.getLineNumber(offset)
        val lineStart = document.getLineStartOffset(lineNum)
        val lineEnd = document.getLineEndOffset(lineNum)
        return document.text.substring(lineStart, lineEnd).trim().take(120)
    }

    // ---- 操作实现 ----

    private fun goToDefinition(element: PsiElement, project: Project, basePath: String): ToolResult {
        // 尝试通过 reference 解析
        val reference = element.reference
        if (reference != null) {
            val resolved = reference.resolve()
            if (resolved != null) {
                val name = (resolved as? PsiNamedElement)?.name ?: resolved.text.take(40)
                return ToolResult.ok("### 定义位置\n符号: $name\n位置: ${formatLocation(resolved, basePath)}")
            }
        }
        // 元素本身即为定义
        if (element is PsiNamedElement && element.name != null) {
            return ToolResult.ok("### 定义位置\n符号: ${element.name}\n位置: ${formatLocation(element, basePath)}\n（元素本身即为定义）")
        }
        // 向上查找父元素引用
        var parent = element.parent
        while (parent != null) {
            if (parent is PsiNamedElement && parent.name != null) {
                val parentRef = parent.reference
                if (parentRef != null) {
                    val resolved = parentRef.resolve()
                    if (resolved != null) {
                        val name = (resolved as? PsiNamedElement)?.name ?: resolved.text.take(40)
                        return ToolResult.ok("### 定义位置\n符号: $name\n位置: ${formatLocation(resolved, basePath)}")
                    }
                }
                // 父元素本身就是定义
                return ToolResult.ok("### 定义位置\n符号: ${parent.name}\n位置: ${formatLocation(parent, basePath)}")
            }
            parent = parent.parent
        }
        return ToolResult.err("无法解析该位置的符号定义")
    }

    private fun findReferences(element: PsiElement, project: Project, basePath: String, maxResults: Int): ToolResult {
        val searchTarget = findSearchableElement(element)
            ?: return ToolResult.err("该位置没有可搜索引用的符号")

        val symbolName = (searchTarget as? PsiNamedElement)?.name ?: searchTarget.text.take(40)
        val refs = try {
            ReferencesSearch.search(searchTarget, GlobalSearchScope.projectScope(project))
                .findAll()
                .take(maxResults)
        } catch (_: Exception) {
            return ToolResult.err("该符号类型不支持引用搜索")
        }

        if (refs.isEmpty()) {
            return ToolResult.ok("### 查找引用\n符号: $symbolName\n未找到引用")
        }

        val sb = StringBuilder()
        sb.appendLine("### 查找引用")
        sb.appendLine("符号: $symbolName (${formatLocation(searchTarget, basePath)})")
        sb.appendLine("找到 ${refs.size} 个引用:")
        sb.appendLine()
        refs.forEachIndexed { i, ref ->
            val refEl = ref.element
            val loc = formatLocation(refEl, basePath)
            val line = getLineContent(refEl)
            val marker = if (refEl == searchTarget) " ← 定义" else ""
            sb.appendLine("${i + 1}. $loc  |  $line$marker")
        }
        return ToolResult.ok(sb.toString())
    }

    private fun findSearchableElement(element: PsiElement): PsiElement? {
        var e: PsiElement? = element
        while (e != null) {
            if (e is PsiNamedElement && e.name != null) return e
            e = e.parent
        }
        return null
    }

    private fun findImplementations(element: PsiElement, project: Project, basePath: String, maxResults: Int): ToolResult {
        // 找到 PsiClass（接口或抽象类）
        val psiClass = findParentPsiClass(element)
            ?: return ToolResult.err("该位置不是类或接口声明")

        return try {
            val isInterface = psiClass.isInterface
            val isAbstract = psiClass.hasModifierProperty("abstract")
            if (!isInterface && !isAbstract) {
                return ToolResult.err("${psiClass.name} 不是接口或抽象类")
            }

            val kind = if (isInterface) "接口" else "抽象类"
            val inheritors = ClassInheritorsSearch.search(psiClass, GlobalSearchScope.projectScope(project), true)
                .findAll()
                .take(maxResults)

            val sb = StringBuilder()
            sb.appendLine("### 查找实现")
            sb.appendLine("$kind: ${psiClass.name} (${formatLocation(psiClass, basePath)})")
            sb.appendLine()

            if (inheritors.isEmpty()) {
                sb.appendLine("未找到实现类")
            } else {
                sb.appendLine("找到 ${inheritors.size} 个实现:")
                inheritors.forEachIndexed { i, impl ->
                    val loc = formatLocation(impl, basePath)
                    sb.appendLine("${i + 1}. ${impl.name ?: "(匿名)"} — $loc")
                }
            }
            ToolResult.ok(sb.toString())
        } catch (e: Exception) {
            ToolResult.err("查找实现失败: ${e.message}（当前 IDE 可能不支持该语言的类层级搜索）")
        }
    }

    private fun findParentPsiClass(element: PsiElement): PsiClass? {
        var e: PsiElement? = element
        while (e != null) {
            if (e is PsiClass) return e
            e = e.parent
        }
        return null
    }

    private fun hover(element: PsiElement, project: Project, basePath: String): ToolResult {
        val sb = StringBuilder()
        sb.appendLine("### 类型信息")

        // 找到最近的命名元素
        val namedElement = findSearchableElement(element)
        if (namedElement != null && namedElement is PsiNamedElement && namedElement.name != null) {
            sb.appendLine("符号: ${namedElement.name}")
            sb.appendLine("位置: ${formatLocation(namedElement, basePath)}")

            // 类型/呈现文本
            try {
                val typeText = SymbolPresentationUtil.getSymbolPresentableText(namedElement)
                if (typeText.isNotEmpty()) {
                    sb.appendLine("类型: $typeText")
                }
            } catch (_: Exception) { /* 类型推导不可用 */ }

            // 尝试解析引用目标
            val reference = namedElement.reference
            if (reference != null) {
                try {
                    val resolved = reference.resolve()
                    if (resolved != null && resolved != namedElement) {
                        sb.appendLine("定义: ${formatLocation(resolved, basePath)}")
                    }
                } catch (_: Exception) { /* 解析失败 */ }
            }
        } else {
            sb.appendLine("符号: ${element.text.take(60)}")
            sb.appendLine("位置: ${formatLocation(element, basePath)}")
        }

        return ToolResult.ok(sb.toString())
    }

    private fun documentSymbols(params: Map<String, String>, project: Project, basePath: String): ToolResult {
        val filePath = params["file_path"] ?: return ToolResult.err("缺少 file_path 参数")
        val separator = java.io.File.separator
        val fullPath = if (filePath.startsWith(basePath)) filePath else "$basePath$separator$filePath"
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(fullPath)
            ?: return ToolResult.err("文件不存在: $filePath")
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
            ?: return ToolResult.err("无法解析: $filePath")

        val symbols = collectTopLevelSymbols(psiFile)
        if (symbols.isEmpty()) {
            return ToolResult.ok("### 文件符号\n文件: $filePath\n未找到顶层符号")
        }

        val sb = StringBuilder()
        sb.appendLine("### 文件符号")
        sb.appendLine("文件: $filePath")
        sb.appendLine("共 ${symbols.size} 个顶层符号:")
        sb.appendLine()
        symbols.forEachIndexed { i, (name, kind, elLine) ->
            sb.appendLine("${i + 1}. [$kind] $name (行 $elLine)")
        }
        return ToolResult.ok(sb.toString())
    }

    private fun collectTopLevelSymbols(psiFile: PsiElement): List<Triple<String, String, Int>> {
        val symbols = mutableListOf<Triple<String, String, Int>>()
        for (child in psiFile.children) {
            if (child is PsiNamedElement && child.name != null) {
                val kind = child.javaClass.simpleName
                    .removePrefix("Psi")
                    .removeSuffix("Impl")
                    .ifEmpty { "Element" }
                val document = child.containingFile?.virtualFile?.let {
                    FileDocumentManager.getInstance().getDocument(it)
                }
                val line = document?.getLineNumber(child.textOffset)?.plus(1) ?: 0
                symbols.add(Triple(child.name!!, kind, line))
            }
        }
        return symbols
    }

    private fun workspaceSymbols(
        params: Map<String, String>, project: Project, basePath: String, maxResults: Int
    ): ToolResult {
        val query = params["query"] ?: return ToolResult.err("缺少 query 参数")

        // 通过 FilenameIndex 搜索文件名匹配
        val scope = GlobalSearchScope.projectScope(project)
        val matches = try {
            FilenameIndex.getAllFilenames(project)
                .filter { it.contains(query, ignoreCase = true) }
                .take(maxResults)
        } catch (_: Exception) {
            return ToolResult.err("无法访问文件索引")
        }

        val sb = StringBuilder()
        sb.appendLine("### 全局符号搜索")
        sb.appendLine("搜索词: $query")
        sb.appendLine()

        if (matches.isEmpty()) {
            sb.appendLine("未找到匹配的文件名")
            return ToolResult.ok(sb.toString())
        }

        sb.appendLine("匹配的文件名 (${matches.size}):")
        sb.appendLine()
        for (name in matches) {
            val files = FilenameIndex.getVirtualFilesByName(name, scope).take(3)
            for (vf in files) {
                val relativePath = vf.path.removePrefix("$basePath${java.io.File.separator}")
                sb.appendLine("  $relativePath")
            }
        }

        return ToolResult.ok(sb.toString())
    }
}
