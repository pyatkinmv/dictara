package com.dictara.gateway.mcp

import com.dictara.gateway.guard.PaidOperation
import com.dictara.gateway.port.SummarizerPort
import com.dictara.gateway.port.TranscriberPort
import com.dictara.gateway.service.BacklogReanalysisService
import com.dictara.gateway.service.SubmissionService
import com.dictara.gateway.service.SummaryRegenerationService
import com.dictara.gateway.storage.AudioStorage
import com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo
import com.tngtech.archunit.base.DescribedPredicate.not
import com.tngtech.archunit.core.domain.JavaClass.Predicates.INTERFACES
import com.tngtech.archunit.core.domain.JavaModifier
import com.tngtech.archunit.core.domain.properties.CanBeAnnotated
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test

/**
 * How the code is arranged, checked at every build (ArchUnit reads the compiled classes), so that the promises about MCP
 * do not depend on anyone remembering them:
 *  - the `mcp` package stays apart: nothing outside it uses it;
 *  - tools reach the app only through `mcp.facade`; nothing in `mcp` touches paid ports, adapters, storage or controllers;
 *  - everything that costs money is marked [PaidOperation], so that the guard can refuse it to MCP.
 */
class McpArchitectureTest {

    private val classes = ClassFileImporter()
        .withImportOption(ImportOption.DoNotIncludeTests())
        .importPackages("com.dictara.gateway")

    // -- the mcp package stays apart ------------------------------------------------------------------------

    @Test
    fun `nothing outside the mcp package depends on it`() {
        noClasses().that().resideOutsideOfPackage("com.dictara.gateway.mcp..")
            .should().dependOnClassesThat().resideInAPackage("com.dictara.gateway.mcp..")
            .because("the MCP feature is kept apart: it uses the app, the app does not know it (the migrator and the controllers are found by scanning)")
            .check(classes)
    }

    @Test
    fun `the tools use the facade and not the services, repositories or anything that reaches outside`() {
        classes().that().resideInAPackage("com.dictara.gateway.mcp.tools..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                "com.dictara.gateway.mcp.tools..", "com.dictara.gateway.mcp.facade..", "com.dictara.gateway.mcp.token",
                "java..", "kotlin..", "com.fasterxml.jackson..", "org.springframework.stereotype..", "org.jetbrains..",
            )
            .because("a tool may only call what the facade offers; the McpAccess of a token is the one thing it takes from there")
            .check(classes)
    }

    @Test
    fun `nothing in mcp touches the paid ports, adapters, storage, jobs or the controllers of the app`() {
        noClasses().that().resideInAPackage("com.dictara.gateway.mcp..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "com.dictara.gateway.port..", "com.dictara.gateway.adapter..", "com.dictara.gateway.client..",
                "com.dictara.gateway.storage..", "com.dictara.gateway.job..", "com.dictara.gateway.controller..",
                "com.dictara.gateway.repository..",
            )
            .because("MCP reads through services only, and never reaches the places where money is spent")
            .check(classes)
    }

    @Test
    fun `tools and the facade cannot see the request, so the user can only come from the token`() {
        noClasses().that().resideInAnyPackage("com.dictara.gateway.mcp.tools..", "com.dictara.gateway.mcp.facade..")
            .should().dependOnClassesThat().resideInAnyPackage("jakarta.servlet..", "org.springframework.web..", "org.springframework.http..")
            .because("whose data is read is decided by the token alone, never by anything a client sends")
            .check(classes)
    }

    // -- paid operations are marked -------------------------------------------------------------------------

    @Test
    fun `every summarizer marks its analyze as paid`() {
        methods().that().haveName("analyze").and().areDeclaredInClassesThat(
            assignableTo(SummarizerPort::class.java).and(not(INTERFACES)),
        ).should().beAnnotatedWith(PaidOperation::class.java)
            .because("a call to a summarizer (Gemini) costs money")
            .check(classes)
    }

    @Test
    fun `every transcriber client marks the calls that start a job as paid`() {
        methods().that().haveNameMatching("submit.*").and().areDeclaredInClassesThat(
            assignableTo(TranscriberPort::class.java).and(not(INTERFACES)),
        ).should().beAnnotatedWith(PaidOperation::class.java)
            .because("a transcription runs on a GPU")
            .check(classes)
    }

    @Test
    fun `every audio storage marks its writes as paid`() {
        methods().that().haveNameMatching("upload.*").and().areDeclaredInClassesThat(
            assignableTo(AudioStorage::class.java).and(not(INTERFACES)),
        ).should().beAnnotatedWith(PaidOperation::class.java)
            .because("storing audio costs money")
            .check(classes)
    }

    @Test
    fun `the entry points that start paid work are marked`() {
        methods().that().areDeclaredIn(SummaryRegenerationService::class.java).and().haveName("start")
            .or().areDeclaredIn(BacklogReanalysisService::class.java).and().haveName("start")
            .or().areDeclaredIn(SubmissionService::class.java).and().haveName("createSubmission")
            .should().beAnnotatedWith(PaidOperation::class.java)
            .because("they start work in other threads, where the origin of the call is not known any more")
            .check(classes)
    }

    @Test
    fun `a class with paid methods can be proxied, which the guard needs, so it is not final`() {
        classes().that().containAnyMethodsThat(CanBeAnnotated.Predicates.annotatedWith(PaidOperation::class.java))
            .should().notHaveModifier(JavaModifier.FINAL)
            .because("Spring puts the guard in front of such a class by subclassing it; a final one would fail at startup")
            .check(classes)
    }
}
