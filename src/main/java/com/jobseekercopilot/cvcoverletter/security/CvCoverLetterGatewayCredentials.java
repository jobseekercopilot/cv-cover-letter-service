package com.jobseekercopilot.cvcoverletter.security;

import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class CvCoverLetterGatewayCredentials {

    static final int MINIMUM_TOKEN_BYTES = 32;

    private final String gatewayToken;

    public CvCoverLetterGatewayCredentials(
            @Value("${cv-cover-letter.security.gateway-token}") String gatewayToken) {
        if (gatewayToken == null
                || gatewayToken.isBlank()
                || gatewayToken.getBytes(StandardCharsets.UTF_8).length
                < MINIMUM_TOKEN_BYTES) {
            throw new IllegalStateException(
                    "CV and Cover Letter Gateway token must contain at least 32 bytes.");
        }
        this.gatewayToken = gatewayToken;
    }

    public String gatewayToken() {
        return gatewayToken;
    }
}
