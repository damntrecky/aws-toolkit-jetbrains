// Copyright 2023 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.codemodernizer.mocks

import software.amazon.awssdk.services.codewhispererruntime.model.GetTransformationPlanResponse
import software.amazon.awssdk.services.codewhispererruntime.model.GetTransformationResponse
import software.amazon.awssdk.services.codewhispererruntime.model.StartTransformationResponse
import software.amazon.awssdk.services.codewhispererruntime.model.TransformationDownloadArtifact
import software.amazon.awssdk.services.codewhispererruntime.model.TransformationJob
import software.amazon.awssdk.services.codewhispererruntime.model.TransformationLanguage
import software.amazon.awssdk.services.codewhispererruntime.model.TransformationPlan
import software.amazon.awssdk.services.codewhispererruntime.model.TransformationProgressUpdate
import software.amazon.awssdk.services.codewhispererruntime.model.TransformationProjectState
import software.amazon.awssdk.services.codewhispererruntime.model.TransformationSpec
import software.amazon.awssdk.services.codewhispererruntime.model.TransformationStatus
import software.amazon.awssdk.services.codewhispererruntime.model.TransformationStep
import software.amazon.awssdk.services.codewhispererruntime.model.TransformationType
import java.time.Instant

class ApiMocks {
    fun startCodeModernizationMock(): StartTransformationResponse {
        val jobId = "0903957f-bb61-4d1a-b6b6-b7a2da7c436a"
        return StartTransformationResponse.builder().transformationJobId(jobId).build()
    }

    fun getCodeModernizationJobMock(jobId: String, count: Int): GetTransformationResponse {
        val statusList: List<TransformationStatus> = listOf(
            TransformationStatus.STARTED,
            TransformationStatus.PREPARING,
            TransformationStatus.PREPARED,
            TransformationStatus.PLANNING,
            TransformationStatus.PLANNED,
            TransformationStatus.TRANSFORMING,
            TransformationStatus.TRANSFORMING,
            TransformationStatus.TRANSFORMING,
            TransformationStatus.PAUSED,
        )

        val transformJob = TransformationJob
            .builder()
            .jobId("b91d4aa3-3353-4741-9f6a-cdd15888c5d8")
            .creationTime(Instant.parse("2024-04-17T16:37:10.135Z"))
            .status(statusList[count.coerceAtMost(statusList.lastIndex)])
            .transformationSpec(
                TransformationSpec
                    .builder()
                    .transformationType(TransformationType.LANGUAGE_UPGRADE)
                    .source(TransformationProjectState.builder().language(TransformationLanguage.JAVA_8).build())
                    .target(TransformationProjectState.builder().language(TransformationLanguage.JAVA_17).build())
                    .build()
            )
            .build()

        return GetTransformationResponse
            .builder()
            .transformationJob(transformJob)
            .build()
    }

    fun getCodeModernizationPlanMock(count: Int): GetTransformationPlanResponse {
        val plan1 = TransformationPlan.builder().transformationSteps(
            listOf(
                TransformationStep.builder()
                    .id("1")
                    .name("Step 1 - Update dependencies and code")
                    .description(
                        "Q will update mandatory package dependencies and frameworks. Also, where required for compatability with Java 17, it will replace " +
                            "deprecated code with working code."
                    )
                    .status("CREATED")
                    .progressUpdates(listOf())
                    .build(),
                TransformationStep.builder()
                    .id("2")
                    .name("Step 2 - Build in Java 17 and fix any issues")
                    .description("Q will build the upgraded code in Java 17 and iteratively fix any build errors encountered.")
                    .status("CREATED")
                    .progressUpdates(listOf())
                    .build(),
                TransformationStep.builder()
                    .id("3")
                    .name("Step 3 - Finalize code changes and generate transformation summary")
                    .description(
                        "Q will generate code changes for you to review and accept. It will also summarize the changes made, and will copy over build logs " +
                            "for future reference and troubleshooting."
                    )
                    .status("CREATED")
                    .progressUpdates(listOf())
                    .build(),
            )
        ).build()

        val plan2 = TransformationPlan.builder().transformationSteps(
            listOf(
                TransformationStep.builder()
                    .id("1")
                    .name("Step 1 - Update dependencies and code")
                    .description(
                        "Q will update mandatory package dependencies and frameworks. Also, where required for compatability with Java 17, it will replace " +
                            "deprecated code with working code."
                    )
                    .status("CREATED")
                    .progressUpdates(
                        TransformationProgressUpdate
                            .builder()
                            .name("Applying dependencies and code changes")
                            .status("IN_PROGRESS")
                            .description("Step started")
                            .startTime(Instant.parse("2024-04-16T04:26:51.471Z"))
                            .build()
                    )
                    .startTime(Instant.parse("2024-04-16T04:26:51.471Z"))
                    .build(),
                TransformationStep.builder()
                    .id("2")
                    .name("Step 2 - Build in Java 17 and fix any issues")
                    .description("Q will build the upgraded code in Java 17 and iteratively fix any build errors encountered.")
                    .status("CREATED")
                    .progressUpdates(listOf())
                    .build(),
                TransformationStep.builder()
                    .id("3")
                    .name("Step 3 - Finalize code changes and generate transformation summary")
                    .description(
                        "Q will generate code changes for you to review and accept. It will also summarize the changes made, and will copy over build logs " +
                            "for future reference and troubleshooting."
                    )
                    .status("CREATED")
                    .progressUpdates(listOf())
                    .build(),
            )
        ).build()

        val plan3 = TransformationPlan.builder().transformationSteps(
            listOf(
                TransformationStep.builder()
                    .id("1")
                    .name("Step 1 - Update dependencies and code")
                    .description(
                        "Q will update mandatory package dependencies and frameworks. Also, where required for compatability with Java 17, it will replace " +
                            "deprecated code with working code."
                    )
                    .status("CREATED")
                    .progressUpdates(
                        TransformationProgressUpdate
                            .builder()
                            .name("Applying dependencies and code changes")
                            .status("COMPLETED")
                            .description("Step finished successfully")
                            .startTime(Instant.parse("2024-04-16T04:26:51.471Z"))
                            .endTime(Instant.parse("2024-04-16T04:27:23.054Z"))
                            .build(),
                        TransformationProgressUpdate
                            .builder()
                            .name("Building in Java 17 environment")
                            .status("IN_PROGRESS")
                            .description("Migration step started")
                            .startTime(Instant.parse("2024-04-16T04:27:23.223Z"))
                            .build()
                    )
                    .startTime(Instant.parse("2024-04-16T04:26:51.471Z"))
                    .build(),
                TransformationStep.builder()
                    .id("2")
                    .name("Step 2 - Build in Java 17 and fix any issues")
                    .description("Q will build the upgraded code in Java 17 and iteratively fix any build errors encountered.")
                    .status("CREATED")
                    .progressUpdates(listOf())
                    .build(),
                TransformationStep.builder()
                    .id("3")
                    .name("Step 3 - Finalize code changes and generate transformation summary")
                    .description(
                        "Q will generate code changes for you to review and accept. It will also summarize the changes made, and will copy over build logs " +
                            "for future reference and troubleshooting."
                    )
                    .status("CREATED")
                    .progressUpdates(listOf())
                    .build(),
            )
        ).build()

        val plan4 = TransformationPlan.builder().transformationSteps(
            listOf(
                TransformationStep.builder()
                    .id("1")
                    .name("Step 1 - Update dependencies and code")
                    .description(
                        "Q will update mandatory package dependencies and frameworks. Also, where required for compatability with Java 17, it will replace " +
                            "deprecated code with working code."
                    )
                    .status("CREATED")
                    .progressUpdates(
                        TransformationProgressUpdate
                            .builder()
                            .name("Applying dependencies and code changes")
                            .status("COMPLETED")
                            .description("Step finished successfully")
                            .startTime(Instant.parse("2024-04-16T04:26:51.471Z"))
                            .endTime(Instant.parse("2024-04-16T04:27:23.054Z"))
                            .build(),
                        TransformationProgressUpdate
                            .builder()
                            .name("Building in Java 17 environment")
                            .status("PAUSED")
                            .description("Compile Failed. Error encountered for dependency incompatibility. Paused to get user input.")
                            .startTime(Instant.parse("2024-04-16T04:27:23.223Z"))
                            .endTime(Instant.parse("2024-04-16T04:29:53.836Z"))
                            .downloadArtifacts(
                                listOf(
                                    TransformationDownloadArtifact
                                        .builder()
                                        .downloadArtifactType("CLIENT_INSTRUCTIONS")
                                        .downloadArtifactId("someID")
                                        .build()
                                )
                            )
                            .build()
                    )
                    .startTime(Instant.parse("2024-04-16T04:26:51.471Z"))
                    .build(),
                TransformationStep.builder()
                    .id("2")
                    .name("Step 2 - Build in Java 17 and fix any issues")
                    .description("Q will build the upgraded code in Java 17 and iteratively fix any build errors encountered.")
                    .status("CREATED")
                    .progressUpdates(listOf())
                    .build(),
                TransformationStep.builder()
                    .id("3")
                    .name("Step 3 - Finalize code changes and generate transformation summary")
                    .description(
                        "Q will generate code changes for you to review and accept. It will also summarize the changes made, and will copy over build logs " +
                            "for future reference and troubleshooting."
                    )
                    .status("CREATED")
                    .progressUpdates(listOf())
                    .build(),
            )
        ).build()

        val plans = listOf(plan1, plan2, plan3, plan4)

        return GetTransformationPlanResponse.builder().transformationPlan(plans[count.coerceAtMost(plans.lastIndex)]).build()
    }
}
