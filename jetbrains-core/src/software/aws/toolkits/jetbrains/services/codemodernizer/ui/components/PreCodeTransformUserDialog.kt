// Copyright 2023 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.codemodernizer.ui.components

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtil
import com.intellij.openapi.observable.util.whenItemSelected
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.JavaSdkVersion
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogBuilder
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toMutableProperty
import software.aws.toolkits.core.utils.warn
import software.aws.toolkits.jetbrains.services.codemodernizer.CodeModernizerManager
import software.aws.toolkits.jetbrains.services.codemodernizer.model.CustomerSelection
import software.aws.toolkits.jetbrains.services.codemodernizer.state.CodeTransformTelemetryState
import software.aws.toolkits.jetbrains.services.codemodernizer.tryGetJdk
import software.aws.toolkits.resources.message
import software.aws.toolkits.telemetry.CodetransformTelemetry
import javax.swing.JLabel
import kotlin.math.max

class PreCodeTransformUserDialog(
    val project: Project,
    val supportedBuildFilesInProject: List<VirtualFile>,
    val supportedJavaMappings: Map<JavaSdkVersion, Set<JavaSdkVersion>>,
) {

    internal data class Model(
        var focusedBuildFileIndex: Int,
        var focusedBuildFile: VirtualFile?,
        var selectedJavaModuleVersion: JavaSdkVersion?,
        var focusedJavaInputIndex: Int,
        var targetUpgradeVersion: JavaSdkVersion,
        var focusedBuildFileModule: Module?,
    )

    /**
     * Opens a dialog to user allowing them to select a migration path and details about their project / module.
     */
    fun create(): CustomerSelection? {
        lateinit var dialogPanel: DialogPanel
        lateinit var buildFileComboBox: ComboBox<String>
        lateinit var javaInputSdkComboBox: ComboBox<JavaSdkVersion>

        val buildFiles = supportedBuildFilesInProject
        val javaTransformInputSdks = supportedJavaMappings.keys.map { it }
        var focusedModuleIndex = 0
        val focusedJavaInputIndex = 0
        var chosenBuildFile = buildFiles.firstOrNull()
        val chosenFile = FileEditorManager.getInstance(project).selectedEditor?.file

        // Detect default selection for the build file
        if (chosenFile != null) {
            val focusedModule = ModuleUtil.findModuleForFile(chosenFile, project)
            val matchingBuildFileForChosenModule = buildFiles.find { ModuleUtil.findModuleForFile(it, project) == focusedModule }

            if (focusedModule != null && matchingBuildFileForChosenModule != null) {
                chosenBuildFile = matchingBuildFileForChosenModule
                focusedModuleIndex = max(0, buildFiles.indexOfFirst { it == chosenBuildFile })
            }
        }

        // Detect module for default selected file (if applicable)
        var chosenModule: Module? = null
        if (chosenBuildFile != null) {
            chosenModule = ModuleUtil.findModuleForFile(chosenBuildFile, project)
        }

        /**
         * @description Try to smart detect the Java version, if none or an unsupported version
         * we should display the supported Java list versions of 8 and 11.
         */
        fun tryToGetModuleJavaVersion(module: Module?): JavaSdkVersion? {
            return module?.tryGetJdk(project)
        }

        // Initialize model to hold form data
        val model = Model(
            focusedBuildFileIndex = focusedModuleIndex,
            focusedBuildFile = chosenBuildFile,
            focusedBuildFileModule = chosenModule,
            focusedJavaInputIndex = focusedJavaInputIndex,
            selectedJavaModuleVersion = tryToGetModuleJavaVersion(chosenModule),
            targetUpgradeVersion = JavaSdkVersion.JDK_17,
        )

        CodeModernizerManager.LOG.warn { " validatedBuildFiles in preCodeTransformUserDialog() fnc: $buildFiles" }

        val jdkVersionText = if (model.selectedJavaModuleVersion != null) "We detected Java version: " + model.selectedJavaModuleVersion else "We are unable to detect the Java version of your module"
        val jdkVersionLabel = JLabel(jdkVersionText)
        dialogPanel = panel {
            row { text(message("codemodernizer.customerselectiondialog.description.main")) }
            row { text(message("codemodernizer.customerselectiondialog.description.select")) }
            row {
                buildFileComboBox = comboBox(buildFiles.map { it.path })
                    .bind({ it.selectedIndex }, { t, v -> t.selectedIndex = v }, model::focusedBuildFileIndex.toMutableProperty())
                    .align(AlignX.FILL)
                    .columns(COLUMNS_MEDIUM)
                    .component
                buildFileComboBox.whenItemSelected {
                    dialogPanel.apply() // apply user changes to model
                    model.focusedBuildFile = buildFiles[model.focusedBuildFileIndex]
                    model.focusedBuildFileModule = ModuleUtil.findModuleForFile(buildFiles[model.focusedBuildFileIndex], project)
                    model.selectedJavaModuleVersion = tryToGetModuleJavaVersion(model.focusedBuildFileModule)
                    dialogPanel.reset() // present model changes to user
                    jdkVersionLabel.text = if (model.selectedJavaModuleVersion != null) "We detected Java version: " + model.selectedJavaModuleVersion else "We are unable to detect the Java version of your module"
                    if (model.selectedJavaModuleVersion != null) javaInputSdkComboBox.selectedItem = model.selectedJavaModuleVersion
                }
                buildFileComboBox.addActionListener {
                    CodetransformTelemetry.configurationFileSelectedChanged(
                        codeTransformSessionId = CodeTransformTelemetryState.instance.getSessionId(),
                    )
                }
            }
            row {
                cell(jdkVersionLabel)
            }
            row { text("Select a supported JDK for transformation:") }
            row {
                javaInputSdkComboBox = comboBox(javaTransformInputSdks.map { it })
                    .align(AlignX.FILL)
                    .columns(COLUMNS_MEDIUM)
                    .component
                if (model.selectedJavaModuleVersion != null) javaInputSdkComboBox.selectedItem = model.selectedJavaModuleVersion
                javaInputSdkComboBox.whenItemSelected {
                    dialogPanel.apply() // apply user changes to model
                    dialogPanel.reset() // present model changes to user
                }
            }
            row {
                this.topGap(TopGap.SMALL)
                text(message("codemodernizer.customerselectiondialog.description.after_module"))
            }
            row {
                text(message("codemodernizer.customerselectiondialog.description.after_module_part2"))
            }
        }

        val builder = DialogBuilder()
        builder.setOkOperation {
            CodetransformTelemetry.jobIsStartedFromUserPopupClick(
                codeTransformSessionId = CodeTransformTelemetryState.instance.getSessionId(),
            )
            builder.dialogWrapper.close(DialogWrapper.OK_EXIT_CODE)
        }
        builder.addOkAction().setText(message("codemodernizer.customerselectiondialog.ok_button"))
        builder.setCancelOperation {
            CodetransformTelemetry.jobIsCanceledFromUserPopupClick(
                codeTransformSessionId = CodeTransformTelemetryState.instance.getSessionId(),
            )
            builder.dialogWrapper.close(DialogWrapper.CANCEL_EXIT_CODE)
        }
        builder.addCancelAction()
        builder.setCenterPanel(dialogPanel)
        builder.setTitle(message("codemodernizer.customerselectiondialog.title"))
        if (builder.showAndGet()) {
            val targetJavaVersion = model.targetUpgradeVersion
            val sourceJavaVersion = model.selectedJavaModuleVersion
                ?: throw RuntimeException("Unable to detect source version of selected module")

            return CustomerSelection(
                model.focusedBuildFile ?: throw RuntimeException("A build file must be selected"),
                sourceJavaVersion,
                targetJavaVersion,
            )
        }
        return null
    }
}
