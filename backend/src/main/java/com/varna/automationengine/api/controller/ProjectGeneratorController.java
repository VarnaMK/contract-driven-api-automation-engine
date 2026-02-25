package com.varna.automationengine.api.controller;

import com.varna.automationengine.api.dto.response.ErrorResponse;
import com.varna.automationengine.application.usecase.GenerateAutomationProjectUseCase;
import com.varna.automationengine.domain.exception.ContractParseException;
import com.varna.automationengine.domain.exception.ProjectGenerationException;
import com.varna.automationengine.domain.exception.TemplateRenderException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.UUID;

/**
 * REST Controller for the Contract-Driven API Automation Engine.
 *
 * <p>This controller exposes a single endpoint that:
 * <ol>
 *   <li>Accepts an OpenAPI specification file (YAML or JSON)</li>
 *   <li>Parses its endpoints and schemas</li>
 *   <li>Generates a REST Assured test framework project</li>
 *   <li>Returns the project as a downloadable ZIP file</li>
 * </ol>
 *
 * <p>This class is ONLY responsible for HTTP concerns:
 * <ul>
 *   <li>Accepting requests and reading HTTP inputs</li>
 *   <li>Calling the application layer (use case)</li>
 *   <li>Mapping results to HTTP responses</li>
 *   <li>Translating exceptions into meaningful HTTP error responses</li>
 * </ul>
 *
 * <p>No business logic lives here. The controller simply delegates
 * all work to {@link GenerateAutomationProjectUseCase}.
 */
@RestController

// All endpoints in this controller are prefixed with /api/v1/projects
// Versioning the API this way (/v1/) makes it easy to introduce /v2/ in the future
@RequestMapping("/api/v1/projects")
public class ProjectGeneratorController {

    // SLF4J Logger — the standard way to log in Spring Boot.
    // We pass the current class so logs show exactly which class wrote them.
    private static final Logger log = LoggerFactory.getLogger(ProjectGeneratorController.class);

    // Maximum allowed upload size: 10 MB.
    // Large OpenAPI specs are uncommon; this prevents abuse or accidental huge uploads.
    private static final long MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024; // 10 MB

    // Allowed file extensions for OpenAPI specs.
    // Both YAML and JSON are valid OpenAPI formats.
    private static final String[] ALLOWED_EXTENSIONS = {".yaml", ".yml", ".json"};

    // The use case that handles the full generate pipeline.
    // We inject this via the constructor (constructor injection is preferred over @Autowired on fields).
    private final GenerateAutomationProjectUseCase generateProjectUseCase;

    /**
     * Constructor injection — the recommended approach in Spring Boot.
     *
     * <p>Benefits over field injection (@Autowired):
     * <ul>
     *   <li>Makes dependencies explicit and visible</li>
     *   <li>Allows unit testing without a Spring context</li>
     *   <li>Prevents circular dependency issues at compile time</li>
     * </ul>
     *
     * @param generateProjectUseCase the application use case that orchestrates parsing and generation
     */
    public ProjectGeneratorController(GenerateAutomationProjectUseCase generateProjectUseCase) {
        this.generateProjectUseCase = generateProjectUseCase;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINTS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * POST /api/v1/projects
     *
     * <p>Accepts an OpenAPI specification file and returns a generated REST Assured
     * test framework project as a downloadable ZIP archive.
     *
     * <p><b>Request:</b>
     * <ul>
     *   <li>Content-Type: multipart/form-data</li>
     *   <li>Form field name: {@code contractFile}</li>
     *   <li>Accepted file types: .yaml, .yml, .json</li>
     *   <li>Max file size: 10 MB</li>
     * </ul>
     *
     * <p><b>Response:</b>
     * <ul>
     *   <li>200 OK — ZIP file download (application/zip)</li>
     *   <li>400 Bad Request — file is empty, wrong type, or too large</li>
     *   <li>422 Unprocessable Entity — file is valid but cannot be parsed as OpenAPI</li>
     *   <li>500 Internal Server Error — unexpected failure during generation</li>
     * </ul>
     *
     * @param contractFile the uploaded OpenAPI specification file
     * @param request      the raw HTTP request (used to extract request URI for logging)
     * @return a ResponseEntity containing the ZIP bytes, or an error response
     */
    @PostMapping(
        // This endpoint consumes multipart form data (file uploads use this content type)
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE,

        // This endpoint produces a ZIP binary stream for download
        produces = MediaType.APPLICATION_OCTET_STREAM_VALUE
    )
    public ResponseEntity<byte[]> generateProject(
            // @RequestPart maps to a field in the multipart form.
            // "contractFile" is the name the client must use in their form-data body.
            @RequestPart("contractFile") MultipartFile contractFile,

            // We inject HttpServletRequest to extract the URI for logging purposes.
            HttpServletRequest request) {

        // Generate a unique trace ID for this request.
        // This allows you to search logs for all entries related to one specific request.
        String traceId = UUID.randomUUID().toString();

        log.info("[traceId={}] Received project generation request | URI={} | filename={} | size={} bytes",
                traceId,
                request.getRequestURI(),
                contractFile.getOriginalFilename(),
                contractFile.getSize());

        // ── Step 1: Validate the uploaded file before doing any expensive work ──
        // We fail fast here. If the file is obviously wrong, we don't waste time
        // trying to parse or generate anything.
        ResponseEntity<byte[]> validationError = validateUploadedFile(contractFile, traceId);
        if (validationError != null) {
            // Validation failed — return the error response immediately
            return validationError;
        }

        // ── Step 2: Delegate to the application use case ──
        // The controller's job is done after validation.
        // All business logic (parsing, generating, zipping) belongs in the use case.
        try {
            log.info("[traceId={}] Validation passed. Starting project generation pipeline.", traceId);

            byte[] zipBytes = generateProjectUseCase.execute(contractFile, traceId);

            log.info("[traceId={}] Project generation complete. ZIP size={} bytes", traceId, zipBytes.length);

            // ── Step 3: Build and return the HTTP response with the ZIP ──
            return buildZipDownloadResponse(zipBytes, contractFile.getOriginalFilename());

        } catch (ContractParseException ex) {
            // The file was structurally valid (correct extension, not empty)
            // but did not contain a valid OpenAPI specification.
            log.warn("[traceId={}] Contract parsing failed: {}", traceId, ex.getMessage());

            // 422 Unprocessable Entity — the file was received but could not be processed
            return ResponseEntity
                    .unprocessableEntity()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(toJsonError("PARSE_ERROR", ex.getMessage(), traceId));

        } catch (TemplateRenderException ex) {
            // A template failed to render. This is an internal configuration error,
            // not a client error — the client did everything right.
            log.error("[traceId={}] Template rendering failed: {}", traceId, ex.getMessage(), ex);

            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(toJsonError("TEMPLATE_ERROR", "Failed to render project template.", traceId));

        } catch (ProjectGenerationException ex) {
            // Something went wrong during project assembly or zip creation.
            log.error("[traceId={}] Project generation failed: {}", traceId, ex.getMessage(), ex);

            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(toJsonError("GENERATION_ERROR", "Failed to generate project. Please try again.", traceId));

        } catch (Exception ex) {
            // Catch-all for any unexpected exceptions we did not anticipate.
            // We log the full stack trace here because this is truly unexpected.
            log.error("[traceId={}] Unexpected error during project generation", traceId, ex);

            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(toJsonError("INTERNAL_ERROR", "An unexpected error occurred.", traceId));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Validates the uploaded file before any processing begins.
     *
     * <p>Checks performed:
     * <ol>
     *   <li>File must not be null or empty (zero bytes)</li>
     *   <li>File extension must be .yaml, .yml, or .json</li>
     *   <li>File size must not exceed {@link #MAX_FILE_SIZE_BYTES}</li>
     * </ol>
     *
     * @param file    the file to validate
     * @param traceId the current request trace ID for logging
     * @return a 400 ResponseEntity if validation fails, or {@code null} if validation passes
     */
    private ResponseEntity<byte[]> validateUploadedFile(MultipartFile file, String traceId) {

        // Check 1: File must not be null or empty
        if (file == null || file.isEmpty()) {
            log.warn("[traceId={}] Validation failed: uploaded file is empty or null", traceId);
            return ResponseEntity
                    .badRequest()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(toJsonError("INVALID_FILE", "Uploaded file is empty. Please provide a valid OpenAPI spec.", traceId));
        }

        // Check 2: File extension must be .yaml, .yml, or .json
        String filename = file.getOriginalFilename();
        if (!hasAllowedExtension(filename)) {
            log.warn("[traceId={}] Validation failed: unsupported file type | filename={}", traceId, filename);
            return ResponseEntity
                    .badRequest()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(toJsonError("INVALID_FILE_TYPE",
                            "Unsupported file type. Please upload a .yaml, .yml, or .json OpenAPI specification.",
                            traceId));
        }

        // Check 3: File size must be within the allowed limit
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            log.warn("[traceId={}] Validation failed: file too large | size={} bytes | max={} bytes",
                    traceId, file.getSize(), MAX_FILE_SIZE_BYTES);
            return ResponseEntity
                    .badRequest()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(toJsonError("FILE_TOO_LARGE",
                            "File exceeds maximum allowed size of 10 MB.",
                            traceId));
        }

        // All checks passed
        return null;
    }

    /**
     * Checks if the filename has one of the allowed extensions.
     *
     * @param filename the original filename from the upload
     * @return {@code true} if the extension is allowed, {@code false} otherwise
     */
    private boolean hasAllowedExtension(String filename) {
        if (filename == null || filename.isBlank()) {
            return false;
        }
        // Convert to lowercase so ".YAML" and ".yaml" both match
        String lowerFilename = filename.toLowerCase();
        for (String ext : ALLOWED_EXTENSIONS) {
            if (lowerFilename.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builds a successful HTTP response containing the ZIP file bytes.
     *
     * <p>Sets the appropriate headers so browsers and HTTP clients treat
     * the response as a file download rather than inline content.
     *
     * @param zipBytes         the raw ZIP binary data
     * @param originalFilename the original uploaded filename (used to derive the download name)
     * @return a 200 OK ResponseEntity with ZIP content and download headers
     */
    private ResponseEntity<byte[]> buildZipDownloadResponse(byte[] zipBytes, String originalFilename) {

        // Derive the output ZIP filename from the uploaded spec filename.
        // e.g. "petstore.yaml" → "petstore-automation-tests.zip"
        String baseName = stripExtension(originalFilename);
        String zipFilename = baseName + "-automation-tests.zip";

        // Content-Disposition: attachment tells the browser/client to download the file,
        // not try to display it inline.
        String contentDisposition = "attachment; filename=\"" + zipFilename + "\"";

        return ResponseEntity
                .ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
                .header(HttpHeaders.CONTENT_TYPE, "application/zip")
                // Tell the client exactly how many bytes to expect
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(zipBytes.length))
                .body(zipBytes);
    }

    /**
     * Removes the file extension from a filename.
     *
     * <p>Examples:
     * <ul>
     *   <li>"petstore.yaml" → "petstore"</li>
     *   <li>"my-api.v2.json" → "my-api.v2"</li>
     *   <li>"noextension" → "noextension"</li>
     * </ul>
     *
     * @param filename the original filename
     * @return the filename without its extension, or "generated-project" as a safe fallback
     */
    private String stripExtension(String filename) {
        if (filename == null || filename.isBlank()) {
            return "generated-project";
        }
        int dotIndex = filename.lastIndexOf('.');
        // If no dot found, return the filename as-is
        return dotIndex > 0 ? filename.substring(0, dotIndex) : filename;
    }

    /**
     * Serializes an error into a minimal JSON byte array.
     *
     * <p>We build JSON manually here to avoid adding a dependency on Jackson
     * just for this controller helper. In a real application, you would return
     * a proper {@link ErrorResponse} DTO and let Jackson serialize it automatically.
     *
     * <p>Example output:
     * <pre>
     * {
     *   "code": "PARSE_ERROR",
     *   "message": "Unable to parse OpenAPI spec.",
     *   "traceId": "550e8400-e29b-41d4-a716-446655440000",
     *   "timestamp": "2025-01-01T12:00:00Z"
     * }
     * </pre>
     *
     * @param code     a machine-readable error code (e.g. "PARSE_ERROR")
     * @param message  a human-readable description of what went wrong
     * @param traceId  the unique request trace ID for log correlation
     * @return UTF-8 encoded JSON bytes
     */
    private byte[] toJsonError(String code, String message, String traceId) {
        String json = """
                {
                  "code": "%s",
                  "message": "%s",
                  "traceId": "%s",
                  "timestamp": "%s"
                }
                """.formatted(code, message, traceId, Instant.now().toString());
        return json.getBytes();
    }
}