package com.varna.automationengine.infrastructure.generator;

import com.varna.automationengine.domain.exception.ProjectGenerationException;
import com.varna.automationengine.domain.model.contract.ApiContract;
import com.varna.automationengine.domain.model.project.GeneratedProject;

/**
 * Port interface defining the contract for generating a REST Assured test project
 * from a parsed {@link ApiContract}.
 *
 * <p><b>Where this sits in the pipeline:</b>
 * <pre>
 *   ApiContract (from parser)
 *       ↓
 *   ProjectGenerator.generate()  ← THIS INTERFACE
 *       ↓
 *   GeneratedProject (files ready for zipping)
 * </pre>
 *
 * <p><b>Why an interface?</b><br>
 * The {@code GenerateAutomationProjectService} (application layer) depends on
 * this interface, not on {@code RestAssuredProjectGenerator} directly. This means:
 * <ul>
 *   <li>The application layer is decoupled from the generation technology</li>
 *   <li>You can swap to a different test framework generator (e.g. Karate, JUnit5)
 *       by writing a new implementation — no service changes needed</li>
 *   <li>Unit tests can use a fake/mock implementation that returns pre-built
 *       {@link GeneratedProject} objects without running real generation</li>
 * </ul>
 */
public interface ProjectGenerator {

    /**
     * Generates a complete REST Assured test project from the given {@link ApiContract}.
     *
     * <p>The implementation must produce, at minimum:
     * <ul>
     *   <li>A {@code pom.xml} with all required dependencies</li>
     *   <li>A {@code testng.xml} test suite configuration</li>
     *   <li>A {@code BaseTest.java} with shared REST Assured configuration</li>
     *   <li>One test class per API endpoint group</li>
     *   <li>One POJO class per schema defined in {@code components/schemas}</li>
     * </ul>
     *
     * @param contract the fully parsed API contract; never null
     * @param traceId  the request-scoped trace ID for log correlation
     * @return a {@link GeneratedProject} containing all generated files with their paths
     * @throws ProjectGenerationException if generation fails for any reason
     */
    GeneratedProject generate(ApiContract contract, String traceId);
}