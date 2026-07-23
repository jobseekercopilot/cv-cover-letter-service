package com.jobseekercopilot.cvcoverletter.config;

import com.jobseekercopilot.generated.applicationtrackerservice.api.ApplicationRecordsApi;
import com.jobseekercopilot.generated.applicationtrackerservice.client.ApiClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ApplicationTrackerApiConfig {

    @Bean
    ApplicationRecordsApi applicationRecordsApi(
            @Value("${services.application-tracker-service.base-url:http://localhost:8088}") String baseUrl) {
        ApiClient apiClient = new ApiClient(GeneratedApiClientRestTemplateFactory.create());
        apiClient.setBasePath(baseUrl);
        return new ApplicationRecordsApi(apiClient);
    }
}
