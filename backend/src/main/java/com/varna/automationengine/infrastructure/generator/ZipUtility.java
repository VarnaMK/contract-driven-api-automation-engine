package com.varna.automationengine.infrastructure.generator;

import com.varna.automationengine.domain.exception.ProjectGenerationException;
import com.varna.automationengine.domain.model.project.GeneratedFile;
import com.varna.automationengine.domain.model.project.GeneratedProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Utility responsible for packaging a {@link GeneratedProject} into a ZIP archive
 * and returning it as a raw {@code byte[]}.
 *
 * <p><b>What this class does:</b>
 * <ol>
 *   <li>Creates an in-memory {@link ByteArrayOutputStream} — no temp files on disk</li>
 *   <li>Wraps it in a {@link ZipOutputStream}</li>
 *   <li>Iterates every {@link GeneratedFile} in the {@link GeneratedProject}</li>
 *   <li>Writes each file as a {@link ZipEntry} using its relative path as the entry name,
 *       prefixed with the project name to create a root folder inside the ZIP</li>
 *   <li>Returns the completed ZIP as a {@code byte[]}</li>
 * </ol>
 *
 * <p><b>ZIP structure produced (example for Petstore API):</b>
 * <pre>
 * petstore-api-tests/
 * ├── pom.xml
 * ├── testng.xml
 * └── src/
 *     └── test/
 *         └── java/
 *             └── com/example/
 *                 ├── base/
 *                 │   └── BaseTest.java
 *                 ├── model/
 *                 │   ├── User.java
 *                 │   └── Order.java
 *                 └── tests/
 *                     └── UserApiTest.java
 * </pre>
 *
 * <p><b>Why in-memory (no temp files)?</b><br>
 * Writing to a {@link ByteArrayOutputStream} avoids disk I/O entirely, which is
 * faster and eliminates the need to clean up temp files. For typical OpenAPI specs
 * (10–200 endpoints), the generated ZIP will be well under 1 MB — easily
 * manageable in heap memory.
 *
 * <p><b>Encoding:</b><br>
 * All file content is encoded as UTF-8 before writing to the ZIP stream. This
 * matches how the template engine produces the content strings and ensures the
 * generated Java files open correctly in any editor or IDE.
 */
@Component
public class ZipUtility {

    private static final Logger log = LoggerFactory.getLogger(ZipUtility.class);

    /**
     * The character encoding used when converting file content strings to bytes.
     * UTF-8 is the standard for Java source files and XML/configuration files.
     */
    private static final String FILE_ENCODING = StandardCharsets.UTF_8.name();

    /**
     * No-arg constructor — this utility has no external dependencies.
     *
     * <p>All ZIP operations use the standard {@code java.util.zip} package,
     * which is part of the Java SE standard library (no additional Maven dependency needed).
     */
    public ZipUtility() {
        // No dependencies — uses only java.util.zip from the JDK standard library
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRIMARY API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Packages all files in the given {@link GeneratedProject} into a ZIP archive
     * and returns the archive as a raw {@code byte[]}.
     *
     * <p><b>Entry naming convention:</b><br>
     * Each ZIP entry is named as {@code {projectName}/{relativePath}}. For example,
     * a file with relativePath {@code src/test/java/com/example/model/User.java}
     * in a project named {@code petstore-api-tests} becomes the ZIP entry:
     * {@code petstore-api-tests/src/test/java/com/example/model/User.java}.
     * This ensures that unzipping the archive produces a single named root folder,
     * not a flat pile of files.
     *
     * @param project the generated project containing all files to archive; must not be null
     * @param traceId the request-scoped trace ID for log correlation
     * @return the complete ZIP archive as a {@code byte[]}; never null or empty
     * @throws ProjectGenerationException if writing any entry fails or the stream cannot be closed
     */
    public byte[] zip(GeneratedProject project, String traceId) {
        log.info("[traceId={}] Starting ZIP packaging | project='{}' | fileCount={}",
                traceId, project.getProjectName(), project.getFileCount());

        // ByteArrayOutputStream holds the entire ZIP in memory.
        // The initial capacity of 64KB is a reasonable starting point for most projects;
        // the buffer grows automatically if the content exceeds it.
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream(64 * 1024);

        // ZipOutputStream wraps the byte stream and handles ZIP format encoding,
        // compression, CRC checksums, and directory entries automatically.
        try (ZipOutputStream zipStream = new ZipOutputStream(byteStream)) {

            // Set the compression level. DEFLATED (the default) gives good size reduction
            // for text files like Java source code. If speed is more important than size,
            // this could be changed to ZipOutputStream.STORED (no compression).
            zipStream.setMethod(ZipOutputStream.DEFLATED);

            // Iterate every GeneratedFile and write it as a ZIP entry
            for (GeneratedFile file : project.getFiles()) {
                writeFileToZip(zipStream, project.getProjectName(), file, traceId);
            }

            // ZipOutputStream.close() (called by try-with-resources above) writes the
            // ZIP central directory (the index at the end of the file) and finalises the stream.
            // After close(), the ByteArrayOutputStream contains the complete, valid ZIP.
        } catch (IOException ex) {
            log.error("[traceId={}] Failed to create ZIP archive for project '{}'",
                    traceId, project.getProjectName(), ex);
            throw new ProjectGenerationException(
                    "Failed to create ZIP archive for project '" + project.getProjectName() + "': "
                    + ex.getMessage(),
                    ex
            );
        }

        byte[] zipBytes = byteStream.toByteArray();

        log.info("[traceId={}] ZIP packaging complete | project='{}' | zipSize={} bytes",
                traceId, project.getProjectName(), zipBytes.length);

        return zipBytes;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Writes a single {@link GeneratedFile} into the {@link ZipOutputStream} as a new entry.
     *
     * <p><b>ZIP entry structure:</b>
     * <ol>
     *   <li>Create a {@link ZipEntry} with the full entry name (project name + relative path)</li>
     *   <li>Call {@link ZipOutputStream#putNextEntry(ZipEntry)} to begin the entry</li>
     *   <li>Write the file content bytes (UTF-8 encoded) to the stream</li>
     *   <li>Call {@link ZipOutputStream#closeEntry()} to finalise the entry</li>
     * </ol>
     *
     * <p>Entries are not explicitly compressed here — compression is handled
     * at the stream level by the {@link ZipOutputStream} based on the compression
     * method set in {@link #zip(GeneratedProject, String)}.
     *
     * @param zipStream   the open ZIP stream to write into
     * @param projectName the root folder name to prefix to every entry path
     * @param file        the generated file to write
     * @param traceId     the request trace ID for logging
     * @throws IOException if writing the entry bytes or closing the entry fails
     */
    private void writeFileToZip(ZipOutputStream zipStream,
                                String projectName,
                                GeneratedFile file,
                                String traceId) throws IOException {

        // Build the full entry name: "projectName/relativePath"
        // ZIP entry names always use forward slashes (/ not \), regardless of OS.
        String entryName = projectName + "/" + file.getRelativePath();

        log.debug("[traceId={}] Writing ZIP entry: {}", traceId, entryName);

        // ZipEntry represents a single file (or directory) inside the ZIP archive.
        ZipEntry entry = new ZipEntry(entryName);
        zipStream.putNextEntry(entry);

        // Convert the file's String content to UTF-8 bytes and write them to the stream.
        // getBytes(UTF_8) is safe for all Java source files and XML/configuration files.
        byte[] contentBytes = file.getContent().getBytes(StandardCharsets.UTF_8);
        zipStream.write(contentBytes);

        // closeEntry() signals that all bytes for this entry have been written.
        // The ZIP stream finalises the entry header with the correct compressed size.
        zipStream.closeEntry();

        log.debug("[traceId={}] Wrote ZIP entry '{}' | size={} bytes",
                traceId, entryName, contentBytes.length);
    }
}