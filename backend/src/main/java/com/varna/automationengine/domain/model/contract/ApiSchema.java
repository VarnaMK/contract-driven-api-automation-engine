package com.varna.automationengine.domain.model.contract;

import java.util.Collections;
import java.util.Map;

/**
 * Domain model representing a data schema extracted from an OpenAPI specification.
 *
 * <p>In OpenAPI, a "schema" describes the shape of a data structure — either the
 * body of a request or the body of a response. This class is this engine's internal
 * representation of that shape, decoupled from the Swagger library's own model objects.
 *
 * <p><b>What a schema looks like in the OpenAPI spec:</b>
 * <pre>
 * components:
 *   schemas:
 *     User:
 *       type: object
 *       properties:
 *         id:
 *           type: integer
 *         name:
 *           type: string
 *         email:
 *           type: string
 * </pre>
 *
 * <p><b>What this maps to in code:</b>
 * <pre>
 * ApiSchema(
 *   name       = "User",
 *   type       = "object",
 *   properties = {
 *     "id"    → ApiSchema(name="id",    type="integer", javaType="Integer"),
 *     "name"  → ApiSchema(name="name",  type="string",  javaType="String"),
 *     "email" → ApiSchema(name="email", type="string",  javaType="String")
 *   }
 * )
 * </pre>
 *
 * <p><b>Why this class exists (not just using the Swagger library's Schema directly):</b><br>
 * The Swagger Parser library has its own {@code Schema} class. If the generator
 * used it directly, the generator would depend on the Swagger library — meaning
 * you could never swap the parser without rewriting the generator too. By
 * translating the Swagger {@code Schema} into this domain model in the parser layer,
 * the generator becomes completely parser-agnostic.
 *
 * <p>Like all domain models, this class is immutable after construction.
 */
public final class ApiSchema {

    /**
     * The name of this schema.
     *
     * <p>For top-level schemas referenced from {@code components/schemas}, this is
     * the component name (e.g. {@code "User"}, {@code "Order"}).
     * For inline schemas on individual properties, this is the property name
     * (e.g. {@code "email"}, {@code "status"}).
     */
    private final String name;

    /**
     * The OpenAPI data type of this schema.
     *
     * <p>Standard OpenAPI types: {@code "object"}, {@code "array"}, {@code "string"},
     * {@code "integer"}, {@code "number"}, {@code "boolean"}.
     * The parser maps these to Java types stored in {@link #javaType}.
     */
    private final String type;

    /**
     * The Java type that this schema maps to.
     *
     * <p>Mapping rules applied during parsing:
     * <ul>
     *   <li>{@code string}  → {@code String}</li>
     *   <li>{@code integer} → {@code Integer}</li>
     *   <li>{@code number}  → {@code Double}</li>
     *   <li>{@code boolean} → {@code Boolean}</li>
     *   <li>{@code array}   → {@code List<?>}</li>
     *   <li>{@code object}  → the schema name (e.g. {@code User}), or {@code Object}</li>
     * </ul>
     */
    private final String javaType;

    /**
     * For {@code object} type schemas: the named properties of this object.
     *
     * <p>Key: property name (e.g. {@code "email"}).
     * Value: the {@link ApiSchema} describing that property's type and structure.
     *
     * <p>Empty for primitive types (string, integer, boolean, etc.).
     */
    private final Map<String, ApiSchema> properties;

    /**
     * For {@code array} type schemas: the schema of each element in the array.
     *
     * <p>For example, if the spec declares {@code type: array, items: $ref: '#/components/schemas/User'},
     * then {@code itemSchema} would be the {@code ApiSchema} for {@code User}.
     * {@code null} for non-array types.
     */
    private final ApiSchema itemSchema;

    /**
     * A human-readable description of this schema from the OpenAPI spec.
     *
     * <p>Used as JavaDoc on generated POJO classes and fields. May be empty.
     */
    private final String description;

    /**
     * Constructs a fully populated {@code ApiSchema}.
     *
     * @param name        the schema or property name
     * @param type        the OpenAPI type ("object", "string", "integer", etc.)
     * @param javaType    the resolved Java type name ("String", "Integer", "User", etc.)
     * @param properties  for object schemas: map of property name to property schema; may be empty
     * @param itemSchema  for array schemas: the element schema; {@code null} for non-arrays
     * @param description a human-readable description; may be empty or null
     */
    public ApiSchema(String name,
                     String type,
                     String javaType,
                     Map<String, ApiSchema> properties,
                     ApiSchema itemSchema,
                     String description) {
        this.name        = name != null ? name : "";
        this.type        = type != null ? type : "object";
        this.javaType    = javaType != null ? javaType : "Object";
        this.properties  = properties != null
                ? Collections.unmodifiableMap(properties)
                : Collections.emptyMap();
        this.itemSchema  = itemSchema;
        this.description = description != null ? description : "";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GETTERS — no setters, this object is immutable
    // ─────────────────────────────────────────────────────────────────────────

    /** @return the schema or property name */
    public String getName() { return name; }

    /** @return the OpenAPI type string: "object", "string", "integer", "array", etc. */
    public String getType() { return type; }

    /** @return the resolved Java type name */
    public String getJavaType() { return javaType; }

    /** @return an unmodifiable map of property schemas; empty for non-object types */
    public Map<String, ApiSchema> getProperties() { return properties; }

    /** @return the array element schema, or {@code null} if this is not an array */
    public ApiSchema getItemSchema() { return itemSchema; }

    /** @return a human-readable description; empty string if not specified */
    public String getDescription() { return description; }

    /**
     * Convenience method — returns {@code true} if this schema represents an object
     * with named properties (i.e. generates a POJO class).
     *
     * @return {@code true} if type is "object" and properties are present
     */
    public boolean isObject() {
        return "object".equalsIgnoreCase(type) && !properties.isEmpty();
    }

    /**
     * Convenience method — returns {@code true} if this schema represents an array.
     *
     * @return {@code true} if type is "array"
     */
    public boolean isArray() {
        return "array".equalsIgnoreCase(type);
    }

    @Override
    public String toString() {
        return "ApiSchema{" +
                "name='" + name + '\'' +
                ", type='" + type + '\'' +
                ", javaType='" + javaType + '\'' +
                ", propertyCount=" + properties.size() +
                ", isArray=" + isArray() +
                '}';
    }
}