package com.varna.automationengine.domain.model.contract;

/**
 * Domain model representing a single parameter declared on an API endpoint.
 *
 * <p>In OpenAPI, parameters are inputs to an endpoint that are NOT the request body.
 * They can appear in four locations:
 * <ul>
 *   <li><b>path</b>   — embedded in the URL: {@code /users/{id}} where {@code id} is a path param</li>
 *   <li><b>query</b>  — appended to the URL: {@code /users?page=1&size=10}</li>
 *   <li><b>header</b> — sent as an HTTP header: {@code Authorization: Bearer <token>}</li>
 *   <li><b>cookie</b> — sent as a cookie value (less common)</li>
 * </ul>
 *
 * <p>The code generator uses this model to produce REST Assured test parameters.
 * For example, a path parameter named {@code id} of type {@code string} generates:
 * <pre>
 * .pathParam("id", testId)
 * </pre>
 * And a required query parameter named {@code status} generates:
 * <pre>
 * .queryParam("status", testStatus)
 * </pre>
 *
 * <p>Like all domain models in this engine, this class is immutable — all fields
 * are set in the constructor and there are no setters.
 */
public final class ApiParameter {

    /**
     * The name of the parameter, exactly as it appears in the OpenAPI spec.
     *
     * <p>Examples: {@code id}, {@code page}, {@code Authorization}, {@code X-Correlation-ID}
     */
    private final String name;

    /**
     * Where this parameter is located in the HTTP request.
     *
     * <p>Always one of: {@code "path"}, {@code "query"}, {@code "header"}, {@code "cookie"}.
     * Stored in lowercase to match the OpenAPI specification convention.
     */
    private final String location;

    /**
     * The data type of this parameter, resolved to a Java type name.
     *
     * <p>The OpenAPI type ({@code string}, {@code integer}, {@code boolean}) is
     * translated to the corresponding Java type ({@code String}, {@code Integer},
     * {@code Boolean}) during parsing. Defaults to {@code "String"} for unknown types.
     */
    private final String javaType;

    /**
     * Whether this parameter must be present for the request to be valid.
     *
     * <p>Path parameters are always required (OpenAPI spec mandates this).
     * Query and header parameters may be optional.
     * The generator uses this flag to mark test parameters with comments
     * and to decide whether to include them in negative test cases.
     */
    private final boolean required;

    /**
     * A human-readable description of this parameter from the OpenAPI spec.
     *
     * <p>Sourced from the {@code description} field. Used as a comment in
     * generated test code. May be empty if the spec author did not provide one.
     */
    private final String description;

    /**
     * Constructs a fully populated {@code ApiParameter}.
     *
     * @param name        the parameter name as declared in the spec
     * @param location    where the parameter appears: "path", "query", "header", or "cookie"
     * @param javaType    the resolved Java type name (e.g. "String", "Integer", "Boolean")
     * @param required    {@code true} if this parameter is mandatory
     * @param description a human-readable description; may be empty or null
     */
    public ApiParameter(String name,
                        String location,
                        String javaType,
                        boolean required,
                        String description) {
        this.name        = name;
        this.location    = location != null ? location.toLowerCase() : "query";
        this.javaType    = javaType != null ? javaType : "String";
        this.required    = required;
        this.description = description != null ? description : "";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GETTERS — no setters, this object is immutable
    // ─────────────────────────────────────────────────────────────────────────

    /** @return the parameter name */
    public String getName() { return name; }

    /** @return the parameter location: "path", "query", "header", or "cookie" */
    public String getLocation() { return location; }

    /** @return the Java type name, e.g. "String", "Integer", "Boolean" */
    public String getJavaType() { return javaType; }

    /** @return {@code true} if this parameter must always be provided */
    public boolean isRequired() { return required; }

    /** @return the parameter description; empty string if not specified */
    public String getDescription() { return description; }

    @Override
    public String toString() {
        return "ApiParameter{" +
                "name='" + name + '\'' +
                ", location='" + location + '\'' +
                ", javaType='" + javaType + '\'' +
                ", required=" + required +
                '}';
    }
}