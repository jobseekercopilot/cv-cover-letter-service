package com.jobseekercopilot.cvcoverletter.config;

import com.jobseekercopilot.generated.llmgateway.api.ModelGenerationApi;
import com.jobseekercopilot.generated.llmgateway.client.ApiClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LlmGatewayApiConfig {

    @Bean
    ModelGenerationApi modelGenerationApi(
            @Value("${services.llm-gateway.base-url:http://localhost:8090}") String baseUrl) {
        ApiClient apiClient = new ApiClient(GeneratedApiClientRestTemplateFactory.create());
        apiClient.setBasePath(baseUrl);
        return new ModelGenerationApi(apiClient);
    }
}
