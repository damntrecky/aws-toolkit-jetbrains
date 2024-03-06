// Copyright 2024 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.codemodernizer.vcs

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Font
import java.awt.Graphics
import java.awt.Rectangle
import java.io.File
import javax.swing.Icon

class PomDiffViewer(private val project: Project) {
    private lateinit var diffRequest: SimpleDiffRequest
    private lateinit var virtualFile1: VirtualFile
    private lateinit var virtualFile2: VirtualFile

    fun createDiffView(file1Path: String, file2Path: String) {
        val localFileSystem = LocalFileSystem.getInstance()
        val file1: VirtualFile? = localFileSystem.findFileByIoFile(File(file1Path))
        val file2: VirtualFile? = localFileSystem.findFileByIoFile(File(file2Path))

        if (file1 != null && file2 != null) {
            virtualFile1 = file1
            virtualFile2 = file2
            val contentFactory = DiffContentFactory.getInstance()
            val diffContent1 = contentFactory.create(project, file1)
            val diffContent2 = contentFactory.create(project, file2)

            diffRequest = SimpleDiffRequest(
                "Pom.xml Upgrade Diff",
                diffContent1,
                diffContent2,
                file1.path,
                file2.path
            )
        } else {
            println("Error in createDiffView(): One or both files not found.")
        }
    }

    fun showDiff() {
        try {
            val diffManager = DiffManager.getInstance()
             diffManager.showDiff(project, diffRequest)
        } catch(error: Error) {
            println("Error in showDiff(): Could now show diff view ${error}")
        }
    }

    fun createCustomEditor() {
        val document = FileDocumentManager.getInstance().getDocument(virtualFile2) ?: throw Error("No document found")
        openVirtualFile(virtualFile2)
        val editor = EditorFactory.getInstance().createEditor(document, project, virtualFile2, false)
        // Highlight line #5 in green
        highlightRange(editor,document, 6)
    }

    fun openVirtualFile(virtualFile: VirtualFile) {
        val fileEditorManager = FileEditorManager.getInstance(project)
        val openFileDescription = OpenFileDescriptor(project, virtualFile)
        fileEditorManager.openTextEditor(openFileDescription, true)
    }

    fun highlightRange(editor: Editor, document: Document, lineNumberToHighlight: Int) {
        val highlighterAttributes = TextAttributes(
            JBColor(Color.RED, Color.RED),
            JBColor(Color.GREEN, Color.GREEN),
            JBColor(Color.YELLOW, Color.YELLOW),
            EffectType.LINE_UNDERSCORE,
            Font.BOLD
        )

        val startOffset = document.getLineStartOffset(lineNumberToHighlight)
        val endOffset = document.getLineEndOffset(lineNumberToHighlight + 2)

        val highlighter = editor.markupModel.addRangeHighlighter(
            startOffset,
            endOffset,
            HighlighterLayer.SYNTAX,
            highlighterAttributes,
            HighlighterTargetArea.EXACT_RANGE
        )


        // Optionally, you can customize the range highlighter further
        highlighter.errorStripeMarkColor = JBColor(Color.GREEN, Color.GREEN)
        highlighter.errorStripeTooltip = "This is a highlighted range"
    }

    fun highlightLine(editor: Editor, document: Document, lineNumberToHighlight: Int) {
        val highlighterAttributes = TextAttributes(
            JBColor(Color.RED, Color.RED),
            JBColor(Color.GREEN, Color.GREEN),
            JBColor(Color.YELLOW, Color.YELLOW),
            EffectType.LINE_UNDERSCORE,
            Font.BOLD
        )

        val startOffset = document.getLineStartOffset(lineNumberToHighlight)

        val highlighter = editor.markupModel.addLineHighlighter(
            startOffset,
            highlighterAttributes
        )
    }

}
