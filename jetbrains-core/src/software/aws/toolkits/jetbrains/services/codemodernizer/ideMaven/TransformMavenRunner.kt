// Copyright 2023 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.codemodernizer.ideMaven

import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import org.jetbrains.idea.maven.execution.MavenRunConfigurationType
import org.jetbrains.idea.maven.execution.MavenRunnerParameters
import org.jetbrains.idea.maven.execution.MavenRunnerSettings

class TransformMavenRunner(val project: Project) {

    fun run(parameters: MavenRunnerParameters, settings: MavenRunnerSettings, onComplete: TransformRunnable) {
        FileDocumentManager.getInstance().saveAllDocuments()
        val callback = ProgramRunner.Callback { descriptor: RunContentDescriptor ->
            val handler = descriptor.processHandler
            if (handler == null) {
                // add log error here
                onComplete.exitCode(-1)
                return@Callback
            }
            handler.addProcessListener(object : ProcessAdapter() {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    println {"------ NARDECK TEST --------"}
                    println { handler.getUserData(outputType) }
                    println { "------- EVENT TEXT -------" }
                    println(event.text) // log output
                }
                override fun processTerminated(event: ProcessEvent) {
                    onComplete.exitCode(event.exitCode)
                }
            })
        }
        MavenRunConfigurationType.runConfiguration(project, parameters, null, settings, callback, false)
    }
}
