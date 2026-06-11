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

    override fun execute(params: Map<String, String>, project: Project): ToolResult {
        val notebookPath = params["notebook_path"] ?: return ToolResult.err("缺少 notebook_path 参数")
        val editMode = params["edit_mode"] ?: "replace"
        val basePath = project.basePath ?: return ToolResult.err("项目路径不可用")

        val file = if (File(notebookPath).isAbsolute) File(notebookPath) else File(basePath, notebookPath)
        // 路径穿越防护
        if (!file.canonicalPath.startsWith(File(basePath).canonicalPath)) {
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
                    replaceCell(cells, cellId, newSource)
                }
                "insert" -> {
                    val insertAfterId = params["cell_id"]
                    val newSource = params["new_source"] ?: return ToolResult.err("insert 模式需要 new_source 参数")
                    val cellType = params["cell_type"] ?: "code"
                    insertCell(cells, insertAfterId, newSource, cellType)
                }
                "delete" -> {
                    val cellId = params["cell_id"] ?: return ToolResult.err("delete 模式需要 cell_id 参数")
                    deleteCell(cells, cellId)
                }
                else -> return ToolResult.err("未知编辑模式: $editMode，支持 replace/insert/delete")
            }

            val gson = Gson()
            file.writeText(gson.toJson(nb))
            ToolResult.ok("notebook 已更新: $notebookPath (${editMode})")
        } catch (e: Exception) {
            ToolResult.err("notebook 编辑失败: ${e.message}")
        }
    }

    private fun replaceCell(cells: JsonArray, cellId: String, newSource: String): Int {
        for (i in 0 until cells.size()) {
            val cell = cells[i].asJsonObject
            if (cell.get("id")?.asString == cellId) {
                val srcArr = JsonArray()
                for (line in newSource.lines()) srcArr.add(com.google.gson.JsonPrimitive(line))
                cell.add("source", srcArr)
                return 1
            }
        }
        throw IllegalStateException("未找到 cell id: $cellId")
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
        cells.addAll(clone)
    }

    private fun deleteCell(cells: JsonArray, cellId: String) {
        val clone = JsonArray()
        for (i in 0 until cells.size()) {
            if (cells[i].asJsonObject.get("id")?.asString != cellId) {
                clone.add(cells[i])
            }
        }
        if (clone.size() == cells.size()) throw IllegalStateException("未找到 cell id: $cellId")
        cells.addAll(clone)
    }
}
