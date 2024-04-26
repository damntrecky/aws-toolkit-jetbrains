// Copyright 2024 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.codemodernizer.vcs

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
import software.aws.toolkits.jetbrains.services.codemodernizer.constants.CodeModernizerUIConstants.Companion.getLightYellowThemeBackgroundColor
import java.awt.Color
import java.awt.Font
import javax.swing.Icon

class PomDiffViewer(private val project: Project, private var virtualFile: VirtualFile) {
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

    fun showCustomEditor() {
        runInEdt {
            val isReadOnlyFile = true
            val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: throw Error("No document found")
            val editor = EditorFactory.getInstance().createEditor(document, project, virtualFile, isReadOnlyFile)
            markupModel = DocumentMarkupModel.forDocument(document, project, false)

            // We open the file for the user to see
            openVirtualFile()

            // We apply the editor changes to file
            addGutterIconToLine(editor, document, 19)
        }
    }

    private fun openVirtualFile() {
        val fileEditorManager = FileEditorManager.getInstance(project)
        val openFileDescription = OpenFileDescriptor(project, virtualFile)
        fileEditorManager.openTextEditor(openFileDescription, true)
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
                return "Amazon Q experienced an issue upgrading this dependency version. Use Amazon Q chat to upgrade the version of this dependency to a Java 17 compatible version."
            }

            override fun isNavigateAction(): Boolean {
                return false
            }

            override fun getClickAction(): AnAction = emptyAction

            override fun getPopupMenuActions(): ActionGroup? {
                return null
            }

            override fun getAlignment(): Alignment {
                return Alignment.LEFT
            }
        }

        val highlighterAttributes = TextAttributes(
            null,
            getLightYellowThemeBackgroundColor(),
            getLightYellowThemeBackgroundColor(),
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
            highlighter.errorStripeTooltip = "Amazon Q experienced an issue upgrading this dependency version. This dependency is not compatible with a Java 17 upgrade. Use Amazon Q chat to upgrade the version of this dependency to a Java 17 compatible version."
            highlighter.gutterIconRenderer = gutterIconRenderer
        }
    }
}
