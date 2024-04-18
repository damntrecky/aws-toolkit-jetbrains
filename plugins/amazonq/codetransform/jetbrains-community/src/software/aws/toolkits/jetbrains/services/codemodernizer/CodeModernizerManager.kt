// Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.aws.toolkits.jetbrains.services.codemodernizer

import com.intellij.notification.NotificationAction
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.JavaSdkVersion
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import software.amazon.awssdk.services.codewhispererruntime.model.TransformationJob
import software.amazon.awssdk.services.codewhispererruntime.model.TransformationPlan
import software.amazon.awssdk.services.codewhispererruntime.model.TransformationStatus
import software.aws.toolkits.core.utils.error
import software.aws.toolkits.core.utils.getLogger
import software.aws.toolkits.core.utils.info
import software.aws.toolkits.core.utils.warn
import software.aws.toolkits.jetbrains.core.coroutines.projectCoroutineScope
import software.aws.toolkits.jetbrains.core.explorer.refreshCwQTree
import software.aws.toolkits.jetbrains.services.codemodernizer.client.GumbyClient
import software.aws.toolkits.jetbrains.services.codemodernizer.commands.CodeTransformMessageListener
import software.aws.toolkits.jetbrains.services.codemodernizer.model.CodeModernizerException
import software.aws.toolkits.jetbrains.services.codemodernizer.model.CodeModernizerJobCompletedResult
import software.aws.toolkits.jetbrains.services.codemodernizer.model.CodeModernizerSessionContext
import software.aws.toolkits.jetbrains.services.codemodernizer.model.CodeModernizerStartJobResult
import software.aws.toolkits.jetbrains.services.codemodernizer.model.CustomerSelection
import software.aws.toolkits.jetbrains.services.codemodernizer.model.InvalidTelemetryReason
import software.aws.toolkits.jetbrains.services.codemodernizer.model.JobId
import software.aws.toolkits.jetbrains.services.codemodernizer.model.MAVEN_CONFIGURATION_FILE_NAME
import software.aws.toolkits.jetbrains.services.codemodernizer.model.MavenCopyCommandsResult
import software.aws.toolkits.jetbrains.services.codemodernizer.model.ValidationResult
import software.aws.toolkits.jetbrains.services.codemodernizer.model.api.getArtifactIdentifiers
import software.aws.toolkits.jetbrains.services.codemodernizer.panels.managers.CodeModernizerBottomWindowPanelManager
import software.aws.toolkits.jetbrains.services.codemodernizer.state.CodeModernizerSessionState
import software.aws.toolkits.jetbrains.services.codemodernizer.state.CodeModernizerState
import software.aws.toolkits.jetbrains.services.codemodernizer.state.StateFlags
import software.aws.toolkits.jetbrains.services.codemodernizer.state.buildState
import software.aws.toolkits.jetbrains.services.codemodernizer.toolwindow.CodeModernizerBottomToolWindowFactory
import software.aws.toolkits.jetbrains.services.codemodernizer.utils.STATES_WHERE_PLAN_EXIST
import software.aws.toolkits.jetbrains.services.codemodernizer.utils.TROUBLESHOOTING_URL_MAVEN_COMMANDS
import software.aws.toolkits.jetbrains.services.codemodernizer.utils.TROUBLESHOOTING_URL_PREREQUISITES
import software.aws.toolkits.jetbrains.services.codemodernizer.utils.createPomCopy
import software.aws.toolkits.jetbrains.services.codemodernizer.utils.downloadResultArchive
import software.aws.toolkits.jetbrains.services.codemodernizer.utils.getJsonValuesFromManifestFile
import software.aws.toolkits.jetbrains.services.codemodernizer.utils.getModuleOrProjectNameForFile
import software.aws.toolkits.jetbrains.services.codemodernizer.utils.getSupportedBuildFilesWithSupportedJdk
import software.aws.toolkits.jetbrains.services.codemodernizer.utils.getSupportedJavaMappings
import software.aws.toolkits.jetbrains.services.codemodernizer.utils.getSupportedModules
import software.aws.toolkits.jetbrains.services.codemodernizer.utils.getTransformationStepsFixture
import software.aws.toolkits.jetbrains.services.codemodernizer.utils.isCodeTransformAvailable
import software.aws.toolkits.jetbrains.services.codemodernizer.utils.isGradleProject
import software.aws.toolkits.jetbrains.services.codemodernizer.utils.openTroubleshootingGuideNotificationAction
import software.aws.toolkits.jetbrains.services.codemodernizer.utils.parseXmlDependenciesReport
import software.aws.toolkits.jetbrains.services.codemodernizer.utils.replacePomVersion
import software.aws.toolkits.jetbrains.services.codemodernizer.utils.tryGetJdk
import software.aws.toolkits.jetbrains.ui.feedback.CodeTransformFeedbackDialog
import software.aws.toolkits.jetbrains.utils.isRunningOnRemoteBackend
import software.aws.toolkits.jetbrains.utils.notifyStickyError
import software.aws.toolkits.jetbrains.utils.notifyStickyInfo
import software.aws.toolkits.resources.message
import software.aws.toolkits.telemetry.CodeTransformCancelSrcComponents
import software.aws.toolkits.telemetry.CodeTransformPreValidationError
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

const val AMAZON_Q_FEEDBACK_DIALOG_KEY = "Amazon Q"

@State(name = "codemodernizerStates", storages = [Storage("aws.xml", roamingType = RoamingType.PER_OS)])
class CodeModernizerManager(private val project: Project) : PersistentStateComponent<CodeModernizerState>, Disposable {
    private val telemetry = CodeTransformTelemetryManager.getInstance(project)
    private var managerState = CodeModernizerState()
    val codeModernizerBottomWindowPanelManager by lazy { CodeModernizerBottomWindowPanelManager(project) }
    private val codeModernizerBottomWindowPanelContent by lazy {
        val contentManager = getBottomToolWindow().contentManager
        contentManager.removeAllContents(true)
        contentManager.factory.createContent(
            codeModernizerBottomWindowPanelManager,
            message("codemodernizer.toolwindow.scan_display"),
            false,
        ).also {
            Disposer.register(contentManager, it)
        }
    }
    private val supportedBuildFileNames = listOf(MAVEN_CONFIGURATION_FILE_NAME)
    private val isModernizationInProgress = AtomicBoolean(false)
    private val isResumingJob = AtomicBoolean(false)
    private val isMvnRunning = AtomicBoolean(false)
    private val isJobSuccessfullyResumed = AtomicBoolean(false)

    private val transformationStoppedByUsr = AtomicBoolean(false)
    private var codeTransformationSession: CodeModernizerSession? = null
        set(session) {
            if (session != null) {
                Disposer.register(this, session)
            }
            field = session
        }
    private val artifactHandler = ArtifactHandler(project, GumbyClient.getInstance(project))
    private val supportedJavaMappings = mapOf(
        JavaSdkVersion.JDK_1_8 to setOf(JavaSdkVersion.JDK_17),
        JavaSdkVersion.JDK_11 to setOf(JavaSdkVersion.JDK_17),
    )
    init {
        CodeModernizerSessionState.getInstance(project).setDefaults()
    }

    fun validate(project: Project): ValidationResult {
        fun validateCore(project: Project): ValidationResult {
            if (isRunningOnRemoteBackend()) {
                return ValidationResult(
                    false,
                    message("codemodernizer.notification.warn.invalid_project.description.reason.remote_backend"),
                    InvalidTelemetryReason(
                        CodeTransformPreValidationError.RemoteRunProject
                    )
                )
            }
            if (!isCodeTransformAvailable(project)) {
                return ValidationResult(
                    false,
                    message("codemodernizer.notification.warn.invalid_project.description.reason.not_logged_in"),
                    InvalidTelemetryReason(
                        CodeTransformPreValidationError.NonSsoLogin
                    )
                )
            }

            if (ProjectRootManager.getInstance(project).contentRoots.isEmpty()) {
                return ValidationResult(
                    false,
                    message("codemodernizer.notification.warn.invalid_project.description.reason.missing_content_roots"),
                    InvalidTelemetryReason(
                        CodeTransformPreValidationError.NoPom
                    )
                )
            }
            val supportedModules = project.getSupportedModules(supportedJavaMappings).toSet()
            val validProjectJdk = project.getSupportedJavaMappings(supportedJavaMappings).isNotEmpty()
            val projectJdk = project.tryGetJdk()
            if (supportedModules.isEmpty() && !validProjectJdk) {
                return ValidationResult(
                    false,
                    message(
                        "codemodernizer.notification.warn.invalid_project.description.reason.invalid_jdk_versions",
                        supportedJavaMappings.keys.joinToString()
                    ),
                    InvalidTelemetryReason(
                        CodeTransformPreValidationError.UnsupportedJavaVersion,
                        projectJdk?.toString().orEmpty()
                    )
                )
            }
            val validatedBuildFiles = project.getSupportedBuildFilesWithSupportedJdk(supportedBuildFileNames, supportedJavaMappings)
            return if (validatedBuildFiles.isNotEmpty()) {
                ValidationResult(true, validatedBuildFiles = validatedBuildFiles, validatedProjectJdkName = projectJdk?.description.orEmpty())
            } else {
                ValidationResult(
                    false,
                    message("codemodernizer.notification.warn.invalid_project.description.reason.no_valid_files", supportedBuildFileNames.joinToString()),
                    InvalidTelemetryReason(
                        CodeTransformPreValidationError.NonMavenProject,
                        if (isGradleProject(project)) "Gradle build" else "other build"
                    )
                )
            }
        }

        val result = validateCore(project)
        telemetry.sendValidationResult(result)

        return result
    }

    /**
     * The initial landing UI for the results view panel.
     * This method adds code content to the problems view if not already added.
     * When [setSelected] is true, code scan panel is set to be in focus.
     */
    fun addCodeModernizeUI(setSelected: Boolean = false, moduleOrProjectNameForFile: String? = null) = runInEdt {
        val appModernizerBottomWindow = getBottomToolWindow()
        if (!appModernizerBottomWindow.contentManager.contents.contains(codeModernizerBottomWindowPanelContent)) {
            appModernizerBottomWindow.contentManager.addContent(codeModernizerBottomWindowPanelContent)
        }
        codeModernizerBottomWindowPanelContent.displayName = message("codemodernizer.toolwindow.scan_display")
        if (moduleOrProjectNameForFile == null) {
            appModernizerBottomWindow.stripeTitle = message("codemodernizer.toolwindow.label_no_job")
        } else {
            appModernizerBottomWindow.stripeTitle = message("codemodernizer.toolwindow.label", moduleOrProjectNameForFile)
        }

        if (setSelected) {
            appModernizerBottomWindow.contentManager.setSelectedContent(codeModernizerBottomWindowPanelContent)
            appModernizerBottomWindow.show()
        }
    }

    /**
     * @description Main function for triggering the start of elastic gumby migration.
     */
    fun initModernizationJobUI(shouldOpenBottomWindowOnStart: Boolean = true, moduleOrProjectNameForFile: String) {
        isModernizationInProgress.set(true)
        // Refresh CodeWhisperer Explorer tree node to reflect scan in progress.
        project.refreshCwQTree()
        // Initialize the bottom toolkit window with content
        addCodeModernizeUI(shouldOpenBottomWindowOnStart, moduleOrProjectNameForFile)
        codeModernizerBottomWindowPanelManager.setJobStartingUI()
    }

    fun completeHumanInTheLoopWork(jobId: JobId, userInputRetryCount: Number) {
        LOG.warn { "Entering completeHumanInTheLoopWork $jobId $userInputRetryCount" }

        val pomReplacementDelimiter: String = "*****"
//        val localPathToXmlDependencyList = "/target/dependency-updates-aggregate-report.xml"

        val osTmpDir = Paths.get(System.getProperty("java.io.tmpdir"))
        val tmpDependencyListFolderName = "q-pom-dependency-list"
        val userDependencyUpdateFolderName = "q-pom-dependency-update"
        val tmpDependencyListDir: Path = osTmpDir.resolve(tmpDependencyListFolderName)
        val userDependencyUpdateDir: Path = osTmpDir.resolve(userDependencyUpdateFolderName)

        try {
            // 1) We need to call GetTransformationPlan to get artifactId
            val transformationSteps = getTransformationStepsFixture(jobId)
            val hilTransformationStep = (transformationSteps) ?: throw Exception("No HIL Transformation Step found")

            // TODO
            // Actual start from here
            val downloadArtifact = getArtifactIdentifiers(hilTransformationStep.last()) ?: throw Exception("artifactId or artifactType is undefined")

            // 2) We need to call DownloadResultArchive to get the manifest and pom.xml
            val downloadResultsFileReferences = downloadResultArchive(
                jobId,
                downloadArtifact
            )
            val manifestDownloadedVirtualFile = downloadResultsFileReferences[0]
            val pomDownloadedVirtualFile = downloadResultsFileReferences[1]

            if (manifestDownloadedVirtualFile == null || pomDownloadedVirtualFile == null) {
                throw Exception("Issue downloading HIl artifacts")
            }

            val manifestFileValues = getJsonValuesFromManifestFile(manifestDownloadedVirtualFile)

            // 3) We need to replace version in pom.xml
            val newPomFileVirtualFileReference = createPomCopy(
                tmpDependencyListDir.toString(),
                pomDownloadedVirtualFile,
                "pom.xml"
            )

            if (newPomFileVirtualFileReference == null) {
                throw Exception("Issue writing POM file to local filesystem")
            }

            manifestFileValues.sourcePomVersion?.let {
                replacePomVersion(
                    newPomFileVirtualFileReference,
                    it,
                    pomReplacementDelimiter
                )
            }
//            await highlightPomIssueInProject(newPomFileVirtualFileReference, manifestFileValues.sourcePomVersion)

            // 4) We need to run maven commands on that pom.xml to get available versions
//            const compileFolderInfo: FolderInfo = {
//                    name: tmpDependencyListFolderName,
//                    path: tmpDependencyListDir,
//            }
//            runMavenDependencyUpdateCommands(compileFolderInfo)
            val dependencyList = parseXmlDependenciesReport(
                "path.join(tmpDependencyListDir, localPathToXmlDependencyList)"
            )

            // 5) We need to wait for user input
            // transformByQState.getChatControllers()?.humanInTheLoopIntervention.fire({
            //     latestVersion,
            //     tabID: ChatSessionManager.Instance.getSession().tabID,
            // })
            val getUserInputValue = dependencyList[0]

            // 6) We need to add user input to that pom.xml,
            // original pom.xml is intact somewhere, and run maven compile
            val userInputPomFileVirtualFileReference = createPomCopy(
                userDependencyUpdateDir.toString(),
                pomDownloadedVirtualFile,
                "pom.xml"
            )

            if (userInputPomFileVirtualFileReference == null) {
                throw Exception("Issue writing POM file to local filesystem")
            }

            replacePomVersion(
                userInputPomFileVirtualFileReference,
                getUserInputValue,
                pomReplacementDelimiter
            )

            // 7) We need to take that output of maven and use CreateUploadUrl
//            const uploadFolderInfo: FolderInfo = {
//                    name: userDependencyUpdateFolderName,
//                    path: userDependencyUpdateDir,
//            }
//            runMavenDependencyBuildCommands(uploadFolderInfo)
            // TODO modify code to be re-usable with current framework here
            // TODO Update manifest.json file for upload
//            const uploadPayloadFilePath = await zipCode(uploadFolderInfo)
//            const uploadId = await uploadPayload(uploadPayloadFilePath)
            LOG.warn { "Finished human in the loop work" }
        } catch (err: Exception) {
            // Will probably emit different TYPES of errors from the Human in the loop engagement
            // catch them here and determine what to do with in parent function
            LOG.warn { "Error in completeHumanInTheLoopWork $err" }
        } finally {
            // 1) TODO Always delete items off disk manifest.json and pom.xml

            // Always delete the dependency output
//            console.log('Deleting temporary dependency output', tmpDependencyListDir)
//            fs.rmdirSync(tmpDependencyListDir, { recursive: true })
//            console.log('Deleting temporary dependency output', userDependencyUpdateDir)
//            fs.rmdirSync(userDependencyUpdateDir, { recursive: true })
        }
    }

    fun stopModernize() {
        if (isModernizationJobActive()) {
            userInitiatedStopCodeModernization()
            telemetry.jobIsCancelledByUser(CodeTransformCancelSrcComponents.DevToolsStopButton)
        }
    }

    fun runModernize(copyResult: MavenCopyCommandsResult) {
        initStopParameters()
        val session = codeTransformationSession as CodeModernizerSession
        /*
        val fakeJobId = JobId("test-id")
        completeHumanInTheLoopWork(fakeJobId, 0)
        val test = true
        if (test) {
            return
        }
         */
        initModernizationJobUI(true, project.getModuleOrProjectNameForFile(session.sessionContext.configurationFile))
        launchModernizationJob(session, copyResult)
    }

    /*
    fun resumeTransformation(jobId: String) {
        initStopParameters()
        val session = codeTransformationSession as CodeModernizerSession
        /*
        val fakeJobId = JobId("test-id")
        completeHumanInTheLoopWork(fakeJobId, 0)
        val test = true
        if (test) {
            return
        }
     */
        initModernizationJobUI(true, project.getModuleOrProjectNameForFile(session.sessionContext.configurationFile))
        launchModernizationJob(session, copyResult)
    }
     */

    private fun initStopParameters() {
        transformationStoppedByUsr.set(false)
        CodeModernizerSessionState.getInstance(project).currentJobStatus = TransformationStatus.UNKNOWN_TO_SDK_VERSION
        CodeModernizerSessionState.getInstance(project).currentJobCreationTime = Instant.MIN
        CodeModernizerSessionState.getInstance(project).currentJobStopTime = Instant.MIN
    }

    private fun notifyJobFailure(failureReason: String?, retryable: Boolean) {
        val reason = failureReason ?: message("codemodernizer.notification.info.modernize_failed.unknown_failure_reason") // should not happen
        val retryablestring = if (retryable) message("codemodernizer.notification.info.modernize_failed.failure_is_retryable") else ""
        notifyStickyInfo(
            message("codemodernizer.notification.info.modernize_failed.title"),
            message("codemodernizer.notification.info.modernize_failed.description", reason, retryablestring),
            project,
            listOf(displayFeedbackNotificationAction())
        )
    }

    internal fun notifyTransformationStopped() {
        notifyStickyInfo(
            message("codemodernizer.notification.info.transformation_stop.title"),
            message("codemodernizer.notification.info.transformation_stop.content"),
            project,
            listOf(displayFeedbackNotificationAction())
        )
    }

    internal fun notifyUnableToResumeJob() {
        notifyStickyInfo(
            message("codemodernizer.notification.info.transformation_resume.title"),
            message("codemodernizer.notification.info.transformation_resume.content"),
            project,
            listOf(displayFeedbackNotificationAction())
        )
    }

    internal fun notifyTransformationStartStopping() {
        notifyStickyInfo(
            message("codemodernizer.notification.info.transformation_start_stopping.title"),
            message("codemodernizer.notification.info.transformation_start_stopping.content"),
            project,
        )
    }

    internal fun notifyTransformationFailedToStop() {
        notifyStickyError(
            message("codemodernizer.notification.info.transformation_start_stopping.failed_title"),
            message("codemodernizer.notification.info.transformation_start_stopping.failed_content"),
            project,
            listOf(displayFeedbackNotificationAction())
        )
    }

    fun launchModernizationJob(session: CodeModernizerSession, copyResult: MavenCopyCommandsResult) = projectCoroutineScope(project).launch {
        val result = initModernizationJob(session, copyResult)

        postModernizationJob(result)
    }

    fun resumeJob(session: CodeModernizerSession, lastJobId: JobId, currentJobResult: TransformationJob) = projectCoroutineScope(project).launch {
        if (isModernizationJobActive()) {
            runInEdt { getBottomToolWindow().show() }
            return@launch
        }
        try {
            val plan = if (currentJobResult.status() in STATES_WHERE_PLAN_EXIST) {
                try {
                    delay(1000)
                    session.fetchPlan(lastJobId).transformationPlan()
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
            CodeModernizerSessionState.getInstance(project).currentJobCreationTime = currentJobResult.creationTime()
            codeTransformationSession = session
            initModernizationJobUI(false, project.getModuleOrProjectNameForFile(session.sessionContext.configurationFile))
            codeModernizerBottomWindowPanelManager.setResumeJobUI(currentJobResult, plan, session.sessionContext.sourceJavaVersion)
            session.resumeJob(currentJobResult.creationTime(), lastJobId)
            val result = handleJobStarted(lastJobId, session)
            postModernizationJob(result)
        } catch (e: Exception) {
            notifyUnableToResumeJob()
            LOG.error(e) { e.message.toString() }
            return@launch
        }
    }

    fun setJobOngoing(jobId: JobId, sessionContext: CodeModernizerSessionContext) {
        isModernizationInProgress.set(true)
        managerState = buildState(sessionContext, true, jobId)
    }

    fun setJobNotOngoing() {
        isModernizationInProgress.set(false)
        managerState.flags[StateFlags.IS_ONGOING] = false
    }

    fun handleLocalMavenBuildResult(mavenCopyCommandsResult: MavenCopyCommandsResult) {
        codeTransformationSession?.setLastMvnBuildResult(mavenCopyCommandsResult)
        // Send IDE notifications first
        if (mavenCopyCommandsResult == MavenCopyCommandsResult.Failure) {
            notifyStickyInfo(
                message("codemodernizer.notification.warn.maven_failed.title"),
                message("codemodernizer.notification.warn.maven_failed.content"),
                project,
                listOf(openTroubleshootingGuideNotificationAction(TROUBLESHOOTING_URL_MAVEN_COMMANDS), displayFeedbackNotificationAction()),
            )
        }

        CodeTransformMessageListener.instance.onMavenBuildResult(mavenCopyCommandsResult)
    }

    fun runLocalMavenBuild(project: Project, customerSelection: CustomerSelection) {
        telemetry.jobStartedCompleteFromPopupDialog(customerSelection)

        // Create and set a session
        codeTransformationSession = null
        val session = createCodeModernizerSession(customerSelection, project)
        codeTransformationSession = session

        projectCoroutineScope(project).launch {
            isMvnRunning.set(true)
            val result = session.getDependenciesUsingMaven()
            isMvnRunning.set(false)
            handleLocalMavenBuildResult(result)
        }
    }

    internal suspend fun initModernizationJob(session: CodeModernizerSession, copyResult: MavenCopyCommandsResult): CodeModernizerJobCompletedResult {
        return when (val result = session.createModernizationJob(copyResult)) {
            is CodeModernizerStartJobResult.ZipCreationFailed -> {
                CodeModernizerJobCompletedResult.UnableToCreateJob(
                    message("codemodernizer.notification.warn.zip_creation_failed", result.reason),
                    false,
                )
            }

            is CodeModernizerStartJobResult.UnableToStartJob -> {
                CodeModernizerJobCompletedResult.UnableToCreateJob(
                    message("codemodernizer.notification.warn.unable_to_start_job", result.exception),
                    true,
                )
            }

            is CodeModernizerStartJobResult.Started -> {
                handleJobStarted(result.jobId, session)
            }

            is CodeModernizerStartJobResult.Disposed -> {
                CodeModernizerJobCompletedResult.ManagerDisposed
            }

            is CodeModernizerStartJobResult.Cancelled -> {
                CodeModernizerJobCompletedResult.JobAbortedBeforeStarting
            }

            is CodeModernizerStartJobResult.CancelledMissingDependencies -> {
                CodeModernizerJobCompletedResult.JobAbortedMissingDependencies
            }

            is CodeModernizerStartJobResult.CancelledZipTooLarge -> {
                CodeModernizerJobCompletedResult.JobAbortedZipTooLarge
            }
        }
    }

    // TODO handleJobResumed
    // Update codeModernizerBottomWindowPanelManager with resumed UI

    suspend fun handleJobStarted(jobId: JobId, session: CodeModernizerSession): CodeModernizerJobCompletedResult {
        setJobOngoing(jobId, session.sessionContext)
        // Init the splitter panel to show progress and progress steps
        // https://plugins.jetbrains.com/docs/intellij/general-threading-rules.html#write-access
        ApplicationManager.getApplication().invokeLater {
            codeModernizerBottomWindowPanelManager.setJobRunningUI()
        }

        return session.pollUntilJobCompletion(jobId) { new, plan ->
            codeModernizerBottomWindowPanelManager.handleJobTransition(new, plan, session.sessionContext.sourceJavaVersion)
        }
    }

    suspend fun getArtifactForHil(jobId: JobId, downloadArtifactId: String) = projectCoroutineScope(project).launch {
        try {
            // TODO store in the sessionState?
            val hilDownloadArtifact = artifactHandler.downloadHilArtifact(jobId, downloadArtifactId)
            if (hilDownloadArtifact != null) {
                // TODO parse pom to get dependency
                CodeTransformMessageListener.instance.onTransformPaused(hilDownloadArtifact)

                // TODO run maven get get dependency report
                // TODO parse file and find versions
                findAlternativeDependencyVersions()

                // TODO need to pass the version as params
                CodeTransformMessageListener.instance.onHilArtifactReady()

            } else {
                // TODO handle error
            }

        } catch (e: Error) {
            // TODO error handling
            // CodeTransformMessageListener.instance.onResult()
            print(e.message)
        }
    }

    suspend fun postModernizationJob(result: CodeModernizerJobCompletedResult) {
        codeTransformationSession?.setLastTransformResult(result)

        if (result is CodeModernizerJobCompletedResult.ManagerDisposed) {
            return
        }

        // TODO handle HIL work
        if (result is CodeModernizerJobCompletedResult.JobPaused) {
            // No need to handle failed case here since job would have been FAILED status
            val (jobId, downloadArtifactId) = result
            getArtifactForHil(jobId, downloadArtifactId)

            // return early?
            return
        }


        // https://plugins.jetbrains.com/docs/intellij/general-threading-rules.html#write-access
        ApplicationManager.getApplication().invokeLater {
            // TODO handle paused

            setJobNotOngoing()
            project.refreshCwQTree()
            if (!transformationStoppedByUsr.get()) {
                informUserOfCompletion(result)
                codeModernizerBottomWindowPanelManager.setJobFinishedUI(result)
                CodeTransformMessageListener.instance.onTransformResult(result)
            } else {
                codeModernizerBottomWindowPanelManager.userInitiatedStopCodeModernizationUI()
                notifyTransformationStopped()
                transformationStoppedByUsr.set(false)
                CodeTransformMessageListener.instance.onTransformStopped()
            }
        }
    }

    fun tryResumeJob(onProjectFirstOpen: Boolean = false) = projectCoroutineScope(project).launch {
        try {
            val notYetResumed = isResumingJob.compareAndSet(false, true)
            // If the job is already running, compareAndSet will return false because the expected
            // behavior is that the job is not running when trying to resume
            if (!notYetResumed) {
                return@launch
            }

            LOG.info { "Attempting to resume job, current state is: $managerState" }
            if (!managerState.flags.getOrDefault(StateFlags.IS_ONGOING, false)) return@launch

            // Gather project details
            if (onProjectFirstOpen) {
                val validationResult = validate(project)
                telemetry.sendValidationResult(validationResult, onProjectFirstOpen)
            }

            val context = managerState.toSessionContext(project)
            val session = CodeModernizerSession(context)
            val lastJobId = managerState.getLatestJobId()
            LOG.info { "Attempting to resume job with id $lastJobId" }
            val result = session.getJobDetails(lastJobId)
            when (result.status()) {
                TransformationStatus.COMPLETED -> {
                    CodeTransformMessageListener.instance.onTransformResuming()
                    notifyStickyInfo(
                        message("codemodernizer.manager.job_finished_title"),
                        message("codemodernizer.manager.job_finished_content"),
                        project,
                        listOf(
                            displayDiffNotificationAction(lastJobId),
                            displaySummaryNotificationAction(lastJobId),
                            viewTransformationHubAction()
                        )
                    )
                    resumeJob(session, lastJobId, result)
                    setJobNotOngoing()
                }

                TransformationStatus.PARTIALLY_COMPLETED -> {
                    CodeTransformMessageListener.instance.onTransformResuming()
                    notifyStickyInfo(
                        message("codemodernizer.notification.info.modernize_failed.title"),
                        message("codemodernizer.manager.job_failed_content", result.reason()),
                        project,
                        listOf(
                            displayDiffNotificationAction(lastJobId),
                            displaySummaryNotificationAction(lastJobId),
                            displayFeedbackNotificationAction(),
                            viewTransformationHubAction()
                        )
                    )
                    resumeJob(session, lastJobId, result)
                    setJobNotOngoing()
                }

                TransformationStatus.UNKNOWN_TO_SDK_VERSION -> {
                    CodeTransformMessageListener.instance.onTransformResuming()
                    notifyStickyInfo(
                        message("codemodernizer.notification.warn.on_resume.unknown_status_response.title"),
                        message("codemodernizer.notification.warn.on_resume.unknown_status_response.content"),
                    )
                    setJobNotOngoing()
                }

                TransformationStatus.STOPPED, TransformationStatus.STOPPING -> {
                    CodeTransformMessageListener.instance.onTransformResuming()
                    setJobNotOngoing()
                }

                else -> {
                    isJobSuccessfullyResumed.set(true)
                    CodeTransformMessageListener.instance.onTransformResuming()
                    resumeJob(session, lastJobId, result)
                    notifyStickyInfo(
                        message("codemodernizer.manager.job_ongoing_title"),
                        message("codemodernizer.manager.job_ongoing_content"),
                        project,
                        listOf(resumeJobNotificationAction(session, lastJobId, result)),
                    )
                }
            }
            telemetry.jobIsResumedAfterIdeClose(lastJobId, result.status())
        } catch (e: AccessDeniedException) {
            LOG.error { "Unable to resume job as credentials are invalid" }
            // User is logged in with old or invalid credentials, nothing to do until they log in with valid credentials
        } catch (e: Exception) {
            LOG.error { "Unable to resume job as an unexpected exception occurred ${e.stackTraceToString()}" }
        } finally {
            isResumingJob.set(false)
        }
    }

    private fun viewTransformationHubAction() = NotificationAction.createSimple(message("codemodernizer.notification.info.modernize_complete.view_summary")) {
        getBottomToolWindow().show()
    }

    private fun resumeJobNotificationAction(session: CodeModernizerSession, lastJobId: JobId, currentJobResult: TransformationJob) =
        NotificationAction.createSimple(message("codemodernizer.notification.info.modernize_ongoing.view_status")) {
            resumeJob(session, lastJobId, currentJobResult)
        }

    private fun displayDiffNotificationAction(jobId: JobId): NotificationAction = NotificationAction.createSimple(
        message("codemodernizer.notification.info.modernize_complete.view_diff")
    ) {
        artifactHandler.displayDiffAction(jobId)
    }

    private fun displaySummaryNotificationAction(jobId: JobId) =
        NotificationAction.createSimple(message("codemodernizer.notification.info.modernize_complete.view_summary")) {
            artifactHandler.showTransformationSummary(jobId)
        }

    private fun displayFeedbackNotificationAction() =
        NotificationAction.createSimple(message("codemodernizer.notification.warn.submit_feedback")) {
            CodeTransformFeedbackDialog(project).showAndGet()
        }

    private fun informUserOfCompletion(result: CodeModernizerJobCompletedResult) {
        var jobId: JobId? = null
        when (result) {
            is CodeModernizerJobCompletedResult.UnableToCreateJob -> notifyJobFailure(
                result.failureReason,
                result.retryable,
            )

            is CodeModernizerJobCompletedResult.RetryableFailure -> notifyJobFailure(
                result.failureReason,
                true,
            )

            is CodeModernizerJobCompletedResult.JobFailed -> notifyJobFailure(
                result.failureReason,
                false,
            )

            is CodeModernizerJobCompletedResult.JobFailedInitialBuild -> notifyStickyInfo(
                message("codemodernizer.builderrordialog.description.title"),
                result.failureReason,
                project,
                listOf(displayFeedbackNotificationAction())
            )

            is CodeModernizerJobCompletedResult.JobPartiallySucceeded -> {
                notifyStickyInfo(
                    message("codemodernizer.notification.info.modernize_partial_complete.title"),
                    message("codemodernizer.notification.info.modernize_partial_complete.content", result.targetJavaVersion.description),
                    project,
                    listOf(displayDiffNotificationAction(result.jobId), displaySummaryNotificationAction(result.jobId), displayFeedbackNotificationAction()),
                )
                jobId = result.jobId
            }

            is CodeModernizerJobCompletedResult.JobCompletedSuccessfully -> {
                notifyStickyInfo(
                    message("codemodernizer.notification.info.modernize_complete.title"),
                    message("codemodernizer.notification.info.modernize_complete.content"),
                    project,
                    listOf(displayDiffNotificationAction(result.jobId), displaySummaryNotificationAction(result.jobId)),
                )
                jobId = result.jobId
            }

            is CodeModernizerJobCompletedResult.ManagerDisposed -> LOG.warn { "Manager disposed" }
            is CodeModernizerJobCompletedResult.JobAbortedBeforeStarting -> LOG.warn { "Job was aborted" }
            is CodeModernizerJobCompletedResult.JobAbortedMissingDependencies -> notifyStickyInfo(
                message("codemodernizer.notification.warn.maven_failed.title"),
                message("codemodernizer.notification.warn.maven_failed.content"),
                project,
                listOf(openTroubleshootingGuideNotificationAction(TROUBLESHOOTING_URL_MAVEN_COMMANDS), displayFeedbackNotificationAction()),
            )
            is CodeModernizerJobCompletedResult.JobAbortedZipTooLarge -> notifyStickyInfo(
                message("codemodernizer.notification.warn.zip_too_large.title"),
                message("codemodernizer.notification.warn.zip_too_large.content"),
                project,
                listOf(openTroubleshootingGuideNotificationAction(TROUBLESHOOTING_URL_PREREQUISITES), displayFeedbackNotificationAction()),
            )
            is CodeModernizerJobCompletedResult.Stopped -> notifyStickyInfo(
                message("codemodernizer.notification.info.transformation_stop.title"),
                message("codemodernizer.notification.info.transformation_stop.content"),
                project,
                listOf(displayFeedbackNotificationAction())
            )

            // TODO
            is CodeModernizerJobCompletedResult.JobPaused -> notifyStickyInfo(
                message("codemodernizer.notification.info.transformation_stop.title"),
                message("codemodernizer.notification.info.transformation_stop.content"),
                project,
                listOf(displayFeedbackNotificationAction())
            )
        }
        telemetry.totalRunTime(result.toString(), jobId)
    }

    fun createCodeModernizerSession(customerSelection: CustomerSelection, project: Project) = CodeModernizerSession(
        CodeModernizerSessionContext(
            project,
            customerSelection.configurationFile,
            customerSelection.sourceJavaVersion,
            customerSelection.targetJavaVersion,
        ),
    )

    fun showModernizationProgressUI() = codeModernizerBottomWindowPanelManager.showUnalteredJobUI()

    fun showPreviousJobHistoryUI() {
        codeModernizerBottomWindowPanelManager.setPreviousJobHistoryUI(isModernizationJobActive())
    }

    fun userInitiatedStopCodeModernization() {
        notifyTransformationStartStopping()
        if (transformationStoppedByUsr.getAndSet(true)) return
        val currentId = codeTransformationSession?.getActiveJobId()
        projectCoroutineScope(project).launch {
            try {
                val success = codeTransformationSession?.stopTransformation(currentId?.id) ?: true // no session -> no job to stop
                if (!success) {
                    // This should not happen
                    throw CodeModernizerException(message("codemodernizer.notification.info.transformation_start_stopping.as_no_response"))
                }
            } catch (e: Exception) {
                LOG.error(e) { e.message.toString() }
                notifyTransformationFailedToStop()
            } finally {
                telemetry.totalRunTime("JobCancelled", currentId)
            }
        }
    }

    fun isModernizationJobResuming(): Boolean = isResumingJob.get()

    fun isModernizationJobStopping(): Boolean = transformationStoppedByUsr.get()

    fun isModernizationJobActive(): Boolean = isModernizationInProgress.get()

    fun isRunningMvn(): Boolean = isMvnRunning.get()

    fun isJobSuccessfullyResumed(): Boolean = isJobSuccessfullyResumed.get()

    fun getLastMvnBuildResult(): MavenCopyCommandsResult? = codeTransformationSession?.getLastMvnBuildResult()

    fun getLastTransformResult(): CodeModernizerJobCompletedResult? = codeTransformationSession?.getLastTransformResult()

    fun getBottomToolWindow() = ToolWindowManager.getInstance(project).getToolWindow(CodeModernizerBottomToolWindowFactory.id)
        ?: error(message("codemodernizer.toolwindow.problems_window_not_found"))

    fun getMvnBuildWindow() = ToolWindowManager.getInstance(project).getToolWindow("Run")
        ?: error(message("codemodernizer.toolwindow.problems_mvn_window_not_found"))

    override fun getState(): CodeModernizerState = CodeModernizerState().apply {
        lastJobContext.putAll(managerState.lastJobContext)
        flags.putAll(managerState.flags)
    }

    override fun loadState(state: CodeModernizerState) {
        managerState.lastJobContext.clear()
        managerState.lastJobContext.putAll(state.lastJobContext)
        managerState.flags.clear()
        managerState.flags.putAll(state.flags)
    }

    fun warnUnsupportedProject(reason: String?) {
        addCodeModernizeUI(true)
        val maybeUnknownReason = reason ?: message("codemodernizer.notification.warn.invalid_project.description.reason.unknown")
        codeModernizerBottomWindowPanelManager.setProjectInvalidUI(maybeUnknownReason)
        notifyStickyInfo(
            message("codemodernizer.validationerrordialog.description.title"),
            message("codemodernizer.validationerrordialog.description.main"),
            project,
            listOf(openTroubleshootingGuideNotificationAction(TROUBLESHOOTING_URL_PREREQUISITES)),
        )
    }

    fun getTransformationPlan(): TransformationPlan? = codeTransformationSession?.getTransformationPlan()
    fun getTransformationSummary(): TransformationSummary? {
        val job = codeTransformationSession?.getActiveJobId() ?: return null
        return artifactHandler.getSummary(job)
    }

    companion object {
        fun getInstance(project: Project): CodeModernizerManager = project.service()
        val LOG = getLogger<CodeModernizerManager>()
    }

    override fun dispose() {}
    fun showTransformationSummary() {
        val job = codeTransformationSession?.getActiveJobId() ?: return
        artifactHandler.showTransformationSummary(job)
    }

    fun showTransformationPlan() {
        codeTransformationSession?.tryOpenTransformationPlanEditor()
    }

    fun showDiff() {
        val job = codeTransformationSession?.getActiveJobId() ?: return
        artifactHandler.displayDiffAction(job)
    }

    fun handleCredentialsChanged() {
        codeTransformationSession?.dispose()
        codeModernizerBottomWindowPanelManager.reset()
        isModernizationInProgress.set(false)
    }

    fun findAlternativeDependencyVersions() {
        // TODO telemetry
        // telemetry.jobStartedCompleteFromPopupDialog(customerSelection)

        isMvnRunning.set(true)

        val result = codeTransformationSession?.getDependencyReportUsingMaven()

        isMvnRunning.set(false)
    }

    // TODO fix param
    suspend fun tryResumeWithAlternativeVersion() {
        // TODO remove
        delay(3000)

        // TODO change version in downloaded pom.xml

        // run maven locally with updated pom.xml
        // TODO create upload URL
        // TODO upload artifact
        // TODO call resume
        delay(3000)

        val localZipPathForTesting = "your-zip-path"
        // TODO create upload URL
        // TODO upload the zip to s3
        // TODO call resumeTransformation with COMPLETED status

        CodeTransformMessageListener.instance.onResumedWithAlternativeVersion()
    }
}
