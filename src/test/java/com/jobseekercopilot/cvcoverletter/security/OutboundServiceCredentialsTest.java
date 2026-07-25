package com.jobseekercopilot.cvcoverletter.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class OutboundServiceCredentialsTest {

    private static final String GATEWAY_TOKEN =
            "test-only-cv-gateway-service-token-32-bytes";
    private static final String STORE_TOKEN =
            "test-only-document-store-producer-token-32-bytes";
    private static final String TRACKER_TOKEN =
            "test-only-application-tracker-producer-token-32-bytes";

    @Test
    void acceptsStrongPairwiseDistinctRuntimeCredentials() {
        OutboundServiceCredentials credentials = credentials(STORE_TOKEN, TRACKER_TOKEN);

        assertEquals(STORE_TOKEN, credentials.documentStoreProducerToken());
        assertEquals(TRACKER_TOKEN, credentials.applicationTrackerProducerToken());
    }

    @Test
    void rejectsMissingShortAndReusedCredentialsWithoutReflectingValues() {
        IllegalStateException missing = assertThrows(
                IllegalStateException.class,
                () -> credentials("", TRACKER_TOKEN));
        IllegalStateException shortToken = assertThrows(
                IllegalStateException.class,
                () -> credentials("short", TRACKER_TOKEN));
        IllegalStateException outboundReuse = assertThrows(
                IllegalStateException.class,
                () -> credentials(STORE_TOKEN, STORE_TOKEN));
        IllegalStateException gatewayReuse = assertThrows(
                IllegalStateException.class,
                () -> credentials(GATEWAY_TOKEN, TRACKER_TOKEN));

        assertEquals(missing.getMessage(), shortToken.getMessage());
        assertEquals(missing.getMessage(), outboundReuse.getMessage());
        assertEquals(missing.getMessage(), gatewayReuse.getMessage());
        assertFalse(missing.getMessage().contains("short"));
        assertFalse(missing.getMessage().contains(STORE_TOKEN));
        assertFalse(missing.getMessage().contains(GATEWAY_TOKEN));
    }

    private OutboundServiceCredentials credentials(String storeToken, String trackerToken) {
        return new OutboundServiceCredentials(
                storeToken,
                trackerToken,
                new CvCoverLetterGatewayCredentials(GATEWAY_TOKEN));
    }
}
