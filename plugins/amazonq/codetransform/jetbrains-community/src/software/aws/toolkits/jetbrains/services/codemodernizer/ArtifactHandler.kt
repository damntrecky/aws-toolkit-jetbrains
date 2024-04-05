// Copyright 2023 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.codemodernizer

import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.patch.ApplyPatchDefaultExecutor
import com.intellij.openapi.vcs.changes.patch.ApplyPatchDifferentiatedDialog
import com.intellij.openapi.vcs.changes.patch.ApplyPatchMode
import com.intellij.openapi.vcs.changes.patch.ImportToShelfExecutor
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.writeText
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import kotlinx.coroutines.launch
import software.aws.toolkits.core.utils.debug
import software.aws.toolkits.core.utils.error
import software.aws.toolkits.core.utils.exists
import software.aws.toolkits.core.utils.getLogger
import software.aws.toolkits.core.utils.info
import software.aws.toolkits.jetbrains.core.coroutines.projectCoroutineScope
import software.aws.toolkits.jetbrains.services.codemodernizer.client.GumbyClient
import software.aws.toolkits.jetbrains.services.codemodernizer.model.CodeModernizerArtifact
import software.aws.toolkits.jetbrains.services.codemodernizer.model.JobId
import software.aws.toolkits.jetbrains.services.codemodernizer.summary.CodeModernizerSummaryVirtualFile
import software.aws.toolkits.jetbrains.utils.notifyStickyInfo
import software.aws.toolkits.jetbrains.utils.notifyStickyWarn
import software.aws.toolkits.resources.message
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

data class DownloadArtifactResult(val artifact: CodeModernizerArtifact?, val zipPath: String)
class ArtifactHandler(private val project: Project, private val clientAdaptor: GumbyClient) {
    private val telemetry = CodeTransformTelemetryManager.getInstance(project)
    private val downloadedArtifacts = mutableMapOf<JobId, Path>()
    private val downloadedSummaries = mutableMapOf<JobId, TransformationSummary>()

    private var isCurrentlyDownloading = AtomicBoolean(false)
    internal suspend fun displayDiff(job: JobId) {
        if (isCurrentlyDownloading.get()) return
        val result = downloadArtifact(job)
        if (result.artifact == null) {
            notifyUnableToApplyPatch(result.zipPath)
        } else {
            displayDiffUsingPatch(result.artifact.patch, job)
        }
    }

    private fun notifyDownloadStart() {
        notifyStickyInfo(
            message("codemodernizer.notification.info.download.started.title"),
            message("codemodernizer.notification.info.download.started.content"),
            project,
        )
    }

    suspend fun downloadArtifact(job: JobId): DownloadArtifactResult {
        isCurrentlyDownloading.set(true)
        val downloadStartTime = Instant.now()
        try {
            // 1. Attempt reusing previously downloaded artifact for job
            val previousArtifact = downloadedArtifacts.getOrDefault(job, null)
            if (previousArtifact != null && previousArtifact.exists()) {
                val zipPath = previousArtifact.toAbsolutePath().toString()
                return try {
                    val artifact = CodeModernizerArtifact.create(zipPath)
                    downloadedSummaries[job] = artifact.summary
                    DownloadArtifactResult(artifact, zipPath)
                } catch (e: RuntimeException) {
                    LOG.error { e.message.toString() }
                    DownloadArtifactResult(null, zipPath)
                }
            }

            // 2. Download the data
            notifyDownloadStart()
            LOG.info { "About to download the export result archive" }
            val downloadResultsResponse = clientAdaptor.downloadExportResultArchive(job)

            // 3. Convert to zip
            LOG.info { "Downloaded the export result archive, about to transform to zip" }
            val path = Files.createTempFile(null, ".zip")
            var totalDownloadBytes = 0
            Files.newOutputStream(path).use {
                for (bytes in downloadResultsResponse) {
                    it.write(bytes)
                    totalDownloadBytes += bytes.size
                }
            }
            LOG.info { "Successfully converted the download to a zip at ${path.toAbsolutePath()}." }
            val zipPath = path.toAbsolutePath().toString()

            // 4. Deserialize zip to CodeModernizerArtifact
            var telemetryErrorMessage: String? = null
            return try {
                val output = DownloadArtifactResult(CodeModernizerArtifact.create(zipPath), zipPath)
                downloadedArtifacts[job] = path
                output
            } catch (e: RuntimeException) {
                LOG.error { e.message.toString() }
                telemetryErrorMessage = "Unexpected error when downloading result ${e.localizedMessage}"
                DownloadArtifactResult(null, zipPath)
            } finally {
                telemetry.jobArtifactDownloadAndDeserializeTime(
                    downloadStartTime,
                    job,
                    totalDownloadBytes,
                    telemetryErrorMessage,
                )
            }
        } catch (e: Exception) {
            return DownloadArtifactResult(null, "")
        } finally {
            isCurrentlyDownloading.set(false)
        }
    }

    /**
     * Opens the built-in patch dialog to display the diff and allowing users to apply the changes locally.
     */
    internal fun displayDiffUsingPatch(patchFile: VirtualFile, jobId: JobId) {
        runInEdt {
            val dialog = ApplyPatchDifferentiatedDialog(
                project,
                ApplyPatchDefaultExecutor(project),
                listOf(ImportToShelfExecutor(project)),
                ApplyPatchMode.APPLY,
                patchFile,
                null,
                ChangeListManager.getInstance(project)
                    .addChangeList(message("codemodernizer.patch.name"), ""),
                null,
                null,
                null,
                false,
            )
            dialog.isModal = true

            telemetry.vcsDiffViewerVisible(jobId)
            if (dialog.showAndGet()) {
                telemetry.vcsViewerSubmitted(jobId)
            } else {
                telemetry.vscViewerCancelled(jobId)
            }
        }
    }

    fun notifyUnableToApplyPatch(patchPath: String) {
        LOG.error { "Unable to find patch for file: $patchPath" }
        notifyStickyWarn(
            message("codemodernizer.notification.warn.view_diff_failed.title"),
            message("codemodernizer.notification.warn.view_diff_failed.content"),
            project,
            listOf(openTroubleshootingGuideNotificationAction(TROUBLESHOOTING_URL_DOWNLOAD_DIFF)),
        )
    }

    fun notifyUnableToShowSummary() {
        LOG.error { "Unable to display summary" }
        notifyStickyWarn(
            message("codemodernizer.notification.warn.view_summary_failed.title"),
            message("codemodernizer.notification.warn.view_summary_failed.content"),
            project,
            listOf(openTroubleshootingGuideNotificationAction(TROUBLESHOOTING_URL_DOWNLOAD_DIFF)),
        )
    }

    fun displayDiffAction(jobId: JobId) = runReadAction {
        telemetry.vcsViewerClicked(jobId)
        projectCoroutineScope(project).launch {
            displayDiff(jobId)
        }
    }

    fun getSummary(job: JobId) = downloadedSummaries[job]

    fun showTransformationSummary(jobId: JobId?) {
        println("showTransformationSummary: $jobId")
        if (isCurrentlyDownloading.get()) return
        runReadAction {
            projectCoroutineScope(project).launch {
                val summary = """
                ## Code Transformation Summary By Q

                _Amazon Q made the following changes to your code. We verified the changes in Java 17.
                You can review the summary details below._

                ### Files changed

                | Files        | Action  |
                |--------------|---------|
                | `pom.xml` | Updated |

                ### Dependencies changed

                | Dependency Name    | Action | From | To |
                |--------------------|--------|-----------------|----------------|
                | `org.springframework.boot:spring-boot-starter-validation` | Added | |  |
                | `javax.validation:validation-api` | Added | | 2.0.1.Final |
                | `com.mysql:mysql-connector-j` | Added | | 8.0.33 |
                | `org.webjars:webjars-locator` | Deleted | 0.32 | |
                | `mysql:mysql-connector-java` | Deleted | 8.0.28 | |

                The final JDK 17 build succeeded with the following result:
                ```
                The Maven build was successful in compiling 10 Java source files, packaging the code into a JAR file, and running tests. No compile errors or test failures occurred. Some warnings were logged about the maven-compiler-plugin configuration missing the compiler version.
                ```

                To see the final build log, check `buildCommandOutput.log`.


                ### Next Steps
                Please review and accept the code changes using the diff viewer. If you are using a Private Repository, please ensure that updated dependencies are available.


                In order to successfully verify these changes on your machine, you will need to change your project to use Java 17. We verified the changes using [Amazon Corretto](https://aws.amazon.com/corretto) Java17 build environment.
                
                ### Related links
                1. [Open pom.xml](./pom.xml)
                2. [Open AxonScaleDemoApplication.java](src/main/java/com/demo/AxonScaleDemoApplication.java) from project root path
                3. [Open pom.xml](/Users/nardeck/Documents/workplace-tmp/gumby/axon-scale-demo-master/pom.xml) from absolute filesystem path
                """.trimIndent()
                runInEdt {
//                    CodeModernizerSummaryEditorProvider.openEditor(project, summary)
                    var basePath = project.basePath
                    if (basePath == null) {
                        LOG.debug { "Project basePath is null, not opening transformation job summary page" }
                        basePath = "/test/"
                    }
                    val virtualFile = CodeModernizerSummaryVirtualFile(basePath)
                    virtualFile.writeText(summary)
                    replaceLinksWithFullProjectPath(project, virtualFile)
//                    virtualFile.putUserData(CodeModernizerSummaryEditorProvider.MIGRATION_SUMMARY_KEY, summary.content)
                    OpenFileDescriptor(project, virtualFile).navigate(true)
                }
            }
        }
    }

    fun replaceLinksWithFullProjectPath(project: Project, virtualFile: VirtualFile) {
        var psiManger = PsiManager.getInstance(project)
        var psiDocumentManger = PsiDocumentManager.getInstance(project)
        val psiFile = psiManger.findFile(virtualFile) ?: return
        val document = psiDocumentManger.getDocument(psiFile) ?: return

        val text = document.text
        val linkPattern = Regex("\\[.*?\\]\\((.*?)\\)")
        linkPattern.findAll(text).forEach { matchResult ->
            val linkUrl = matchResult.groupValues[1]
            if (isLocalFilePath(linkUrl)) {
                val path = Paths.get(linkUrl).toAbsolutePath().toString()
                val linkedFile = VirtualFileManager.getInstance().findFileByUrl(VfsUtil.pathToUrl(path)) ?: return@forEach
                println("linkedFile $linkedFile")
                FileEditorManager.getInstance(project).openFile(linkedFile, true)
            }
        }
    }

    private fun isLocalFilePath(path: String): Boolean {
        // Add your logic to determine if the path is a local file path
        return path.startsWith("/") || path.startsWith("./") || path.startsWith("../")
    }

    companion object {
        val LOG = getLogger<ArtifactHandler>()
    }
}
