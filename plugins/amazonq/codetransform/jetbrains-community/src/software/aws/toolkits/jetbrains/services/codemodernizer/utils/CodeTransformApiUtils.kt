// Copyright 2024 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.codemodernizer.utils

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.serviceContainer.AlreadyDisposedException
import org.slf4j.Logger
import software.amazon.awssdk.awscore.exception.AwsServiceException
import software.amazon.awssdk.services.codewhispererruntime.model.AccessDeniedException
import software.amazon.awssdk.services.codewhispererruntime.model.CodeWhispererRuntimeException
import software.amazon.awssdk.services.codewhispererruntime.model.GetTransformationResponse
import software.amazon.awssdk.services.codewhispererruntime.model.InternalServerException
import software.amazon.awssdk.services.codewhispererruntime.model.ResumeTransformationResponse
import software.amazon.awssdk.services.codewhispererruntime.model.ThrottlingException
import software.amazon.awssdk.services.codewhispererruntime.model.TransformationDownloadArtifact
import software.amazon.awssdk.services.codewhispererruntime.model.TransformationJob
import software.amazon.awssdk.services.codewhispererruntime.model.TransformationPlan
import software.amazon.awssdk.services.codewhispererruntime.model.TransformationProgressUpdate
import software.amazon.awssdk.services.codewhispererruntime.model.TransformationStatus
import software.amazon.awssdk.services.codewhispererruntime.model.TransformationStep
import software.amazon.awssdk.services.codewhispererruntime.model.TransformationStepStatus
import software.amazon.awssdk.services.codewhispererruntime.model.TransformationUserActionStatus
import software.amazon.awssdk.services.codewhispererruntime.model.ValidationException
import software.aws.toolkits.core.utils.WaiterUnrecoverableException
import software.aws.toolkits.core.utils.Waiters.waitUntil
import software.aws.toolkits.core.utils.error
import software.aws.toolkits.core.utils.info
import software.aws.toolkits.jetbrains.services.codemodernizer.CodeTransformTelemetryManager
import software.aws.toolkits.jetbrains.services.codemodernizer.client.GumbyClient
import software.aws.toolkits.jetbrains.services.codemodernizer.model.JobId
import software.aws.toolkits.telemetry.CodeTransformApiNames
import java.io.File
import java.lang.Thread.sleep
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

data class PollingResult(
    val succeeded: Boolean,
    val jobDetails: TransformationJob?,
    val state: TransformationStatus,
    val transformationPlan: TransformationPlan?
)

/**
 * Wrapper around [waitUntil] that polls the API DescribeMigrationJob to check the migration job status.
 */
suspend fun JobId.pollTransformationStatusAndPlan(
    succeedOn: Set<TransformationStatus>,
    failOn: Set<TransformationStatus>,
    clientAdaptor: GumbyClient,
    initialSleepDurationMillis: Long,
    sleepDurationMillis: Long,
    isDisposed: AtomicBoolean,
    project: Project,
    maxDuration: Duration = Duration.ofSeconds(604800),
    onStateChange: (previousStatus: TransformationStatus?, currentStatus: TransformationStatus, transformationPlan: TransformationPlan?) -> Unit,
): PollingResult {
    val telemetry = CodeTransformTelemetryManager.getInstance(project)
    var state = TransformationStatus.UNKNOWN_TO_SDK_VERSION
    var transformationResponse: GetTransformationResponse? = null
    var transformationPlan: TransformationPlan? = null
    var didSleepOnce = false
    val maxRefreshes = 10
    var numRefreshes = 0
    var count = 0
    var statusCount = 0
    refreshToken(project)

    try {
        waitUntil(
            succeedOn = { result -> result in succeedOn },
            failOn = { result -> result in failOn },
            maxDuration = maxDuration,
            exceptionsToStopOn = setOf(
                InternalServerException::class,
                ValidationException::class,
                AwsServiceException::class,
                CodeWhispererRuntimeException::class,
                RuntimeException::class,
            ),
            exceptionsToIgnore = setOf(ThrottlingException::class)
        ) {
            try {
                if (!didSleepOnce) {
                    sleep(initialSleepDurationMillis)
                    didSleepOnce = true
                }
                if (isDisposed.get()) throw AlreadyDisposedException("The invoker is disposed.")
                // transformationResponse = clientAdaptor.getCodeModernizationJobMock(this.id, statusCount)
                transformationResponse = clientAdaptor.getCodeModernizationJob(this.id)
                statusCount++
                val newStatus = transformationResponse?.transformationJob()?.status() ?: throw RuntimeException("Unable to get job status")
                var newPlan: TransformationPlan? = null
                if (newStatus in STATES_WHERE_PLAN_EXIST) {
                    sleep(sleepDurationMillis)
                    newPlan = clientAdaptor.getCodeModernizationPlan(this).transformationPlan()
                    // newPlan = clientAdaptor.getCodeModernizationPlanMock(this, count).transformationPlan()
                    count++
                }
                if (newStatus != state) {
                    telemetry.jobStatusChanged(this, newStatus.toString(), state.toString())
                }
                if (newPlan != transformationPlan) {
                    telemetry.jobStatusChanged(this, "PLAN_UPDATED", state.toString())
                }
                if (newStatus !in failOn && (newStatus != state || newPlan != transformationPlan)) {
                    transformationPlan = newPlan
                    onStateChange(state, newStatus, transformationPlan)
                }
                state = newStatus
                numRefreshes = 0
                return@waitUntil state
            } catch (e: AccessDeniedException) {
                if (numRefreshes++ > maxRefreshes) throw e
                refreshToken(project)
                return@waitUntil state
            } finally {
                sleep(sleepDurationMillis)
            }
        }
    } catch (e: Exception) {
        // Still call onStateChange to update the UI
        onStateChange(state, TransformationStatus.FAILED, transformationPlan)
        when (e) {
            is WaiterUnrecoverableException, is AccessDeniedException -> {
                return PollingResult(false, transformationResponse?.transformationJob(), state, transformationPlan)
            }
            else -> throw e
        }
    }
    return PollingResult(true, transformationResponse?.transformationJob(), state, transformationPlan)
}

// TODO fix this
fun getTransformationStepsFixture(
    jobId: JobId
): List<TransformationStep> {
    println("In getTransformationStepsFixture $jobId")

    val downloadArtifact = TransformationDownloadArtifact.builder()
        .downloadArtifactId("fake-artifact-id")
        .downloadArtifactType("1p-hil-artifact-type")

    var progressUpdate = TransformationProgressUpdate.builder()
        .name("Status step")
        .status(TransformationStepStatus.FAILED.toString())
        .description("This step should be hil identifier")
        .startTime(Instant.now())
        .endTime(Instant.now())
        .downloadArtifacts(downloadArtifact.build())

    val transformationStepBuilder = TransformationStep.builder()
        .id("fake-step-id-1")
        .name("Building Code")
        .description("Building dependencies")
        .status(TransformationStatus.PAUSED.toString())
        .startTime(Instant.now())
        .endTime(Instant.now())
        .progressUpdates(progressUpdate.build())

    return listOf(transformationStepBuilder.build())
}

fun downloadResultArchive(jobId: JobId, downloadArtifact: TransformationDownloadArtifact): Array<VirtualFile?> {
    // TODO change to logger
    println("In downloadResultArchive $jobId $downloadArtifact")

    // TODO parse xml to json
    val manifestFileFilePath = "/src/amazonqGumby/mock/downloadHilZip/manifest.json"
    val pomFileFilePath = "/src/amazonqGumby/mock/downloadHilZip/pom.xml"

    val manifestFileFileReference = File(manifestFileFilePath)
    val pomFileFileReference = File(pomFileFilePath)

    val localFileSystem = LocalFileSystem.getInstance()
    val manifestFileVirtualFileReference = localFileSystem.findFileByIoFile(manifestFileFileReference)
    val pomFileVirtualFileReference = localFileSystem.findFileByIoFile(pomFileFileReference)

    return arrayOf(manifestFileVirtualFileReference, pomFileVirtualFileReference)
}

fun restartCodeTransformation(
    project: Project,
    jobId: JobId,
    status: TransformationUserActionStatus,
    logger: Logger,
    telemetry: CodeTransformTelemetryManager
): TransformationStatus? {
    val restartCodeTransformResponse: ResumeTransformationResponse?
    val clientAdaptor = GumbyClient.getInstance(project)
    val uploadStartTime = Instant.now()
    try {
        logger.info {
            "Resuming transformation jobId: ${jobId.id} with status $status"
        }

        restartCodeTransformResponse = clientAdaptor.resumeCodeTransformation(jobId.id, status)
        telemetry.logApiLatency(
            CodeTransformApiNames.StartTransformation,
            uploadStartTime,
            0,
            restartCodeTransformResponse.responseMetadata().requestId()
        )
    } catch (e: Exception) {
        val errorMessage = "Unexpected error when uploading artifact to S3: ${e.localizedMessage}"
        logger.error { errorMessage }
        // emit this metric here manually since we don't use callApi(), which emits its own metric
        telemetry.apiError(errorMessage, CodeTransformApiNames.StartTransformation, jobId.id)
        throw e // pass along error to callee
    }

    return restartCodeTransformResponse.transformationStatus()
}
