package com.varna.automationengine.domain.model.contract;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Domain model representing a fully parsed OpenAPI contract.
 *
 * <p>This is the <em>aggregate root</em> of the contract domain model — the single
 * object that the parser produces and the generator consumes. It is the common
 * language between the parsing infrastructure and the code generation infrastructure.
 * Neither side needs to know anything about the other, as long as both agree on
 * this model as the hand-off point.
 *
 * <p><b>Pipeline position:</b>
 * <pre>
 *   MultipartFile (raw upload)
 *       ↓  parsed by SwaggerOpenApiParser
 *   ApiContract  ← THIS CLASS (hand-off point)
 *       ↓  consumed by ProjectGenerator (future layer)
 *   GeneratedProject (list of source files)
 *       ↓  packaged by ZipArchiveAdapter
 *   byte[] ZIP
 * </pre>
 *
 * <p><b>What it contains:</b>
 * <ul>
 *   <li>API metadata — title, version, description, base URL</li>
 *   <li>All endpoints — each endpoint with its path, method, parameters, and schemas</li>
 *   <li>All shared schemas — the components/schemas section (POJOs to generate)</li>
 * </ul>
 *
 * <p><b>Why "aggregate root"?</b><br>
 * In Domain-Driven Design, an aggregate root is the entry point to a cluster of
 * related domain objects. You never access {@link ApiEndpoint} or {@link ApiSchema}
 * objects directly from outside — you always go through {@code ApiContract}.
 * This keeps the contract model cohesive and prevents fragmented access patterns.
 *
 * <p>This class is immutable after construction — all fields are {@code final}
 * and collections are wrapped in unmodifiable views.
 */
public final class ApiContract {

    /**
     * The title of the API, from the {@code info.title} field in the OpenAPI spec.
     *
     * <p>Used by the generator as the name of the generated test project and
     * as the class name prefix for test classes. Example: {@code "Pet Store API"}
     */
    private final String title;

    /**
     * The version of the API, from the {@code info.version} field.
     *
     * <p>Example: {@code "1.0.0"}, {@code "v2"}, {@code "2024-01-01"}
     * Included as a comment in generated files.
     */
    private final String version;

    /**
     * A description of the API, from the {@code info.description} field.
     *
     * <p>Used as a JavaDoc comment on the generated base test class. May be empty.
     */
    private final String description;

    /**
     * The base URL of the API — the host and optional base path.
     *
     * <p>In OpenAPI 3.x this comes from the first entry in the {@code servers} list.
     * Example: {@code "https://api.example.com/v1"}
     *
     * <p>Used by the generator to set the base URI in the REST Assured
     * {@code RequestSpecification}. Defaults to {@code "http://localhost:8080"}
     * if no servers are declared in the spec.
     */
    private final String baseUrl;

    /**
     * All endpoints extracted from the {@code paths} section of the spec.
     *
     * <p>Each entry in the OpenAPI {@code paths} block may define multiple HTTP methods,
     * so a single path like {@code /users} can produce multiple {@link ApiEndpoint}
     * objects — one for {@code GET /users}, one for {@code POST /users}, etc.
     */
    private final List<ApiEndpoint> endpoints;

    /**
     * All reusable schemas from the {@code components/schemas} section.
     *
     * <p>Key: the schema name as declared in the spec (e.g. {@code "User"}, {@code "Order"}).
     * Value: the {@link ApiSchema} describing its properties.
     *
     * <p>These schemas are the source for generating POJO model classes in the
     * test project. Inline schemas (defined directly on an endpoint rather than
     * in components) are also captured here, with a generated name derived from
     * the endpoint path and method.
     */
    private final Map<String, ApiSchema> schemas;

    /**
     * Constructs a fully populated {@code ApiContract}.
     *
     * @param title       the API title from info.title
     * @param version     the API version from info.version
     * @param description the API description from info.description; may be empty
     * @param baseUrl     the resolved base URL from the servers section
     * @param endpoints   all parsed endpoints; must not be null or empty
     * @param schemas     all reusable component schemas; may be empty
     */
    public ApiContract(String title,
                       String version,
                       String description,
                       String baseUrl,
                       List<ApiEndpoint> endpoints,
                       Map<String, ApiSchema> schemas) {
        this.title       = title != null ? title : "Generated API";
        this.version     = version != null ? version : "1.0.0";
        this.description = description != null ? description : "";
        this.baseUrl     = baseUrl != null ? baseUrl : "http://localhost:8080";
        this.endpoints   = endpoints != null
                ? Collections.unmodifiableList(endpoints)
                : Collections.emptyList();
        this.schemas     = schemas != null
                ? Collections.unmodifiableMap(schemas)
                : Collections.emptyMap();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GETTERS — no setters, this object is immutable
    // ─────────────────────────────────────────────────────────────────────────

    /** @return the API title */
    public String getTitle() { return title; }

    /** @return the API version */
    public String getVersion() { return version; }

    /** @return the API description; empty string if not provided */
    public String getDescription() { return description; }

    /** @return the resolved base URL for the API */
    public String getBaseUrl() { return baseUrl; }

    /** @return an unmodifiable list of all parsed endpoints; never {@code null} */
    public List<ApiEndpoint> getEndpoints() { return endpoints; }

    /** @return an unmodifiable map of reusable schema name to schema model; never {@code null} */
    public Map<String, ApiSchema> getSchemas() { return schemas; }

    /**
     * Convenience method — returns the total number of endpoints in this contract.
     *
     * @return the endpoint count
     */
    public int getEndpointCount() {
        return endpoints.size();
    }

    /**
     * Convenience method — returns {@code true} if any reusable schemas were found
     * in the {@code components/schemas} section.
     *
     * @return {@code true} if the schemas map is non-empty
     */
    public boolean hasSchemas() {
        return !schemas.isEmpty();
    }

    @Override
    public String toString() {
        return "ApiContract{" +
                "title='" + title + '\'' +
                ", version='" + version + '\'' +
                ", baseUrl='" + baseUrl + '\'' +
                ", endpointCount=" + endpoints.size() +
                ", schemaCount=" + schemas.size() +
                '}';
    }
}