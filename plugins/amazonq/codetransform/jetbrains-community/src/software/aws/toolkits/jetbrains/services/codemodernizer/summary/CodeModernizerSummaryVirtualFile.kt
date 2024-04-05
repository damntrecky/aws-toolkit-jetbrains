// Copyright 2023 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.codemodernizer.summary

import com.intellij.testFramework.LightVirtualFile

// class CodeModernizerSummaryVirtualFile : LightVirtualFile(message("codemodernizer.migration_summary.header.title")) {
class CodeModernizerSummaryVirtualFile : LightVirtualFile {
    var projectBasePath: String

    constructor(basePath: String) {
        projectBasePath = basePath
        println("CodeModernizerSummaryVirtualFile basePath is: $projectBasePath")
    }

    override fun getUrl(): String = "file://$projectBasePath/transformation-summary.md"

    override fun getCanonicalPath(): String? = "$projectBasePath/transformation-summary.md"

    override fun getPresentableName(): String = "transformation-summary.md"

    override fun getPath(): String = "$projectBasePath/transformation-summary.md"

    override fun isWritable(): Boolean = true

    // This along with hashCode() is to make sure only one editor for this is opened at a time
    override fun equals(other: Any?) = other is CodeModernizerSummaryVirtualFile && this.hashCode() == other.hashCode()

    override fun hashCode(): Int = presentableName.hashCode()

    override fun getName(): String = "transformation-summary.md"
}
