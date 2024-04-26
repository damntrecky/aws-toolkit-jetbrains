// Copyright 2024 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.codemodernizer.file

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.MarkupModel
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import software.aws.toolkits.jetbrains.services.codemodernizer.utils.findLineNumber
import java.awt.Color
import java.awt.Font
import javax.swing.Icon

class PomFileAnnotator(private val project: Project, private val virtualFileRef: VirtualFile) {
    private lateinit var markupModel: MarkupModel

    val emptyAction: AnAction = object : AnAction() {
        override fun actionPerformed(e: AnActionEvent) {
            // No action performed
            return
        }

        override fun update(e: AnActionEvent) {
            // No update logic
            return
        }
    }

    fun showCustomEditor(currentVersion: String) {
        runInEdt {
            val isReadOnlyFile = true
            val document = FileDocumentManager.getInstance().getDocument(virtualFileRef) ?: throw Error("No document found")
            val editor = EditorFactory.getInstance().createEditor(document, project, virtualFileRef, isReadOnlyFile)
            markupModel = DocumentMarkupModel.forDocument(document, project, false)

            // We open the file for the user to see
            openVirtualFile()

            val lineNumberToHighlight = findLineNumber(virtualFileRef, "<version>${currentVersion}</version>")

            if (lineNumberToHighlight != null) {
                // We apply the editor changes to file
                highlightRange(editor, document, lineNumberToHighlight)
                addGutterIconToLine(editor, document, lineNumberToHighlight + 2)
//            addGutterIconToLine(editor, document, 12)
            }
        }
    }

    private fun openVirtualFile() {
        val fileEditorManager = FileEditorManager.getInstance(project)
        val openFileDescription = OpenFileDescriptor(project, virtualFileRef)
        fileEditorManager.openTextEditor(openFileDescription, true)
    }

    fun highlightRange(editor: Editor, document: Document, lineNumberToHighlight: Int) {
        val highlighterAttributes = TextAttributes(
            JBColor(Color.RED, Color.RED),
            JBColor(Color.GREEN, Color.GREEN),
            JBColor(Color.YELLOW, Color.YELLOW),
            EffectType.SLIGHTLY_WIDER_BOX,
            Font.BOLD
        )

        val startOffset = document.getLineStartOffset(lineNumberToHighlight - 1)
        val endOffset = document.getLineEndOffset(lineNumberToHighlight + 2)
        markupModel?.apply {
            val highlighter = addRangeHighlighter(
                startOffset,
                endOffset,
                1, // like z-index
                highlighterAttributes,
                HighlighterTargetArea.LINES_IN_RANGE
            )
            // Optionally, you can customize the range highlighter further
            highlighter.errorStripeMarkColor = JBColor(JBColor.RED, Color.RED)
//        highlighter.setLineMarkerRenderer()
            highlighter.errorStripeTooltip = "This is a test tooltip displaying more information about the highlighted range"
        }
    }

    fun highlightLine(editor: Editor, document: Document, lineNumberToHighlight: Int) {
        if (lineNumberToHighlight < 0 || lineNumberToHighlight >= document.lineCount) {
            println("Error in highlightLine(): lineNumberToHighlight not in document range $lineNumberToHighlight > ${document.lineCount}")
            return
        }
        val highlighterAttributes = TextAttributes(
            JBColor(JBColor.RED, Color.RED),
            JBColor(JBColor.GREEN, Color.GREEN),
            JBColor(JBColor.YELLOW, Color.YELLOW),
            EffectType.LINE_UNDERSCORE,
            Font.BOLD
        )

        markupModel?.apply {
            val highlighter = addLineHighlighter(
                lineNumberToHighlight - 1,
                HighlighterLayer.ERROR, // like z-index,
                highlighterAttributes
            )
            highlighter.errorStripeMarkColor = JBColor(JBColor.RED, Color.RED)
            highlighter.errorStripeTooltip = "This tooltip does not work for single lines for some reason... Only if you define highlighter.errorStripeMarkColor"
        }
    }

    fun addGutterIconToLine(editor: Editor, document: Document, lineNumberToHighlight: Int) {
        val gutterIconRenderer = object : GutterIconRenderer() {
            override fun equals(other: Any?): Boolean {
                return true
            }

            override fun hashCode(): Int {
                return javaClass.hashCode()
            }

            override fun getIcon(): Icon {
                val scaledIcon = AllIcons.General.BalloonWarning
                return scaledIcon
            }

            override fun getTooltipText(): String {
                return "There is an issue with your pom.xml file at this line that needs input."
            }

            override fun isNavigateAction(): Boolean {
                return true
            }

            override fun getClickAction(): AnAction = emptyAction

            override fun getPopupMenuActions(): ActionGroup? {
                return null
            }

            override fun getAlignment(): Alignment {
                return Alignment.RIGHT
            }
        }

        val highlighterAttributes = TextAttributes(
            null,
            JBColor(JBColor.RED, Color.RED),
            JBColor(JBColor.RED, Color.RED),
            EffectType.STRIKEOUT,
            Font.BOLD
        )

        // Define your action availability hint

        val startOffset = document.getLineStartOffset(lineNumberToHighlight - 1)
        val endOffset = document.getLineEndOffset(lineNumberToHighlight - 1)

        markupModel?.apply {
            val highlighter = addRangeHighlighter(
                startOffset,
                endOffset,
                HighlighterLayer.SYNTAX, // like z-index
                highlighterAttributes,
                HighlighterTargetArea.EXACT_RANGE
            )

            // Optionally, you can customize the range highlighter further
            highlighter.errorStripeMarkColor = JBColor(JBColor.RED, Color.RED)
            highlighter.errorStripeTooltip = "Your Java Version needs attention and human input"
            highlighter.gutterIconRenderer = gutterIconRenderer
        }
    }
}

