package com.varna.automationengine.domain.model.contract;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Domain model representing a single API endpoint extracted from an OpenAPI specification.
 *
 * <p>An endpoint is the combination of an HTTP method and a URL path — for example,
 * {@code GET /users/{id}} or {@code POST /orders}. This class captures everything
 * the code generator needs to know about one endpoint in order to produce a
 * corresponding REST Assured test method.
 *
 * <p><b>What "domain model" means here:</b><br>
 * This is a pure Java class with no framework dependencies. It is the engine's
 * internal language for talking about API endpoints — completely independent of
 * how the data arrived (OpenAPI YAML, JSON, Swagger 2.x, etc.). The parser layer
 * translates whatever format was uploaded into this model. The generator layer
 * consumes this model to produce code. Neither layer needs to know about the other.
 *
 * <p><b>Immutability:</b><br>
 * All fields are {@code final} and set via the constructor. There are no setters.
 * Once constructed, an {@code ApiEndpoint} cannot be changed. This prevents bugs
 * where one part of the pipeline accidentally modifies data another part is still
 * reading, and makes the object safe to share across threads.
 *
 * <p><b>Example — what this represents in a real spec:</b>
 * <pre>
 * # In the OpenAPI YAML:
 * paths:
 *   /users/{id}:
 *     get:
 *       summary: Get user by ID
 *       parameters:
 *         - name: id
 *           in: path
 *       responses:
 *         '200':
 *           description: User found
 *
 * # Maps to:
 * ApiEndpoint(
 *   path       = "/users/{id}",
 *   httpMethod = "GET",
 *   summary    = "Get user by ID",
 *   parameters = [ApiParameter(name="id", location="path", ...)],
 *   responses  = {"200": ApiSchema(...)}
 * )
 * </pre>
 */
public final class ApiEndpoint {

    /**
     * The URL path of this endpoint as it appears in the OpenAPI spec.
     *
     * <p>May contain path parameters wrapped in curly braces.
     * Examples: {@code /users}, {@code /users/{id}}, {@code /orders/{orderId}/items}
     */
    private final String path;

    /**
     * The HTTP method for this endpoint, always in uppercase.
     *
     * <p>Standard values from the OpenAPI spec: {@code GET}, {@code POST},
     * {@code PUT}, {@code PATCH}, {@code DELETE}, {@code HEAD}, {@code OPTIONS}.
     */
    private final String httpMethod;

    /**
     * A short, human-readable description of what this endpoint does.
     *
     * <p>Sourced from the {@code summary} field in the OpenAPI spec.
     * Used by the generator as the JavaDoc comment on the test method.
     * May be empty if the spec author did not provide a summary.
     */
    private final String summary;

    /**
     * The operation ID from the OpenAPI spec — a unique identifier for this endpoint.
     *
     * <p>Sourced from the {@code operationId} field. Used by the generator to
     * derive the test method name (e.g. {@code operationId: getUserById} →
     * test method: {@code testGetUserById()}). Falls back to a path+method
     * combination if no operationId is specified in the spec.
     */
    private final String operationId;

    /**
     * All parameters declared for this endpoint.
     *
     * <p>Covers path parameters ({@code /users/{id}}), query parameters
     * ({@code ?page=1&size=10}), and header parameters. Each parameter is
     * represented as an {@link ApiParameter} with its name, location, type,
     * and whether it is required.
     */
    private final List<ApiParameter> parameters;

    /**
     * The schema of the request body, if this endpoint accepts one.
     *
     * <p>Will be {@code null} for endpoints that do not have a request body
     * (e.g. {@code GET}, {@code DELETE}). For {@code POST} and {@code PUT},
     * this captures the shape of the JSON body the client must send.
     */
    private final ApiSchema requestBodySchema;

    /**
     * Map of HTTP response codes to their response schemas.
     *
     * <p>Key: HTTP status code as a String (e.g. {@code "200"}, {@code "404"}, {@code "default"}).
     * Value: The {@link ApiSchema} describing the shape of that response body.
     *
     * <p>Example: {@code {"200": ApiSchema(name="User", ...), "404": ApiSchema(name="ErrorResponse", ...)}}
     */
    private final Map<String, ApiSchema> responses;

    /**
     * Constructs a fully populated {@code ApiEndpoint}.
     *
     * <p>All collections ({@code parameters}, {@code responses}) are defensively copied
     * to prevent external mutation of the internal state after construction.
     *
     * @param path              the URL path (e.g. {@code /users/{id}})
     * @param httpMethod        the HTTP method in uppercase (e.g. {@code GET})
     * @param summary           a human-readable description of this endpoint; may be empty
     * @param operationId       the unique operation identifier from the spec; may be empty
     * @param parameters        list of path, query, and header parameters; may be empty
     * @param requestBodySchema the request body schema; {@code null} if no body expected
     * @param responses         map of response codes to response schemas; may be empty
     */
    public ApiEndpoint(String path,
                       String httpMethod,
                       String summary,
                       String operationId,
                       List<ApiParameter> parameters,
                       ApiSchema requestBodySchema,
                       Map<String, ApiSchema> responses) {
        this.path              = path;
        this.httpMethod        = httpMethod;
        this.summary           = summary != null ? summary : "";
        this.operationId       = operationId != null ? operationId : "";
        // Defensive copy — the caller's list cannot affect our internal state
        this.parameters        = parameters != null
                ? Collections.unmodifiableList(parameters)
                : Collections.emptyList();
        this.requestBodySchema = requestBodySchema;
        // Defensive copy for the response map too
        this.responses         = responses != null
                ? Collections.unmodifiableMap(responses)
                : Collections.emptyMap();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GETTERS
    // No setters — this object is immutable after construction.
    // ─────────────────────────────────────────────────────────────────────────

    /** @return the URL path, e.g. {@code /users/{id}} */
    public String getPath() { return path; }

    /** @return the HTTP method in uppercase, e.g. {@code GET} */
    public String getHttpMethod() { return httpMethod; }

    /** @return the endpoint summary; empty string if not specified */
    public String getSummary() { return summary; }

    /** @return the operationId; empty string if not specified */
    public String getOperationId() { return operationId; }

    /** @return an unmodifiable list of parameters; never {@code null} */
    public List<ApiParameter> getParameters() { return parameters; }

    /**
     * @return the request body schema, or {@code null} if this endpoint
     *         does not accept a request body
     */
    public ApiSchema getRequestBodySchema() { return requestBodySchema; }

    /** @return an unmodifiable map of response codes to schemas; never {@code null} */
    public Map<String, ApiSchema> getResponses() { return responses; }

    /**
     * Convenience method — returns {@code true} if this endpoint has a request body.
     *
     * <p>Useful in templates: {@code <#if endpoint.hasRequestBody()>}
     *
     * @return {@code true} if {@link #requestBodySchema} is non-null
     */
    public boolean hasRequestBody() {
        return requestBodySchema != null;
    }

    /**
     * Convenience method — returns {@code true} if this endpoint has path parameters.
     *
     * @return {@code true} if at least one parameter has location {@code "path"}
     */
    public boolean hasPathParameters() {
        return parameters.stream()
                .anyMatch(p -> "path".equalsIgnoreCase(p.getLocation()));
    }

    @Override
    public String toString() {
        return "ApiEndpoint{" +
                "httpMethod='" + httpMethod + '\'' +
                ", path='" + path + '\'' +
                ", operationId='" + operationId + '\'' +
                ", parameterCount=" + parameters.size() +
                ", hasRequestBody=" + hasRequestBody() +
                ", responseCount=" + responses.size() +
                '}';
    }
}