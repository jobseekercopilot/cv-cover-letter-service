package com.jobseekercopilot.cvcoverletter.config;

import com.jobseekercopilot.cvcoverletter.security.OutboundServiceCredentials;
import com.jobseekercopilot.generated.applicationtrackerservice.api.ApplicationRecordsApi;
import com.jobseekercopilot.generated.applicationtrackerservice.client.ApiClient;
import com.jobseekercopilot.generated.applicationtrackerservice.client.auth.ApiKeyAuth;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ApplicationTrackerApiConfig {

    @Bean
    ApplicationRecordsApi applicationRecordsApi(
            @Value("${services.application-tracker-service.base-url:http://localhost:8088}") String baseUrl,
            OutboundServiceCredentials credentials) {
        ApiClient apiClient = new ApiClient(GeneratedApiClientRestTemplateFactory.create());
        apiClient.setBasePath(baseUrl);
        if (!(apiClient.getAuthentication("serviceToken") instanceof ApiKeyAuth serviceToken)) {
            throw new IllegalStateException(
                    "Application Tracker client contract has no service-token authentication.");
        }
        serviceToken.setApiKey(credentials.applicationTrackerProducerToken());
        return new ApplicationRecordsApi(apiClient);
    }
}
