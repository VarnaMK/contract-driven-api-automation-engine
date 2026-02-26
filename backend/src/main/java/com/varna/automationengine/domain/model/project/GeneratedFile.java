package com.varna.automationengine.domain.model.project;

/**
 * Domain model representing a single in-memory source file to be written into the ZIP.
 *
 * <p>Every file the generator produces — a POJO class, a test class, a pom.xml,
 * a testng.xml — is captured as a {@code GeneratedFile} before anything is written
 * to disk or a ZIP stream. This allows the entire project to be assembled in memory
 * and inspected or tested before archiving.
 *
 * <p><b>What it holds:</b>
 * <ul>
 *   <li>{@code relativePath} — where the file should live inside the ZIP archive,
 *       preserving the Maven project structure. Example:
 *       {@code src/main/java/com/example/model/User.java}</li>
 *   <li>{@code content} — the full text content of the file, already rendered
 *       by the template engine. This is what gets written verbatim into the ZIP.</li>
 * </ul>
 *
 * <p><b>Invariants enforced at construction:</b>
 * <ul>
 *   <li>{@code relativePath} must not be null or blank</li>
 *   <li>{@code content} must not be null (may be empty for placeholder files)</li>
 * </ul>
 *
 * <p>Like all domain models in this engine, this class is immutable after construction.
 */
public final class GeneratedFile {

    /**
     * The relative path of this file within the ZIP archive.
     *
     * <p>Uses forward slashes as path separators regardless of the host OS,
     * because ZIP entries always use forward slashes per the ZIP specification.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code pom.xml}</li>
     *   <li>{@code testng.xml}</li>
     *   <li>{@code src/test/java/com/example/tests/UserApiTest.java}</li>
     *   <li>{@code src/test/java/com/example/model/User.java}</li>
     *   <li>{@code src/test/java/com/example/base/BaseTest.java}</li>
     * </ul>
     */
    private final String relativePath;

    /**
     * The full rendered text content of this file.
     *
     * <p>This is the final output from the template engine — a complete, valid
     * Java source file, XML document, or other text file ready to be written.
     */
    private final String content;

    /**
     * Constructs a new {@code GeneratedFile} with a relative path and content.
     *
     * @param relativePath the ZIP-relative path for this file; must not be null or blank
     * @param content      the full text content of the file; must not be null
     * @throws IllegalArgumentException if {@code relativePath} is null or blank,
     *                                  or if {@code content} is null
     */
    public GeneratedFile(String relativePath, String content) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException(
                    "GeneratedFile relativePath must not be null or blank. " +
                    "Ensure the generator strategy provides a valid file path."
            );
        }
        if (content == null) {
            throw new IllegalArgumentException(
                    "GeneratedFile content must not be null for path: " + relativePath
            );
        }
        this.relativePath = relativePath;
        this.content      = content;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GETTERS — no setters, this object is immutable
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * @return the ZIP-relative path of this file, using forward slashes
     */
    public String getRelativePath() {
        return relativePath;
    }

    /**
     * @return the fully rendered text content of this file
     */
    public String getContent() {
        return content;
    }

    @Override
    public String toString() {
        return "GeneratedFile{" +
                "relativePath='" + relativePath + '\'' +
                ", contentLength=" + content.length() +
                '}';
    }
}