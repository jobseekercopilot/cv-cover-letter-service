package com.jobseekercopilot.cvcoverletter.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

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
                        .version("2.0.0")
                        .description("Generates, renders, and stores tailored CV and cover-letter content, then records the application."));
    }
}
