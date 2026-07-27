package com.jobseekercopilot.cvcoverletter.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final Set<String> CLOSED_INPUT_SCHEMAS = Set.of(
            "ContactInputSnapshot",
            "DraftGenerationAudit",
            "DraftGenerationEstimateResponse",
            "DraftGenerationResponse",
            "DraftGenerationUsage",
            "EmploymentInput",
            "GenerateRequest",
            "JobInputSnapshot",
            "PromptGenerationMetadata",
            "ProfileInputSnapshot",
            "QualificationInput",
            "SnapshotProvenance");

    @Bean
    OpenAPI cvCoverLetterOpenApi() {
        return new OpenAPI()
                .components(new Components().addSecuritySchemes(
                        "serviceToken",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-Service-Token")))
                .info(new Info()
                        .title("Jobseeker Copilot - CV Cover Letter Service API")
                        .version("3.2.0")
                        .description("""
                                Produces bounded provenance-aware CV and cover-letter
                                drafts for a Gateway-owned durable workflow. The
                                legacy generate-and-commit endpoint remains during
                                the coordinated consumer migration.
                                """));
    }

    @Bean
    OpenApiCustomizer closeApiSchemas() {
        return openApi -> CLOSED_INPUT_SCHEMAS.forEach(schemaName -> {
            var schema = openApi.getComponents().getSchemas().get(schemaName);
            if (schema != null) {
                schema.setAdditionalProperties(false);
            }
        });
    }
}
