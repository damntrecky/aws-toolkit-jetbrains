// Copyright 2023 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.codemodernizer.model

import software.aws.toolkits.telemetry.CodeTransformPreValidationError

data class ValidationResult(
    val valid: Boolean,
    val invalidReason: String? = null,
    val invalidTelemetryReason: CodeTransformPreValidationError? = null
)
