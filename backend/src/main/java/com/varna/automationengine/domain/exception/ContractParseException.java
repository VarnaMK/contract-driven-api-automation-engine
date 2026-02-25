package com.varna.automationengine.domain.exception;

/**
 * Thrown when an uploaded file cannot be parsed as a valid OpenAPI specification.
 *
 * <p><b>When is this thrown?</b><br>
 * This exception is raised by the parser layer (infrastructure) and represents
 * a <em>client error</em> — the user uploaded a file that the engine cannot process.
 * Typical scenarios include:
 * <ul>
 *   <li>The file is not valid YAML or JSON at all (malformed syntax)</li>
 *   <li>The file is valid YAML/JSON but does not conform to the OpenAPI specification</li>
 *   <li>The OpenAPI spec is missing required sections (e.g. no {@code paths} block)</li>
 *   <li>The spec references schemas that cannot be resolved (e.g. broken {@code $ref})</li>
 *   <li>The OpenAPI version is not supported by the parser</li>
 * </ul>
 *
 * <p><b>How is this handled?</b><br>
 * The {@code ProjectGeneratorController} catches this exception and maps it to
 * an HTTP {@code 422 Unprocessable Entity} response — indicating that the server
 * understood the request but could not process the content.
 * The {@code GlobalExceptionHandler} catches it as a fallback via the parent class.
 *
 * <p><b>Architecture note:</b><br>
 * This class lives in the {@code domain} layer and has no dependencies on Spring
 * or any infrastructure library. It is thrown by infrastructure adapters
 * (e.g. {@code OpenApiParserAdapter}) and caught at the controller boundary.
 * The domain itself defines the exception; infrastructure throws it; the API layer
 * handles it. This is the correct direction of dependency flow.
 */
public class ContractParseException extends AutomationEngineException {

    /**
     * Constructs a new exception with a descriptive message.
     *
     * <p>Use this when a parsing rule was violated and there is no underlying
     * Java exception to attach — for example, when the spec is syntactically
     * valid but semantically incomplete.
     *
     * <p>Example:
     * <pre>
     * if (openApi.getPaths() == null || openApi.getPaths().isEmpty()) {
     *     throw new ContractParseException(
     *         "The OpenAPI specification contains no paths (endpoints). " +
     *         "Ensure the 'paths' section is present and non-empty."
     *     );
     * }
     * </pre>
     *
     * @param message a clear description of why the contract could not be parsed;
     *                this message is safe to include in the API error response
     */
    public ContractParseException(String message) {
        super(message);
    }

    /**
     * Constructs a new exception wrapping a lower-level parsing library exception.
     *
     * <p>Use this when a third-party parser (e.g. the Swagger Parser library)
     * throws its own exception. Wrapping it preserves the original stack trace
     * while translating the library-specific exception into a domain concept.
     *
     * <p>Example:
     * <pre>
     * try {
     *     SwaggerParseResult result = new OpenAPIParser().readContents(content, null, null);
     * } catch (Exception ex) {
     *     throw new ContractParseException(
     *         "Unexpected error while parsing the OpenAPI specification.", ex
     *     );
     * }
     * </pre>
     *
     * @param message a clear description of why the contract could not be parsed
     * @param cause   the underlying library or I/O exception that caused this failure
     */
    public ContractParseException(String message, Throwable cause) {
        super(message, cause);
    }
}