package com.varna.automationengine.domain.exception;

/**
 * Root exception for the entire Automation Engine domain.
 *
 * <p><b>Why does this class exist?</b><br>
 * In clean architecture, the domain layer defines its own exception hierarchy.
 * This root class acts as the single parent for every domain-specific exception
 * in this system. Having a common root gives you two powerful abilities:
 * <ol>
 *   <li>The {@code GlobalExceptionHandler} can catch ALL domain exceptions with
 *       a single {@code @ExceptionHandler(AutomationEngineException.class)} as a
 *       safety net, without needing to know every subclass.</li>
 *   <li>Any layer can declare {@code throws AutomationEngineException} in a method
 *       signature to communicate "something domain-level went wrong" without
 *       coupling callers to a specific subtype.</li>
 * </ol>
 *
 * <p><b>Why {@code abstract}?</b><br>
 * This class should never be thrown directly — it has no meaning on its own.
 * Forcing it to be abstract ensures developers always throw a specific, meaningful
 * subclass (e.g. {@link ContractParseException}) instead of the vague root.
 *
 * <p><b>Why extend {@code RuntimeException} (unchecked)?</b><br>
 * Checked exceptions (those that extend {@code Exception}) force every caller up
 * the call stack to either catch or declare them. In a layered architecture, that
 * creates noise in every service, use case, and controller method signature.
 * Unchecked exceptions let the {@code GlobalExceptionHandler} handle them at the
 * boundary, keeping intermediate layers clean.
 *
 * <p><b>Architecture note:</b><br>
 * This class belongs to the {@code domain} layer — the innermost layer of the
 * architecture. It has NO imports from Spring, infrastructure, or any other
 * layer. It is pure Java. This keeps the domain fully portable and independently
 * testable.
 */
public abstract class AutomationEngineException extends RuntimeException {

    /**
     * Constructs a new exception with a descriptive message.
     *
     * <p>Use this constructor when you know what went wrong but there is no
     * underlying Java exception to attach (e.g. a business rule was violated).
     *
     * <p>Example:
     * <pre>
     * throw new ContractParseException("No paths found in the OpenAPI spec.");
     * </pre>
     *
     * @param message a clear, human-readable description of what went wrong;
     *                this message will appear in logs and may be surfaced to the API client
     */
    protected AutomationEngineException(String message) {
        super(message);
    }

    /**
     * Constructs a new exception with a descriptive message and the original cause.
     *
     * <p>Use this constructor when wrapping a lower-level exception (e.g. a library
     * threw an {@code IOException} or a parsing library threw its own exception).
     * Preserving the {@code cause} ensures the original stack trace is not lost,
     * which is critical for debugging in production.
     *
     * <p>Example:
     * <pre>
     * try {
     *     parser.parse(file);
     * } catch (IOException ex) {
     *     throw new ContractParseException("Failed to read the OpenAPI file.", ex);
     * }
     * </pre>
     *
     * @param message a clear, human-readable description of what went wrong
     * @param cause   the original exception that triggered this one;
     *                preserved in the stack trace for full diagnostic context
     */
    protected AutomationEngineException(String message, Throwable cause) {
        super(message, cause);
    }
}