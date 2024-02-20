// Copyright 2024 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.codemodernizer

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import org.jetbrains.idea.maven.execution.MavenRunner
import software.aws.toolkits.jetbrains.services.codemodernizer.ideMaven.TransformMavenRunner
import software.aws.toolkits.jetbrains.services.codemodernizer.ideMaven.runMavenCopyDependencies
import java.io.File

class JarDependency1pHandler(private val project: Project) {
    private val tempDir = FileUtil.createTempDirectory("codeTransformJarArtifacts", null)
    private val tmpPomPath = "pom.xml"
    val mvnSettings = MavenRunner.getInstance(project).settings.clone() // clone required to avoid editing user settings
    val transformMvnRunner = TransformMavenRunner(project)

    fun extractCurrentPomToTempDirectory(): File {
        return createTempPomFile()
    }

    fun runMavenCommandsOnTempPom() {
        val buildLogBuilder = StringBuilder("Starting Build Log...\n")
        runMavenCopyDependencies(
            tempDir.toPath().toFile(),
            buildLogBuilder,
            mvnSettings,
            transformMvnRunner,
            tempDir.toPath(),
            CodeModernizerManager.LOG
        )
    }

    private fun createTempPomFile(): File {
        val pomPath = tempDir.toPath().resolve(tmpPomPath)
        val pomFile = pomPath.toFile()

        val pomContent = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n" +
            "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
            "         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd\">\n" +
            "    <modelVersion>4.0.0</modelVersion>\n" +
            "    \n" +
            "    <groupId>com.sample</groupId>\n" +
            "    <artifactId>LiquorStoreApp</artifactId>\n" +
            "    <version>1.0-SNAPSHOT</version>\n" +
            "    \n" +
            "    <packaging>war</packaging>\n" +
            "    \n" +
            "    <properties>\n" +
            "        <maven.compiler.target>1.8</maven.compiler.target>\n" +
            "        <maven.compiler.source>1.8</maven.compiler.source>\n" +
            "    </properties>\n" +
            "    \n" +
            "    <dependencies>\n" +
            "        <!-- Gumby to scan -->\n" +
            "        <dependency>\n" +
            "            <groupId>javax.servlet</groupId>\n" +
            "            <artifactId>javax.servlet-api</artifactId>\n" +
            "            <version>3.0.1</version>\n" +
            "            <scope>provided</scope>\n" +
            "        </dependency>\n" +
            "        \n" +
            "        <!-- Scan local mirror -->\n" +
            "    </dependencies>\n" +
            "    \n" +
            "    <build>\n" +
            "    </build>\n" +
            "</project>\n";
        pomFile.writeText(pomContent)

        return pomFile
    }
}
