package com.varna.automationengine.domain.exception;

/**
 * Thrown when a code generation template fails to render.
 *
 * <p><b>When is this thrown?</b><br>
 * This exception is raised by the template rendering infrastructure
 * (e.g. {@code FreemarkerTemplateRenderer}) and represents a
 * <em>server-side configuration error</em> — the user did nothing wrong.
 * Typical scenarios include:
 * <ul>
 *   <li>A {@code .ftl} or {@code .mustache} template file is missing from
 *       the classpath (deployment packaging error)</li>
 *   <li>A template contains a syntax error introduced during development</li>
 *   <li>A required template variable was not provided by the generation strategy
 *       (e.g. the template expects {@code ${baseUrl}} but it was never set)</li>
 *   <li>The template engine itself fails to initialise (misconfiguration)</li>
 * </ul>
 *
 * <p><b>How is this handled?</b><br>
 * The {@code ProjectGeneratorController} catches this exception and maps it to
 * an HTTP {@code 500 Internal Server Error} response. The user-facing message
 * is intentionally generic ("Failed to render project template") because the
 * root cause is an internal configuration problem, not something the caller
 * can fix. The full cause is logged server-side with the trace ID.
 *
 * <p><b>Architecture note:</b><br>
 * This exception is defined in the {@code domain} layer but thrown by the
 * {@code infrastructure} template adapter. This is correct — the domain defines
 * the contract of what can go wrong; infrastructure fulfils (or fails to fulfil)
 * that contract. No infrastructure types appear anywhere in this class.
 */
public class TemplateRenderException extends AutomationEngineException {

    /**
     * Constructs a new exception with a descriptive message.
     *
     * <p>Use this when the render failure can be described clearly without
     * an underlying cause — for example, a missing required model attribute
     * detected before the template engine is even invoked.
     *
     * <p>Example:
     * <pre>
     * if (templateModel.get("className") == null) {
     *     throw new TemplateRenderException(
     *         "Template model is missing required attribute 'className' " +
     *         "for template: TestClass.ftl"
     *     );
     * }
     * </pre>
     *
     * @param message a clear description of which template failed and why;
     *                include the template name where possible for easier debugging
     */
    public TemplateRenderException(String message) {
        super(message);
    }

    /**
     * Constructs a new exception wrapping a template engine exception.
     *
     * <p>Use this when the underlying template engine (Freemarker, Mustache, etc.)
     * throws its own exception during rendering. Wrapping it preserves the full
     * stack trace from the template engine while surfacing a clean domain exception
     * to the caller.
     *
     * <p>Example:
     * <pre>
     * try {
     *     template.process(model, writer);
     * } catch (TemplateException | IOException ex) {
     *     throw new TemplateRenderException(
     *         "Failed to render template 'TestClass.ftl': " + ex.getMessage(), ex
     *     );
     * }
     * </pre>
     *
     * @param message a clear description of which template failed and why
     * @param cause   the underlying template engine or I/O exception
     */
    public TemplateRenderException(String message, Throwable cause) {
        super(message, cause);
    }
}