package com.jobseekercopilot.cvcoverletter;

import com.jobseekercopilot.cvcoverletter.config.LlmProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(LlmProperties.class)
public class CvCoverLetterServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CvCoverLetterServiceApplication.class, args);
    }
}
