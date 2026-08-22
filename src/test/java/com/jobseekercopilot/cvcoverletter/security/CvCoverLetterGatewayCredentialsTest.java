package com.jobseekercopilot.cvcoverletter.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CvCoverLetterGatewayCredentialsTest {

    @Test
    void acceptsStrongRuntimeCredential() {
        String token = "test-only-cv-gateway-service-token-32-bytes";

        assertEquals(token, new CvCoverLetterGatewayCredentials(token).gatewayToken());
    }

    @Test
    void rejectsMissingOrShortCredentialWithoutReflectingIt() {
        IllegalStateException missing = assertThrows(
                IllegalStateException.class,
                () -> new CvCoverLetterGatewayCredentials(""));
        IllegalStateException shortToken = assertThrows(
                IllegalStateException.class,
                () -> new CvCoverLetterGatewayCredentials("short"));

        assertEquals(
                "CV and Cover Letter Gateway token must contain at least 32 bytes.",
                missing.getMessage());
        assertEquals(missing.getMessage(), shortToken.getMessage());
    }
}
