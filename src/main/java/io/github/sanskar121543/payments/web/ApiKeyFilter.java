package io.github.sanskar121543.payments.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Deny by default: every path needs a valid key except the dashboard's static files and the actuator
 * endpoints (nginx hides those from outside; Prometheus scrapes them on the internal network).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";
    public static final String CLIENT_ID_ATTRIBUTE = "payments.clientId";

    private static final Set<String> OPEN_PATHS = Set.of("/", "/index.html", "/app.js", "/styles.css",
            "/favicon.ico");
    private static final List<String> OPEN_PREFIXES = List.of("/vendor/", "/actuator/");

    private final ApiKeys apiKeys;
    private final JsonMapper jsonMapper;

    public ApiKeyFilter(ApiKeys apiKeys, JsonMapper jsonMapper) {
        this.apiKeys = apiKeys;
        this.jsonMapper = jsonMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // The decoded path without ;params, i.e. what Spring MVC routes on. The raw URI would let
        // "/v1;x/accounts" or "/%761/accounts" look like a different path and skip this check.
        String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        return OPEN_PATHS.contains(path) || OPEN_PREFIXES.stream().anyMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<String> clientId = apiKeys.clientFor(request.getHeader(HEADER));
        if (clientId.isEmpty()) {
            ProblemDetail problem = Problems.of(HttpStatus.UNAUTHORIZED, "unauthorized",
                    "Missing or invalid API key", "Send a valid key in the " + HEADER + " header.");
            problem.setInstance(URI.create(request.getRequestURI()));
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            jsonMapper.writeValue(response.getOutputStream(), problem);
            return;
        }
        request.setAttribute(CLIENT_ID_ATTRIBUTE, clientId.get());
        chain.doFilter(request, response);
    }
}
