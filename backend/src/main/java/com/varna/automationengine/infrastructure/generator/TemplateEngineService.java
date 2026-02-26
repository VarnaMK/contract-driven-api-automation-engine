package com.varna.automationengine.infrastructure.generator;

import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;
import com.varna.automationengine.domain.exception.TemplateRenderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service responsible for rendering Mustache templates into file content strings.
 *
 * <p><b>What is Mustache?</b><br>
 * Mustache is a "logic-less" template language — templates contain placeholders
 * like {@code {{variableName}}} and simple sections like {@code {{#items}}...{{/items}}},
 * but no if-statements, loops, or expressions. Logic stays in Java; templates stay clean.
 *
 * <p><b>Add this to your pom.xml:</b>
 * <pre>
 * &lt;dependency&gt;
 *     &lt;groupId&gt;com.github.spullara.mustache.java&lt;/groupId&gt;
 *     &lt;artifactId&gt;compiler&lt;/artifactId&gt;
 *     &lt;version&gt;0.9.14&lt;/version&gt;
 * &lt;/dependency&gt;
 * </pre>
 *
 * <p><b>How templates are loaded:</b><br>
 * The {@link MustacheFactory} is configured to look for templates in the
 * {@code templates/} directory on the classpath (i.e. {@code src/main/resources/templates/}).
 * Template names passed to {@link #render(String, Map)} are relative paths within
 * that directory — for example, {@code "pojo.mustache"} resolves to
 * {@code src/main/resources/templates/pojo.mustache}.
 *
 * <p><b>Template caching:</b><br>
 * Compiled {@link Mustache} objects are cached in a {@link ConcurrentHashMap} after
 * their first use. Compilation (parsing the template file) only happens once per
 * template name per application lifecycle. Subsequent renders reuse the compiled
 * template, which is significantly faster for high-volume generation.
 *
 * <p><b>Thread safety:</b><br>
 * Compiled {@link Mustache} instances are thread-safe for rendering. The cache
 * uses {@link ConcurrentHashMap} to safely handle concurrent first-access scenarios.
 */
@Component
public class TemplateEngineService {

    private static final Logger log = LoggerFactory.getLogger(TemplateEngineService.class);

    /**
     * The Mustache factory — responsible for loading and compiling template files.
     *
     * <p>{@link DefaultMustacheFactory} searches the classpath for templates.
     * The string argument {@code "templates/"} sets the root prefix so all
     * template lookups are relative to {@code src/main/resources/templates/}.
     */
    private final MustacheFactory mustacheFactory;

    /**
     * Cache of compiled {@link Mustache} instances, keyed by template name.
     *
     * <p>Compiling a template (reading and parsing the .mustache file) is relatively
     * expensive. This cache ensures each template is compiled at most once and reused
     * for all subsequent render calls during the application's lifetime.
     *
     * <p>{@link ConcurrentHashMap} is used because multiple threads could simultaneously
     * request the same template for the first time — we need the map to be thread-safe.
     */
    private final Map<String, Mustache> templateCache;

    /**
     * Constructor — initialises the Mustache factory pointing at the templates classpath root.
     *
     * <p>No external dependencies are injected because the Mustache factory is a
     * self-contained library component. Spring will instantiate this class once
     * at startup and reuse it for all generation requests.
     */
    public TemplateEngineService() {
        // "templates/" tells DefaultMustacheFactory to look in src/main/resources/templates/
        // for all template files. This prefix is prepended to every template name automatically.
        this.mustacheFactory = new DefaultMustacheFactory("templates/");
        this.templateCache   = new ConcurrentHashMap<>();

        log.info("TemplateEngineService initialised | template root=classpath:templates/");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRIMARY API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Renders a Mustache template with the given data model and returns the result as a String.
     *
     * <p><b>How this works step by step:</b>
     * <ol>
     *   <li>Look up the compiled {@link Mustache} in the cache (or compile it if first time)</li>
     *   <li>Create a {@link StringWriter} to capture the rendered output in memory</li>
     *   <li>Call {@link Mustache#execute(java.io.Writer, Object)} with the data model</li>
     *   <li>Flush the writer and return the rendered string</li>
     * </ol>
     *
     * <p><b>Data model convention:</b><br>
     * The {@code dataModel} map keys become the variable names in the template.
     * For example, {@code dataModel.put("className", "UserModel")} makes
     * {@code {{className}}} in the template render as {@code UserModel}.
     *
     * <p><b>Example usage:</b>
     * <pre>
     * Map&lt;String, Object&gt; model = new HashMap&lt;&gt;();
     * model.put("className", "User");
     * model.put("packageName", "com.example.model");
     * model.put("fields", fieldList);
     *
     * String javaSource = templateEngine.render("pojo.mustache", model);
     * </pre>
     *
     * @param templateName the filename of the template relative to {@code src/main/resources/templates/}
     *                     (e.g. {@code "pojo.mustache"}, {@code "basetest.mustache"})
     * @param dataModel    a map of variable names to values; may contain Strings, booleans,
     *                     Lists, or nested Maps/objects — Mustache accesses them reflectively
     * @return the fully rendered file content as a String
     * @throws TemplateRenderException if the template cannot be found, compiled, or rendered
     */
    public String render(String templateName, Map<String, Object> dataModel) {
        log.debug("Rendering template: {}", templateName);

        // ── Step 1: Get compiled template (from cache or compile fresh) ──────
        Mustache mustache = getCompiledTemplate(templateName);

        // ── Step 2: Render into a StringWriter ────────────────────────────────
        // StringWriter is an in-memory writer — no file I/O, no temp files.
        // The rendered output accumulates in its internal StringBuffer.
        StringWriter writer = new StringWriter();

        try {
            // execute() walks through the template, substituting {{variables}} with
            // values from dataModel, expanding {{#sections}} with list items, etc.
            mustache.execute(writer, dataModel).flush();
        } catch (IOException ex) {
            // IOException from StringWriter.flush() is extremely unlikely (it's in-memory)
            // but the API signature requires us to handle it.
            log.error("Failed to render template '{}': {}", templateName, ex.getMessage(), ex);
            throw new TemplateRenderException(
                    "Failed to render template '" + templateName + "': " + ex.getMessage(), ex
            );
        }

        String rendered = writer.toString();
        log.debug("Template '{}' rendered successfully | outputLength={} chars",
                templateName, rendered.length());

        return rendered;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns a compiled {@link Mustache} for the given template name, using the
     * cache to avoid re-compiling templates that have already been loaded.
     *
     * <p>{@link ConcurrentHashMap#computeIfAbsent} is used for thread-safe lazy
     * initialisation — if two threads request the same template simultaneously,
     * the factory will be called only once (or at most a small number of times
     * — acceptable for a cache-warm scenario).
     *
     * @param templateName the template file name (relative to the configured root)
     * @return a compiled, reusable {@link Mustache} instance
     * @throws TemplateRenderException if the template file cannot be found or compiled
     */
    private Mustache getCompiledTemplate(String templateName) {
        try {
            return templateCache.computeIfAbsent(templateName, name -> {
                log.debug("Compiling template for first time: {}", name);
                // compile() reads the template file from the classpath and parses it.
                // The resulting Mustache object is reusable and thread-safe.
                return mustacheFactory.compile(name);
            });
        } catch (Exception ex) {
            // compile() can throw if the template file is not found on the classpath
            // or if the template contains a syntax error.
            log.error("Failed to compile template '{}': {}", templateName, ex.getMessage(), ex);
            throw new TemplateRenderException(
                    "Template '" + templateName + "' could not be loaded. " +
                    "Ensure the file exists at src/main/resources/templates/" + templateName,
                    ex
            );
        }
    }
}