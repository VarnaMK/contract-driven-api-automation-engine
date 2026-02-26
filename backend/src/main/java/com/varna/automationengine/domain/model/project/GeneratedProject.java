package com.varna.automationengine.domain.model.project;

import java.util.Collections;
import java.util.List;

/**
 * Domain model representing the complete generated REST Assured test project.
 *
 * <p>This is the output aggregate of the generator layer. It holds every file
 * that will be written into the downloadable ZIP — all Java source files, pom.xml,
 * testng.xml, and any other project files — as a flat list of {@link GeneratedFile}
 * objects, each carrying its own relative path and content.
 *
 * <p><b>Pipeline position:</b>
 * <pre>
 *   ApiContract
 *       ↓  consumed by RestAssuredProjectGenerator
 *   GeneratedProject  ← THIS CLASS (hand-off between generator and ZipUtility)
 *       ↓  consumed by ZipUtility
 *   byte[] ZIP
 * </pre>
 *
 * <p><b>Invariant:</b> A {@code GeneratedProject} must contain at least one file.
 * An empty project is considered a generation failure and triggers a
 * {@code ProjectGenerationException} at construction time.
 */
public final class GeneratedProject {

    /**
     * The human-readable name of this generated project.
     *
     * <p>Derived from the API title in the {@code ApiContract}. Used as the
     * root folder name inside the ZIP archive, so when a user unzips the download,
     * they get a folder with a meaningful name rather than just bare files.
     *
     * <p>Example: {@code "pet-store-api-tests"}
     */
    private final String projectName;

    /**
     * All files that make up the generated project.
     *
     * <p>Each {@link GeneratedFile} carries its own relative path
     * (e.g. {@code src/test/java/com/example/tests/UserApiTest.java})
     * and rendered content. The {@link ZipUtility} iterates this list to build
     * the ZIP archive, preserving each file's relative path as the ZIP entry name.
     */
    private final List<GeneratedFile> files;

    /**
     * Constructs a {@code GeneratedProject} with a name and list of files.
     *
     * @param projectName the root project folder name; must not be null or blank
     * @param files       all generated files; must not be null or empty
     * @throws IllegalArgumentException if {@code projectName} is blank or {@code files} is empty
     */
    public GeneratedProject(String projectName, List<GeneratedFile> files) {
        if (projectName == null || projectName.isBlank()) {
            throw new IllegalArgumentException(
                    "GeneratedProject projectName must not be null or blank."
            );
        }
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException(
                    "GeneratedProject must contain at least one GeneratedFile. " +
                    "Check that at least one generation strategy produced output."
            );
        }
        this.projectName = projectName;
        // Defensive copy — callers cannot modify the list after construction
        this.files = Collections.unmodifiableList(files);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GETTERS — no setters, this object is immutable
    // ─────────────────────────────────────────────────────────────────────────

    /** @return the root project folder name used inside the ZIP */
    public String getProjectName() {
        return projectName;
    }

    /** @return an unmodifiable list of all generated files; never null or empty */
    public List<GeneratedFile> getFiles() {
        return files;
    }

    /** @return the total number of files in this project */
    public int getFileCount() {
        return files.size();
    }

    @Override
    public String toString() {
        return "GeneratedProject{" +
                "projectName='" + projectName + '\'' +
                ", fileCount=" + files.size() +
                '}';
    }
}