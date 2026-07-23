package com.jobseekercopilot.cvcoverletter.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI cvCoverLetterOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Jobseeker Copilot - CV Cover Letter Service API")
                .version("1.0.0")
                .description("Generates, renders, and stores tailored CV and cover-letter content, then records the application."));
    }
}
