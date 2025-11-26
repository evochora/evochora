package org.evochora.node.processes.http.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.typesafe.config.Config;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiResponse;
import org.evochora.node.processes.http.AbstractController;
import org.evochora.node.spi.ServiceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Controller for serving OpenAPI documentation with dynamic basePaths injected.
 * <p>
 * This controller reads the generated OpenAPI specification and injects the basePaths
 * from the route configuration, ensuring that all paths in the documentation reflect
 * the actual API endpoints.
 * <p>
 * Key features:
 * <ul>
 *   <li>Loads generated OpenAPI specification from classpath</li>
 *   <li>Receives controller basePaths from HttpServerProcess via options</li>
 *   <li>Scans controller classes for @OpenApi annotations</li>
 *   <li>Combines relative paths with basePaths to create full paths</li>
 *   <li>Returns modified OpenAPI specification with correct paths</li>
 * </ul>
 * <p>
 * <strong>Note:</strong> This controller requires special handling by HttpServerProcess
 * to inject basePaths. This is an exception to the plugin pattern, acceptable because
 * OpenAPI documentation is a cross-cutting concern that needs knowledge of all routes.
 * <p>
 * Thread Safety: This controller is thread-safe and can handle concurrent requests.
 */
public class OpenApiController extends AbstractController {

    private static final Logger LOGGER = LoggerFactory.getLogger(OpenApiController.class);

    /**
     * Constructs a new OpenApiController.
     *
     * @param registry The central service registry.
     * @param options  The configuration for this controller (should include the routes config).
     */
    public OpenApiController(final ServiceRegistry registry, final Config options) {
        super(registry, options);
    }

    @Override
    public void registerRoutes(final Javalin app, final String basePath) {
        final String fullPath = (basePath + "/openapi.json").replaceAll("//", "/");
        LOGGER.debug("Registering OpenAPI endpoint at: {}", fullPath);
        app.get(fullPath, this::getOpenApiSpec);
    }

    @OpenApi(
        path = "openapi.json",
        methods = {HttpMethod.GET},
        summary = "Get OpenAPI specification",
        description = "Returns the OpenAPI 3.0 specification with dynamic basePaths injected",
        tags = {"api-docs"},
        responses = {
            @OpenApiResponse(status = "200", description = "OK", content = @OpenApiContent(from = Map.class)),
            @OpenApiResponse(status = "404", description = "OpenAPI specification not found"),
            @OpenApiResponse(status = "500", description = "Internal server error")
        }
    )
    private void getOpenApiSpec(final Context ctx) {
        try {
            // Load the generated OpenAPI file from classpath
            final InputStream openApiStream = getClass().getClassLoader()
                .getResourceAsStream("openapi-plugin/openapi-default.json");

            if (openApiStream == null) {
                LOGGER.warn("OpenAPI file not found in classpath");
                ctx.status(HttpStatus.NOT_FOUND).result("OpenAPI specification not found");
                return;
            }

            // Parse JSON
            final ObjectMapper mapper = new ObjectMapper();
            final ObjectNode openApiJson = (ObjectNode) mapper.readTree(openApiStream);
            openApiStream.close();

            // Get paths node
            final JsonNode pathsNode = openApiJson.get("paths");
            if (pathsNode == null || !pathsNode.isObject()) {
                ctx.json(openApiJson);
                return;
            }

            // Build path metadata (relative→full mappings + annotation metadata)
            final PathMetadata pathMetadata = buildPathMetadata();
            final Map<String, List<Map.Entry<String, String>>> relativePathToFullPaths = pathMetadata.relativePathToFullPaths();
            
            LOGGER.debug("OpenAPI: Found {} relative paths with mappings", relativePathToFullPaths.size());
            if (relativePathToFullPaths.isEmpty()) {
                LOGGER.warn("OpenAPI: No path mappings found! Check if controllers are being scanned correctly.");
            }

            // Update all paths with their basePaths
            // First pass: collect all changes to avoid ConcurrentModificationException
            final ObjectNode pathsObject = (ObjectNode) pathsNode;
            final Map<String, JsonNode> pathsToAdd = new HashMap<>();
            final List<String> pathsToRemove = new ArrayList<>();

            // Collect all changes first
            // Match relative paths from OpenAPI file with our mappings
            for (final Iterator<Map.Entry<String, JsonNode>> it = pathsObject.fields(); it.hasNext(); ) {
                final Map.Entry<String, JsonNode> entry = it.next();
                String relativePath = entry.getKey();
                
                // Normalize path: ensure it starts with /
                final String normalizedRelativePath = relativePath.startsWith("/") 
                    ? relativePath 
                    : "/" + relativePath;
                
                // Find matching full paths for this relative path
                final List<Map.Entry<String, String>> fullPathMappings = relativePathToFullPaths.get(normalizedRelativePath);
                
                if (fullPathMappings != null && !fullPathMappings.isEmpty()) {
                    // If multiple controllers have the same relative path, we need to handle all of them
                    // But the OpenAPI file only has one entry per relative path, so we need to duplicate it
                    for (final Map.Entry<String, String> mapping : fullPathMappings) {
                        final String fullPath = mapping.getKey();
                        
                        if (!fullPath.equals(relativePath)) {
                            // Path needs to be updated/duplicated.
                            // IMPORTANT: We must deep-copy the path node here; otherwise the
                            // same ObjectNode instance would be referenced by multiple full
                            // paths, and any later modifications (e.g., applying annotation
                            // metadata) would affect all of them identically.
                            LOGGER.debug("OpenAPI: Adding path: {} -> {}", relativePath, fullPath);
                            final JsonNode originalPathNode = entry.getValue();
                            final JsonNode clonedPathNode = originalPathNode.deepCopy();
                            pathsToAdd.put(fullPath, clonedPathNode);
                        }
                    }
                    
                    // Remove the original relative path if it doesn't match any full path
                    boolean shouldRemove = true;
                    for (final Map.Entry<String, String> mapping : fullPathMappings) {
                        if (mapping.getKey().equals(relativePath)) {
                            shouldRemove = false;
                            break;
                        }
                    }
                    if (shouldRemove) {
                        pathsToRemove.add(relativePath);
                    }
                    
                    // If there are multiple mappings, log info
                    if (fullPathMappings.size() > 1) {
                        LOGGER.debug("OpenAPI: Multiple controllers have the same relative path '{}', creating {} full paths", 
                            normalizedRelativePath, fullPathMappings.size());
                    }
                } else {
                    LOGGER.warn("OpenAPI: No basePath found for path: {}", relativePath);
                }
            }

            // Second pass: apply changes
            for (final String oldPath : pathsToRemove) {
                pathsObject.remove(oldPath);
            }
            for (final Map.Entry<String, JsonNode> entry : pathsToAdd.entrySet()) {
                pathsObject.set(entry.getKey(), entry.getValue());
            }
            
            LOGGER.debug("OpenAPI: Updated {} paths with basePaths", pathsToRemove.size());
            if (pathsToRemove.isEmpty()) {
                LOGGER.warn("OpenAPI: No paths were updated! This means no basePaths were matched.");
            }

            // Post-process operations using annotation metadata so that each
            // full path (including duplicated relative paths like "/ticks")
            // uses the summary/description/tags from its own @OpenApi annotation.
            applyAnnotationMetadata(openApiJson, pathMetadata.fullPathToAnnotation());

            // Convert string examples to JSON objects for proper ReDoc formatting
            convertStringExamplesToJsonObjects(openApiJson);

            ctx.contentType("application/json");
            ctx.status(HttpStatus.OK).json(openApiJson);
        } catch (final Exception e) {
            LOGGER.error("Failed to process OpenAPI specification", e);
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .result("Failed to generate OpenAPI specification: " + e.getMessage());
        }
    }

    /**
     * Holds metadata derived from controller annotations:
     * <ul>
     *   <li>relativePathToFullPaths: normalized relative path → (fullPath, basePath) pairs</li>
     *   <li>fullPathToAnnotation: fullPath → @OpenApi annotation of the handling method</li>
     * </ul>
     */
    private record PathMetadata(
        Map<String, List<Map.Entry<String, String>>> relativePathToFullPaths,
        Map<String, OpenApi> fullPathToAnnotation
    ) {
    }

    /**
     * Builds path metadata from controller annotations.
     * <p>
     * This is the single source of truth for how relative annotation paths map
     * to full paths and which @OpenApi metadata belongs to each full path.
     *
     * @return PathMetadata with relative and full-path mappings.
     */
    private PathMetadata buildPathMetadata() {
        // Map: relativePath -> List of (fullPath, basePath) pairs
        final Map<String, List<Map.Entry<String, String>>> relativePathToFullPaths = new HashMap<>();
        // Map: fullPath -> @OpenApi annotation
        final Map<String, OpenApi> fullPathToAnnotation = new HashMap<>();

        final Map<String, String> controllerBasePaths = extractControllerBasePaths();

        LOGGER.debug("OpenAPI: Extracted {} controller basePaths", controllerBasePaths.size());
        if (controllerBasePaths.isEmpty()) {
            LOGGER.warn("OpenAPI: No controller basePaths extracted! Check routes config path.");
        } else {
            for (final Map.Entry<String, String> entry : controllerBasePaths.entrySet()) {
                LOGGER.debug("OpenAPI: Controller: {} -> basePath: {}", entry.getKey(), entry.getValue());
            }
        }

        // Build maps: for each controller, scan annotations and build full paths
        for (final Map.Entry<String, String> entry : controllerBasePaths.entrySet()) {
            final String className = entry.getKey();
            final String basePath = entry.getValue();

            try {
                final Class<?> controllerClass = Class.forName(className);
                final Method[] methods = controllerClass.getDeclaredMethods();

                LOGGER.debug("OpenAPI: Scanning controller {} for @OpenApi annotations (found {} methods)",
                    className, methods.length);

                for (final Method method : methods) {
                    final OpenApi annotation = method.getAnnotation(OpenApi.class);
                    if (annotation != null) {
                        final String annotationPath = annotation.path();
                        // Normalize annotation path (add leading slash if missing)
                        final String normalizedAnnotationPath = annotationPath.startsWith("/")
                            ? annotationPath
                            : "/" + annotationPath;

                        // Build full path
                        final String fullPath = (basePath + normalizedAnnotationPath).replaceAll("//+", "/");

                        // Store in map: relativePath -> (fullPath, basePath)
                        relativePathToFullPaths
                            .computeIfAbsent(normalizedAnnotationPath, k -> new ArrayList<>())
                            .add(new java.util.AbstractMap.SimpleEntry<>(fullPath, basePath));

                        // Store annotation for this full path
                        fullPathToAnnotation.put(fullPath, annotation);

                        LOGGER.debug("OpenAPI: Found annotation path: {} -> fullPath: {} (basePath: {})",
                            annotationPath, fullPath, basePath);
                    }
                }
            } catch (final ClassNotFoundException e) {
                LOGGER.warn("Controller class not found: {}", className);
            }
        }

        LOGGER.debug("OpenAPI: Built relativePath-to-fullPaths map with {} entries", relativePathToFullPaths.size());
        LOGGER.debug("OpenAPI: Built fullPath-to-annotation map with {} entries", fullPathToAnnotation.size());

        return new PathMetadata(relativePathToFullPaths, fullPathToAnnotation);
    }

    /**
     * Applies metadata from @OpenApi annotations back onto the JSON tree so that
     * each full path uses its own summary/description/tags, even when multiple
     * controllers share the same relative path (e.g. "/ticks").
     *
     * @param openApiJson         The OpenAPI JSON root node.
     * @param fullPathToAnnotation Map of full paths to their @OpenApi annotations.
     */
    private void applyAnnotationMetadata(final ObjectNode openApiJson,
                                         final Map<String, OpenApi> fullPathToAnnotation) {
        final JsonNode pathsNode = openApiJson.get("paths");
        if (!(pathsNode instanceof ObjectNode)) {
            return;
        }

        final ObjectNode pathsObject = (ObjectNode) pathsNode;

        for (final Map.Entry<String, OpenApi> entry : fullPathToAnnotation.entrySet()) {
            final String fullPath = entry.getKey();
            final OpenApi annotation = entry.getValue();

            final JsonNode pathNode = pathsObject.get(fullPath);
            if (!(pathNode instanceof ObjectNode)) {
                continue;
            }

            final ObjectNode pathObject = (ObjectNode) pathNode;

            for (final HttpMethod methodEnum : annotation.methods()) {
                final String methodName = methodEnum.name().toLowerCase();
                final JsonNode methodNode = pathObject.get(methodName);
                if (!(methodNode instanceof ObjectNode)) {
                    continue;
                }

                final ObjectNode methodObject = (ObjectNode) methodNode;

                // Override summary if present on annotation
                if (annotation.summary() != null && !annotation.summary().isEmpty()) {
                    methodObject.put("summary", annotation.summary());
                }

                // Override description if present on annotation
                if (annotation.description() != null && !annotation.description().isEmpty()) {
                    methodObject.put("description", annotation.description());
                }

                // Override tags if present on annotation
                final String[] tags = annotation.tags();
                if (tags != null && tags.length > 0) {
                    methodObject.remove("tags");
                    final com.fasterxml.jackson.databind.node.ArrayNode tagsArray = methodObject.putArray("tags");
                    for (final String tag : tags) {
                        tagsArray.add(tag);
                    }
                }
            }
        }
    }

    /**
     * Extracts controller basePaths from the options.
     * <p>
     * HttpServerProcess injects basePaths under the "basePaths" key in options,
     * where each controller class name maps to its basePath.
     *
     * @return Map from controller class name to basePath
     */
    private Map<String, String> extractControllerBasePaths() {
        final Map<String, String> controllerBasePaths = new HashMap<>();

        // HttpServerProcess injects basePaths under "basePaths" key
        if (options.hasPath("basePaths")) {
            try {
                final Config basePathsConfig = options.getConfig("basePaths");
                // ConfigFactory.parseMap() with dots in keys creates nested structure
                // We need to recursively extract all string values
                extractBasePathsRecursive(basePathsConfig.root(), "", controllerBasePaths);
            } catch (final Exception e) {
                LOGGER.warn("OpenAPI: Failed to read basePaths from options: {}", e.getMessage(), e);
            }
        } else {
            LOGGER.warn("OpenAPI: No 'basePaths' key found in options! Available keys: {}", options.root().keySet());
        }

        LOGGER.debug("OpenAPI: Extracted {} controller basePaths from options", controllerBasePaths.size());
        return controllerBasePaths;
    }

    /**
     * Recursively extracts basePaths from a ConfigObject, handling nested structures
     * created by ConfigFactory.parseMap() when keys contain dots.
     *
     * @param configObject The ConfigObject to extract from
     * @param currentPath The current path being built (for nested keys)
     * @param controllerBasePaths The map to populate
     */
    private void extractBasePathsRecursive(final com.typesafe.config.ConfigObject configObject,
                                           final String currentPath,
                                           final Map<String, String> controllerBasePaths) {
        for (final java.util.Map.Entry<String, com.typesafe.config.ConfigValue> entry : configObject.entrySet()) {
            final String key = entry.getKey();
            final com.typesafe.config.ConfigValue value = entry.getValue();
            final String fullPath = currentPath.isEmpty() ? key : currentPath + "." + key;

            if (value.valueType() == com.typesafe.config.ConfigValueType.STRING) {
                // Read the string value directly from the ConfigValue
                final String basePath = (String) value.unwrapped();
                // Check if this looks like a controller class name
                if (fullPath.contains("Controller") && basePath.startsWith("/")) {
                    controllerBasePaths.put(fullPath, basePath);
                    LOGGER.debug("OpenAPI: Found basePath mapping: {} -> {}", fullPath, basePath);
                }
            } else if (value.valueType() == com.typesafe.config.ConfigValueType.OBJECT) {
                extractBasePathsRecursive((com.typesafe.config.ConfigObject) value, fullPath, controllerBasePaths);
            }
        }
    }

    /**
     * Converts string examples to JSON objects in the OpenAPI specification.
     * <p>
     * The annotation processor stores examples as strings, but ReDoc expects JSON objects
     * for proper formatting (multi-line display, expand/collapse functionality).
     * <p>
     * This method only converts examples that are explicitly marked with the "JSON:" prefix.
     * Examples without this prefix remain as strings. This allows controllers to choose
     * per endpoint whether an example should be displayed as a formatted JSON object or
     * as a plain string.
     * <p>
     * Usage in annotations:
     * <pre>{@code
     * @OpenApiContent(
     *     from = Map.class,
     *     example = "JSON:\n{\"key\": \"value\"}"
     * )
     * }</pre>
     *
     * @param openApiJson The OpenAPI JSON root node
     */
    private void convertStringExamplesToJsonObjects(final ObjectNode openApiJson) {
        final JsonNode pathsNode = openApiJson.get("paths");
        if (pathsNode == null || !pathsNode.isObject()) {
            return;
        }

        final ObjectNode pathsObject = (ObjectNode) pathsNode;
        for (final Iterator<Map.Entry<String, JsonNode>> pathIt = pathsObject.fields(); pathIt.hasNext(); ) {
            final JsonNode pathNode = pathIt.next().getValue();
            if (!pathNode.isObject()) {
                continue;
            }

            final ObjectNode pathObject = (ObjectNode) pathNode;
            // Iterate over HTTP methods (get, post, etc.)
            for (final Iterator<Map.Entry<String, JsonNode>> methodIt = pathObject.fields(); methodIt.hasNext(); ) {
                final JsonNode methodNode = methodIt.next().getValue();
                if (!methodNode.isObject()) {
                    continue;
                }

                final ObjectNode methodObject = (ObjectNode) methodNode;
                final JsonNode responsesNode = methodObject.get("responses");
                if (responsesNode == null || !responsesNode.isObject()) {
                    continue;
                }

                final ObjectNode responsesObject = (ObjectNode) responsesNode;
                // Iterate over response codes (200, 404, etc.)
                for (final Iterator<Map.Entry<String, JsonNode>> responseIt = responsesObject.fields(); responseIt.hasNext(); ) {
                    final JsonNode responseNode = responseIt.next().getValue();
                    if (!responseNode.isObject()) {
                        continue;
                    }

                    final ObjectNode responseObject = (ObjectNode) responseNode;
                    final JsonNode contentNode = responseObject.get("content");
                    if (contentNode == null || !contentNode.isObject()) {
                        continue;
                    }

                    final ObjectNode contentObject = (ObjectNode) contentNode;
                    // Iterate over content types (application/json, etc.)
                    for (final Iterator<Map.Entry<String, JsonNode>> contentTypeIt = contentObject.fields(); contentTypeIt.hasNext(); ) {
                        final JsonNode contentTypeNode = contentTypeIt.next().getValue();
                        if (!contentTypeNode.isObject()) {
                            continue;
                        }

                        final ObjectNode contentTypeObject = (ObjectNode) contentTypeNode;
                        final JsonNode exampleNode = contentTypeObject.get("example");
                        if (exampleNode != null && exampleNode.isTextual()) {
                            final String exampleString = exampleNode.asText();
                            
                            // Check if example should be converted to JSON object
                            // Prefix "JSON:" signals that the string should be parsed as JSON
                            if (exampleString.startsWith("JSON:")) {
                                try {
                                    final String jsonString = exampleString.substring(5).trim();
                                    final ObjectMapper mapper = new ObjectMapper();
                                    final JsonNode parsedExample = mapper.readTree(jsonString);
                                    contentTypeObject.set("example", parsedExample);
                                    LOGGER.debug("OpenAPI: Converted JSON: prefixed example to JSON object");
                                } catch (final Exception e) {
                                    LOGGER.debug("OpenAPI: Failed to parse JSON: prefixed example, keeping as string: {}", e.getMessage());
                                }
                            }
                            // If no prefix, keep as string (default behavior)
                        }
                    }
                }
            }
        }
    }

}

