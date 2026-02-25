package com.varna.automationengine.domain.exception;

/**
 * Thrown when the overall project generation or packaging pipeline fails.
 *
 * <p><b>When is this thrown?</b><br>
 * This exception covers failures that occur <em>after</em> parsing and
 * <em>after</em> individual template rendering — specifically in the
 * assembly and packaging stages. Like {@link TemplateRenderException},
 * this represents a <em>server-side error</em>, not a client error.
 * Typical scenarios include:
 * <ul>
 *   <li>The {@code ZipArchiveAdapter} fails to write the ZIP stream
 *       (e.g. disk full, I/O error)</li>
 *   <li>The {@code ProjectAssembler} receives an empty list of generated files
 *       (no strategies produced output — indicates a configuration gap)</li>
 *   <li>A {@link GeneratedFile} cannot be written into the ZIP archive
 *       (e.g. invalid file path characters in the resolved filename)</li>
 *   <li>An unexpected error occurs during project structure assembly that is
 *       not covered by {@link TemplateRenderException}</li>
 * </ul>
 *
 * <p><b>How is this handled?</b><br>
 * The {@code ProjectGeneratorController} catches this exception and maps it to
 * an HTTP {@code 500 Internal Server Error} response with a safe, generic message.
 * The detailed cause is always logged server-side with the request trace ID so
 * engineers can diagnose the failure without exposing internal details to callers.
 *
 * <p><b>Distinction from {@link TemplateRenderException}:</b><br>
 * {@link TemplateRenderException} is specific to a single template failing to
 * render. {@code ProjectGenerationException} is broader — it represents failure
 * at the level of the project as a whole: assembling files, creating the ZIP,
 * or any cross-cutting generation concern. If in doubt, use the more specific
 * exception when you can.
 *
 * <p><b>Architecture note:</b><br>
 * Defined in the {@code domain} layer; thrown by {@code infrastructure} (archiver,
 * assembler) and {@code generator} layer components. The domain owns the vocabulary
 * of failure; infrastructure and generator layers speak that vocabulary when
 * things go wrong.
 */
public class ProjectGenerationException extends AutomationEngineException {

    /**
     * Constructs a new exception with a descriptive message.
     *
     * <p>Use this when the generation failure can be described without an
     * underlying Java exception — for example, a business rule check that
     * detects no files were produced before attempting to create the ZIP.
     *
     * <p>Example:
     * <pre>
     * if (generatedFiles.isEmpty()) {
     *     throw new ProjectGenerationException(
     *         "Project assembly produced zero files. " +
     *         "Check that at least one GenerationStrategy is registered."
     *     );
     * }
     * </pre>
     *
     * @param message a clear description of what stage of generation failed and why;
     *                include the component name (e.g. "ZipArchiveAdapter") where helpful
     */
    public ProjectGenerationException(String message) {
        super(message);
    }

    /**
     * Constructs a new exception wrapping a lower-level I/O or system exception.
     *
     * <p>Use this when infrastructure components (archiver, file writer) throw
     * Java I/O or system exceptions. Wrapping preserves the original stack trace
     * for diagnostics while surfacing a clean domain concept to the controller.
     *
     * <p>Example:
     * <pre>
     * try {
     *     zipOutputStream.putNextEntry(new ZipEntry(file.getRelativePath()));
     *     zipOutputStream.write(file.getContent().getBytes());
     * } catch (IOException ex) {
     *     throw new ProjectGenerationException(
     *         "Failed to write file '" + file.getRelativePath() + "' into ZIP archive.", ex
     *     );
     * }
     * </pre>
     *
     * @param message a clear description of what stage of generation failed and why
     * @param cause   the underlying I/O, system, or library exception
     */
    public ProjectGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}