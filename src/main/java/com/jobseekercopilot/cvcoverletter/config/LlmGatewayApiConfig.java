package com.jobseekercopilot.cvcoverletter.config;

import com.jobseekercopilot.generated.llmgateway.api.ModelGenerationApi;
import com.jobseekercopilot.generated.llmgateway.client.ApiClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LlmGatewayApiConfig {

    @Bean
    ModelGenerationApi modelGenerationApi(
            @Value("${services.llm-gateway.base-url:http://localhost:8090}") String baseUrl,
            @Value("${services.llm-gateway.connect-timeout:PT5S}") Duration connectTimeout,
            @Value("${services.llm-gateway.read-timeout:PT90S}") Duration readTimeout) {
        // The llm-gateway performs Bedrock generations that take tens of
        // seconds, so it needs a longer read timeout than fast downstreams.
        // A bounded timeout still guarantees a hung connection fails fast
        // (well within the document-generation-gateway's 150s downstream cap),
        // instead of hanging until an outer deadline surfaces as a 504.
        ApiClient apiClient = new ApiClient(
                GeneratedApiClientRestTemplateFactory.create(
                        connectTimeout, readTimeout));
        apiClient.setBasePath(baseUrl);
        return new ModelGenerationApi(apiClient);
    }
}
