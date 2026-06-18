package com.aiassistant.tools

import com.aiassistant.agent.AgentTool
import com.aiassistant.agent.ToolParameter
import com.aiassistant.agent.ToolResult
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.project.Project
import java.io.File

/**
 * Jupyter Notebook (.ipynb) 编辑工具。
 * 支持 replace、insert、delete 三种编辑模式。
 */
class NotebookEditTool : AgentTool {
    override val name = "notebook_edit"
    override val description = "编辑 Jupyter notebook 文件。支持替换、插入、删除 cell。"
    override val parameters = listOf(
        ToolParameter("notebook_path", "string", "notebook 文件路径，相对于项目根目录", required = true),
        ToolParameter("cell_id", "string", "要操作的 cell ID（insert/delete 时使用）"),
        ToolParameter("new_source", "string", "新的 cell 源码（replace/insert 时使用）"),
        ToolParameter("cell_type", "string", "cell 类型：code 或 markdown（insert 时必需）"),
        ToolParameter("edit_mode", "string", "编辑模式：replace（默认）、insert、delete")
    )

    override fun execute(params: Map<String, String>, project: Project, onProgress: ((String) -> Unit)?): ToolResult {
        val notebookPath = params["notebook_path"] ?: return ToolResult.err("缺少 notebook_path 参数")
        val editMode = params["edit_mode"] ?: "replace"
        val basePath = project.basePath ?: return ToolResult.err("项目路径不可用")

        val file = if (File(notebookPath).isAbsolute) File(notebookPath) else File(basePath, notebookPath)
        // 路径穿越防护（统一使用 PathUtils）
        if (!com.aiassistant.shared.PathUtils.isInsideProject(notebookPath, basePath)) {
            return ToolResult.err("安全限制：不能操作项目目录之外的文件")
        }
        if (!file.exists()) return ToolResult.err("文件不存在: $notebookPath")

        return try {
            val json = file.readText()
            val nb = JsonParser.parseString(json).asJsonObject
            val cells = nb.getAsJsonArray("cells") ?: return ToolResult.err("无效的 notebook 格式")

            when (editMode) {
                "replace" -> {
                    val cellId = params["cell_id"] ?: return ToolResult.err("replace 模式需要 cell_id 参数")
                    val newSource = params["new_source"] ?: return ToolResult.err("replace 模式需要 new_source 参数")
                    if (!replaceCell(cells, cellId, newSource)) {
                        return ToolResult.err("未找到 cell id: $cellId，请检查 cell_id 是否正确")
                    }
                }
                "insert" -> {
                    val insertAfterId = params["cell_id"]
                    val newSource = params["new_source"] ?: return ToolResult.err("insert 模式需要 new_source 参数")
                    val cellType = params["cell_type"] ?: "code"
                    insertCell(cells, insertAfterId, newSource, cellType)
                }
                "delete" -> {
                    val cellId = params["cell_id"] ?: return ToolResult.err("delete 模式需要 cell_id 参数")
                    if (!deleteCell(cells, cellId)) {
                        return ToolResult.err("未找到 cell id: $cellId，请检查 cell_id 是否正确")
                    }
                }
                else -> return ToolResult.err("未知编辑模式: $editMode，支持 replace/insert/delete")
            }

            val gson = Gson()
            // 原子写入：先写临时文件再 rename，防止写入中途崩溃损坏原文件
            val tmp = File(file.path + ".tmp")
            val bak = File(file.path + ".bak")
            try {
                tmp.writeText(gson.toJson(nb))
                try {
                    java.nio.file.Files.move(tmp.toPath(), file.toPath(),
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    if (file.exists()) java.nio.file.Files.move(file.toPath(), bak.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    bak.delete()
                }
            } finally {
                if (tmp.exists() && !tmp.delete()) tmp.deleteOnExit()
            }
            ToolResult.ok("notebook 已更新: $notebookPath (${editMode})")
        } catch (e: Exception) {
            ToolResult.err("notebook 编辑失败: ${e.message}")
        }
    }

    /** @return true 找到并替换，false 未找到对应 cell */
    private fun replaceCell(cells: JsonArray, cellId: String, newSource: String): Boolean {
        for (i in 0 until cells.size()) {
            val cell = cells[i].asJsonObject
            if (cell.get("id")?.asString == cellId) {
                val srcArr = JsonArray()
                for (line in newSource.lines()) srcArr.add(com.google.gson.JsonPrimitive(line))
                cell.add("source", srcArr)
                return true
            }
        }
        return false
    }

    private fun insertCell(cells: JsonArray, afterId: String?, newSource: String, cellType: String) {
        val newCell = JsonObject().apply {
            addProperty("cell_type", cellType)
            add("source", JsonArray().apply { add(com.google.gson.JsonPrimitive(newSource)) })
            add("metadata", JsonObject())
            add("outputs", JsonArray())
            addProperty("id", "cell_${System.currentTimeMillis()}")
        }

        // 重建数组
        val clone = JsonArray()
        if (afterId == null) {
            clone.add(newCell)
            for (i in 0 until cells.size()) clone.add(cells[i])
        } else {
            var done = false
            for (i in 0 until cells.size()) {
                clone.add(cells[i])
                if (!done && cells[i].asJsonObject.get("id")?.asString == afterId) {
                    clone.add(newCell)
                    done = true
                }
            }
            if (!done) clone.add(newCell)
        }
        // 先清空再追加：避免 addAll 导致数组膨胀
        for (i in cells.size() - 1 downTo 0) cells.remove(i)
        cells.addAll(clone)
    }

    /** @return true 找到并删除，false 未找到对应 cell */
    private fun deleteCell(cells: JsonArray, cellId: String): Boolean {
        val clone = JsonArray()
        for (i in 0 until cells.size()) {
            if (cells[i].asJsonObject.get("id")?.asString != cellId) {
                clone.add(cells[i])
            }
        }
        if (clone.size() == cells.size()) return false
        for (i in cells.size() - 1 downTo 0) cells.remove(i)
        cells.addAll(clone)
        return true
    }
}
