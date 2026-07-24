package com.jobseekercopilot.cvcoverletter.security;

import com.fasterxml.jackson.databind.ObjectMapper;
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
public class CvCoverLetterIdentityFilter extends OncePerRequestFilter {

    public static final String OWNER_ATTRIBUTE = "cvCoverLetterDocumentOwner";

    private static final String PROTECTED_PATH = "/api/v1/cv-cover-letter/";
    private static final String SERVICE_TOKEN_HEADER = "X-Service-Token";
    private static final String DOCUMENT_OWNER_HEADER = "X-Document-Owner";

    private final CvCoverLetterGatewayCredentials credentials;
    private final ObjectMapper objectMapper;

    public CvCoverLetterIdentityFilter(
            CvCoverLetterGatewayCredentials credentials,
            ObjectMapper objectMapper) {
        this.credentials = credentials;
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
        String serviceToken = singleHeader(request, SERVICE_TOKEN_HEADER);
        if (serviceToken == null
                || !MessageDigest.isEqual(
                        credentials.gatewayToken().getBytes(StandardCharsets.UTF_8),
                        serviceToken.getBytes(StandardCharsets.UTF_8))) {
            reject(
                    response,
                    HttpServletResponse.SC_UNAUTHORIZED,
                    "SERVICE_AUTHENTICATION_REQUIRED",
                    "Service authentication required.");
            return;
        }

        String documentOwner = singleHeader(request, DOCUMENT_OWNER_HEADER);
        if (documentOwner == null || documentOwner.isBlank()) {
            reject(
                    response,
                    HttpServletResponse.SC_BAD_REQUEST,
                    "DOCUMENT_OWNER_REQUIRED",
                    "Exactly one document owner is required.");
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
        objectMapper.writeValue(response.getOutputStream(), new ServiceIdentityError(code, message));
    }
}
