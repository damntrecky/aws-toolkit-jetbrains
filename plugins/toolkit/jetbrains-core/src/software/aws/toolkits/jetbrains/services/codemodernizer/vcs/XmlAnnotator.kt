// Copyright 2024 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.codemodernizer.vcs

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.runInEdt
import com.intellij.psi.PsiElement
import com.intellij.psi.xml.XmlTag

class XmlAnnotator : Annotator {
    init {
        println { "XmlAnnotator has been initialized!" }
    }
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        println { "XmlAnnotator annotate called for element $element " }
        if (element is XmlTag) {
            val name = element.name
            if (name == "properties" || name == "PROPERTIES") {
                runInEdt {
                    holder.newAnnotation(HighlightSeverity.ERROR, "This Java Version needs to be upgraded")
                        .range(element.textRange)
                        .create()
                }
            }
        }
    }
}
