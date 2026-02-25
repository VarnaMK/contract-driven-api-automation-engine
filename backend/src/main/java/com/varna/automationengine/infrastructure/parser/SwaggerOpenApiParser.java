package com.varna.automationengine.infrastructure.parser;

import com.varna.automationengine.domain.exception.ContractParseException;
import com.varna.automationengine.domain.model.contract.ApiContract;
import com.varna.automationengine.domain.model.contract.ApiEndpoint;
import com.varna.automationengine.domain.model.contract.ApiParameter;
import com.varna.automationengine.domain.model.contract.ApiSchema;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Production implementation of {@link OpenApiParser} using the Swagger Parser library
 * to parse OpenAPI v3 specification files (YAML or JSON).
 *
 * <p><b>Library used:</b><br>
 * {@code io.swagger.parser.v3:swagger-parser} — the official, battle-tested OpenAPI
 * parser maintained by the Swagger/OpenAPI Initiative. Add this to your {@code pom.xml}:
 * <pre>
 * &lt;dependency&gt;
 *     &lt;groupId&gt;io.swagger.parser.v3&lt;/groupId&gt;
 *     &lt;artifactId&gt;swagger-parser&lt;/artifactId&gt;
 *     &lt;version&gt;2.1.22&lt;/version&gt;
 * &lt;/dependency&gt;
 * </pre>
 *
 * <p><b>Responsibility of this class:</b><br>
 * This class is an <em>adapter</em> — it translates between the external world
 * (a Swagger Parser library result) and the internal domain (an {@link ApiContract}).
 * It is the only class in the entire project that imports Swagger library types.
 * All other classes work with the domain model only.
 *
 * <p><b>The {@code @Component} annotation:</b><br>
 * Like {@code @Service}, this tells Spring to auto-detect and register this class
 * as a bean. Because it implements {@link OpenApiParser}, Spring will inject it
 * wherever that interface is declared as a dependency.
 *
 * <p><b>What this class does NOT do:</b>
 * <ul>
 *   <li>It does not validate file extensions or size — the controller does that</li>
 *   <li>It does not handle Swagger 2.x specs — those require a separate adapter</li>
 *   <li>It does not generate any code — that is the generator layer's responsibility</li>
 * </ul>
 */
@Component
public class SwaggerOpenApiParser implements OpenApiParser {

    private static final Logger log = LoggerFactory.getLogger(SwaggerOpenApiParser.class);

    // The MIME type we expect request bodies to use.
    // REST APIs almost universally use JSON. We prefer this type when
    // extracting request/response schemas from the spec.
    private static final String PREFERRED_MEDIA_TYPE = "application/json";

    // ─────────────────────────────────────────────────────────────────────────
    // CONSTRUCTOR
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * No-arg constructor — this class has no dependencies to inject.
     *
     * <p>The Swagger Parser library's {@link OpenAPIV3Parser} is instantiated
     * per-call in the {@link #parse} method rather than as a field, because the
     * library's parser is lightweight and stateless. If performance profiling
     * later shows constructor overhead is significant, it can be promoted to a
     * field safely.
     */
    public SwaggerOpenApiParser() {
        // No dependencies required — Swagger Parser is a self-contained library
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRIMARY ENTRY POINT
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p><b>Implementation overview:</b>
     * <ol>
     *   <li>Extract raw file content as a UTF-8 string from the MultipartFile</li>
     *   <li>Feed it to the Swagger Parser with {@code resolve=true} so {@code $ref}
     *       references are automatically followed and inlined</li>
     *   <li>Validate the parse result — check for errors and required sections</li>
     *   <li>Extract API metadata (title, version, base URL) from the spec info</li>
     *   <li>Walk every path and operation to build {@link ApiEndpoint} objects</li>
     *   <li>Walk the {@code components/schemas} section to build {@link ApiSchema} objects</li>
     *   <li>Assemble and return the final {@link ApiContract}</li>
     * </ol>
     */
    @Override
    public ApiContract parse(MultipartFile contractFile, String traceId) {

        log.info("[traceId={}] Starting OpenAPI parsing | filename={}",
                traceId, contractFile.getOriginalFilename());

        // ── Step 1: Read the raw file content ────────────────────────────────
        String specContent = readFileContent(contractFile, traceId);

        // ── Step 2: Parse the content using the Swagger Parser library ────────
        OpenAPI openApi = parseWithSwaggerLibrary(specContent, traceId);

        // ── Step 3: Validate the parsed result has what we need ──────────────
        validateOpenApiObject(openApi, traceId);

        // ── Step 4: Extract metadata ──────────────────────────────────────────
        String title       = extractTitle(openApi);
        String version     = extractVersion(openApi);
        String description = extractDescription(openApi);
        String baseUrl     = extractBaseUrl(openApi);

        log.info("[traceId={}] API metadata extracted | title='{}' | version='{}' | baseUrl='{}'",
                traceId, title, version, baseUrl);

        // ── Step 5: Extract all endpoints from the paths section ──────────────
        List<ApiEndpoint> endpoints = extractEndpoints(openApi, traceId);

        log.info("[traceId={}] Extracted {} endpoint(s)", traceId, endpoints.size());

        // ── Step 6: Extract all reusable schemas from components/schemas ───────
        Map<String, ApiSchema> schemas = extractComponentSchemas(openApi, traceId);

        log.info("[traceId={}] Extracted {} component schema(s)", traceId, schemas.size());

        // ── Step 7: Assemble and return the final ApiContract ─────────────────
        ApiContract contract = new ApiContract(title, version, description, baseUrl, endpoints, schemas);

        log.info("[traceId={}] Parsing complete | {}", traceId, contract);

        return contract;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STEP 1 — Read file content
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Reads the raw text content of the uploaded file as a UTF-8 string.
     *
     * <p>We read the file as a string (not a byte array) because the Swagger Parser
     * library's {@code readContents()} method accepts a string. Both YAML and JSON
     * are text-based formats, so UTF-8 encoding is always correct here.
     *
     * @param contractFile the uploaded multipart file
     * @param traceId      the request trace ID for logging
     * @return the full spec content as a UTF-8 string
     * @throws ContractParseException if the file bytes cannot be read
     */
    private String readFileContent(MultipartFile contractFile, String traceId) {
        try {
            byte[] bytes = contractFile.getBytes();
            String content = new String(bytes, StandardCharsets.UTF_8);
            log.debug("[traceId={}] File content read successfully | size={} chars", traceId, content.length());
            return content;
        } catch (Exception ex) {
            log.error("[traceId={}] Failed to read file bytes from MultipartFile", traceId, ex);
            throw new ContractParseException(
                    "Unable to read the uploaded file. The file may be corrupted or the upload incomplete.",
                    ex
            );
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STEP 2 — Parse with Swagger library
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Feeds the raw spec content to the Swagger Parser library and returns the
     * parsed {@link OpenAPI} object.
     *
     * <p><b>ParseOptions explained:</b><br>
     * {@code setResolve(true)} tells the parser to automatically follow all
     * {@code $ref} references (e.g. {@code $ref: '#/components/schemas/User'}) and
     * inline the referenced schema. Without this, you would receive unresolved
     * references that would need to be looked up manually.
     *
     * <p>{@code setResolveFully(true)} goes further — it resolves nested references
     * recursively, so every schema you access is fully populated without any
     * {@code $ref} strings remaining.
     *
     * @param specContent the raw YAML or JSON spec content
     * @param traceId     the request trace ID for logging
     * @return a fully parsed {@link OpenAPI} object from the Swagger library
     * @throws ContractParseException if the parser reports errors or returns null
     */
    private OpenAPI parseWithSwaggerLibrary(String specContent, String traceId) {
        try {
            // Configure parser options
            ParseOptions options = new ParseOptions();
            options.setResolve(true);       // follow $ref references
            options.setResolveFully(true);  // resolve nested $refs recursively

            // Parse the content — null is passed as the URL because we have the
            // content directly as a string (not loading from a URL or file path)
            SwaggerParseResult result = new OpenAPIV3Parser().readContents(specContent, null, options);

            // The parser always returns a result object — never throws an exception.
            // We must inspect the result to detect failures.
            if (result == null) {
                throw new ContractParseException(
                        "The OpenAPI parser returned a null result. " +
                        "The file may not be a valid OpenAPI specification."
                );
            }

            // The parser collects all warnings and errors in a messages list.
            // Log them all so they appear in the trace for diagnostics.
            if (result.getMessages() != null && !result.getMessages().isEmpty()) {
                result.getMessages().forEach(message ->
                        log.warn("[traceId={}] Swagger parser message: {}", traceId, message)
                );
            }

            // getOpenAPI() returns null when the content is not a recognisable OpenAPI spec
            if (result.getOpenAPI() == null) {
                // Collect parser messages into a readable string for the error response
                String parserErrors = result.getMessages() != null
                        ? String.join("; ", result.getMessages())
                        : "no details available";
                log.error("[traceId={}] Swagger parser could not parse spec | errors: {}", traceId, parserErrors);
                throw new ContractParseException(
                        "The uploaded file is not a valid OpenAPI 3.x specification. " +
                        "Parser details: " + parserErrors
                );
            }

            log.debug("[traceId={}] Swagger parser successfully parsed the OpenAPI object", traceId);
            return result.getOpenAPI();

        } catch (ContractParseException ex) {
            // Re-throw our own domain exception directly — don't wrap it again
            throw ex;
        } catch (Exception ex) {
            // Catch any unexpected exception from the Swagger library
            log.error("[traceId={}] Unexpected error from Swagger parser library", traceId, ex);
            throw new ContractParseException(
                    "An unexpected error occurred while parsing the OpenAPI specification. " +
                    "Ensure the file is a valid YAML or JSON OpenAPI 3.x document.",
                    ex
            );
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STEP 3 — Validate the parsed result
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Validates that the parsed {@link OpenAPI} object contains the required
     * sections needed to generate a test project.
     *
     * <p>A spec must have at least one path defined — otherwise there is nothing
     * to generate tests for. All other sections (components, info) are optional
     * and have safe defaults if missing.
     *
     * @param openApi the parsed OpenAPI object
     * @param traceId the request trace ID for logging
     * @throws ContractParseException if required sections are missing
     */
    private void validateOpenApiObject(OpenAPI openApi, String traceId) {
        // OpenAPI 3.x requires an info block — the parser usually catches this,
        // but we check it ourselves to provide a better error message
        if (openApi.getInfo() == null) {
            log.warn("[traceId={}] OpenAPI spec is missing the 'info' section", traceId);
            throw new ContractParseException(
                    "The OpenAPI specification is missing the required 'info' section. " +
                    "Ensure your spec includes 'info.title' and 'info.version'."
            );
        }

        // The paths section must exist and must not be empty
        if (openApi.getPaths() == null || openApi.getPaths().isEmpty()) {
            log.warn("[traceId={}] OpenAPI spec has no paths defined", traceId);
            throw new ContractParseException(
                    "The OpenAPI specification contains no paths (endpoints). " +
                    "Ensure the 'paths' section is present and defines at least one endpoint."
            );
        }

        log.debug("[traceId={}] Validation passed | pathCount={}", traceId, openApi.getPaths().size());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STEP 4 — Extract metadata
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Extracts the API title from the {@code info.title} field.
     * Falls back to {@code "Unknown API"} if not present.
     */
    private String extractTitle(OpenAPI openApi) {
        if (openApi.getInfo() == null || openApi.getInfo().getTitle() == null) {
            return "Unknown API";
        }
        return openApi.getInfo().getTitle().trim();
    }

    /**
     * Extracts the API version from the {@code info.version} field.
     * Falls back to {@code "1.0.0"} if not present.
     */
    private String extractVersion(OpenAPI openApi) {
        if (openApi.getInfo() == null || openApi.getInfo().getVersion() == null) {
            return "1.0.0";
        }
        return openApi.getInfo().getVersion().trim();
    }

    /**
     * Extracts the API description from the {@code info.description} field.
     * Returns an empty string if not present.
     */
    private String extractDescription(OpenAPI openApi) {
        if (openApi.getInfo() == null || openApi.getInfo().getDescription() == null) {
            return "";
        }
        return openApi.getInfo().getDescription().trim();
    }

    /**
     * Extracts the base URL from the first entry in the {@code servers} list.
     *
     * <p>OpenAPI 3.x uses a {@code servers} array instead of the {@code host} +
     * {@code basePath} fields used in Swagger 2.x. We take the first server URL
     * as the base URL for the generated REST Assured tests.
     *
     * <p>Falls back to {@code "http://localhost:8080"} if no servers are declared,
     * which is a safe default for local development testing.
     *
     * @param openApi the parsed OpenAPI object
     * @return the base URL string
     */
    private String extractBaseUrl(OpenAPI openApi) {
        if (openApi.getServers() == null || openApi.getServers().isEmpty()) {
            log.debug("No servers defined in spec, defaulting to http://localhost:8080");
            return "http://localhost:8080";
        }

        String url = openApi.getServers().get(0).getUrl();

        // The Swagger Parser may return "/" as the URL when no servers block is present
        if (url == null || url.isBlank() || url.equals("/")) {
            return "http://localhost:8080";
        }

        return url.trim();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STEP 5 — Extract endpoints
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Walks the {@code paths} section of the OpenAPI spec and converts every
     * path + HTTP method combination into an {@link ApiEndpoint} domain object.
     *
     * <p><b>How OpenAPI paths are structured:</b>
     * <pre>
     * paths:
     *   /users:            ← PathItem — may contain multiple operations
     *     get:             ← Operation (one ApiEndpoint)
     *       ...
     *     post:            ← Operation (another ApiEndpoint)
     *       ...
     *   /users/{id}:       ← Another PathItem
     *     get:
     *       ...
     * </pre>
     *
     * @param openApi the parsed OpenAPI object
     * @param traceId the request trace ID for logging
     * @return a list of all {@link ApiEndpoint} objects extracted from the spec
     */
    private List<ApiEndpoint> extractEndpoints(OpenAPI openApi, String traceId) {
        List<ApiEndpoint> endpoints = new ArrayList<>();

        // Iterate over every path entry (e.g. "/users", "/users/{id}")
        for (Map.Entry<String, PathItem> pathEntry : openApi.getPaths().entrySet()) {
            String path     = pathEntry.getKey();
            PathItem pathItem = pathEntry.getValue();

            log.debug("[traceId={}] Processing path: {}", traceId, path);

            // A PathItem holds one Operation per HTTP method.
            // We extract each method→operation pair and build an ApiEndpoint for each.
            Map<String, Operation> operations = getOperationsFromPathItem(pathItem);

            for (Map.Entry<String, Operation> opEntry : operations.entrySet()) {
                String    httpMethod = opEntry.getKey();   // e.g. "GET"
                Operation operation  = opEntry.getValue(); // the operation details

                ApiEndpoint endpoint = buildEndpoint(path, httpMethod, operation, traceId);
                endpoints.add(endpoint);

                log.debug("[traceId={}] Extracted endpoint: {} {}", traceId, httpMethod, path);
            }
        }

        return endpoints;
    }

    /**
     * Maps all defined HTTP method operations from a {@link PathItem} into a
     * plain {@code Map<String, Operation>} for easy iteration.
     *
     * <p>The Swagger library exposes operations as individual getter methods
     * ({@code getGet()}, {@code getPost()}, etc.) rather than a map. We convert
     * them into a map here so the calling code can loop cleanly without checking
     * each method individually.
     *
     * @param pathItem the PathItem containing the operations
     * @return a map of uppercase HTTP method name to its Operation; never null
     */
    private Map<String, Operation> getOperationsFromPathItem(PathItem pathItem) {
        // LinkedHashMap preserves insertion order — GET, PUT, POST, DELETE, PATCH, HEAD, OPTIONS
        Map<String, Operation> operations = new LinkedHashMap<>();

        // Check each HTTP method — only add it if the operation is defined in the spec
        if (pathItem.getGet()     != null) operations.put("GET",     pathItem.getGet());
        if (pathItem.getPost()    != null) operations.put("POST",    pathItem.getPost());
        if (pathItem.getPut()     != null) operations.put("PUT",     pathItem.getPut());
        if (pathItem.getDelete()  != null) operations.put("DELETE",  pathItem.getDelete());
        if (pathItem.getPatch()   != null) operations.put("PATCH",   pathItem.getPatch());
        if (pathItem.getHead()    != null) operations.put("HEAD",    pathItem.getHead());
        if (pathItem.getOptions() != null) operations.put("OPTIONS", pathItem.getOptions());

        return operations;
    }

    /**
     * Builds a single {@link ApiEndpoint} from a path string, HTTP method, and
     * Swagger {@link Operation}.
     *
     * @param path       the URL path (e.g. {@code /users/{id}})
     * @param httpMethod the HTTP method in uppercase (e.g. {@code GET})
     * @param operation  the Swagger Operation object for this path+method combination
     * @param traceId    the request trace ID for logging
     * @return a fully populated {@link ApiEndpoint}
     */
    private ApiEndpoint buildEndpoint(String path, String httpMethod, Operation operation, String traceId) {
        String summary     = operation.getSummary();
        String operationId = resolveOperationId(operation, path, httpMethod);

        // Extract parameters (path, query, header)
        List<ApiParameter> parameters = extractParameters(operation, traceId);

        // Extract request body schema (only relevant for POST, PUT, PATCH)
        ApiSchema requestBodySchema = extractRequestBodySchema(operation, traceId);

        // Extract response schemas (keyed by HTTP status code)
        Map<String, ApiSchema> responses = extractResponseSchemas(operation, traceId);

        return new ApiEndpoint(
                path,
                httpMethod,
                summary,
                operationId,
                parameters,
                requestBodySchema,
                responses
        );
    }

    /**
     * Resolves the operation ID for an endpoint.
     *
     * <p>If the spec author provided an {@code operationId}, we use it directly —
     * it becomes the basis for the generated test method name.
     *
     * <p>If no {@code operationId} is provided, we generate one from the HTTP method
     * and path. For example, {@code GET /users/{id}} becomes {@code getUsersById}.
     * This ensures every endpoint always has a usable identifier for code generation.
     *
     * @param operation  the Swagger Operation
     * @param path       the URL path (used as fallback)
     * @param httpMethod the HTTP method (used as fallback)
     * @return the resolved operation ID; never null or empty
     */
    private String resolveOperationId(Operation operation, String path, String httpMethod) {
        if (operation.getOperationId() != null && !operation.getOperationId().isBlank()) {
            return operation.getOperationId();
        }

        // Generate a camelCase name from the method and path
        // e.g. GET /users/{id} → getUsers_id → getUsersId
        String sanitisedPath = path
                .replaceAll("\\{", "")       // remove { from path params
                .replaceAll("}", "")         // remove } from path params
                .replaceAll("/", "_")        // replace / with _
                .replaceAll("^_", "")        // remove leading _
                .replaceAll("-", "_");       // replace hyphens with _

        return httpMethod.toLowerCase() + "_" + sanitisedPath;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STEP 5a — Extract parameters
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Extracts all parameters from a Swagger {@link Operation} and converts them
     * into {@link ApiParameter} domain objects.
     *
     * <p>The Swagger library's {@link Parameter} class covers all parameter locations
     * (path, query, header, cookie) in a single class. We map each to an
     * {@link ApiParameter} with the resolved Java type.
     *
     * @param operation the Swagger Operation containing the parameters
     * @param traceId   the request trace ID for logging
     * @return a list of {@link ApiParameter} objects; empty if no parameters
     */
    private List<ApiParameter> extractParameters(Operation operation, String traceId) {
        if (operation.getParameters() == null || operation.getParameters().isEmpty()) {
            return Collections.emptyList();
        }

        List<ApiParameter> result = new ArrayList<>();

        for (Parameter swaggerParam : operation.getParameters()) {
            // Skip parameters that have no name (malformed spec) — log a warning
            if (swaggerParam.getName() == null || swaggerParam.getName().isBlank()) {
                log.warn("[traceId={}] Skipping parameter with null/blank name", traceId);
                continue;
            }

            // Resolve the OpenAPI schema type to a Java type name
            String javaType = resolveJavaType(swaggerParam.getSchema());

            ApiParameter parameter = new ApiParameter(
                    swaggerParam.getName(),
                    swaggerParam.getIn(),            // "path", "query", "header", "cookie"
                    javaType,
                    Boolean.TRUE.equals(swaggerParam.getRequired()),
                    swaggerParam.getDescription()
            );

            result.add(parameter);
            log.debug("[traceId={}] Extracted parameter: {} ({}) [{}]",
                    traceId, swaggerParam.getName(), javaType, swaggerParam.getIn());
        }

        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STEP 5b — Extract request body schema
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Extracts the request body schema from a Swagger {@link Operation}.
     *
     * <p>In OpenAPI 3.x, the request body is a separate concept from parameters.
     * It is declared under the {@code requestBody} key with a {@code content} map
     * that can specify different schemas for different MIME types. We extract
     * the schema for {@code application/json} as our preferred type.
     *
     * @param operation the Swagger Operation
     * @param traceId   the request trace ID for logging
     * @return the request body {@link ApiSchema}, or {@code null} if no request body
     */
    private ApiSchema extractRequestBodySchema(Operation operation, String traceId) {
        if (operation.getRequestBody() == null) {
            return null; // no request body — normal for GET, DELETE, HEAD
        }

        Content content = operation.getRequestBody().getContent();
        if (content == null || content.isEmpty()) {
            return null;
        }

        // Prefer application/json; fall back to the first available media type
        MediaType mediaType = content.get(PREFERRED_MEDIA_TYPE);
        if (mediaType == null) {
            mediaType = content.values().iterator().next();
        }

        if (mediaType.getSchema() == null) {
            return null;
        }

        ApiSchema schema = convertSchema("RequestBody", mediaType.getSchema(), traceId);
        log.debug("[traceId={}] Extracted request body schema: {}", traceId, schema);
        return schema;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STEP 5c — Extract response schemas
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Extracts all response schemas from a Swagger {@link Operation}.
     *
     * <p>An operation can declare multiple responses (one per HTTP status code).
     * We extract all of them and key the resulting map by status code string
     * (e.g. {@code "200"}, {@code "404"}, {@code "default"}).
     *
     * @param operation the Swagger Operation
     * @param traceId   the request trace ID for logging
     * @return a map of status code strings to {@link ApiSchema} objects; empty if none
     */
    private Map<String, ApiSchema> extractResponseSchemas(Operation operation, String traceId) {
        if (operation.getResponses() == null || operation.getResponses().isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, ApiSchema> responseSchemas = new LinkedHashMap<>();

        for (Map.Entry<String, ApiResponse> responseEntry : operation.getResponses().entrySet()) {
            String      statusCode   = responseEntry.getKey();   // e.g. "200", "404"
            ApiResponse apiResponse  = responseEntry.getValue();

            if (apiResponse.getContent() == null || apiResponse.getContent().isEmpty()) {
                // No response body (e.g. 204 No Content, 201 Created with no body)
                log.debug("[traceId={}] Response {} has no content schema", traceId, statusCode);
                continue;
            }

            // Prefer application/json; fall back to first available
            MediaType mediaType = apiResponse.getContent().get(PREFERRED_MEDIA_TYPE);
            if (mediaType == null) {
                mediaType = apiResponse.getContent().values().iterator().next();
            }

            if (mediaType.getSchema() != null) {
                ApiSchema schema = convertSchema("Response_" + statusCode, mediaType.getSchema(), traceId);
                responseSchemas.put(statusCode, schema);
                log.debug("[traceId={}] Extracted response schema for status {}: {}", traceId, statusCode, schema);
            }
        }

        return responseSchemas;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STEP 6 — Extract component schemas
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Extracts all schemas from the {@code components/schemas} section of the spec.
     *
     * <p>These are the reusable data models declared at the top level of the spec
     * and referenced via {@code $ref} throughout the endpoints. Each becomes a POJO
     * class in the generated test project.
     *
     * @param openApi the parsed OpenAPI object
     * @param traceId the request trace ID for logging
     * @return a map of schema name to {@link ApiSchema}; empty if no components
     */
    private Map<String, ApiSchema> extractComponentSchemas(OpenAPI openApi, String traceId) {
        if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
            log.debug("[traceId={}] No components/schemas section found in spec", traceId);
            return Collections.emptyMap();
        }

        Map<String, ApiSchema> result = new LinkedHashMap<>();
        Map<String, Schema> rawSchemas = openApi.getComponents().getSchemas();

        for (Map.Entry<String, Schema> entry : rawSchemas.entrySet()) {
            String        schemaName  = entry.getKey();
            Schema<?>     rawSchema   = entry.getValue();
            ApiSchema     apiSchema   = convertSchema(schemaName, rawSchema, traceId);

            result.put(schemaName, apiSchema);
            log.debug("[traceId={}] Extracted component schema: {}", traceId, apiSchema);
        }

        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SCHEMA CONVERSION HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Recursively converts a Swagger library {@link Schema} object into an
     * {@link ApiSchema} domain object.
     *
     * <p>This method handles all schema types:
     * <ul>
     *   <li>{@code object} — recursively converts each property</li>
     *   <li>{@code array} — recursively converts the {@code items} schema</li>
     *   <li>Primitives ({@code string}, {@code integer}, {@code number}, {@code boolean}) — resolved to Java types</li>
     * </ul>
     *
     * @param name      the name to assign to this schema (component name or property name)
     * @param schema    the Swagger library schema object to convert
     * @param traceId   the request trace ID for logging
     * @return a fully populated {@link ApiSchema}
     */
    @SuppressWarnings("rawtypes")
    private ApiSchema convertSchema(String name, Schema schema, String traceId) {
        if (schema == null) {
            // Return a minimal "unknown" schema rather than null to avoid NPEs downstream
            return new ApiSchema(name, "object", "Object", Collections.emptyMap(), null, "");
        }

        String openApiType = schema.getType() != null ? schema.getType() : "object";
        String javaType    = resolveJavaType(schema);
        String description = schema.getDescription();

        // ── Handle ARRAY type ────────────────────────────────────────────────
        if ("array".equalsIgnoreCase(openApiType) && schema instanceof ArraySchema) {
            ArraySchema arraySchema = (ArraySchema) schema;
            ApiSchema itemSchema = null;

            if (arraySchema.getItems() != null) {
                // Recursively convert the items schema
                itemSchema = convertSchema(name + "Item", arraySchema.getItems(), traceId);
            }

            return new ApiSchema(name, "array", javaType, Collections.emptyMap(), itemSchema, description);
        }

        // ── Handle OBJECT type ───────────────────────────────────────────────
        if ("object".equalsIgnoreCase(openApiType) || schema.getProperties() != null) {
            Map<String, ApiSchema> properties = new LinkedHashMap<>();

            if (schema.getProperties() != null) {
                // Recursively convert each property of this object schema
                for (Object propEntry : schema.getProperties().entrySet()) {
                    Map.Entry entry = (Map.Entry) propEntry;
                    String    propName   = (String) entry.getKey();
                    Schema    propSchema = (Schema) entry.getValue();

                    ApiSchema propApiSchema = convertSchema(propName, propSchema, traceId);
                    properties.put(propName, propApiSchema);
                }
            }

            return new ApiSchema(name, "object", javaType, properties, null, description);
        }

        // ── Handle PRIMITIVE types (string, integer, number, boolean) ─────────
        return new ApiSchema(name, openApiType, javaType, Collections.emptyMap(), null, description);
    }

    /**
     * Resolves an OpenAPI schema type string to the corresponding Java type name.
     *
     * <p>Mapping table:
     * <table border="1">
     *   <tr><th>OpenAPI type</th><th>OpenAPI format</th><th>Java type</th></tr>
     *   <tr><td>string</td><td>—</td><td>String</td></tr>
     *   <tr><td>string</td><td>date-time</td><td>String</td></tr>
     *   <tr><td>integer</td><td>—</td><td>Integer</td></tr>
     *   <tr><td>integer</td><td>int64</td><td>Long</td></tr>
     *   <tr><td>number</td><td>float</td><td>Float</td></tr>
     *   <tr><td>number</td><td>double</td><td>Double</td></tr>
     *   <tr><td>boolean</td><td>—</td><td>Boolean</td></tr>
     *   <tr><td>array</td><td>—</td><td>List&lt;?&gt;</td></tr>
     *   <tr><td>object</td><td>—</td><td>Object</td></tr>
     * </table>
     *
     * @param schema the Swagger schema whose type should be resolved
     * @return the Java type name as a string; defaults to {@code "Object"}
     */
    @SuppressWarnings("rawtypes")
    private String resolveJavaType(Schema schema) {
        if (schema == null) {
            return "Object";
        }

        String type   = schema.getType();
        String format = schema.getFormat(); // e.g. "int64", "float", "date-time"

        if (type == null) {
            // Likely a $ref that was not fully resolved — use Object as safe default
            return "Object";
        }

        return switch (type.toLowerCase()) {
            case "string"  -> "String";
            case "boolean" -> "Boolean";
            case "array"   -> "List<?>";
            case "integer" -> ("int64".equalsIgnoreCase(format)) ? "Long" : "Integer";
            case "number"  -> switch (format != null ? format.toLowerCase() : "") {
                case "float"  -> "Float";
                case "double" -> "Double";
                default       -> "Double";
            };
            default -> "Object";
        };
    }
}