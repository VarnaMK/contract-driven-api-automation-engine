package com.varna.automationengine.infrastructure.generator;

import com.varna.automationengine.domain.exception.ProjectGenerationException;
import com.varna.automationengine.domain.model.contract.ApiContract;
import com.varna.automationengine.domain.model.contract.ApiEndpoint;
import com.varna.automationengine.domain.model.contract.ApiParameter;
import com.varna.automationengine.domain.model.contract.ApiSchema;
import com.varna.automationengine.domain.model.project.GeneratedFile;
import com.varna.automationengine.domain.model.project.GeneratedProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Production implementation of {@link ProjectGenerator} that generates a complete,
 * ready-to-run REST Assured + TestNG Maven project from a parsed {@link ApiContract}.
 *
 * <p><b>Files generated:</b>
 * <ul>
 *   <li>{@code pom.xml} — Maven project file with REST Assured, TestNG, Jackson dependencies</li>
 *   <li>{@code testng.xml} — TestNG suite configuration file</li>
 *   <li>{@code src/test/java/.../base/BaseTest.java} — shared REST Assured setup</li>
 *   <li>{@code src/test/java/.../tests/{GroupName}ApiTest.java} — one test class per endpoint group</li>
 *   <li>{@code src/test/java/.../model/{SchemaName}.java} — one POJO per component schema</li>
 * </ul>
 *
 * <p><b>Endpoint grouping strategy:</b><br>
 * Endpoints are grouped by the first segment of their path. All endpoints starting
 * with {@code /users} go into {@code UsersApiTest.java}; all endpoints starting with
 * {@code /orders} go into {@code OrdersApiTest.java}. This produces one focused test
 * class per resource, which is cleaner than one giant test class for all endpoints.
 *
 * <p><b>Base package:</b><br>
 * The generated project uses {@code com.automation.tests} as the Java package for
 * all generated classes. This is hardcoded here for simplicity; in a future version
 * it could be made configurable via a {@code GenerationConfig} model.
 *
 * <p><b>Template engine:</b><br>
 * All code is produced by rendering Mustache templates via {@link TemplateEngineService}.
 * This class is responsible for preparing the data model (the {@code Map<String, Object>}
 * passed to the template); the templates handle all the actual text formatting.
 */
@Component
public class RestAssuredProjectGenerator implements ProjectGenerator {

    private static final Logger log = LoggerFactory.getLogger(RestAssuredProjectGenerator.class);

    // ─────────────────────────────────────────────────────────────────────────
    // CONSTANTS
    // ─────────────────────────────────────────────────────────────────────────

    /** Base Java package used for all generated classes in the test project. */
    private static final String BASE_PACKAGE = "com.automation.tests";

    /** Sub-package for test classes (appended to BASE_PACKAGE). */
    private static final String TESTS_SUBPACKAGE = BASE_PACKAGE + ".tests";

    /** Sub-package for POJO model classes (appended to BASE_PACKAGE). */
    private static final String MODEL_SUBPACKAGE = BASE_PACKAGE + ".model";

    /** Sub-package for the base test class (appended to BASE_PACKAGE). */
    private static final String BASE_SUBPACKAGE = BASE_PACKAGE + ".base";

    /**
     * Converts a Java package name into a file path segment.
     * e.g. "com.automation.tests" → "com/automation/tests"
     */
    private static final String TESTS_PATH  = TESTS_SUBPACKAGE.replace('.', '/');
    private static final String MODEL_PATH  = MODEL_SUBPACKAGE.replace('.', '/');
    private static final String BASE_PATH   = BASE_SUBPACKAGE.replace('.', '/');

    // ─────────────────────────────────────────────────────────────────────────
    // DEPENDENCIES
    // ─────────────────────────────────────────────────────────────────────────

    /** Renders Mustache templates into Java/XML source strings. */
    private final TemplateEngineService templateEngine;

    /**
     * Constructor — injects the {@link TemplateEngineService} via constructor injection.
     *
     * @param templateEngine the Mustache template rendering service
     */
    public RestAssuredProjectGenerator(TemplateEngineService templateEngine) {
        this.templateEngine = templateEngine;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRIMARY ENTRY POINT
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p><b>Generation sequence:</b>
     * <ol>
     *   <li>Derive a safe project name from the API title</li>
     *   <li>Generate {@code pom.xml}</li>
     *   <li>Generate {@code testng.xml}</li>
     *   <li>Generate {@code BaseTest.java}</li>
     *   <li>For each schema in {@code components/schemas}: generate a POJO class</li>
     *   <li>Group endpoints by resource path and generate one test class per group</li>
     *   <li>Validate at least one file was produced</li>
     *   <li>Wrap everything in a {@link GeneratedProject} and return</li>
     * </ol>
     */
    @Override
    public GeneratedProject generate(ApiContract contract, String traceId) {
        log.info("[traceId={}] Starting project generation | contract={}", traceId, contract);

        // Accumulates every GeneratedFile produced by the generation steps below
        List<GeneratedFile> allFiles = new ArrayList<>();

        // Derive a filesystem-safe project name from the API title
        // e.g. "Pet Store API" → "pet-store-api-tests"
        String projectName = toProjectName(contract.getTitle());

        // ── Step 1: Generate pom.xml ──────────────────────────────────────────
        log.debug("[traceId={}] Generating pom.xml", traceId);
        allFiles.add(generatePomXml(contract, projectName));

        // ── Step 2: Generate testng.xml ───────────────────────────────────────
        log.debug("[traceId={}] Generating testng.xml", traceId);
        allFiles.add(generateTestNgXml(contract));

        // ── Step 3: Generate BaseTest.java ────────────────────────────────────
        log.debug("[traceId={}] Generating BaseTest.java", traceId);
        allFiles.add(generateBaseTest(contract));

        // ── Step 4: Generate POJO model classes ───────────────────────────────
        // One Java class per schema in components/schemas
        if (contract.hasSchemas()) {
            log.debug("[traceId={}] Generating {} POJO class(es)", traceId, contract.getSchemas().size());
            for (Map.Entry<String, ApiSchema> entry : contract.getSchemas().entrySet()) {
                String    schemaName = entry.getKey();
                ApiSchema schema     = entry.getValue();
                // Only generate POJOs for object-type schemas with properties
                if (schema.isObject()) {
                    allFiles.add(generatePojoClass(schemaName, schema));
                }
            }
        }

        // ── Step 5: Group endpoints and generate test classes ─────────────────
        // Group by the first path segment (e.g. "/users/{id}" → "users")
        Map<String, List<ApiEndpoint>> endpointGroups = groupEndpointsByResource(contract.getEndpoints());
        log.debug("[traceId={}] Endpoint groups: {}", traceId, endpointGroups.keySet());

        for (Map.Entry<String, List<ApiEndpoint>> group : endpointGroups.entrySet()) {
            String              resourceName = group.getKey();
            List<ApiEndpoint>   endpoints    = group.getValue();
            log.debug("[traceId={}] Generating test class for resource '{}' ({} endpoint(s))",
                    traceId, resourceName, endpoints.size());
            allFiles.add(generateTestClass(resourceName, endpoints, contract.getBaseUrl()));
        }

        // ── Step 6: Safety check — at least one file must have been produced ──
        if (allFiles.isEmpty()) {
            throw new ProjectGenerationException(
                    "Project generation produced zero files for contract: " + contract.getTitle() +
                    ". Ensure the contract has at least one endpoint."
            );
        }

        log.info("[traceId={}] Project generation complete | fileCount={}", traceId, allFiles.size());

        return new GeneratedProject(projectName, allFiles);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GENERATION STEPS — one method per file type
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Generates the {@code pom.xml} Maven project descriptor.
     *
     * <p>The pom.xml includes dependencies for:
     * <ul>
     *   <li>REST Assured — HTTP client for API testing</li>
     *   <li>TestNG — test runner and assertion framework</li>
     *   <li>Jackson Databind — JSON serialisation/deserialisation</li>
     * </ul>
     *
     * @param contract    the parsed API contract (provides API title and version)
     * @param projectName the Maven artifactId for the generated project
     * @return a {@link GeneratedFile} with path {@code pom.xml}
     */
    private GeneratedFile generatePomXml(ApiContract contract, String projectName) {
        Map<String, Object> model = new HashMap<>();
        model.put("projectName",    projectName);
        model.put("apiTitle",       contract.getTitle());
        model.put("apiVersion",     contract.getVersion());
        model.put("groupId",        "com.automation");
        model.put("artifactId",     projectName);

        String content = templateEngine.render("pom.mustache", model);
        return new GeneratedFile("pom.xml", content);
    }

    /**
     * Generates the {@code testng.xml} TestNG suite configuration.
     *
     * <p>This file tells TestNG which test classes to run and how to organise them
     * into suites and groups. The generated file includes all test class names so
     * TestNG can discover and run them without classpath scanning.
     *
     * @param contract the parsed API contract (provides the suite name)
     * @return a {@link GeneratedFile} with path {@code testng.xml}
     */
    private GeneratedFile generateTestNgXml(ApiContract contract) {
        // Build the list of test class names for the <class> elements in testng.xml
        List<String> testClassNames = groupEndpointsByResource(contract.getEndpoints())
                .keySet()
                .stream()
                .map(resource -> TESTS_SUBPACKAGE + "." + toClassName(resource) + "ApiTest")
                .collect(Collectors.toList());

        Map<String, Object> model = new HashMap<>();
        model.put("suiteName",      contract.getTitle() + " API Test Suite");
        model.put("testName",       contract.getTitle() + " Tests");
        model.put("testClasses",    testClassNames);

        String content = templateEngine.render("testng.mustache", model);
        return new GeneratedFile("testng.xml", content);
    }

    /**
     * Generates the {@code BaseTest.java} class that all test classes extend.
     *
     * <p>The base test class sets up the shared REST Assured {@code RequestSpecification}:
     * <ul>
     *   <li>Base URI from the API contract's server URL</li>
     *   <li>Content-Type: application/json (default for REST APIs)</li>
     *   <li>Accept: application/json</li>
     *   <li>Relaxed HTTPS validation (useful during development/testing)</li>
     * </ul>
     *
     * @param contract the parsed API contract (provides the base URL)
     * @return a {@link GeneratedFile} with the correct Maven source path
     */
    private GeneratedFile generateBaseTest(ApiContract contract) {
        Map<String, Object> model = new HashMap<>();
        model.put("packageName",    BASE_SUBPACKAGE);
        model.put("baseUrl",        contract.getBaseUrl());
        model.put("apiTitle",       contract.getTitle());
        model.put("apiVersion",     contract.getVersion());

        String content  = templateEngine.render("basetest.mustache", model);
        String filePath = "src/test/java/" + BASE_PATH + "/BaseTest.java";
        return new GeneratedFile(filePath, content);
    }

    /**
     * Generates a single POJO (Plain Old Java Object) class for an API schema.
     *
     * <p>The generated POJO has:
     * <ul>
     *   <li>A {@code @JsonProperty} annotated field for each schema property</li>
     *   <li>A no-arg constructor</li>
     *   <li>Getters and setters for all fields</li>
     *   <li>A {@code toString()} method for debug logging</li>
     * </ul>
     *
     * @param schemaName the class name (PascalCase, e.g. "User", "OrderItem")
     * @param schema     the schema describing the POJO's fields
     * @return a {@link GeneratedFile} with the correct Maven source path
     */
    private GeneratedFile generatePojoClass(String schemaName, ApiSchema schema) {
        // Convert each schema property into a simple map for the template
        List<Map<String, String>> fields = new ArrayList<>();
        for (Map.Entry<String, ApiSchema> prop : schema.getProperties().entrySet()) {
            String propName = prop.getKey();
            ApiSchema propSchema = prop.getValue();

            Map<String, String> field = new HashMap<>();
            field.put("name",          propName);
            field.put("type",          propSchema.getJavaType());
            field.put("capitalised",   capitalise(propName)); // for getXxx() / setXxx()
            field.put("description",   propSchema.getDescription());
            fields.add(field);
        }

        Map<String, Object> model = new HashMap<>();
        model.put("packageName",   MODEL_SUBPACKAGE);
        model.put("className",     schemaName);
        model.put("description",   schema.getDescription());
        model.put("fields",        fields);

        String content  = templateEngine.render("pojo.mustache", model);
        String filePath = "src/test/java/" + MODEL_PATH + "/" + schemaName + ".java";
        return new GeneratedFile(filePath, content);
    }

    /**
     * Generates a REST Assured test class for a group of endpoints that share
     * the same resource (first path segment).
     *
     * <p>Each endpoint in the group becomes one {@code @Test} method in the class.
     * The method:
     * <ul>
     *   <li>Builds a REST Assured request using the shared {@code requestSpec} from BaseTest</li>
     *   <li>Adds path parameters and query parameters from the endpoint definition</li>
     *   <li>Sets the request body if the endpoint has one</li>
     *   <li>Executes the HTTP call ({@code .get()}, {@code .post()}, etc.)</li>
     *   <li>Asserts the response status code is 200 (a sensible starter assertion)</li>
     * </ul>
     *
     * @param resourceName the API resource name, used as the class name prefix (e.g. "users")
     * @param endpoints    all endpoints belonging to this resource group
     * @param baseUrl      the API base URL (used in comments within the generated class)
     * @return a {@link GeneratedFile} with the correct Maven test source path
     */
    private GeneratedFile generateTestClass(String resourceName,
                                            List<ApiEndpoint> endpoints,
                                            String baseUrl) {
        String className = toClassName(resourceName) + "ApiTest";

        // Build one method model per endpoint
        List<Map<String, Object>> methods = new ArrayList<>();
        for (ApiEndpoint endpoint : endpoints) {
            methods.add(buildTestMethodModel(endpoint));
        }

        Map<String, Object> model = new HashMap<>();
        model.put("packageName",   TESTS_SUBPACKAGE);
        model.put("basePackage",   BASE_SUBPACKAGE);
        model.put("className",     className);
        model.put("resourceName",  resourceName);
        model.put("baseUrl",       baseUrl);
        model.put("methods",       methods);

        String content  = templateEngine.render("testclass.mustache", model);
        String filePath = "src/test/java/" + TESTS_PATH + "/" + className + ".java";
        return new GeneratedFile(filePath, content);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DATA MODEL BUILDERS — prepare data for templates
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds the data model map for a single test method template.
     *
     * <p>This map contains everything the {@code testclass.mustache} template needs
     * to render one {@code @Test} method — the method name, HTTP method, path,
     * parameters, expected status code, and whether a request body is needed.
     *
     * @param endpoint the API endpoint to generate a test method for
     * @return a map of template variable names to values
     */
    private Map<String, Object> buildTestMethodModel(ApiEndpoint endpoint) {
        Map<String, Object> method = new HashMap<>();

        // Method name: derived from operationId, converted to camelCase test method name
        // e.g. operationId "getUserById" → method name "testGetUserById"
        String methodName = "test" + capitalise(sanitiseMethodName(endpoint.getOperationId()));
        method.put("methodName",   methodName);
        method.put("httpMethod",   endpoint.getHttpMethod().toLowerCase()); // for .get(), .post()
        method.put("path",         endpoint.getPath());
        method.put("summary",      endpoint.getSummary());
        method.put("hasBody",      endpoint.hasRequestBody());

        // Build path parameter list for the template
        List<Map<String, String>> pathParams = endpoint.getParameters().stream()
                .filter(p -> "path".equalsIgnoreCase(p.getLocation()))
                .map(this::parameterToTemplateModel)
                .collect(Collectors.toList());
        method.put("pathParams", pathParams);
        method.put("hasPathParams", !pathParams.isEmpty());

        // Build query parameter list for the template
        List<Map<String, String>> queryParams = endpoint.getParameters().stream()
                .filter(p -> "query".equalsIgnoreCase(p.getLocation()))
                .map(this::parameterToTemplateModel)
                .collect(Collectors.toList());
        method.put("queryParams", queryParams);
        method.put("hasQueryParams", !queryParams.isEmpty());

        // Determine the expected success status code for the assertion
        // Use 200 as the default; prefer 201 if it's declared in the spec
        String expectedStatus = endpoint.getResponses().containsKey("201") ? "201" : "200";
        method.put("expectedStatus", expectedStatus);

        return method;
    }

    /**
     * Converts an {@link ApiParameter} into a simple string-to-string map
     * that the Mustache template can render without needing to know the
     * {@link ApiParameter} class.
     *
     * @param param the parameter to convert
     * @return a map with "name", "type", and "defaultValue" keys
     */
    private Map<String, String> parameterToTemplateModel(ApiParameter param) {
        Map<String, String> p = new HashMap<>();
        p.put("name",         param.getName());
        p.put("type",         param.getJavaType());
        p.put("defaultValue", defaultValueForType(param.getJavaType()));
        return p;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENDPOINT GROUPING
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Groups a flat list of endpoints by the first segment of their URL path.
     *
     * <p><b>Example:</b>
     * <pre>
     * Input:
     *   GET  /users
     *   POST /users
     *   GET  /users/{id}
     *   GET  /orders
     *   POST /orders/{id}/items
     *
     * Output:
     *   "users"  → [GET /users, POST /users, GET /users/{id}]
     *   "orders" → [GET /orders, POST /orders/{id}/items]
     * </pre>
     *
     * @param endpoints the full list of endpoints from the contract
     * @return a map of resource name to its endpoints; preserves insertion order
     */
    private Map<String, List<ApiEndpoint>> groupEndpointsByResource(List<ApiEndpoint> endpoints) {
        // LinkedHashMap preserves insertion order — alphabetical if the parser returned them sorted
        Map<String, List<ApiEndpoint>> groups = new java.util.LinkedHashMap<>();

        for (ApiEndpoint endpoint : endpoints) {
            String resourceName = extractResourceName(endpoint.getPath());
            // computeIfAbsent creates a new list for this key if one doesn't exist yet
            groups.computeIfAbsent(resourceName, k -> new ArrayList<>()).add(endpoint);
        }

        return groups;
    }

    /**
     * Extracts the resource name from a URL path — the first non-empty path segment.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code /users}          → {@code "users"}</li>
     *   <li>{@code /users/{id}}     → {@code "users"}</li>
     *   <li>{@code /api/v1/orders}  → {@code "api"}</li>
     *   <li>{@code /}              → {@code "root"}</li>
     * </ul>
     *
     * @param path the URL path from the OpenAPI spec
     * @return the first path segment, lowercase, or "root" if the path has no segments
     */
    private String extractResourceName(String path) {
        if (path == null || path.isBlank() || path.equals("/")) {
            return "root";
        }
        // Split on "/" and find the first non-empty segment
        String[] segments = path.split("/");
        for (String segment : segments) {
            if (!segment.isBlank()) {
                // Remove any path parameter braces: "{id}" → "id"
                return segment.replaceAll("[{}]", "").toLowerCase();
            }
        }
        return "root";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STRING UTILITIES
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Converts an API title to a safe Maven project name (artifact ID).
     *
     * <p>Rules:
     * <ul>
     *   <li>Convert to lowercase</li>
     *   <li>Replace spaces and special characters with hyphens</li>
     *   <li>Append "-tests" suffix</li>
     * </ul>
     *
     * <p>Example: {@code "Pet Store API v2"} → {@code "pet-store-api-v2-tests"}
     *
     * @param apiTitle the raw API title from the OpenAPI info section
     * @return a kebab-case project name safe for use as a Maven artifactId
     */
    private String toProjectName(String apiTitle) {
        return apiTitle
                .toLowerCase()
                .replaceAll("[^a-z0-9]+", "-") // replace anything non-alphanumeric with hyphen
                .replaceAll("^-|-$", "")       // remove leading or trailing hyphens
                + "-tests";
    }

    /**
     * Converts a resource name (kebab-case or lowercase) to a Java class name (PascalCase).
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code "users"} → {@code "Users"}</li>
     *   <li>{@code "order-items"} → {@code "OrderItems"}</li>
     *   <li>{@code "pet_store"} → {@code "PetStore"}</li>
     * </ul>
     *
     * @param name the raw resource name
     * @return a PascalCase Java class name
     */
    private String toClassName(String name) {
        if (name == null || name.isBlank()) return "Unknown";
        // Split on hyphens and underscores, capitalise each word, join
        String[] words = name.split("[-_\\s]+");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (!word.isBlank()) {
                sb.append(capitalise(word));
            }
        }
        return sb.toString();
    }

    /**
     * Capitalises the first letter of a string.
     *
     * <p>Examples: {@code "userId"} → {@code "UserId"}, {@code "get"} → {@code "Get"}
     *
     * @param s the string to capitalise
     * @return the string with its first character in uppercase
     */
    private String capitalise(String s) {
        if (s == null || s.isBlank()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * Sanitises an operation ID for use as a Java method name.
     *
     * <p>Removes characters that are not valid in Java identifiers and
     * ensures the result is a valid camelCase identifier.
     *
     * @param operationId the raw operationId from the OpenAPI spec
     * @return a sanitised Java-safe method name fragment
     */
    private String sanitiseMethodName(String operationId) {
        if (operationId == null || operationId.isBlank()) {
            return "UnknownOperation";
        }
        // Replace hyphens and non-word chars with underscores, then capitalise each segment
        String[] parts = operationId.split("[^a-zA-Z0-9]+");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!part.isBlank()) {
                sb.append(capitalise(part));
            }
        }
        return sb.length() > 0 ? sb.toString() : "UnknownOperation";
    }

    /**
     * Returns a sensible default test value for a given Java type.
     *
     * <p>These are placeholder values used in generated test methods where a
     * parameter value must be provided. A real team would replace these with
     * data from a test data file or factory.
     *
     * @param javaType the Java type name (e.g. "String", "Integer", "Boolean")
     * @return a literal value string suitable for use in Java source code
     */
    private String defaultValueForType(String javaType) {
        if (javaType == null) return "null";
        return switch (javaType) {
            case "String"  -> "\"test-value\"";
            case "Integer" -> "1";
            case "Long"    -> "1L";
            case "Double"  -> "1.0";
            case "Float"   -> "1.0f";
            case "Boolean" -> "true";
            default        -> "null";
        };
    }
}