package com.varna.automationengine.application.service;

import com.varna.automationengine.application.usecase.GenerateAutomationProjectUseCase;
import com.varna.automationengine.domain.exception.ContractParseException;
import com.varna.automationengine.domain.exception.ProjectGenerationException;
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
 * Concrete implementation of {@link GenerateAutomationProjectUseCase}.
 *
 * <p><b>Responsibility of this class:</b><br>
 * This service owns the <em>orchestration</em> of the generation pipeline.
 * It does NOT do the actual parsing, generating, or zipping itself — it
 * delegates each step to a specialist component (parser, generator, archiver).
 * Its only job is to call those components in the right order, pass data
 * between them, and handle any errors that arise.
 *
 * <p><b>Why is this in the {@code application.service} package, not {@code application.usecase}?</b><br>
 * Following the principle of separating interface from implementation:
 * <ul>
 *   <li>{@code application.usecase} — contains interfaces (the "what")</li>
 *   <li>{@code application.service} — contains implementations (the "how")</li>
 * </ul>
 * This keeps the packages clean and makes it immediately obvious which classes
 * define contracts vs. which classes fulfil them.
 *
 * <p><b>The {@code @Service} annotation:</b><br>
 * This is a Spring stereotype annotation. It tells Spring to automatically
 * detect this class during startup (via component scanning) and register it
 * as a bean in the application context. Because this class implements
 * {@link GenerateAutomationProjectUseCase}, Spring will inject it wherever
 * that interface is declared as a dependency (e.g. in the controller).
 *
 * <p><b>Architecture note:</b><br>
 * This class sits in the {@code application} layer. It is allowed to:
 * <ul>
 *   <li>Import and use domain models and exceptions</li>
 *   <li>Import Spring framework annotations ({@code @Service})</li>
 *   <li>Depend on infrastructure via interfaces (ports) — NOT concrete adapters</li>
 * </ul>
 * It must NEVER directly import or instantiate infrastructure classes
 * (e.g. {@code OpenApiParserAdapter}, {@code FreemarkerTemplateRenderer}).
 * Those are injected via their port interfaces.
 */
@Service
public class GenerateAutomationProjectService implements GenerateAutomationProjectUseCase {

    private static final Logger log = LoggerFactory.getLogger(GenerateAutomationProjectService.class);

    // ─────────────────────────────────────────────────────────────────────────
    // DEPENDENCIES (ports — interfaces that infrastructure will implement)
    //
    // These fields are currently commented out because the port interfaces and
    // their infrastructure implementations do not exist yet. They are declared
    // here as a clear blueprint of what this service will need.
    //
    // When each layer is generated, you will:
    //   1. Uncomment the field
    //   2. Add it as a constructor parameter
    //   3. Remove the corresponding TODO stub in the execute() method
    //
    // Each dependency is an INTERFACE (port), not a concrete class.
    // Spring will inject the correct implementation at runtime automatically.
    // ─────────────────────────────────────────────────────────────────────────

    private final OpenApiParser contractParser;
    private final ProjectGenerator projectGenerator;
    private final ZipUtility zipUtility;

    /**
     * Constructor injection — the only constructor in this class.
     *
     * <p><b>Why constructor injection?</b><br>
     * Constructor injection is the recommended approach in Spring Boot for three reasons:
     * <ol>
     *   <li><b>Explicit dependencies</b> — you can see exactly what this class needs
     *       to function just by reading the constructor signature</li>
     *   <li><b>Immutability</b> — fields can be declared {@code final}, meaning they
     *       can never be accidentally reassigned after construction</li>
     *   <li><b>Testability</b> — in unit tests you can instantiate this class with
     *       mock dependencies directly, no Spring context needed</li>
     * </ol>
     *
     * <p>When {@code @Service} is present and a class has exactly ONE constructor,
     * Spring automatically uses that constructor for injection — no {@code @Autowired}
     * annotation is required on the constructor (Spring Boot 2.x and above).
     *
     * <p>TODO: As port interfaces are added, include them as parameters here.
     */
    public GenerateAutomationProjectService(OpenApiParser contractParser,
                                            ProjectGenerator projectGenerator,
                                            ZipUtility zipUtility) {
        this.contractParser   = contractParser;
        this.projectGenerator = projectGenerator;
        this.zipUtility       = zipUtility;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // USE CASE IMPLEMENTATION
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p><b>Current state:</b> Stubbed. Each stage of the pipeline is marked
     * with a TODO and returns a placeholder result. The orchestration structure
     * (sequence of steps, logging, error handling) is fully in place and ready
     * for real implementations to be plugged in.
     *
     * <p><b>Full pipeline (once all layers are implemented):</b>
     * <pre>
     *   Stage 1 — Read file bytes from MultipartFile
     *       ↓
     *   Stage 2 — Parse bytes into ApiContract domain model  [ContractParserPort]
     *       ↓
     *   Stage 3 — Generate project files from ApiContract    [ProjectGeneratorPort]
     *       ↓
     *   Stage 4 — Zip all generated files into a byte[]      [ArchivePort]
     *       ↓
     *   Return byte[] to controller
     * </pre>
     */
    @Override
    public byte[] execute(MultipartFile contractFile, String traceId) {

        log.info("[traceId={}] GenerateAutomationProjectService.execute() started | filename={}",
                traceId, contractFile.getOriginalFilename());

        // ── Stage 1 + 2: Parse the uploaded file into an ApiContract domain model ───
        //
        // The OpenApiParser (implemented by SwaggerOpenApiParser) handles both
        // reading the raw bytes AND converting the spec content into the ApiContract.
        // It throws ContractParseException on any failure — the controller maps
        // that to HTTP 422 Unprocessable Entity.
        ApiContract contract = contractParser.parse(contractFile, traceId);

        log.info("[traceId={}] Contract parsed successfully | {}", traceId, contract);

        // ── Stage 3: Generate all project source files from the parsed contract ─────
        //
        // RestAssuredProjectGenerator fans out to produce pom.xml, testng.xml,
        // BaseTest.java, POJO classes, and one test class per resource group.
        // Throws ProjectGenerationException if generation fails.
        log.info("[traceId={}] Stage 3 — generating project files", traceId);
        GeneratedProject generatedProject = projectGenerator.generate(contract, traceId);
        log.info("[traceId={}] Stage 3 complete | {}", traceId, generatedProject);

        // ── Stage 4: Package all generated files into a ZIP archive ───────────────
        //
        // ZipUtility writes each GeneratedFile into a ZipOutputStream in memory
        // and returns the complete ZIP as a byte[].
        log.info("[traceId={}] Stage 4 — packaging project into ZIP", traceId);
        byte[] zipBytes = zipUtility.zip(generatedProject, traceId);

        log.info("[traceId={}] GenerateAutomationProjectService.execute() complete | zip size={} bytes",
                traceId, zipBytes.length);

        return zipBytes;
    }
}