package com.aiassistant.ui

import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil

// ponytail: monitors editor selection → updates ChatViewModel's selectionRef

class EditorSelectionListener(
    private val project: Project,
    private val onSelectionChanged: (filePath: String, startLine: Int, endLine: Int, content: String) -> Unit,
    private val onSelectionCleared: () -> Unit = {}
) {
    private var lastSelection: Triple<String, IntRange, String>? = null

    init {
        EditorFactory.getInstance().eventMulticaster.addSelectionListener(object :
            SelectionListener {
            override fun selectionChanged(e: SelectionEvent) {
                val editor = e.editor
                val file = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
                    .getFile(editor.document) ?: return
                val selection = editor.selectionModel
                if (!selection.hasSelection()) {
                    lastSelection = null
                    onSelectionCleared()
                    return
                }
                val startLine = editor.document.getLineNumber(selection.selectionStart) + 1
                val endLine = editor.document.getLineNumber(selection.selectionEnd) + 1
                val content = selection.selectedText ?: return
                val path = VfsUtil.getRelativePath(file, project.baseDir) ?: file.presentableName
                val key = Triple(path, startLine..endLine, content)
                if (key != lastSelection) {
                    lastSelection = key
                    onSelectionChanged(path, startLine, endLine, content)
                }
            }
        })
    }
}
