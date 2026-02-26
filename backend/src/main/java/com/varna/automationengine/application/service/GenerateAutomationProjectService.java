package com.varna.automationengine.application.service;

import com.varna.automationengine.application.usecase.GenerateAutomationProjectUseCase;
import com.varna.automationengine.domain.exception.ContractParseException;
import com.varna.automationengine.domain.exception.ProjectGenerationException;
import com.varna.automationengine.domain.exception.TemplateRenderException;
import com.varna.automationengine.domain.model.contract.ApiContract;
import com.varna.automationengine.domain.model.project.GeneratedProject;
import com.varna.automationengine.infrastructure.generator.ProjectGenerator;
import com.varna.automationengine.infrastructure.generator.ZipUtility;
import com.varna.automationengine.infrastructure.parser.OpenApiParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Orchestrates the full API automation project generation pipeline.
 *
 * <p>This class is the <b>application layer</b> of the system — it sits between
 * the HTTP controller (which handles requests) and the infrastructure components
 * (which do the real work). Its only job is to call the right components in the
 * right order and return the result.
 *
 * <p><b>Complete pipeline this class drives:</b>
 * <pre>
 *
 *  [HTTP Controller]
 *        │  MultipartFile (uploaded .yaml / .json spec)
 *        │  traceId       (unique request ID for log correlation)
 *        ▼
 *  ┌─────────────────────────────────────────────────────────────┐
 *  │         GenerateAutomationProjectService  (THIS CLASS)      │
 *  │                                                             │
 *  │  Stage 1 ──► OpenApiParser.parse()                         │
 *  │               │  reads file bytes                          │
 *  │               │  extracts endpoints, schemas, metadata     │
 *  │               ▼                                             │
 *  │           ApiContract  ◄── domain model hand-off point     │
 *  │               │                                             │
 *  │  Stage 2 ──► ProjectGenerator.generate()                   │
 *  │               │  renders pom.xml          via Mustache     │
 *  │               │  renders testng.xml        via Mustache    │
 *  │               │  renders BaseTest.java      via Mustache   │
 *  │               │  renders POJO classes       via Mustache   │
 *  │               │  renders test classes       via Mustache   │
 *  │               ▼                                             │
 *  │           GeneratedProject  (list of GeneratedFile objects) │
 *  │               │                                             │
 *  │  Stage 3 ──► ZipUtility.zip()                              │
 *  │               │  writes each file into ZipOutputStream     │
 *  │               │  entirely in memory — no temp files        │
 *  │               ▼                                             │
 *  │           byte[]  (complete ZIP archive)                    │
 *  └─────────────────────────────────────────────────────────────┘
 *        │
 *        ▼
 *  [HTTP Controller]  → HTTP 200 + Content-Disposition: attachment
 *
 * </pre>
 *
 * <p><b>Exception propagation — what each exception means and where it is handled:</b>
 * <ul>
 *   <li>{@link ContractParseException} — thrown by Stage 1 (parser) when the file is not
 *       valid OpenAPI. Caught by the controller → HTTP 422 Unprocessable Entity.</li>
 *   <li>{@link TemplateRenderException} — thrown by Stage 2 (generator) when a
 *       Mustache template file is missing or has a syntax error. Caught by the
 *       controller → HTTP 500 Internal Server Error.</li>
 *   <li>{@link ProjectGenerationException} — thrown by Stage 2 or Stage 3 when
 *       no files are produced or the ZIP stream fails. Caught by the
 *       controller → HTTP 500 Internal Server Error.</li>
 * </ul>
 * This class does NOT catch any of these exceptions. Letting them propagate keeps
 * the error message precise and avoids wrapping context that is already set at source.
 *
 * <p><b>Spring annotation: {@code @Service}</b><br>
 * Marks this class as a Spring-managed bean (singleton by default). Spring auto-detects
 * it at startup via component scanning and registers it in the application context.
 * Because this class implements {@link GenerateAutomationProjectUseCase}, Spring injects
 * it automatically into {@code ProjectGeneratorController} without any extra wiring config.
 *
 * <p><b>Architecture rules for this class:</b>
 * <ul>
 *   <li>✅ MAY use domain models ({@code ApiContract}, {@code GeneratedProject})</li>
 *   <li>✅ MAY use domain exceptions ({@code ContractParseException}, etc.)</li>
 *   <li>✅ MAY depend on infrastructure INTERFACES ({@code OpenApiParser}, {@code ProjectGenerator})</li>
 *   <li>✅ MAY use Spring annotations ({@code @Service})</li>
 *   <li>❌ MUST NOT import concrete infrastructure classes ({@code SwaggerOpenApiParser}, etc.)</li>
 *   <li>❌ MUST NOT contain HTTP concepts ({@code ResponseEntity}, status codes, headers)</li>
 *   <li>❌ MUST NOT contain template or ZIP logic — those live in infrastructure</li>
 * </ul>
 */
@Service
public class GenerateAutomationProjectService implements GenerateAutomationProjectUseCase {

    private static final Logger log = LoggerFactory.getLogger(GenerateAutomationProjectService.class);

    // ─────────────────────────────────────────────────────────────────────────
    // DEPENDENCIES — all typed to INTERFACES, declared final
    //
    // Typed to interfaces (not concrete classes) so that:
    //   1. This class is decoupled from the implementation details of each layer
    //   2. Spring can inject any bean that implements the interface
    //   3. Unit tests can inject mocks or fakes without needing Spring at all
    //
    // Declared final so that:
    //   1. They can only ever be assigned once — in the constructor below
    //   2. The compiler prevents accidental reassignment later in the class
    //   3. The object is effectively immutable after construction
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Parses an uploaded OpenAPI spec file into an {@link ApiContract} domain model.
     * Spring injects: {@code SwaggerOpenApiParser} (infrastructure layer).
     */
    private final OpenApiParser contractParser;

    /**
     * Generates all REST Assured project source files from an {@link ApiContract}.
     * Spring injects: {@code RestAssuredProjectGenerator} (infrastructure layer).
     */
    private final ProjectGenerator projectGenerator;

    /**
     * Packages a {@link GeneratedProject} into a ZIP archive and returns raw bytes.
     * Spring injects: {@code ZipUtility} (infrastructure layer).
     */
    private final ZipUtility zipUtility;

    // ─────────────────────────────────────────────────────────────────────────
    // CONSTRUCTOR — the ONLY place dependencies are assigned
    //
    // WHY CONSTRUCTOR INJECTION (not @Autowired field injection)?
    //
    //   @Autowired on a field:          Constructor injection (this approach):
    //   ──────────────────────          ────────────────────────────────────────
    //   Hidden — not obvious what       Explicit — constructor signature shows
    //   the class depends on            exactly what the class needs to work
    //
    //   Fields cannot be final          Fields CAN be final → immutability
    //
    //   Need Spring context to test     Can test with: new Service(mockA, mockB, mockC)
    //
    //   Circular deps fail at runtime   Circular deps caught at Spring startup
    //
    // Spring Boot 2.x+ rule:
    //   When a class has exactly ONE constructor, Spring uses it automatically.
    //   You do NOT need to write @Autowired on the constructor.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Constructs the service with all required dependencies injected by Spring.
     *
     * <p>Spring resolves each parameter by finding a bean in the application context
     * that implements the declared interface type:
     * <ul>
     *   <li>{@code OpenApiParser}    → finds {@code SwaggerOpenApiParser} ({@code @Component})</li>
     *   <li>{@code ProjectGenerator} → finds {@code RestAssuredProjectGenerator} ({@code @Component})</li>
     *   <li>{@code ZipUtility}       → finds {@code ZipUtility} ({@code @Component})</li>
     * </ul>
     *
     * @param contractParser   parses uploaded OpenAPI files into domain models
     * @param projectGenerator generates all source files from a parsed contract
     * @param zipUtility       packages generated files into a ZIP byte array
     */
    public GenerateAutomationProjectService(final OpenApiParser contractParser,
                                            final ProjectGenerator projectGenerator,
                                            final ZipUtility zipUtility) {
        this.contractParser   = contractParser;
        this.projectGenerator = projectGenerator;
        this.zipUtility       = zipUtility;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // USE CASE ENTRY POINT — called by the controller
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Executes the full three-stage project generation pipeline.
     *
     * <p>Delegates each stage to a dedicated infrastructure component. This method
     * contains zero business logic of its own — it is a pure orchestrator.
     * Exceptions from any stage are not caught here; they propagate directly
     * to the controller which maps them to appropriate HTTP responses.
     *
     * @param contractFile the uploaded OpenAPI specification file (.yaml, .yml, or .json);
     *                     guaranteed non-null and non-empty — validated by the controller
     *                     before this method is called
     * @param traceId      a UUID string unique to this HTTP request; pass this to every
     *                     downstream component so all log lines for this request share it
     * @return the generated REST Assured project as a complete ZIP archive in raw bytes,
     *         ready to be written into the HTTP response body by the controller
     * @throws ContractParseException     if Stage 1 fails (invalid or unrecognisable spec)
     * @throws TemplateRenderException    if Stage 2 fails (template missing or broken)
     * @throws ProjectGenerationException if Stage 2 or Stage 3 fails (no files, ZIP error)
     */
    @Override
    public byte[] execute(MultipartFile contractFile, String traceId) {

        log.info("[traceId={}] ═══ Pipeline START | file='{}' | size={} bytes ═══",
                traceId,
                contractFile.getOriginalFilename(),
                contractFile.getSize());

        // ═════════════════════════════════════════════════════════════════════
        // STAGE 1 — PARSE
        //
        // INPUT:  MultipartFile  (raw bytes of the uploaded spec file)
        // OUTPUT: ApiContract    (structured domain model)
        //
        // What SwaggerOpenApiParser does internally:
        //   1. Reads the file bytes as a UTF-8 string
        //   2. Feeds them to OpenAPIV3Parser (Swagger library)
        //   3. Resolves all $ref references (setResolveFully = true)
        //   4. Validates: info section present, at least one path defined
        //   5. Maps every path+method combination to an ApiEndpoint object
        //   6. Maps every components/schema to an ApiSchema object
        //   7. Wraps everything in an ApiContract and returns it
        //
        // If anything in steps 1–7 fails, SwaggerOpenApiParser throws
        // ContractParseException — the controller maps that to HTTP 422.
        // ═════════════════════════════════════════════════════════════════════

        log.info("[traceId={}] ─── Stage 1: Parsing OpenAPI contract ───", traceId);

        ApiContract contract = contractParser.parse(contractFile, traceId);

        //  contract now contains:
        //  ┌─────────────────────────────────────────────────────────────────┐
        //  │  contract.getTitle()       "Pet Store API"                       │
        //  │  contract.getVersion()     "1.0.0"                               │
        //  │  contract.getBaseUrl()     "https://petstore.example.com/v3"     │
        //  │  contract.getEndpoints()   [GET /pets, POST /pets, GET /pets/{id}]│
        //  │  contract.getSchemas()     {Pet: ApiSchema, Error: ApiSchema}     │
        //  └─────────────────────────────────────────────────────────────────┘
        log.info("[traceId={}] ─── Stage 1 DONE | {}", traceId, contract);


        // ═════════════════════════════════════════════════════════════════════
        // STAGE 2 — GENERATE
        //
        // INPUT:  ApiContract     (from Stage 1)
        // OUTPUT: GeneratedProject (list of in-memory GeneratedFile objects)
        //
        // What RestAssuredProjectGenerator does internally:
        //   1. Renders pom.xml         using pom.mustache template
        //   2. Renders testng.xml      using testng.mustache template
        //   3. Renders BaseTest.java   using basetest.mustache template
        //   4. For each object schema  → renders a POJO using pojo.mustache
        //   5. Groups endpoints by first path segment (e.g. /pets → "pets")
        //   6. For each group          → renders a test class using testclass.mustache
        //   7. Collects all GeneratedFile objects into a GeneratedProject
        //
        // If a .mustache file is missing or broken → TemplateRenderException (HTTP 500)
        // If no files were produced                 → ProjectGenerationException (HTTP 500)
        // ═════════════════════════════════════════════════════════════════════

        log.info("[traceId={}] ─── Stage 2: Generating project files ───", traceId);

        GeneratedProject generatedProject = projectGenerator.generate(contract, traceId);

        //  generatedProject now contains:
        //  ┌─────────────────────────────────────────────────────────────────┐
        //  │  generatedProject.getProjectName()  "pet-store-api-tests"       │
        //  │  generatedProject.getFileCount()    6 (or however many produced) │
        //  │  generatedProject.getFiles()        [                            │
        //  │    GeneratedFile("pom.xml",         "<?xml version=..."),        │
        //  │    GeneratedFile("testng.xml",       "<!DOCTYPE..."),             │
        //  │    GeneratedFile("src/test/.../BaseTest.java", "package..."),     │
        //  │    GeneratedFile("src/test/.../Pet.java",      "package..."),     │
        //  │    GeneratedFile("src/test/.../PetsApiTest.java","package..."),   │
        //  │  ]                                                               │
        //  └─────────────────────────────────────────────────────────────────┘
        log.info("[traceId={}] ─── Stage 2 DONE | {}", traceId, generatedProject);


        // ═════════════════════════════════════════════════════════════════════
        // STAGE 3 — ZIP
        //
        // INPUT:  GeneratedProject  (list of GeneratedFile objects from Stage 2)
        // OUTPUT: byte[]            (complete ZIP archive, entirely in memory)
        //
        // What ZipUtility does internally:
        //   1. Creates a ByteArrayOutputStream (no disk writes)
        //   2. Wraps it in a ZipOutputStream with DEFLATED compression
        //   3. For each GeneratedFile:
        //      a. Creates a ZipEntry named "{projectName}/{file.relativePath}"
        //      b. Writes the file content bytes (UTF-8) into the entry
        //      c. Closes the entry
        //   4. Closes the ZipOutputStream (writes the ZIP central directory)
        //   5. Returns byteStream.toByteArray()
        //
        // ZIP structure produced:
        //   pet-store-api-tests/
        //   ├── pom.xml
        //   ├── testng.xml
        //   └── src/test/java/com/automation/tests/
        //       ├── base/BaseTest.java
        //       ├── model/Pet.java
        //       └── tests/PetsApiTest.java
        //
        // If the ZipOutputStream throws IOException → ProjectGenerationException (HTTP 500)
        // ═════════════════════════════════════════════════════════════════════

        log.info("[traceId={}] ─── Stage 3: Packaging into ZIP ───", traceId);

        byte[] zipBytes = zipUtility.zip(generatedProject, traceId);

        log.info("[traceId={}] ═══ Pipeline DONE | zipSize={} bytes | files={} ═══",
                traceId, zipBytes.length, generatedProject.getFileCount());

        // Hand the raw ZIP bytes back to the controller.
        // The controller sets:
        //   Content-Disposition: attachment; filename="pet-store-api-tests.zip"
        //   Content-Type: application/zip
        //   Content-Length: {zipBytes.length}
        return zipBytes;
    }
}