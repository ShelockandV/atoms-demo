package dev.atoms.web;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ValueConstants;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

@RestController
final class GatewayOpenApiController {
    private final ObjectProvider<RequestMappingHandlerMapping> mappings;

    GatewayOpenApiController(@Qualifier("requestMappingHandlerMapping") ObjectProvider<RequestMappingHandlerMapping> mappings) {
        this.mappings = mappings;
    }

    @GetMapping(value = "/openapi.json", produces = "application/json")
    Map<String, Object> spec() {
        var paths = new TreeMap<String, Map<String, Object>>();
        mappings.getObject().getHandlerMethods().forEach((route, handler) -> {
            for (String pattern : route.getPatternValues()) {
                if (!gatewayRoute(pattern)) continue;
                String path = pattern.replace("{*", "{");
                var operations = paths.computeIfAbsent(path, ignored -> new TreeMap<>());
                var status = handler.getMethodAnnotation(ResponseStatus.class);
                var parameters = new ArrayList<Map<String, Object>>();
                boolean body = false;
                boolean bodyRequired = false;
                for (var parameter : handler.getMethodParameters()) {
                    var pathVariable = parameter.getParameterAnnotation(PathVariable.class);
                    var query = parameter.getParameterAnnotation(RequestParam.class);
                    var requestBody = parameter.getParameterAnnotation(RequestBody.class);
                    if (pathVariable != null) {
                        parameters.add(parameter(parameter, pathVariable.name().isEmpty() ? pathVariable.value() : pathVariable.name(), "path", true));
                    } else if (query != null) {
                        boolean required = query.required() && ValueConstants.DEFAULT_NONE.equals(query.defaultValue());
                        parameters.add(parameter(parameter, query.name().isEmpty() ? query.value() : query.name(), "query", required));
                    } else if (requestBody != null) {
                        body = true;
                        bodyRequired = requestBody.required();
                    }
                }
                for (var method : route.getMethodsCondition().getMethods()) {
                    var operation = new TreeMap<String, Object>();
                    operation.put("parameters", parameters);
                    operation.put("responses", Map.of(status == null ? "2XX" : Integer.toString(status.code().value()),
                            Map.of("description", "Success")));
                    if (body) operation.put("requestBody", Map.of("required", bodyRequired, "content",
                            Map.of("application/json", Map.of("schema", Map.of("type", "object")))));
                    if (List.of("/api/v1/auth/options", "/api/v1/auth/register", "/api/v1/auth/refresh").contains(path)) {
                        operation.put("security", List.of());
                    } else if (path.startsWith("/api/v1/") || path.startsWith("/v1/")) {
                        operation.put("security", List.of(Map.of("bearerAuth", List.of()), Map.of()));
                    }
                    operations.put(method.name().toLowerCase(Locale.ROOT), operation);
                }
            }
        });
        return Map.of("openapi", "3.0.3", "info", Map.of("title", "Atoms Gateway API", "version", "0.1.0"),
                "paths", paths, "components", Map.of("securitySchemes", Map.of("bearerAuth",
                        Map.of("type", "http", "scheme", "bearer"))));
    }

    private static boolean gatewayRoute(String path) {
        return List.of("/health", "/readiness", "/api/health").contains(path) || path.startsWith("/api/v1/") && !path.equals("/api/v1/stream/ws")
                || path.startsWith("/v1/") && !path.startsWith("/v1/ws/");
    }

    private static Map<String, Object> parameter(MethodParameter parameter, String name, String location, boolean required) {
        Class<?> type = parameter.getParameterType();
        String schema = type == int.class || type == Integer.class || type == long.class || type == Long.class
                ? "integer" : type == boolean.class || type == Boolean.class ? "boolean" : "string";
        return Map.of("name", name.isEmpty() ? parameter.getParameter().getName() : name, "in", location,
                "required", required, "schema", Map.of("type", schema));
    }
}
