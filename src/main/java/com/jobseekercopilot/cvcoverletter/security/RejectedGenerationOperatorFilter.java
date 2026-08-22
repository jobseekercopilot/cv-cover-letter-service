package com.jobseekercopilot.cvcoverletter.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobseekercopilot.cvcoverletter.config.RejectedGenerationQuarantineProperties;
import com.jobseekercopilot.cvcoverletter.dto.ServiceIdentityError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RejectedGenerationOperatorFilter extends OncePerRequestFilter {
    public static final String OWNER_ATTRIBUTE = "rejectedGenerationDocumentOwner";

    private static final String PROTECTED_PATH =
            "/internal/v1/cv-cover-letter/rejected-generations/";
    private static final String OPERATOR_TOKEN_HEADER = "X-Operator-Token";
    private static final String DOCUMENT_OWNER_HEADER = "X-Document-Owner";
    private static final int MAXIMUM_OWNER_CHARACTERS = 256;

    private final RejectedGenerationQuarantineProperties properties;
    private final ObjectMapper objectMapper;

    public RejectedGenerationOperatorFilter(
            RejectedGenerationQuarantineProperties properties,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PROTECTED_PATH);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (!properties.isEnabled()) {
            reject(
                    response,
                    HttpServletResponse.SC_NOT_FOUND,
                    "NOT_FOUND",
                    "Resource not found.");
            return;
        }
        String operatorToken = singleHeader(request, OPERATOR_TOKEN_HEADER);
        if (operatorToken == null
                || !MessageDigest.isEqual(
                        properties.getOperatorToken().getBytes(StandardCharsets.UTF_8),
                        operatorToken.getBytes(StandardCharsets.UTF_8))) {
            reject(
                    response,
                    HttpServletResponse.SC_UNAUTHORIZED,
                    "OPERATOR_AUTHENTICATION_REQUIRED",
                    "Operator authentication required.");
            return;
        }
        String documentOwner = singleHeader(request, DOCUMENT_OWNER_HEADER);
        if (documentOwner == null
                || documentOwner.isBlank()
                || documentOwner.length() > MAXIMUM_OWNER_CHARACTERS) {
            reject(
                    response,
                    HttpServletResponse.SC_BAD_REQUEST,
                    "DOCUMENT_OWNER_REQUIRED",
                    "Exactly one bounded document owner is required.");
            return;
        }
        request.setAttribute(OWNER_ATTRIBUTE, documentOwner);
        filterChain.doFilter(request, response);
    }

    private String singleHeader(HttpServletRequest request, String name) {
        List<String> values = Collections.list(request.getHeaders(name));
        if (values.size() != 1 || values.get(0).contains(",")) {
            return null;
        }
        return values.get(0);
    }

    private void reject(
            HttpServletResponse response,
            int status,
            String code,
            String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(
                response.getOutputStream(),
                new ServiceIdentityError(code, message));
    }
}
