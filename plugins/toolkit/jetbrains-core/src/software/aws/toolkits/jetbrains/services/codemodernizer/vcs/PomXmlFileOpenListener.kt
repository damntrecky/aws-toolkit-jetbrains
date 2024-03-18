// Copyright 2024 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.codemodernizer.vcs

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.utils.vfs.getPsiFile

class PomXmlFileOpenListener : FileEditorManagerListener {
    private val pomXmlInspection = XmlInspector()
    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        if (file.name == "pom.xml" || file.name == "pom1.xml" || file.name == "pom2.xml") {
            val inspectionManager = InspectionManager.getInstance(source.project)
            val psiFile = PsiManager.getInstance(source.project).findFile(file)
            val holder = ProblemsHolder(inspectionManager, file.getPsiFile(source.project), false)
            val visitor = pomXmlInspection.buildVisitor(holder, isOnTheFly = false)
            if (psiFile != null) visitor.visitFile(psiFile)
        }
    }

    override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
        // todo
    }

    override fun selectionChanged(event: FileEditorManagerEvent) {
        // todo
    }
}
