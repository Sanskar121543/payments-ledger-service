package io.github.sanskar121543.payments.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Optional;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";
    public static final String CLIENT_ID_ATTRIBUTE = "payments.clientId";

    private final ApiKeys apiKeys;
    private final JsonMapper jsonMapper;

    public ApiKeyFilter(ApiKeys apiKeys, JsonMapper jsonMapper) {
        this.apiKeys = apiKeys;
        this.jsonMapper = jsonMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/v1/");
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
