package com.jobseekercopilot.cvcoverletter.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.jobseekercopilot.cvcoverletter.security.CvCoverLetterGatewayCredentials;
import com.jobseekercopilot.cvcoverletter.security.OutboundServiceCredentials;
import org.junit.jupiter.api.Test;

class OutboundApiClientConfigTest {

    private static final String STORE_TOKEN =
            "test-only-document-store-producer-token-32-bytes";
    private static final String TRACKER_TOKEN =
            "test-only-application-tracker-producer-token-32-bytes";

    private final OutboundServiceCredentials credentials = new OutboundServiceCredentials(
            STORE_TOKEN,
            TRACKER_TOKEN,
            new CvCoverLetterGatewayCredentials(
                    "test-only-cv-gateway-service-token-32-bytes"));

    @Test
    void documentStoreClientUsesOnlyItsDedicatedProducerIdentity() {
        var api = new DocumentStoreApiConfig()
                .generatedDocumentsApi("http://document-store.test", credentials);
        var authentication = assertInstanceOf(
                com.jobseekercopilot.generated.documentstoreservice.client.auth.ApiKeyAuth.class,
                api.getApiClient().getAuthentication("serviceToken"));

        assertEquals(STORE_TOKEN, authentication.getApiKey());
        assertEquals("http://document-store.test", api.getApiClient().getBasePath());
    }

    @Test
    void applicationTrackerClientUsesOnlyItsDedicatedProducerIdentity() {
        var api = new ApplicationTrackerApiConfig()
                .applicationRecordsApi("http://application-tracker.test", credentials);
        var authentication = assertInstanceOf(
                com.jobseekercopilot.generated.applicationtrackerservice.client.auth.ApiKeyAuth.class,
                api.getApiClient().getAuthentication("serviceToken"));

        assertEquals(TRACKER_TOKEN, authentication.getApiKey());
        assertEquals("http://application-tracker.test", api.getApiClient().getBasePath());
    }
}
