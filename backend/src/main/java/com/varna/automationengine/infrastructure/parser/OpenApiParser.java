package com.varna.automationengine.infrastructure.parser;

import com.varna.automationengine.domain.exception.ContractParseException;
import com.varna.automationengine.domain.model.contract.ApiContract;
import org.springframework.web.multipart.MultipartFile;

/**
 * Port interface defining the contract for parsing an uploaded OpenAPI specification
 * into the engine's internal {@link ApiContract} domain model.
 *
 * <p><b>What is a "port" in clean architecture?</b><br>
 * A port is an interface declared close to the domain that defines <em>what</em>
 * a capability should do, without specifying <em>how</em> it does it.
 * The actual implementation (the "adapter") lives in the infrastructure layer
 * and is injected at runtime by Spring.
 *
 * <p>This interface sits in the {@code infrastructure.parser} package rather than
 * {@code domain.port.outbound} for pragmatic reasons during incremental development.
 * In a fully mature architecture it would move to {@code domain.port.outbound}.
 *
 * <p><b>Why an interface instead of a direct class dependency?</b>
 * <ul>
 *   <li><b>Swappability</b> — today we use the Swagger Parser library. Tomorrow
 *       we could switch to a different OpenAPI library. Only the adapter changes;
 *       the service and use case that call this interface are untouched.</li>
 *   <li><b>Testability</b> — in unit tests, you can provide a mock or fake
 *       implementation that returns a pre-built {@link ApiContract} without
 *       needing any real file or parsing library.</li>
 *   <li><b>Single Responsibility</b> — the service that calls this interface
 *       doesn't know or care how parsing works. It only knows the result.</li>
 * </ul>
 *
 * <p><b>Architecture note:</b><br>
 * The {@code application} layer (specifically {@code GenerateAutomationProjectService})
 * depends on this interface. The {@code infrastructure} layer provides the
 * implementation ({@code SwaggerOpenApiParser}). Dependency flows inward —
 * infrastructure knows about the interface; the interface does not know about
 * any infrastructure class.
 */
public interface OpenApiParser {

    /**
     * Parses an uploaded OpenAPI specification file into an {@link ApiContract}.
     *
     * <p>Implementations of this method must:
     * <ol>
     *   <li>Read the raw content from the {@link MultipartFile}</li>
     *   <li>Detect and validate that it is a supported OpenAPI format (3.x)</li>
     *   <li>Extract all endpoint paths, HTTP methods, parameters, and schemas</li>
     *   <li>Map everything into the {@link ApiContract} domain model and return it</li>
     * </ol>
     *
     * <p>If any step fails — the file is malformed, the spec is not OpenAPI,
     * or required sections are missing — the implementation must throw a
     * {@link ContractParseException} with a descriptive message.
     *
     * @param contractFile the uploaded specification file (YAML or JSON);
     *                     guaranteed non-null and non-empty by the controller layer
     * @param traceId      the request-scoped trace ID for log correlation;
     *                     implementations should include this in every log statement
     * @return a fully populated {@link ApiContract} containing all endpoints and schemas
     * @throws ContractParseException if the file cannot be read, is not valid OpenAPI,
     *                                or is missing required sections (e.g. no paths defined)
     */
    ApiContract parse(MultipartFile contractFile, String traceId);
}