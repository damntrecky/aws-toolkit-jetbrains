// Copyright 2024 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.codemodernizer.commands

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import software.aws.toolkits.jetbrains.services.codemodernizer.model.CodeModernizerJobCompletedResult
import software.aws.toolkits.jetbrains.services.codemodernizer.model.CodeTransformHilDownloadArtifact
import software.aws.toolkits.jetbrains.services.codemodernizer.model.MavenCopyCommandsResult

class CodeTransformMessageListener {

    private val _messages by lazy { MutableSharedFlow<CodeTransformActionMessage>(extraBufferCapacity = 10) }
    val flow = _messages.asSharedFlow()

    // TODO fix parameters
    fun onHilArtifactReady() {
        _messages.tryEmit(
            CodeTransformActionMessage(
                CodeTransformCommand.HilArtifactReady,
                hilAvailableVersions = listOf(
                    "1.1",
                    "1.2"
                )
            )
        )
    }

    fun onStopClicked() {
        _messages.tryEmit(CodeTransformActionMessage(CodeTransformCommand.StopClicked))
    }

    // TODO fix parameters
    fun onTransformPaused(codeTransformHilDownloadArtifact: CodeTransformHilDownloadArtifact) {
        _messages.tryEmit(CodeTransformActionMessage(CodeTransformCommand.Paused, hilDownloadArtifact = codeTransformHilDownloadArtifact))
    }

    fun onTransformStopped() {
        _messages.tryEmit(CodeTransformActionMessage(CodeTransformCommand.TransformStopped))
    }

    fun onMavenBuildResult(result: MavenCopyCommandsResult) {
        _messages.tryEmit(CodeTransformActionMessage(CodeTransformCommand.MavenBuildComplete, mavenBuildResult = result))
    }

    fun onTransformResult(result: CodeModernizerJobCompletedResult) {
        _messages.tryEmit(CodeTransformActionMessage(CodeTransformCommand.TransformComplete, transformResult = result))
    }

    fun onTransformResuming() {
        _messages.tryEmit(CodeTransformActionMessage(CodeTransformCommand.TransformResuming))
    }

    fun onAuthRestored() {
        _messages.tryEmit(CodeTransformActionMessage(CodeTransformCommand.AuthRestored))
    }

    // TODO fix parameters
    fun onResumedWithAlternativeVersion() {
        _messages.tryEmit(CodeTransformActionMessage(CodeTransformCommand.ResumedWithAltVersion))
    }

    fun onRequestUserInput() {
    }

    // provide singleton access
    companion object {
        val instance = CodeTransformMessageListener()
    }
}
