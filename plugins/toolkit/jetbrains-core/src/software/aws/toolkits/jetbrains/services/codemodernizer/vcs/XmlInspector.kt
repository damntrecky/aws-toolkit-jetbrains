package software.aws.toolkits.jetbrains.services.codemodernizer.vcs

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.XmlElementVisitor
import com.intellij.psi.xml.XmlTag

class XmlInspector : LocalInspectionTool() {
    init {
        println { "XmlInspector instantiated!" }
    }

    override fun getDisplayName(): String = "POM File Inspection"
    override fun getGroupDisplayName(): String = "XML Inspections"
    override fun isEnabledByDefault(): Boolean = true
    override fun getShortName(): String = "XmlInspection"
    override fun getGroupPath(): Array<String> = arrayOf("XML", "POM")

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        println { "buildVisitor element $holder $isOnTheFly" }
        super.buildVisitor(holder, isOnTheFly)

        return object : XmlElementVisitor() {
            override fun visitXmlFile(file: com.intellij.psi.xml.XmlFile) {
                println { "Visiting element: $file" }
                super.visitXmlFile(file)
                if (file.name == "pom2.xml" || file.name == "pom.xml" || file.name == "pom1.xml") {
                    val javaVersionTagFound = file.rootTag?.findFirstSubTag("java.version")
                    if (javaVersionTagFound != null) {
                        val syntaxProblem = holder.manager.createProblemDescriptor(
                            javaVersionTagFound,
                            "Syntax problem",
                            true,
                            ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                            true
                        )

                        holder.registerProblem(syntaxProblem)

                        // Add another problem at a different line
                        val essentialProblem = holder.manager.createProblemDescriptor(
                            javaVersionTagFound,
                            "Essential problem",
                            true,
                            ProblemHighlightType.WARNING,
                            true
                        )

                        holder.registerProblem(essentialProblem)
                    }
                }
            }
            override fun visitElement(element: PsiElement) {
                println { "Visiting element: $element" }
                super.visitElement(element)
                if (element is XmlTag && element.name == "java.version") {
                    val syntaxProblem = holder.manager.createProblemDescriptor(
                        element,
                        "Syntax problem",
                        true,
                        ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                        true
                    )
                    holder.registerProblem(syntaxProblem)
                }
            }
        }
    }
}
