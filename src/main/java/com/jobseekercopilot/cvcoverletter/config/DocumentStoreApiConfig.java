package com.jobseekercopilot.cvcoverletter.config;

import com.jobseekercopilot.cvcoverletter.security.OutboundServiceCredentials;
import com.jobseekercopilot.generated.documentstoreservice.api.GeneratedDocumentsApi;
import com.jobseekercopilot.generated.documentstoreservice.client.ApiClient;
import com.jobseekercopilot.generated.documentstoreservice.client.auth.ApiKeyAuth;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DocumentStoreApiConfig {

    @Bean
    GeneratedDocumentsApi generatedDocumentsApi(
            @Value("${services.document-store-service.base-url:http://localhost:8089}") String baseUrl,
            OutboundServiceCredentials credentials) {
        ApiClient apiClient = new ApiClient(GeneratedApiClientRestTemplateFactory.create());
        apiClient.setBasePath(baseUrl);
        if (!(apiClient.getAuthentication("serviceToken") instanceof ApiKeyAuth serviceToken)) {
            throw new IllegalStateException(
                    "Document Store client contract has no service-token authentication.");
        }
        serviceToken.setApiKey(credentials.documentStoreProducerToken());
        return new GeneratedDocumentsApi(apiClient);
    }
}
