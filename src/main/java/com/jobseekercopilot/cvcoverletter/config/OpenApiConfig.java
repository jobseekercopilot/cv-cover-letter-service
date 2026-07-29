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
            "EvidenceSnapshotFactInput",
            "EvidenceSnapshotInput",
            "EvidenceSnapshotSelectionInput",
            "EvidenceSnapshotsInput",
            "GenerateRequest",
            "JobInputSnapshot",
            "PromptGenerationMetadata",
            "ProfileInputSnapshot",
            "QualificationInput",
            "SnapshotProvenance",
            "ValidatedClaim",
            "ValidatedClaimLedger");

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
                        .version("3.4.0")
                        .description("""
                                Produces bounded CV and cover-letter drafts from
                                purpose-specific immutable confirmed evidence
                                snapshots. The legacy schema 1.0 endpoint contract
                                remains during the coordinated consumer migration.
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
