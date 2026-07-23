package com.jobseekercopilot.cvcoverletter.config;

import com.jobseekercopilot.generated.documentstoreservice.api.GeneratedDocumentsApi;
import com.jobseekercopilot.generated.documentstoreservice.client.ApiClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DocumentStoreApiConfig {

    @Bean
    GeneratedDocumentsApi generatedDocumentsApi(
            @Value("${services.document-store-service.base-url:http://localhost:8089}") String baseUrl) {
        ApiClient apiClient = new ApiClient(GeneratedApiClientRestTemplateFactory.create());
        apiClient.setBasePath(baseUrl);
        return new GeneratedDocumentsApi(apiClient);
    }
}
